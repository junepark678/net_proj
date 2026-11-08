package dev.junepark.oniontalkie.call

import dev.junepark.oniontalkie.audio.AudioFormat
import dev.junepark.oniontalkie.audio.AudioInput
import dev.junepark.oniontalkie.audio.AudioOutput
import dev.junepark.oniontalkie.audio.AudioRouteController
import dev.junepark.oniontalkie.audio.OpusCodec
import dev.junepark.oniontalkie.protocol.Frame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OpusCallSessionTest {
    @Test
    fun sessionCapturesSendsPlaysReceivedAudioAndRestoresCallRoute() = runTest {
        val input = StreamingInput()
        val output = RecordingOutput()
        val codec = RecordingCodec()
        val route = RecordingRoute()
        val sent = Channel<Frame.Audio>(Channel.UNLIMITED)
        val metricSnapshots = mutableListOf<CallMediaMetrics>()
        val factory = OpusCallSessionFactory(
            inputFactory = { input },
            outputFactory = { output },
            codecFactory = { codec },
            routeController = route,
            audioDispatcher = StandardTestDispatcher(testScheduler),
            clockMs = { testScheduler.currentTime },
            playoutDelay = { delay(it) },
            metricsListener = { _, metrics -> metricSnapshots += metrics },
        )
        val session = factory.create("peer.onion")
        session.receive(frame(0))
        session.receive(frame(2))
        session.receive(frame(3))

        val job = backgroundScope.launch { session.run { sent.send(it) } }
        runCurrent()
        withTimeout(2_000) { input.firstRead.await() }
        runCurrent()
        advanceTimeBy(80)
        runCurrent()
        job.cancelAndJoin()
        session.close()

        assertTrue(sent.tryReceive().isSuccess, "capture should encode and send an AUDIO frame")
        assertTrue(output.frames.size >= 4, "playout should write frames at 20 ms cadence")
        assertEquals(AudioFormat.SAMPLES_PER_FRAME, output.frames.first().size)
        assertTrue(codec.plcDecodes >= 1, "the missing sequence should request Opus PLC")
        assertEquals(1, route.begins)
        assertEquals(1, route.ends)
        assertFalse(input.started)
        assertFalse(output.started)
        assertTrue(metricSnapshots.any { it.jitterBuffer.plc > 0 })
    }

    @Test
    fun receiveDropsOldestWhenItsBoundedQueueIsFull() = runTest {
        val input = StreamingInput()
        val output = RecordingOutput()
        val route = RecordingRoute()
        val metrics = mutableListOf<CallMediaMetrics>()
        val session = OpusCallSessionFactory(
            inputFactory = { input },
            outputFactory = { output },
            codecFactory = { RecordingCodec() },
            routeController = route,
            audioDispatcher = StandardTestDispatcher(testScheduler),
            clockMs = { testScheduler.currentTime },
            playoutDelay = { delay(it) },
            metricsListener = { _, snapshot -> metrics += snapshot },
        ).create("peer.onion")

        repeat(80) { session.receive(frame(it)) }
        val job = backgroundScope.launch { session.run {} }
        runCurrent()
        withTimeout(2_000) { input.firstRead.await() }
        runCurrent()
        advanceTimeBy(20)
        runCurrent()
        job.cancelAndJoin()
        session.close()

        assertTrue(metrics.any { it.incomingQueueDrops == 5L })
    }

    private fun frame(sequence: Int) = Frame.Audio(
        seq = sequence.toUShort(),
        tsMs = (sequence * 20).toUInt(),
        payload = byteArrayOf(7),
    )

    private class StreamingInput : AudioInput {
        val firstRead = CompletableDeferred<Unit>()
        var started = false
            private set
        private var readCount = 0

        override fun start() {
            check(!started)
            started = true
        }

        override fun read(): ShortArray? {
            check(started)
            if (readCount++ > 0) Thread.sleep(2)
            firstRead.complete(Unit)
            return if (started) ShortArray(AudioFormat.SAMPLES_PER_FRAME) { 400 } else null
        }

        override fun stop() {
            started = false
        }
    }

    private class RecordingOutput : AudioOutput {
        var started = false
            private set
        val frames = mutableListOf<ShortArray>()
        override val underrunCount: Long get() = 3

        override fun start() {
            check(!started)
            started = true
        }

        override fun write(pcm: ShortArray) {
            check(started)
            frames += pcm.copyOf()
        }

        override fun stop() {
            started = false
        }
    }

    private class RecordingCodec : OpusCodec {
        var plcDecodes = 0
            private set

        override fun encode(pcm: ShortArray): ByteArray = byteArrayOf(7)

        override fun decode(packet: ByteArray?, out: ShortArray): Int {
            if (packet == null) {
                plcDecodes++
                out.fill(0)
            } else {
                out.fill(400)
            }
            return out.size
        }

        override fun decodeFec(nextPacket: ByteArray, out: ShortArray): Int = decode(nextPacket, out)
    }

    private class RecordingRoute : AudioRouteController {
        var begins = 0
        var ends = 0
        override fun beginCommunication() { begins++ }
        override fun endCommunication() { ends++ }
        override fun setSpeakerphone(enabled: Boolean): Boolean = true
        override fun isSpeakerphoneSelected(): Boolean = false
    }
}
