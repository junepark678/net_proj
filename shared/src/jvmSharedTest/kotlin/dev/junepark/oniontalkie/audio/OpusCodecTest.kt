package dev.junepark.oniontalkie.audio

import dev.junepark.oniontalkie.shared.audio.AudioConstants
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpusCodecTest {
    @Test
    fun toneRoundTripHasMoreThan20DbSnr() {
        for (complexity in listOf(5, 10)) {
            val codec = ConcentusOpusCodec(complexity)
            val source = ShortArray(100 * AudioConstants.SAMPLES_PER_FRAME) { toneSample(it) }
            val decoded = ShortArray(source.size)
            val out = ShortArray(AudioConstants.SAMPLES_PER_FRAME)
            repeat(100) { frame ->
                val offset = frame * out.size
                codec.decode(codec.encode(source.copyOfRange(offset, offset + out.size)), out)
                out.copyInto(decoded, offset)
            }
            // Allow one second for codec adaptation, then align within half a
            // tone period of the reported lookahead: VoIP filters also add phase
            // shift. Compare actual PCM amplitudes without gain normalization.
            val lookahead = referenceEncoder().lookahead
            val halfPeriod = AudioConstants.SAMPLE_RATE / 1_000 / 2
            val aligned = (lookahead - halfPeriod..lookahead + halfPeriod).map { delay ->
                var signal = 0.0
                var noise = 0.0
                for (i in AudioConstants.SAMPLE_RATE until source.size - lookahead - halfPeriod) {
                    val expected = source[i].toDouble()
                    val error = expected - decoded[i + delay]
                    signal += expected * expected
                    noise += error * error
                }
                delay to 10 * log10(signal / noise)
            }
            val (delay, snr) = aligned.maxBy { it.second }
            println("Opus complexity=$complexity tone SNR=$snr dB, delay=$delay samples")
            assertTrue(snr > 20, "Tone SNR must exceed 20 dB, got $snr at complexity $complexity")
        }
    }

    @Test
    fun toneAndSilenceStayWithinPacketBudget() {
        val codec = createOpusCodec()
        for (silence in listOf(false, true)) {
            val sizes = (0 until 100).map { frame ->
                codec.encode(if (silence) ShortArray(AudioConstants.SAMPLES_PER_FRAME) else toneFrame(frame)).size
            }
            val average = sizes.average()
            println("Opus silence=$silence average packet=$average bytes")
            assertTrue(average in 50.0..70.0, "Average packet size was $average bytes")
            // DTX must remain disabled even after sustained silence.
            assertTrue(sizes.all { it >= 50 })
        }
    }

    @Test
    fun packetLossConcealmentHandlesStartupBurstAndRecovery() {
        val codec = createOpusCodec()
        val out = ShortArray(AudioConstants.SAMPLES_PER_FRAME) { Short.MAX_VALUE }
        codec.decode(null, out)
        assertContentEquals(ShortArray(out.size), out)
        repeat(20) { codec.decode(codec.encode(toneFrame(it)), out) }
        repeat(5) {
            codec.decode(null, out)
            assertTrue(out.any { it != Short.MAX_VALUE })
        }
        repeat(20) { codec.decode(codec.encode(toneFrame(it + 25)), out) }
        assertTrue(out.any { it.toInt() != 0 }, "Audio should resume after loss")
    }

    @Test
    fun fixedInputProducesIdenticalPacketsAcrossFreshEncoders() {
        for (complexity in listOf(5, 10)) {
            val first = ConcentusOpusCodec(complexity)
            val second = ConcentusOpusCodec(complexity)
            repeat(100) { frame ->
                val pcm = toneFrame(frame)
                val original = pcm.copyOf()
                assertContentEquals(first.encode(pcm), second.encode(pcm), "Frame $frame differs")
                assertContentEquals(original, pcm, "Encoder modified input PCM")
            }
        }
    }

    @Test
    fun returnedPacketsOwnTheirStorageAndDecodeWithAnIndependentDecoder() {
        val codec = createOpusCodec()
        val packet = codec.encode(toneFrame(0))
        val saved = packet.copyOf()
        repeat(10) { codec.encode(toneFrame(it + 1)) }
        assertContentEquals(saved, packet)
        val samples = OpusDecoder(AudioConstants.SAMPLE_RATE, 1).decode(
            packet, 0, packet.size, ShortArray(AudioConstants.SAMPLES_PER_FRAME), 0,
            AudioConstants.SAMPLES_PER_FRAME, false,
        )
        assertEquals(AudioConstants.SAMPLES_PER_FRAME, samples)
    }

    @Test
    fun rejectsInvalidBuffersAndPacketFormats() {
        val codec = createOpusCodec()
        val out = ShortArray(AudioConstants.SAMPLES_PER_FRAME)
        for (size in listOf(0, 959, 961)) {
            assertFailsWith<IllegalArgumentException> { codec.encode(ShortArray(size)) }
            assertFailsWith<IllegalArgumentException> { codec.decode(null, ShortArray(size)) }
        }
        assertFailsWith<IllegalArgumentException> { codec.decode(byteArrayOf(), out) }
        assertFailsWith<IllegalArgumentException> { codec.decode(byteArrayOf(3), out) }
        for ((channels, samples) in listOf(2 to 960, 1 to 480, 1 to 1920)) {
            val encoder = referenceEncoder(channels)
            val buffer = ByteArray(1_275)
            val size = encoder.encode(ShortArray(channels * samples), 0, samples, buffer, 0, buffer.size)
            assertFailsWith<IllegalArgumentException> { codec.decode(buffer.copyOf(size), out) }
        }
        codec.decode(codec.encode(toneFrame(0)), out)
    }

    @Test
    fun encodeAndDecodeAverageLessThan5MsPerFrame() {
        // Warm up the JVM before measuring, including codec allocation/GC costs
        // inside encode/decode, but excluding initial construction and tone generation.
        for (complexity in listOf(5, 10)) {
            val codec = ConcentusOpusCodec(complexity)
            val pcm = toneFrame(0)
            val out = ShortArray(AudioConstants.SAMPLES_PER_FRAME)
            repeat(300) { codec.decode(codec.encode(pcm), out) }
            val frames = 600
            val elapsed = measureNanoTime {
                repeat(frames) { codec.decode(codec.encode(pcm), out) }
            }
            val msPerFrame = elapsed / 1_000_000.0 / frames
            println("Opus complexity=$complexity encode+decode: $msPerFrame ms/frame ($frames frames)")
            assertTrue(msPerFrame < 5.0, "Encode+decode took $msPerFrame ms/frame")
        }
    }

    private fun toneFrame(frame: Int) = ShortArray(AudioConstants.SAMPLES_PER_FRAME) {
        toneSample(frame * AudioConstants.SAMPLES_PER_FRAME + it)
    }

    private fun toneSample(sample: Int): Short =
        (16_000 * sin(2 * PI * 1_000 * sample / AudioConstants.SAMPLE_RATE)).toInt().toShort()

    private fun referenceEncoder(channels: Int = 1) = OpusEncoder(
        AudioConstants.SAMPLE_RATE, channels, OpusApplication.OPUS_APPLICATION_VOIP,
    )
}
