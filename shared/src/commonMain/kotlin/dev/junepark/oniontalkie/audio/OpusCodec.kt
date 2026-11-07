package dev.junepark.oniontalkie.audio

/**
 * Opus encoder + decoder for one call direction pair: 48 kHz mono, 20 ms
 * frames, 24 kbps constrained-VBR ("CBR-ish") VoIP mode, inband FEC on (10% expected loss), DTX off so
 * every 20 ms produces a packet (the receiver measures jitter from them).
 *
 * Instances are stateful and **not thread-safe**; use one per call and one
 * thread for [encode] and one for [decode] at most.
 */
interface OpusCodec {
    /**
     * Encodes one frame of [AudioConstants.FRAME_SAMPLES] samples of 16-bit mono PCM.
     *
     * @return the Opus packet (roughly 60 bytes at 24 kbps)
     * @throws IllegalArgumentException if [pcm] is not exactly one frame
     */
    fun encode(pcm: ShortArray): ByteArray

    /**
     * Decodes [packet] into [out], which must hold exactly
     * [AudioConstants.FRAME_SAMPLES] samples.
     *
     * If [packet] is `null` the packet was lost and the decoder runs packet
     * loss concealment (PLC), synthesizing a frame from its internal state.
     *
     * @return the number of samples written to [out]
     * @throws IllegalArgumentException if [out] is not exactly one frame
     */
    fun decode(packet: ByteArray?, out: ShortArray): Int

    /**
     * Recovers the frame that was lost *before* [nextPacket] from the inband
     * FEC data carried in [nextPacket], writing it to [out]. Call this instead
     * of `decode(null, out)` when the following packet is already available,
     * then decode [nextPacket] normally.
     *
     * @return the number of samples written to [out]
     */
    fun decodeFec(nextPacket: ByteArray, out: ShortArray): Int
}

/** Creates the platform's [OpusCodec], using [AudioConstants.OPUS_COMPLEXITY_ANDROID] or `_DESKTOP`. */
expect fun createOpusCodec(): OpusCodec
