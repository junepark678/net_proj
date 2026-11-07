package dev.junepark.oniontalkie.metrics

import kotlin.math.abs

/**
 * Accumulates raw call events (frames, PONGs, jitter-buffer and Tor info) and turns them
 * into a [CallStats] snapshot each time [sample] is called (once per second by the call engine).
 *
 * All times are caller-supplied milliseconds on a single monotonic clock, which keeps the
 * math deterministic and testable. Not thread-safe: call from one coroutine/thread.
 *
 * @param startMs clock reading at call start; `t_s` is measured from here.
 */
class CallMetricsCollector(private val startMs: Long) {
    private var lastSampleMs = startMs

    // RFC 3550 §6.4.1 interarrival jitter, in ms (timestamps are already in ms).
    private var jitter = 0.0
    private var prevArrivalMs = 0L
    private var prevSenderTs = 0L
    private var haveTransit = false

    // RFC 3550 Appendix A.1 sequence tracking (16-bit seq, extended with cycles).
    private var haveSeq = false
    private var baseSeq = 0L
    private var maxSeq = 0L // extended highest sequence number
    private var received = 0L
    private var expectedPrior = 0L
    private var receivedPrior = 0L

    private var rttMs: Double? = null
    private var late = 0
    private var plc = 0
    private var bufferMs = 0.0
    private var txBytes = 0L
    private var rxBytes = 0L
    private var circuitId: String? = null
    private var hops: Int? = null

    /**
     * Records a received AUDIO frame.
     *
     * @param seq 16-bit wire sequence number.
     * @param senderTsMs 32-bit sender timestamp in ms from the AUDIO header.
     * @param arrivalMs local arrival time in ms.
     * @param bytes size counted towards the receive bitrate.
     */
    fun onAudioReceived(seq: Int, senderTsMs: Long, arrivalMs: Long, bytes: Int) {
        rxBytes += bytes
        received++
        updateSequence(seq and 0xFFFF)
        if (haveTransit) {
            // D(i-1,i) = (Rj - Ri) - (Sj - Si); the sender delta is taken modulo 2^32.
            val senderDelta = (senderTsMs - prevSenderTs).toInt().toLong()
            val d = (arrivalMs - prevArrivalMs) - senderDelta
            jitter += (abs(d) - jitter) / 16.0
        }
        prevArrivalMs = arrivalMs
        prevSenderTs = senderTsMs
        haveTransit = true
    }

    /** Records an AUDIO frame sent; [bytes] counts towards the send bitrate. */
    fun onAudioSent(bytes: Int) {
        txBytes += bytes
    }

    /**
     * Records a PONG that echoes the `tsMs` of one of our PINGs (a 32-bit ms value taken from
     * the same clock as [nowMs]). Updates the RTT; ignores echoes from the future.
     */
    fun onPong(echoedTsMs: Long, nowMs: Long) {
        val rtt = (nowMs - echoedTsMs).toInt() // modulo 2^32
        if (rtt >= 0) rttMs = rtt.toDouble()
    }

    /** Counts a frame that arrived too late to play. */
    fun onLateFrame() {
        late++
    }

    /** Counts a frame concealed by PLC. */
    fun onPlcFrame() {
        plc++
    }

    /** Sets the current jitter-buffer delay in ms. */
    fun setBufferMs(ms: Double) {
        bufferMs = ms
    }

    /** Sets Tor circuit info; pass `null` when it is not (yet) available. */
    fun setCircuit(id: String?, hops: Int?) {
        circuitId = id
        this.hops = hops
    }

    /**
     * Produces the snapshot for the interval since the previous call (or the call start) and
     * starts a new interval.
     */
    fun sample(nowMs: Long): CallStats {
        val elapsedMs = nowMs - lastSampleMs
        val expected = if (haveSeq) maxSeq - baseSeq + 1 else 0L
        val expectedInterval = expected - expectedPrior
        val lostInterval = expectedInterval - (received - receivedPrior)
        val lossPct = if (expectedInterval > 0 && lostInterval > 0) {
            lostInterval * 100.0 / expectedInterval
        } else {
            0.0
        }
        // bytes * 8 bits / ms == kbit/s
        val txKbps = if (elapsedMs > 0) txBytes * 8.0 / elapsedMs else 0.0
        val rxKbps = if (elapsedMs > 0) rxBytes * 8.0 / elapsedMs else 0.0
        val stats = CallStats(
            tSeconds = (nowMs - startMs) / 1000,
            rttMs = rttMs,
            jitterMs = jitter,
            lossPct = lossPct,
            late = late,
            plc = plc,
            bufferMs = bufferMs,
            txKbps = txKbps,
            rxKbps = rxKbps,
            circuitId = circuitId,
            hops = hops,
        )
        lastSampleMs = nowMs
        expectedPrior = expected
        receivedPrior = received
        late = 0
        plc = 0
        txBytes = 0
        rxBytes = 0
        return stats
    }

    private fun updateSequence(seq: Int) {
        if (!haveSeq) {
            haveSeq = true
            baseSeq = seq.toLong()
            maxSeq = baseSeq
            return
        }
        val delta = (seq - (maxSeq and 0xFFFF).toInt()) and 0xFFFF
        // Forward (possibly wrapping) by less than half the space: new highest sequence number.
        // Otherwise it is a reordered/duplicate packet and the highest number is unchanged.
        if (delta in 1 until 0x8000) maxSeq += delta
    }
}
