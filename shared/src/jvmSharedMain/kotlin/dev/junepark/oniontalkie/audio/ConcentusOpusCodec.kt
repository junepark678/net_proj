package dev.junepark.oniontalkie.audio

import dev.junepark.oniontalkie.shared.audio.AudioConstants
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusPacketInfo

internal class ConcentusOpusCodec(complexity: Int) : OpusCodec {
    private val encoder = OpusEncoder(
        AudioConstants.SAMPLE_RATE,
        AudioConstants.CHANNELS,
        OpusApplication.OPUS_APPLICATION_VOIP,
    ).apply {
        setBitrate(AudioConstants.BITRATE)
        setComplexity(complexity)
        setUseVBR(false)
        setUseInbandFEC(true)
        setPacketLossPercent(AudioConstants.EXPECTED_PACKET_LOSS_PERCENT)
        setUseDTX(false)
    }
    private val decoder = OpusDecoder(AudioConstants.SAMPLE_RATE, AudioConstants.CHANNELS)
    private val encoded = ByteArray(AudioConstants.OPUS_MAX_PACKET_BYTES)

    override fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size == AudioConstants.SAMPLES_PER_FRAME) {
            "Expected ${AudioConstants.SAMPLES_PER_FRAME} mono PCM samples"
        }
        val size = encoder.encode(pcm, 0, AudioConstants.SAMPLES_PER_FRAME, encoded, 0, encoded.size)
        return encoded.copyOf(size)
    }

    override fun decode(packet: ByteArray?, out: ShortArray): Int = decodeFrame(packet, out, false)

    override fun decodeFec(nextPacket: ByteArray, out: ShortArray): Int = decodeFrame(nextPacket, out, true)

    private fun decodeFrame(packet: ByteArray?, out: ShortArray, decodeFec: Boolean): Int {
        require(out.size == AudioConstants.SAMPLES_PER_FRAME) {
            "Expected a ${AudioConstants.SAMPLES_PER_FRAME}-sample output buffer"
        }
        if (packet != null) {
            require(packet.isNotEmpty()) { "Use null for packet-loss concealment" }
            require(OpusPacketInfo.getNumEncodedChannels(packet, 0) == AudioConstants.CHANNELS) {
                "Expected a mono Opus packet"
            }
            require(
                OpusPacketInfo.getNumSamples(packet, 0, packet.size, AudioConstants.SAMPLE_RATE) ==
                    AudioConstants.SAMPLES_PER_FRAME,
            ) { "Expected a ${AudioConstants.FRAME_DURATION_MS} ms Opus packet" }
        }
        val samples = decoder.decode(packet, 0, packet?.size ?: 0, out, 0, out.size, decodeFec)
        check(samples == AudioConstants.SAMPLES_PER_FRAME) { "Unexpected decoded frame size: $samples" }
        return samples
    }
}
