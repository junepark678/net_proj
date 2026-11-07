package dev.junepark.oniontalkie.codec

/**
 * Temporary codec boundary until #10 supplies Opus. Uses 48 kHz mono, 960-sample
 * frames. No codec implementation or Opus quality claim is provided here.
 */
interface OpusCodec {
    /** Encodes exactly one frame without modifying [pcm]. */
    fun encode(pcm: ShortArray): ByteArray

    /** Decodes into [out], returning samples written; null requests loss concealment. */
    fun decode(packet: ByteArray?, out: ShortArray): Int
}
