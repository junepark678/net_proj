package dev.junepark.oniontalkie.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioLoopbackTest {
    private fun runLoopback(source: ShortArray, dropBits: Int = 4): Triple<LoopbackResult, CapturingAudioOutput, FakeAudioInput> {
        val input = FakeAudioInput(source)
        val output = CapturingAudioOutput()
        val result = AudioLoopback(input, output, FakeOpusCodec(dropBits), FakeOpusCodec(dropBits)).run()
        return Triple(result, output, input)
    }

    @Test
    fun toneSurvivesRoundTripWithHighSnr() {
        val source = tone(440.0, seconds = 1.0)
        val (result, output) = runLoopback(source)
        assertEquals(50, result.framesProcessed)
        assertEquals(source.size, output.samples().size)
        val snr = snrDb(source, output.samples())
        assertTrue(snr > 20.0, "SNR was $snr dB")
    }

    @Test
    fun frameLatencyIsBounded() {
        val (result, output) = runLoopback(tone(440.0, seconds = 1.0))
        assertTrue(output.frames.all { it.size == AudioFormat.SAMPLES_PER_FRAME })
        // Processing a frame must stay within two frame periods (slack for CI jitter / JIT warm-up).
        assertTrue(result.maxLatencyMs < 2.0 * AudioFormat.FRAME_MS, "max latency ${result.maxLatencyMs} ms")
        assertTrue(result.meanLatencyMs <= result.maxLatencyMs)
    }

    @Test
    fun devicesAreStartedAndClosed() {
        val (_, output, input) = runLoopback(tone(440.0, seconds = 0.1))
        assertTrue(input.started && input.closed)
        assertTrue(output.started && output.closed)
    }

    @Test
    fun shortFinalReadIsZeroPaddedToAFullFrame() {
        val source = ShortArray(AudioFormat.SAMPLES_PER_FRAME + 100) { 1000 }
        val (result, output) = runLoopback(source, dropBits = 0)
        assertEquals(2, result.framesProcessed)
        val last = output.frames.last()
        assertEquals(AudioFormat.SAMPLES_PER_FRAME, last.size)
        assertEquals(1000, last[99].toInt())
        assertEquals(0, last[100].toInt())
    }

    @Test
    fun maxFramesLimitsTheRun() {
        val result = AudioLoopback(
            FakeAudioInput(tone(440.0, seconds = 1.0)), CapturingAudioOutput(), FakeOpusCodec(), FakeOpusCodec(),
        ).run(maxFrames = 3)
        assertEquals(3, result.framesProcessed)
    }

    @Test
    fun losslessCodecIsBitExact() {
        val source = tone(1000.0, seconds = 0.2)
        val (_, output) = runLoopback(source, dropBits = 0)
        assertContentEquals(source, output.samples())
        assertEquals(Double.POSITIVE_INFINITY, snrDb(source, output.samples()))
    }
}
