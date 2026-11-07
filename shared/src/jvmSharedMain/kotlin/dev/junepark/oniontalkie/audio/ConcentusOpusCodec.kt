package dev.junepark.oniontalkie.audio

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder

/**
 * [OpusCodec] backed by Concentus, the pure-JVM Opus port (no native libopus).
 *
 * @param complexity encoder complexity 0..10 (see [AudioConstants])
 */
class ConcentusOpusCodec(complexity: Int) : OpusCodec {
    private val encoder = OpusEncoder(
        AudioConstants.SAMPLE_RATE,
        AudioConstants.CHANNELS,
        OpusApplication.OPUS_APPLICATION_VOIP,
    ).apply {
        bitrate = AudioConstants.OPUS_BITRATE_BPS
        setComplexity(complexity)
        // Constrained VBR is "CBR-ish": packet sizes stay near 60 B so TCP pacing is
        // predictable. Strict CBR measurably hurts quality (a 1 kHz tone drops to
        // 17 dB SNR at complexity 5), constrained VBR gives 24+ dB at ~68 B/packet.
        useVBR = true
        useConstrainedVBR = true
        useInbandFEC = true
        packetLossPercent = AudioConstants.OPUS_EXPECTED_LOSS_PERCENT
        // Walkie-talkie semantics: a continuous packet stream feeds jitter measurement.
        useDTX = false
    }

    private val decoder = OpusDecoder(AudioConstants.SAMPLE_RATE, AudioConstants.CHANNELS)

    private val packetBuffer = ByteArray(AudioConstants.OPUS_MAX_PACKET_BYTES)

    override fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size == AudioConstants.FRAME_SAMPLES) {
            "expected ${AudioConstants.FRAME_SAMPLES} samples, got ${pcm.size}"
        }
        val length = encoder.encode(pcm, 0, AudioConstants.FRAME_SAMPLES, packetBuffer, 0, packetBuffer.size)
        return packetBuffer.copyOf(length)
    }

    override fun decode(packet: ByteArray?, out: ShortArray): Int {
        requireFrame(out)
        return if (packet == null) {
            decoder.decode(null, 0, 0, out, 0, AudioConstants.FRAME_SAMPLES, false)
        } else {
            decoder.decode(packet, 0, packet.size, out, 0, AudioConstants.FRAME_SAMPLES, false)
        }
    }

    override fun decodeFec(nextPacket: ByteArray, out: ShortArray): Int {
        requireFrame(out)
        return decoder.decode(nextPacket, 0, nextPacket.size, out, 0, AudioConstants.FRAME_SAMPLES, true)
    }

    private fun requireFrame(out: ShortArray) {
        require(out.size == AudioConstants.FRAME_SAMPLES) {
            "expected ${AudioConstants.FRAME_SAMPLES} samples, got ${out.size}"
        }
    }
}
