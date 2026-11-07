package dev.junepark.oniontalkie.audio

import dev.junepark.oniontalkie.codec.OpusCodec
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource

@OptIn(ExperimentalTime::class)
class AudioLoopbackTest {
    @Test
    fun toneRoundTripHasSnrAboveTwentyDbAndLatencyBelowOneFrame() {
        val time = TestTimeSource()
        val originals = List(100) { frame ->
            ShortArray(960) { sample ->
                (12_000 * sin(2 * PI * 1_000 * (frame * 960 + sample) / 48_000)).toInt().toShort()
            }
        }
        var next = 0
        val input = FakeAudioInput {
            time += 4.milliseconds
            originals.getOrNull(next++)
        }
        val output = CapturingAudioOutput { time += 1.milliseconds }
        val codec = FakeOpusCodec(
            onEncode = { time += 2.milliseconds },
            onDecode = { time += 3.milliseconds },
        )
        val clock = FrameClock(time)
        val loopback = AudioLoopback(input, output, codec, clock, time)
        input.start()
        output.start()
        try {
            repeat(originals.size) { i ->
                val result = loopback.processNextFrame()!!
                assertEquals(i, result.frame.seq)
                assertEquals(i * 20L, result.frame.tsMs)
                assertEquals(10.milliseconds, result.latency)
                assertTrue(result.latency < AudioFormat.FRAME_DURATION_MS.milliseconds)
                assertContentEquals(output.frames.last(), result.frame.pcm)
                time += clock.delayUntilNextFrame()
            }
            assertNull(loopback.processNextFrame())
        } finally {
            input.stop()
            output.stop()
        }
        assertEquals(originals.size, output.frames.size)
        val snr = snrDb(
            originals.flatMap { it.asList() }.toShortArray(),
            output.frames.flatMap { it.asList() }.toShortArray(),
        )
        assertTrue(snr.isFinite(), "Fake codec must exercise finite quantization noise")
        assertTrue(snr > 20, "SNR was $snr dB")
    }

    @Test
    fun boundedRunsPreservePcmBitExactlyAndStopAtLimitOrEof() {
        val time = TestTimeSource()
        val originals = List(3) { frame ->
            ShortArray(960) { sample -> (Short.MIN_VALUE + frame * 960 + sample * 73).toShort() }
                .also { it[0] = Short.MIN_VALUE; it[1] = Short.MAX_VALUE }
        }
        var next = 0
        var writes = 0
        val input = FakeAudioInput { originals.getOrNull(next++) }
        val output = CapturingAudioOutput { time += (++writes).milliseconds }
        val loopback = AudioLoopback(input, output, PassthroughPcmCodec(), FrameClock(time), time)
        input.start()
        output.start()
        try {
            assertFailsWith<IllegalArgumentException> { loopback.run(-1) }
            val empty = loopback.run(0)
            assertEquals(0, empty.framesProcessed)
            assertEquals(0, empty.framesDropped)
            assertTrue(empty.snrDb.isNaN())
            assertEquals(0.milliseconds, empty.maxLatency)
            assertEquals(0, next)

            val bounded = loopback.run(2)
            assertEquals(2, bounded.framesProcessed)
            assertEquals(0, bounded.framesDropped)
            assertEquals(Double.POSITIVE_INFINITY, bounded.snrDb)
            assertEquals(2.milliseconds, bounded.maxLatency)
            assertEquals(2, next)

            val remainder = loopback.run(100)
            assertEquals(1, remainder.framesProcessed)
            assertEquals(0, remainder.framesDropped)
            assertEquals(Double.POSITIVE_INFINITY, remainder.snrDb)
            assertEquals(3.milliseconds, remainder.maxLatency)
            assertEquals(3, output.frames.size)
            originals.zip(output.frames).forEach { (original, decoded) ->
                assertContentEquals(original, decoded)
            }
            assertEquals(0, loopback.run(1).framesProcessed)
        } finally {
            input.stop()
            output.stop()
        }
    }

    @Test
    fun aggregateSnrMatchesReusableMetricForLossyPcm() {
        val original = ShortArray(960) { 100 }
        val input = FakeAudioInput { original }
        val output = CapturingAudioOutput()
        input.start()
        output.start()
        try {
            val result = AudioLoopback(input, output, FakeOpusCodec()).run(2)
            assertEquals(2, result.framesProcessed)
            assertEquals(0, result.framesDropped)
            assertEquals(snrDb(original, output.frames.first()), result.snrDb, 1e-10)
            assertTrue(result.snrDb > 20)
        } finally {
            input.stop()
            output.stop()
        }
    }

    @Test
    fun eofDoesNotCallCodecOrOutputOrAdvanceClock() {
        val clock = FrameClock(TestTimeSource())
        val input = FakeAudioInput { null }
        val codec = FakeOpusCodec(onEncode = { error("Unexpected encode at EOF") })
        val output = CapturingAudioOutput { error("Unexpected playback at EOF") }
        input.start()
        output.start()
        try {
            val loopback = AudioLoopback(input, output, codec, clock)
            assertNull(loopback.processNextFrame())
            assertNull(loopback.processNextFrame())
            assertEquals(0, clock.nextFrame(ShortArray(960)).seq)
            assertTrue(output.frames.isEmpty())
        } finally {
            input.stop()
            output.stop()
        }
    }

    @Test
    fun shortDecodeIsRejectedBeforePlayback() {
        val codec = object : OpusCodec by FakeOpusCodec() {
            override fun decode(packet: ByteArray?, out: ShortArray): Int = 959
        }
        val input = FakeAudioInput { ShortArray(960) }
        val output = CapturingAudioOutput()
        input.start()
        output.start()
        try {
            assertFailsWith<IllegalStateException> {
                AudioLoopback(input, output, codec).processNextFrame()
            }
            assertTrue(output.frames.isEmpty())
        } finally {
            input.stop()
            output.stop()
        }
    }

    @Test
    fun playbackErrorsPropagateToCaller() {
        val input = FakeAudioInput { ShortArray(960) }
        val output = CapturingAudioOutput { throw IllegalStateException("Playback failed") }
        input.start()
        output.start()
        try {
            val failure = assertFailsWith<IllegalStateException> {
                AudioLoopback(input, output, FakeOpusCodec()).processNextFrame()
            }
            assertEquals("Playback failed", failure.message)
        } finally {
            input.stop()
            output.stop()
        }
    }
}
