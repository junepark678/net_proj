package dev.junepark.oniontalkie.protocol

/** Why a byte sequence was rejected as a wire-protocol frame. */
enum class ProtocolError {
    /** The type byte is not one of the defined frame types. */
    UNKNOWN_TYPE,

    /** A HELLO or ACCEPT carries a protocol version this build does not speak. */
    UNSUPPORTED_VERSION,

    /** A length field exceeds the limit for its frame ([Frame.MAX_AUDIO_PAYLOAD], [Frame.MAX_DISPLAY_NAME_BYTES]). */
    LENGTH_EXCEEDED,

    /** The stream ended in the middle of a frame, or a decoded buffer is shorter than its header claims. */
    TRUNCATED,

    /** The bytes are structurally invalid: trailing data, invalid UTF-8, or an unknown BYE reason. */
    MALFORMED,

    /** A well-formed frame arrived where the protocol does not allow it (e.g. no HELLO first). */
    UNEXPECTED_FRAME,
}

/**
 * The only exception the codec throws for bad input from the peer. After one is thrown by a
 * [FrameReader] the stream is unusable and the caller must close the underlying channel.
 */
class ProtocolException(val error: ProtocolError, message: String) : Exception("$error: $message")
