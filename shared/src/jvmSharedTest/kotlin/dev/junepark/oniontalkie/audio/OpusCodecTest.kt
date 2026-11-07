package dev.junepark.oniontalkie.audio

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpusCodecTest {
    private val frame = AudioConstants.FRAME_SAMPLES

    /** [frames] consecutive 20 ms frames of a 1 kHz sine at amplitude 10000. */
    private fun tone(frames: Int): ShortArray = ShortArray(frames * frame) { i ->
        (10_000 * sin(2 * PI * 1000.0 * i / AudioConstants.SAMPLE_RATE)).toInt().toShort()
    }

    private fun encodeAll(codec: OpusCodec, pcm: ShortArray): List<ByteArray> =
        (0 until pcm.size / frame).map { codec.encode(pcm.copyOfRange(it * frame, (it + 1) * frame)) }

    private fun decodeAll(codec: OpusCodec, packets: List<ByteArray?>): ShortArray {
        val out = ShortArray(packets.size * frame)
        val buf = ShortArray(frame)
        packets.forEachIndexed { i, p ->
            assertEquals(frame, codec.decode(p, buf))
            buf.copyInto(out, i * frame)
        }
        return out
    }

    /** SNR in dB after compensating the codec delay by searching the best alignment lag. */
    private fun snrDb(reference: ShortArray, decoded: ShortArray, skip: Int): Double {
        var best = Double.NEGATIVE_INFINITY
        for (lag in 0..600) {
            var signal = 0.0
            var noise = 0.0
            for (i in skip until reference.size - lag) {
                val r = reference[i].toDouble()
                val d = decoded[i + lag].toDouble()
                signal += r * r
                noise += (r - d) * (r - d)
            }
            best = maxOf(best, 10 * log10(signal / noise))
        }
        return best
    }

    @Test
    fun toneRoundTripSnrAbove20Db() {
        val pcm = tone(50)
        val decoded = decodeAll(createOpusCodec(), encodeAll(createOpusCodec(), pcm))
        val snr = snrDb(pcm, decoded, skip = 5 * frame)
        println("1 kHz tone round-trip SNR: %.1f dB".format(snr))
        assertTrue(snr > 20.0, "SNR was $snr dB")
    }

    @Test
    fun snrHoldsAtBothPlatformComplexities() {
        val pcm = tone(50)
        for (c in listOf(AudioConstants.OPUS_COMPLEXITY_ANDROID, AudioConstants.OPUS_COMPLEXITY_DESKTOP)) {
            val decoded = decodeAll(ConcentusOpusCodec(c), encodeAll(ConcentusOpusCodec(c), pcm))
            assertTrue(snrDb(pcm, decoded, skip = 5 * frame) > 20.0, "complexity $c")
        }
    }

    @Test
    fun averagePacketSizeIsAbout24Kbps() {
        val packets = encodeAll(createOpusCodec(), tone(100))
        val average = packets.sumOf { it.size } / packets.size.toDouble()
        println("average packet size: %.1f bytes (min %d, max %d)".format(average, packets.minOf { it.size }, packets.maxOf { it.size }))
        assertTrue(average in 50.0..70.0, "average was $average bytes")
    }

    @Test
    fun everyFrameProducesAPacketEvenForSilence() {
        // DTX is off: silence must still yield a real (non-empty) packet every 20 ms.
        val codec = createOpusCodec()
        repeat(50) { assertTrue(codec.encode(ShortArray(frame)).size > 2) }
    }

    @Test
    fun packetLossConcealmentDecodesWithoutException() {
        val packets = encodeAll(createOpusCodec(), tone(20)).toMutableList<ByteArray?>()
        packets[10] = null
        packets[11] = null
        val decoded = decodeAll(createOpusCodec(), packets)
        assertEquals(20 * frame, decoded.size)
        // Concealment of a steady tone must not be silence.
        assertTrue(decoded.copyOfRange(10 * frame, 11 * frame).any { it.toInt() != 0 })
    }

    @Test
    fun fecRecoversLostFrameFromNextPacket() {
        val packets = encodeAll(createOpusCodec(), tone(20))
        val decoder = createOpusCodec()
        val buf = ShortArray(frame)
        for (i in 0 until 9) decoder.decode(packets[i], buf)
        // Frame 9 is lost; frame 10's packet carries FEC for it.
        assertEquals(frame, decoder.decodeFec(packets[10], buf))
        assertEquals(frame, decoder.decode(packets[10], buf))
    }

    @Test
    fun encoderIsDeterministic() {
        val pcm = tone(30)
        val a = encodeAll(createOpusCodec(), pcm)
        val b = encodeAll(createOpusCodec(), pcm)
        assertEquals(a.size, b.size)
        a.indices.forEach { assertContentEquals(a[it], b[it], "frame $it differs") }
    }

    @Test
    fun rejectsWrongFrameSize() {
        val codec = createOpusCodec()
        assertFailsWith<IllegalArgumentException> { codec.encode(ShortArray(frame - 1)) }
        assertFailsWith<IllegalArgumentException> { codec.decode(null, ShortArray(frame + 1)) }
    }

    @Test
    fun encodeDecodeTimePerFrameIsFarBelowFrameBudget() {
        val codec = createOpusCodec()
        val pcm = tone(1)
        val out = ShortArray(frame)
        repeat(200) { codec.decode(codec.encode(pcm), out) } // JIT warm-up
        val iterations = 500
        val start = System.nanoTime()
        repeat(iterations) { codec.decode(codec.encode(pcm), out) }
        val perFrameMs = (System.nanoTime() - start) / 1e6 / iterations
        println("opus encode+decode: %.3f ms per 20 ms frame".format(perFrameMs))
        assertTrue(perFrameMs < 5.0, "took $perFrameMs ms per frame")
    }
}
