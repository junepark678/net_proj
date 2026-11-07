package dev.junepark.oniontalkie.protocol

/** Wire values use unsigned types so all bits of each protocol integer are preserved. */
sealed class Frame {
    data class Hello(val version: UByte, val displayName: String, val opusRateHint: UInt) : Frame()
    data class Accept(val version: UByte) : Frame()
    data class Audio(val seq: UShort, val tsMs: UInt, val payload: ByteArray) : Frame() {
        override fun equals(other: Any?): Boolean = other is Audio &&
            seq == other.seq && tsMs == other.tsMs && payload.contentEquals(other.payload)

        override fun hashCode(): Int = 31 * (31 * seq.hashCode() + tsMs.hashCode()) + payload.contentHashCode()
    }
    data class Ping(val tsMs: UInt) : Frame()
    data class Pong(val echoedTsMs: UInt, val peerTsMs: UInt) : Frame()
    data class Bye(val reason: ByeReason) : Frame()
}

/** Reason codes for the protocol's u8 BYE field. */
enum class ByeReason(val code: UByte) {
    NORMAL(0u),
    VERSION(1u),
    BUSY(2u),
    GLARE(3u),
    PROTOCOL_ERROR(4u),
}

/** A malformed wire value or unsupported protocol version; transport failures remain transport errors. */
class ProtocolException(val error: Error, message: String) : Exception(message) {
    enum class Error { UNKNOWN_TYPE, INVALID_LENGTH, INVALID_UTF8, UNKNOWN_REASON, TRUNCATED, TRAILING_DATA, VERSION }
}

/** Encodes/decodes exactly one frame, with strict UTF-8 and bounded length-prefixed fields. */
object FrameCodec {
    const val VERSION: UByte = 1u
    const val MAX_AUDIO_BYTES: Int = 1275
    const val MAX_NAME_BYTES: Int = 256

    fun encode(frame: Frame): ByteArray = when (frame) {
        is Frame.Hello -> {
            // A UTF-8 string cannot have fewer bytes than UTF-16 code units. Bound before allocation.
            checkLength(frame.displayName.length <= MAX_NAME_BYTES, "Display name is too long")
            val name = try {
                frame.displayName.encodeToByteArray(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                throw ProtocolException(ProtocolException.Error.INVALID_UTF8, "Invalid display name")
            }
            checkLength(name.size <= MAX_NAME_BYTES, "Display name exceeds $MAX_NAME_BYTES UTF-8 bytes")
            ByteArray(8 + name.size).apply {
                this[0] = 1
                this[1] = frame.version.toByte()
                put(2, name.size.toUInt(), 2)
                name.copyInto(this, 4)
                put(4 + name.size, frame.opusRateHint, 4)
            }
        }
        is Frame.Accept -> byteArrayOf(2, frame.version.toByte())
        is Frame.Audio -> {
            checkLength(frame.payload.size <= MAX_AUDIO_BYTES, "AUDIO exceeds $MAX_AUDIO_BYTES bytes")
            ByteArray(9 + frame.payload.size).apply {
                this[0] = 3
                put(1, frame.seq.toUInt(), 2)
                put(3, frame.tsMs, 4)
                put(7, frame.payload.size.toUInt(), 2)
                frame.payload.copyInto(this, 9)
            }
        }
        is Frame.Ping -> ByteArray(5).apply { this[0] = 4; put(1, frame.tsMs, 4) }
        is Frame.Pong -> ByteArray(9).apply {
            this[0] = 5
            put(1, frame.echoedTsMs, 4)
            put(5, frame.peerTsMs, 4)
        }
        is Frame.Bye -> byteArrayOf(6, frame.reason.code.toByte())
    }

    /** Rejects unsupported HELLO/ACCEPT versions before checking the remainder of the frame. */
    fun decode(bytes: ByteArray): Frame {
        if (bytes.isEmpty()) truncated()
        val headerSize = headerSize(bytes[0])
        if ((bytes[0].toInt() == 1 || bytes[0].toInt() == 2) && bytes.size >= 2) {
            checkVersion(bytes[1].toUByte())
        }
        if (bytes.size < headerSize) truncated()
        val size = frameSize(bytes)
        if (bytes.size < size) truncated()
        if (bytes.size > size) {
            throw ProtocolException(ProtocolException.Error.TRAILING_DATA, "Bytes after frame")
        }
        return when (bytes[0].toInt()) {
            1 -> {
                val name = try {
                    bytes.decodeToString(4, size - 4, throwOnInvalidSequence = true)
                } catch (_: CharacterCodingException) {
                    throw ProtocolException(ProtocolException.Error.INVALID_UTF8, "Invalid UTF-8 display name")
                }
                Frame.Hello(bytes[1].toUByte(), name, bytes.uint(size - 4, 4))
            }
            2 -> Frame.Accept(bytes[1].toUByte())
            3 -> Frame.Audio(bytes.uint(1, 2).toUShort(), bytes.uint(3, 4), bytes.copyOfRange(9, size))
            4 -> Frame.Ping(bytes.uint(1, 4))
            5 -> Frame.Pong(bytes.uint(1, 4), bytes.uint(5, 4))
            else -> Frame.Bye(ByeReason.entries.firstOrNull { it.code == bytes[1].toUByte() }
                ?: throw ProtocolException(ProtocolException.Error.UNKNOWN_REASON, "Unknown BYE reason"))
        }
    }

    internal fun headerSize(type: Byte): Int = when (type.toInt()) {
        1 -> 4
        2, 6 -> 2
        3, 5 -> 9
        4 -> 5
        else -> throw ProtocolException(ProtocolException.Error.UNKNOWN_TYPE, "Unknown frame type ${type.toUByte()}")
    }

    internal fun frameSize(header: ByteArray): Int = when (header[0].toInt()) {
        1 -> {
            val length = header.uint(2, 2).toInt()
            checkLength(length <= MAX_NAME_BYTES, "Display name exceeds $MAX_NAME_BYTES UTF-8 bytes")
            8 + length
        }
        3 -> {
            val length = header.uint(7, 2).toInt()
            checkLength(length <= MAX_AUDIO_BYTES, "AUDIO exceeds $MAX_AUDIO_BYTES bytes")
            9 + length
        }
        else -> headerSize(header[0])
    }

    private fun checkLength(valid: Boolean, message: String) {
        if (!valid) throw ProtocolException(ProtocolException.Error.INVALID_LENGTH, message)
    }

    internal fun checkVersion(version: UByte) {
        if (version != VERSION) {
            throw ProtocolException(ProtocolException.Error.VERSION, "Unsupported protocol version $version")
        }
    }

    internal fun truncated(): Nothing = throw ProtocolException(ProtocolException.Error.TRUNCATED, "Truncated frame")

    private fun ByteArray.uint(offset: Int, count: Int): UInt {
        var value = 0u
        repeat(count) { value = (value shl 8) or this[offset + it].toUByte().toUInt() }
        return value
    }

    private fun ByteArray.put(offset: Int, value: UInt, count: Int) {
        repeat(count) { this[offset + it] = (value shr (8 * (count - 1 - it))).toByte() }
    }
}
