package dev.junepark.oniontalkie.audio

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
        setPacketLossPercent(10)
        setUseDTX(false)
    }
    private val decoder = OpusDecoder(AudioConstants.SAMPLE_RATE, AudioConstants.CHANNELS)
    // CBR at 24 kbps produces 60 bytes for each 20 ms frame.
    private val encoded = ByteArray(AudioConstants.BITRATE * AudioConstants.FRAME_DURATION_MS / 8_000)

    override fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size == AudioConstants.SAMPLES_PER_FRAME) { "Expected 960 mono PCM samples" }
        val size = encoder.encode(pcm, 0, AudioConstants.SAMPLES_PER_FRAME, encoded, 0, encoded.size)
        return encoded.copyOf(size)
    }

    override fun decode(packet: ByteArray?, out: ShortArray) {
        require(out.size == AudioConstants.SAMPLES_PER_FRAME) { "Expected a 960-sample output buffer" }
        if (packet != null) {
            require(packet.isNotEmpty()) { "Use null for packet-loss concealment" }
            require(OpusPacketInfo.getNumEncodedChannels(packet, 0) == AudioConstants.CHANNELS) {
                "Expected a mono Opus packet"
            }
            require(
                OpusPacketInfo.getNumSamples(packet, 0, packet.size, AudioConstants.SAMPLE_RATE) ==
                    AudioConstants.SAMPLES_PER_FRAME,
            ) { "Expected a 20 ms Opus packet" }
        }
        val samples = decoder.decode(packet, 0, packet?.size ?: 0, out, 0, out.size, false)
        check(samples == AudioConstants.SAMPLES_PER_FRAME) { "Unexpected decoded frame size: $samples" }
    }
}
