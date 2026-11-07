package dev.junepark.oniontalkie.call

import dev.junepark.oniontalkie.audio.AudioFormat
import dev.junepark.oniontalkie.audio.AudioInput
import dev.junepark.oniontalkie.audio.AudioOutput
import dev.junepark.oniontalkie.audio.AudioRouteController
import dev.junepark.oniontalkie.audio.FrameClock
import dev.junepark.oniontalkie.audio.JitterBuffer
import dev.junepark.oniontalkie.audio.JitterBufferMetrics
import dev.junepark.oniontalkie.audio.NoOpAudioRouteController
import dev.junepark.oniontalkie.audio.OpusCodec
import dev.junepark.oniontalkie.audio.createAudioInput
import dev.junepark.oniontalkie.audio.createAudioOutput
import dev.junepark.oniontalkie.audio.createOpusCodec
import dev.junepark.oniontalkie.audio.frames
import dev.junepark.oniontalkie.protocol.Frame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/** Snapshot for the UI or metrics logger; counts are per media session. */
data class CallMediaMetrics(
    val jitterBuffer: JitterBufferMetrics,
    val hardwareUnderruns: Long,
    val incomingQueueDrops: Long,
    val outgoingQueueDrops: Long,
)

/**
 * Connects Android/desktop PCM devices, the negotiated 24 kbps mono Opus codec, and #15's
 * playout buffer to the #16 call engine. One session owns one codec, input, output, and route
 * lease. Capture/encode, network writes, and paced decode/playback run independently so a slow
 * network write cannot stall microphone capture. Queue overflow drops stale speech and leaves a
 * sequence gap for the peer's PLC path.
 */
class OpusCallSessionFactory(
    private val inputFactory: () -> AudioInput = { createAudioInput() },
    private val outputFactory: () -> AudioOutput = { createAudioOutput() },
    private val codecFactory: () -> OpusCodec = { createOpusCodec() },
    private val routeController: AudioRouteController = NoOpAudioRouteController,
    private val audioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMs: () -> Long = monotonicMillis(),
    private val playoutDelay: suspend (Long) -> Unit = { delay(it) },
    private val metricsListener: (peerOnion: String, metrics: CallMediaMetrics) -> Unit = { _, _ -> },
) : CallSessionFactory {
    override fun create(peerOnion: String): CallSession = OpusCallSession(
        peerOnion = peerOnion,
        input = inputFactory(),
        output = outputFactory(),
        codec = codecFactory(),
        routeController = routeController,
        audioDispatcher = audioDispatcher,
        clockMs = clockMs,
        playoutDelay = playoutDelay,
        metricsListener = metricsListener,
    )
}

