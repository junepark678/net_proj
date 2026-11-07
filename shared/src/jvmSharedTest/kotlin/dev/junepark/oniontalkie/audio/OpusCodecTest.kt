package dev.junepark.oniontalkie.audio

import dev.junepark.oniontalkie.shared.audio.AudioConstants
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusBandwidth
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusException
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
        for (complexity in listOf(AudioConstants.ANDROID_OPUS_COMPLEXITY, AudioConstants.DESKTOP_OPUS_COMPLEXITY)) {
            val codec = ConcentusOpusCodec(complexity)
            val source = ShortArray(100 * AudioConstants.SAMPLES_PER_FRAME) { toneSample(it) }
            val decoded = ShortArray(source.size)
            val out = ShortArray(AudioConstants.SAMPLES_PER_FRAME)
            repeat(100) { frame ->
                val offset = frame * out.size
                assertEquals(out.size, codec.decode(codec.encode(source.copyOfRange(offset, offset + out.size)), out))
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
        assertEquals(out.size, codec.decode(null, out))
        assertContentEquals(ShortArray(out.size), out)
        repeat(20) { codec.decode(codec.encode(toneFrame(it)), out) }
        repeat(5) {
            assertEquals(out.size, codec.decode(null, out))
            assertTrue(out.any { it != Short.MAX_VALUE })
        }
        repeat(20) { codec.decode(codec.encode(toneFrame(it + 25)), out) }
        assertTrue(out.any { it.toInt() != 0 }, "Audio should resume after loss")
    }

    @Test
    fun fecReconstructsLostToneFrameBetterThanPlc() {
        for (complexity in listOf(AudioConstants.ANDROID_OPUS_COMPLEXITY, AudioConstants.DESKTOP_OPUS_COMPLEXITY)) {
            // Force wideband only in this fixture: automatic fullband hybrid
            // encoding can omit LBRR at 24 kbps CBR because of its SILK bit budget.
            val encoder = referenceEncoder().apply {
                setComplexity(complexity)
                setBitrate(AudioConstants.BITRATE)
                setUseVBR(false)
                setUseInbandFEC(true)
                setPacketLossPercent(AudioConstants.EXPECTED_PACKET_LOSS_PERCENT)
                setUseDTX(false)
                setBandwidth(OpusBandwidth.OPUS_BANDWIDTH_WIDEBAND)
            }
            val fecDecoder = ConcentusOpusCodec(complexity)
            val plcDecoder = ConcentusOpusCodec(complexity)
            val lostFrame = 50
            // Change frequency in the missing frame so recovering its content
            // is measurably better than extrapolating the previous steady tone.
            val source = ShortArray((lostFrame + 2) * AudioConstants.SAMPLES_PER_FRAME) {
                toneSample(it, frequency = if (it < lostFrame * AudioConstants.SAMPLES_PER_FRAME) 1_000 else 500)
            }
            val buffer = ByteArray(AudioConstants.OPUS_MAX_PACKET_BYTES)
            val packets = List(lostFrame + 2) { frame ->
                val size = encoder.encode(
                    source, frame * AudioConstants.SAMPLES_PER_FRAME, AudioConstants.SAMPLES_PER_FRAME,
                    buffer, 0, buffer.size,
                )
                buffer.copyOf(size)
            }
            val fecOut = ShortArray(AudioConstants.SAMPLES_PER_FRAME)
            val plcOut = ShortArray(fecOut.size)
            repeat(lostFrame) { frame ->
                assertEquals(fecOut.size, fecDecoder.decode(packets[frame], fecOut))
                assertEquals(plcOut.size, plcDecoder.decode(packets[frame], plcOut))
                assertContentEquals(fecOut, plcOut)
            }
            // Determine the tone's filter/codec delay from the last good frame,
            // using the same bounded alignment as the round-trip quality test.
            val lookahead = encoder.lookahead
            val halfPeriod = AudioConstants.SAMPLE_RATE / 1_000 / 2
            val delay = (lookahead - halfPeriod..lookahead + halfPeriod).maxBy {
                snr(source, fecOut, (lostFrame - 1) * fecOut.size - it)
            }
            // Drop packet k. The decoders have identical history; only the FEC
            // decoder receives packet k+1 to reconstruct k before decoding k+1.
            assertEquals(fecOut.size, fecDecoder.decodeFec(packets[lostFrame + 1], fecOut))
            assertEquals(plcOut.size, plcDecoder.decode(null, plcOut))
            assertTrue(!fecOut.contentEquals(plcOut), "FEC must use redundancy rather than only PLC")
            val offset = lostFrame * fecOut.size - delay
            val fecSnr = snr(source, fecOut, offset)
            val plcSnr = snr(source, plcOut, offset)
            println("Opus complexity=$complexity lost-frame FEC SNR=$fecSnr dB, PLC SNR=$plcSnr dB")
            assertTrue(fecSnr > plcSnr, "FEC SNR $fecSnr must exceed PLC SNR $plcSnr")
            assertEquals(fecOut.size, fecDecoder.decode(packets[lostFrame + 1], fecOut))
            assertTrue(fecOut.any { it.toInt() != 0 }, "Next packet must still decode normally after FEC")
        }
    }

    @Test
    fun malformedOpusPayloadThrowsInsteadOfProducingAudio() {
        // Mono, two 10 ms SILK frames: the declared first-frame length (127)
        // exceeds the remaining payload. Duration/channel checks still pass.
        val malformed = byteArrayOf(0x02, 0x7f)
        val out = ShortArray(AudioConstants.SAMPLES_PER_FRAME)
        assertFailsWith<OpusException> { createOpusCodec().decode(malformed, out) }
        assertFailsWith<OpusException> { createOpusCodec().decodeFec(malformed, out) }
    }

    @Test
    fun fixedInputProducesIdenticalPacketsAcrossFreshEncoders() {
        for (complexity in listOf(AudioConstants.ANDROID_OPUS_COMPLEXITY, AudioConstants.DESKTOP_OPUS_COMPLEXITY)) {
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
            assertFailsWith<IllegalArgumentException> { codec.decodeFec(codec.encode(toneFrame(0)), ShortArray(size)) }
        }
        assertFailsWith<IllegalArgumentException> { codec.decode(byteArrayOf(), out) }
        assertFailsWith<IllegalArgumentException> { codec.decodeFec(byteArrayOf(), out) }
        assertFailsWith<IllegalArgumentException> { codec.decode(byteArrayOf(3), out) }
        for ((channels, samples) in listOf(2 to 960, 1 to 480, 1 to 1920)) {
            val encoder = referenceEncoder(channels)
            val buffer = ByteArray(AudioConstants.OPUS_MAX_PACKET_BYTES)
            val size = encoder.encode(ShortArray(channels * samples), 0, samples, buffer, 0, buffer.size)
            assertFailsWith<IllegalArgumentException> { codec.decode(buffer.copyOf(size), out) }
            assertFailsWith<IllegalArgumentException> { codec.decodeFec(buffer.copyOf(size), out) }
        }
        assertEquals(out.size, codec.decode(codec.encode(toneFrame(0)), out))
    }

    @Test
    fun encodeAndDecodeAverageLessThan5MsPerFrame() {
        // Warm up the JVM before measuring, including codec allocation/GC costs
        // inside encode/decode, but excluding initial construction and tone generation.
        for (complexity in listOf(AudioConstants.ANDROID_OPUS_COMPLEXITY, AudioConstants.DESKTOP_OPUS_COMPLEXITY)) {
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

    private fun snr(source: ShortArray, decoded: ShortArray, sourceOffset: Int): Double {
        var signal = 0.0
        var noise = 0.0
        for (i in decoded.indices) {
            val expected = source[sourceOffset + i].toDouble()
            val error = expected - decoded[i]
            signal += expected * expected
            noise += error * error
        }
        return 10 * log10(signal / noise)
    }

    private fun toneFrame(frame: Int) = ShortArray(AudioConstants.SAMPLES_PER_FRAME) {
        toneSample(frame * AudioConstants.SAMPLES_PER_FRAME + it)
    }

    private fun toneSample(sample: Int, frequency: Int = 1_000): Short =
        (16_000 * sin(2 * PI * frequency * sample / AudioConstants.SAMPLE_RATE)).toInt().toShort()

    private fun referenceEncoder(channels: Int = 1) = OpusEncoder(
        AudioConstants.SAMPLE_RATE, channels, OpusApplication.OPUS_APPLICATION_VOIP,
    )
}
