package dev.junepark.oniontalkie.audio

import dev.junepark.oniontalkie.codec.OpusCodec
import kotlin.math.PI
import kotlin.math.log10
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
        var signal = 0.0
        var noise = 0.0
        originals.zip(output.frames).forEach { (original, decoded) ->
            original.indices.forEach { i ->
                val sample = original[i].toDouble()
                val error = sample - decoded[i]
                signal += sample * sample
                noise += error * error
            }
        }
        assertTrue(noise > 0, "Fake codec must exercise finite quantization noise")
        val snrDb = 10 * log10(signal / noise)
        println("Fake PCM loopback: frames=${output.frames.size}, SNR=$snrDb dB, simulated latency=10 ms/frame")
        assertTrue(snrDb > 20, "SNR was $snrDb dB")
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
