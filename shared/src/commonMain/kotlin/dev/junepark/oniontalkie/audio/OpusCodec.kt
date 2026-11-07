package dev.junepark.oniontalkie.audio

import dev.junepark.oniontalkie.shared.audio.AudioConstants

/**
 * Stateful mono Opus encoder/decoder for one call, using [AudioConstants].
 *
 * One encode thread and one decode thread may use this codec concurrently:
 * encoder and decoder instances are independently single-threaded. Create a new
 * codec for each call so encoder and decoder history cannot leak across calls.
 * Encoding uses VoIP mode, 24 kbps CBR, inband FEC with 10% expected loss, and no DTX.
 * FEC redundancy is selected by Opus.
 */
interface OpusCodec {
    /** Encodes exactly [AudioConstants.SAMPLES_PER_FRAME] signed PCM samples into a newly owned Opus packet. */
    fun encode(pcm: ShortArray): ByteArray

    /**
     * Writes one frame into [out] and returns the number of samples written
     * ([AudioConstants.SAMPLES_PER_FRAME]). Non-null packets must be 20 ms mono Opus.
     * A null [packet] conceals one lost frame using decoder history (PLC); use
     * [decodeFec] instead when the next packet is available in time for playout.
     *
     * Invalid buffer sizes, durations, or channel counts throw [IllegalArgumentException].
     * Malformed Opus payloads rejected by the decoder throw Concentus `OpusException`
     * on Android and desktop, rather than silently producing audio.
     */
    fun decode(packet: ByteArray?, out: ShortArray): Int

    /**
     * Reconstructs the lost current frame using LBRR (inband FEC) data from
     * [nextPacket], writing into [out] and returning the number of samples written
     * ([AudioConstants.SAMPLES_PER_FRAME]). Then call [decode] with the same
     * [nextPacket] to decode its own frame. These calls share decoder history and
     * must run on the decode thread in playout order.
     *
     * Use this when the next packet arrives before the lost frame's playout
     * deadline; otherwise use `decode(null, out)` for PLC. If [nextPacket] has no
     * FEC data, Opus falls back to PLC. Buffer and packet validation and malformed
     * payload exceptions are the same as for [decode].
     */
    fun decodeFec(nextPacket: ByteArray, out: ShortArray): Int
}

/** Creates a codec with encoder complexity 5 on Android and 10 on desktop. */
expect fun createOpusCodec(): OpusCodec