private class OpusCallSession(
    private val peerOnion: String,
    private val input: AudioInput,
    private val output: AudioOutput,
    private val codec: OpusCodec,
    private val routeController: AudioRouteController,
    private val audioDispatcher: CoroutineDispatcher,
    private val clockMs: () -> Long,
    private val playoutDelay: suspend (Long) -> Unit,
    private val metricsListener: (String, CallMediaMetrics) -> Unit,
) : CallSession {
    private val incomingQueueDrops = MutableStateFlow(0L)
    private val outgoingQueueDrops = MutableStateFlow(0L)
    private val incoming = Channel<Frame.Audio>(
        capacity = INCOMING_QUEUE_FRAMES,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { incomingQueueDrops.update { it + 1 } },
    )
    private val jitterBuffer = JitterBuffer(clockMs = clockMs)
    private val lifecycle = Mutex()
    private var running = false
    private var closed = false

    override suspend fun run(sendAudio: suspend (Frame.Audio) -> Unit) {
        lifecycle.withLock {
            check(!closed) { "Call media session is closed" }
            check(!running) { "Call media session can only run once" }
            running = true
        }

        var routeStarted = false
        try {
            withContext(audioDispatcher) {
                routeController.beginCommunication()
                routeStarted = true
                output.start()
                runWorkers(sendAudio)
            }
        } finally {
            withContext(NonCancellable + audioDispatcher) {
                runCatching { input.stop() }
                runCatching { output.stop() }
                if (routeStarted) runCatching { routeController.endCommunication() }
                lifecycle.withLock { running = false }
            }
        }
    }

    /** Enqueues promptly; a full queue sheds its oldest frame to preserve live audio. */
    override suspend fun receive(frame: Frame.Audio) {
        incoming.trySend(frame)
    }

    override suspend fun close() {
        withContext(NonCancellable) {
            lifecycle.withLock {
                if (closed) return@withLock
                closed = true
                incoming.close()
                runCatching { input.stop() }
                runCatching { output.stop() }
            }
        }
    }

    private suspend fun runWorkers(sendAudio: suspend (Frame.Audio) -> Unit) = coroutineScope {
        val outgoing = Channel<Frame.Audio>(
            capacity = OUTGOING_QUEUE_FRAMES,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = { outgoingQueueDrops.update { it + 1 } },
        )
        val sender = launch {
            for (frame in outgoing) sendAudio(frame)
        }
        val capture = launch {
            try {
                val mediaClock = FrameClock()
                input.frames().collect { pcm ->
                    val stamp = mediaClock.nextFrame(pcm)
                    val frame = Frame.Audio(
                        seq = stamp.seq.toUShort(),
                        tsMs = stamp.tsMs.toUInt(),
                        payload = codec.encode(pcm),
                    )
                    outgoing.trySend(frame)
                }
            } finally {
                outgoing.close()
            }
        }
        val playback = launch { playoutLoop() }

        try {
            select<Unit> {
                capture.onJoin { }
                playback.onJoin { }
            }
            if (capture.isCompleted) sender.join()
        } finally {
            outgoing.close()
            capture.cancelAndJoin()
            playback.cancelAndJoin()
            sender.cancelAndJoin()
        }
    }

    private suspend fun playoutLoop() {
        var deadlineMs = clockMs()
        var quietFrameStreak = 0
        while (currentCoroutineContext().isActive) {
            val waitMs = deadlineMs - clockMs()
            if (waitMs > 0) playoutDelay(waitMs)
            drainIncoming()

            val encoded = jitterBuffer.pull(silenceLike = quietFrameStreak >= QUIET_STREAK_FOR_CATCH_UP)
            val pcm = ShortArray(AudioFormat.SAMPLES_PER_FRAME)
            codec.decode(encoded, pcm) // null requests one Opus PLC frame.
            output.write(pcm)
            quietFrameStreak = if (isQuiet(pcm)) quietFrameStreak + 1 else 0
            publishMetrics()
            deadlineMs += AudioFormat.FRAME_DURATION_MS
        }
    }

    private fun drainIncoming() {
        while (true) {
            val result = incoming.tryReceive()
            val frame = result.getOrNull() ?: break
            jitterBuffer.put(frame)
        }
    }

    private fun publishMetrics() {
        val snapshot = CallMediaMetrics(
            jitterBuffer = jitterBuffer.metrics,
            hardwareUnderruns = output.underrunCount,
            incomingQueueDrops = incomingQueueDrops.value,
            outgoingQueueDrops = outgoingQueueDrops.value,
        )
        try {
            metricsListener(peerOnion, snapshot)
        } catch (_: Exception) {
            // Observability must not interrupt capture or playout.
        }
    }

    private fun isQuiet(pcm: ShortArray): Boolean {
        var energy = 0L
        for (sample in pcm) {
            val value = sample.toLong()
            energy += value * value
        }
        return energy < QUIET_RMS_THRESHOLD_SQUARED * pcm.size
    }

    private companion object {
        const val INCOMING_QUEUE_FRAMES = 75 // 1.5 s at the 20 ms media cadence.
        const val OUTGOING_QUEUE_FRAMES = 2
        const val QUIET_STREAK_FOR_CATCH_UP = 2
        const val QUIET_RMS_THRESHOLD = 250L
        const val QUIET_RMS_THRESHOLD_SQUARED = QUIET_RMS_THRESHOLD * QUIET_RMS_THRESHOLD
    }
}

private fun monotonicMillis(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
