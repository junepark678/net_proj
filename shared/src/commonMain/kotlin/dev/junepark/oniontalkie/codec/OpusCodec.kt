package dev.junepark.oniontalkie.codec

/**
 * Minimal codec contract the audio loopback harness needs.
 *
 * This is a placeholder until the real Concentus-backed codec (#10) lands; that
 * implementation replaces or extends this interface. Instances are stateful
 * (Opus keeps encoder/decoder history) and are not thread-safe.
 */
interface OpusCodec {
    /** Encodes one 20 ms frame of 48 kHz mono PCM into a single packet. */
    fun encode(pcm: ShortArray): ByteArray

    /**
     * Decodes [packet] into [out] and returns the number of samples written.
     * A `null` [packet] means the packet was lost: conceal it (PLC).
     */
    fun decode(packet: ByteArray?, out: ShortArray): Int
}
