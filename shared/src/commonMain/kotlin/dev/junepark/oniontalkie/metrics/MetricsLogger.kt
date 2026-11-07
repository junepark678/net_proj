package dev.junepark.oniontalkie.metrics

import kotlin.math.abs
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One logger per call, sampling once per second in [scope]. Feed events in receive
 * order; concurrent producers and sampling are serialized. [clockMs] must be a
 * nondecreasing local monotonic clock, independent of the peer's clock.
 *
 * Run [scope] on an I/O dispatcher: sink writes are synchronous. Always call
 * [close] in the call's finally block, including after scope cancellation. It
 * flushes a final partial interval, writes the summary and closes the sink.
 * Sink failures propagate to the scope (sampling) or caller (close).
 */
class MetricsLogger(
    private val sink: MetricsSink,
    scope: CoroutineScope,
    private val clockMs: () -> Long = monotonicClock(),
) {
    private val mutex = Mutex()
    private val closeMutex = Mutex()
    private val startedMs = clockMs()
    private var lastSampleMs = startedMs
    private var closed = false
    private var dirty = false
    private val mutableStats = MutableStateFlow(CallStats())

    /** Read-only, per-second snapshots for the live metrics screen. */
    val stats: StateFlow<CallStats> = mutableStats.asStateFlow()

    private val samples = mutableListOf<CallStats>()
    private val pendingPings = linkedMapOf<Long, Long>()
    private var rttMs: Double? = null
    private var previousSenderMs: Long? = null
    private var previousArrivalMs = 0L
    private var jitterMs = 0.0
    private var firstSequence: Long? = null
    private var highestSequence = 0L
    private val seenSequences = mutableSetOf<Long>()
    private var receivedFrames = 0L
    private var sampledExpected = 0L
    private var sampledReceived = 0L
    private var sampledLate = 0L
    private var sampledPlc = 0L
    private var txBytes = 0L
    private var rxBytes = 0L
    private var late = 0L
    private var plc = 0L
    private var bufferMs = 0.0
    private var circuitId: String? = null
    private var hops: Int? = null

    init {
        try {
            sink.appendCsv("$CSV_HEADER\n")
        } catch (failure: Throwable) {
            sink.close()
            throw failure
        }
    }

    private val sampler = scope.launch {
        // Schedule against fixed deadlines so sink write latency never skips a
        // second; a slow write is followed by a shorter sleep, not a drifted one.
        var deadlineMs = startedMs + SAMPLE_INTERVAL_MS
        while (isActive) {
            delay((deadlineMs - clockMs()).coerceAtLeast(0))
            val snapshot = mutex.withLock { if (closed) null else sample(clockMs()) }
            snapshot?.let { sink.appendCsv(it.toCsv() + "\n") }
            deadlineMs += SAMPLE_INTERVAL_MS
        }
    }

    /** Records AUDIO; [arrivalMs] is captured before locking, or supplied by the receiver. */
    suspend fun onAudioReceived(
        sequence: Int,
        senderTimestampMs: Long,
        arrivalMs: Long = clockMs(),
    ) = update {
        require(sequence in 0..0xffff)
        require(senderTimestampMs in 0..UINT32_MASK)
        previousSenderMs?.let { previous ->
            val senderDelta = signedDelta(senderTimestampMs, previous, 32)
            val difference = (arrivalMs - previousArrivalMs) - senderDelta
            // RFC 3550 §6.4.1: use consecutive arrivals, even when reordered.
            jitterMs += (abs(difference.toDouble()) - jitterMs) / 16.0
        }
        previousSenderMs = senderTimestampMs
        previousArrivalMs = arrivalMs
        recordSequence(sequence)
        true
    }

    /** Records the u32 PING token and local [sentMs] before locking; keeps 64 probes. */
    suspend fun onPingSent(timestampMs: Long, sentMs: Long = clockMs()) = update {
        require(timestampMs in 0..UINT32_MASK)
        pendingPings[timestampMs] = sentMs
        if (pendingPings.size > 64) pendingPings.remove(pendingPings.keys.first())
        false // A pending probe is bookkeeping, not a measurement.
    }

    /** Matches a PONG using its pre-lock [arrivalMs]. Unknown/duplicate echoes are ignored. */
    suspend fun onPong(echoedTimestampMs: Long, arrivalMs: Long = clockMs()) = update {
        require(echoedTimestampMs in 0..UINT32_MASK)
        val sentMs = pendingPings.remove(echoedTimestampMs) ?: return@update false
        rttMs = (arrivalMs - sentMs).toDouble()
        true
    }

    /**
     * Adds transport bytes, including protocol overhead. Count every read/write
     * once here; [onAudioReceived] does not also count bytes. This excludes Tor's
     * encrypted relay overhead, which is not visible on the application stream.
     */
    suspend fun onTransportBytes(sent: Long = 0, received: Long = 0) = update {
        require(sent >= 0 && received >= 0)
        txBytes += sent
        rxBytes += received
        sent > 0 || received > 0
    }

    /** Adds jitter-buffer discard/PLC events and replaces the current delay. */
    suspend fun onPlayout(bufferMs: Double, late: Long = 0, plc: Long = 0) = update {
        require(bufferMs.isFinite() && bufferMs >= 0 && late >= 0 && plc >= 0)
        val changed = this.bufferMs != bufferMs || late > 0 || plc > 0
        this.bufferMs = bufferMs
        this.late += late
        this.plc += plc
        changed
    }

    /**
     * Updates the call's circuit, identified by the transport/control-port owner.
     * [response] is GETINFO circuit-status data (or its full reply). Only a BUILT
     * entry for [circuitId] supplies a hop count; missing data clears stale hops.
     * Pass null for [circuitId] to clear all circuit information.
     */
    suspend fun onCircuitStatus(circuitId: String?, response: String?) = update {
        require(circuitId == null || Regex("[0-9]+").matches(circuitId))
        val newHops = response?.lineSequence()?.map { it.trim().split(Regex("\\s+")) }
            ?.firstOrNull { it.size >= 3 && it[0] == circuitId && it[1] == "BUILT" }
            ?.get(2)?.takeUnless { '=' in it && !it.startsWith('$') }
            ?.split(',')?.takeIf { path -> path.all { it.isNotEmpty() } }?.size
        val changed = this.circuitId != circuitId || hops != newHops
        this.circuitId = circuitId
        hops = newHops
        changed
    }

    /** Stops sampling and persists the final interval/summary. Safe to repeat. */
    suspend fun close() = withContext(NonCancellable) {
        closeMutex.withLock {
            // Join any in-flight write before finalizing; event recording never
            // acquires closeMutex or waits for the sink.
            sampler.cancelAndJoin()
            val finalOutput = mutex.withLock state@{
                if (closed) return@state null
                closed = true
                val nowMs = clockMs()
                val snapshot = sample(nowMs, final = true)
                Triple(snapshot, samples.toList(), (nowMs - startedMs) / 1_000.0)
            }
            if (finalOutput != null) {
                try {
                    val (snapshot, snapshots, duration) = finalOutput
                    snapshot?.let { sink.appendCsv(it.toCsv() + "\n") }
                    sink.writeSummary(summaryJson(snapshots, duration))
                } finally {
                    sink.close()
                }
            }
        }
    }

    /** [block] returns true when it changed measured state; only then is a final interval owed. */
    private suspend fun update(block: () -> Boolean) = mutex.withLock {
        check(!closed) { "Metrics logger is closed" }
        if (block()) dirty = true
    }

    private fun recordSequence(sequence: Int) {
        val first = firstSequence
        if (first == null) {
            firstSequence = sequence.toLong()
            highestSequence = sequence.toLong()
        }
        val extended = highestSequence + signedDelta(sequence.toLong(), highestSequence and 0xffff, 16)
        if (extended > highestSequence) {
            // Retain half the u16 sequence space to deduplicate and repair gaps
            // on reordering. Each evicted sequence is visited only once.
            for (expired in (highestSequence - 32_767)..(extended - 32_768)) {
                seenSequences.remove(expired)
            }
            highestSequence = extended
        }
        if (extended >= firstSequence!! && extended > highestSequence - 32_768 && seenSequences.add(extended)) {
            receivedFrames++
        }
    }

    private fun sample(nowMs: Long, final: Boolean = false): CallStats? {
        val intervalMs = nowMs - lastSampleMs
        if (intervalMs <= 0 && !(final && dirty)) return null
        val expected = firstSequence?.let { highestSequence - it + 1 } ?: 0
        val intervalExpected = expected - sampledExpected
        val intervalLost = (intervalExpected - (receivedFrames - sampledReceived)).coerceAtLeast(0)
        val snapshot = CallStats(
            elapsedSeconds = (nowMs - startedMs) / 1_000.0,
            rttMs = rttMs,
            jitterMs = jitterMs,
            lossPct = if (expected == 0L) 0.0 else (expected - receivedFrames) * 100.0 / expected,
            late = late,
            plc = plc,
            bufferMs = bufferMs,
            // Sub-millisecond final events still need a row. Use the clock's
            // 1 ms resolution as the minimum rate denominator.
            txKbps = txBytes * 8.0 / intervalMs.coerceAtLeast(1),
            rxKbps = rxBytes * 8.0 / intervalMs.coerceAtLeast(1),
            circuitId = circuitId,
            hops = hops,
            intervalLossPct = if (intervalExpected == 0L) 0.0 else intervalLost * 100.0 / intervalExpected,
            intervalLate = late - sampledLate,
            intervalPlc = plc - sampledPlc,
        )
        samples.add(snapshot)
        mutableStats.value = snapshot
        txBytes = 0
        rxBytes = 0
        lastSampleMs = nowMs
        sampledExpected = expected
        sampledReceived = receivedFrames
        sampledLate = late
        sampledPlc = plc
        dirty = false
        return snapshot
    }

    companion object {
        /** Stable schema: loss_pct, late, plc and bitrates cover the interval. */
        const val CSV_HEADER = "t_s,rtt_ms,jitter_ms,loss_pct,late,plc,buffer_ms,tx_kbps,rx_kbps,circuit_id,hops"
    }
}

private const val SAMPLE_INTERVAL_MS = 1_000L
private const val UINT32_MASK = 0xffff_ffffL

private fun signedDelta(current: Long, previous: Long, bits: Int): Long {
    val half = 1L shl (bits - 1)
    return ((current - previous + half) and ((1L shl bits) - 1)) - half
}

private fun monotonicClock(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}

private fun CallStats.toCsv(): String = listOf(
    elapsedSeconds.toLong().toString(), rttMs?.let(::formatFixed), formatFixed(jitterMs),
    formatFixed(intervalLossPct), intervalLate.toString(), intervalPlc.toString(), formatFixed(bufferMs),
    formatFixed(txKbps), formatFixed(rxKbps), circuitId, hops?.toString(),
).joinToString(",") { it ?: "" }
