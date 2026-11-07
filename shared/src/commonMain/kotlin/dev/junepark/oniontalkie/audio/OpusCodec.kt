package dev.junepark.oniontalkie.audio

/**
 * Stateful mono Opus encoder/decoder for one call, using [AudioConstants].
 *
 * Confine each instance to one audio worker; calls must be serialized. Create a new
 * instance for each call so encoder and decoder history cannot leak across calls.
 * Encoding uses VoIP mode, 24 kbps CBR, inband FEC with 10% expected loss, and no DTX.
 * FEC redundancy is selected by Opus; [decode] performs normal decoding or PLC.
 */
interface OpusCodec {
    /** Encodes exactly 960 signed PCM samples into a newly owned Opus packet. */
    fun encode(pcm: ShortArray): ByteArray

    /**
     * Writes exactly 960 PCM samples into [out]. A null [packet] conceals one lost
     * 20 ms frame using decoder history. Non-null packets must be 20 ms mono Opus.
     * Invalid input sizes or formats throw [IllegalArgumentException].
     */
    fun decode(packet: ByteArray?, out: ShortArray)
}

/** Creates a codec with encoder complexity 5 on Android and 10 on desktop. */
expect fun createOpusCodec(): OpusCodec
