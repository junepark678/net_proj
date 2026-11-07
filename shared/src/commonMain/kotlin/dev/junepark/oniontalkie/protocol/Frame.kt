package dev.junepark.oniontalkie.protocol

/**
 * One message of the Onion Talkie wire protocol (see `docs/ARCHITECTURE.md`). All multi-byte
 * integers are big-endian. [encode] and [decode] convert to and from the exact on-wire bytes;
 * [FrameReader] and [FrameWriter] do the same on a byte stream.
 */
sealed class Frame {
    /** Serializes this frame, including its leading type byte. */
    abstract fun encode(): ByteArray

    /** `type=1 | version | u16 len | utf8 displayName | u32 opusRateHint`. Always encoded with [PROTOCOL_VERSION]. */
    data class Hello(val displayName: String, val opusRateHint: UInt) : Frame() {
        private val nameBytes = displayName.encodeToByteArray()

        init {
            require(nameBytes.size <= MAX_DISPLAY_NAME_BYTES) { "display name exceeds $MAX_DISPLAY_NAME_BYTES UTF-8 bytes" }
        }

        override fun encode(): ByteArray {
            val out = ByteArray(1 + 1 + 2 + nameBytes.size + 4)
            out[0] = TYPE_HELLO.toByte()
            out[1] = PROTOCOL_VERSION.toByte()
            out.putU16(2, nameBytes.size)
            nameBytes.copyInto(out, 4)
            out.putU32(4 + nameBytes.size, opusRateHint)
            return out
        }
    }

    /** `type=2 | version`. Always encoded with [PROTOCOL_VERSION]. */
    data object Accept : Frame() {
        override fun encode() = byteArrayOf(TYPE_ACCEPT.toByte(), PROTOCOL_VERSION.toByte())
    }

    /** `type=3 | u16 seq | u32 tsMs | u16 len | opus frame`; [payload] is at most [MAX_AUDIO_PAYLOAD] bytes. */
    class Audio(val seq: UShort, val tsMs: UInt, val payload: ByteArray) : Frame() {
        init {
            require(payload.size <= MAX_AUDIO_PAYLOAD) { "audio payload exceeds $MAX_AUDIO_PAYLOAD bytes" }
        }

        override fun encode(): ByteArray {
            val out = ByteArray(AUDIO_HEADER + payload.size)
            out[0] = TYPE_AUDIO.toByte()
            out.putU16(1, seq.toInt())
            out.putU32(3, tsMs)
            out.putU16(7, payload.size)
            payload.copyInto(out, AUDIO_HEADER)
            return out
        }

        override fun equals(other: Any?) =
            other is Audio && seq == other.seq && tsMs == other.tsMs && payload.contentEquals(other.payload)

        override fun hashCode() = (seq.hashCode() * 31 + tsMs.hashCode()) * 31 + payload.contentHashCode()

        override fun toString() = "Audio(seq=$seq, tsMs=$tsMs, payload=${payload.size}B)"
    }

    /** `type=4 | u32 tsMs`. */
    data class Ping(val tsMs: UInt) : Frame() {
        override fun encode(): ByteArray {
            val out = ByteArray(5)
            out[0] = TYPE_PING.toByte()
            out.putU32(1, tsMs)
            return out
        }
    }

    /** `type=5 | u32 echoedTsMs | u32 peerTsMs`. */
    data class Pong(val echoedTsMs: UInt, val peerTsMs: UInt) : Frame() {
        override fun encode(): ByteArray {
            val out = ByteArray(9)
            out[0] = TYPE_PONG.toByte()
            out.putU32(1, echoedTsMs)
            out.putU32(5, peerTsMs)
            return out
        }
    }

    /** `type=6 | u8 reason`. */
    data class Bye(val reason: ByeReason) : Frame() {
        override fun encode() = byteArrayOf(TYPE_BYE.toByte(), reason.code.toByte())
    }

    companion object {
        /** The only protocol version this build speaks. */
        const val PROTOCOL_VERSION = 1

        /** Largest Opus packet (120 ms at the maximum bitrate), the cap for an AUDIO payload. */
        const val MAX_AUDIO_PAYLOAD = 1275

        /** Decoder-side cap on a HELLO display name, to bound memory on untrusted input. */
        const val MAX_DISPLAY_NAME_BYTES = 256

        internal const val TYPE_HELLO = 1
        internal const val TYPE_ACCEPT = 2
        internal const val TYPE_AUDIO = 3
        internal const val TYPE_PING = 4
        internal const val TYPE_PONG = 5
        internal const val TYPE_BYE = 6

        private const val AUDIO_HEADER = 1 + 2 + 4 + 2

        /**
         * Decodes exactly one frame from [bytes].
         *
         * @throws ProtocolException if [bytes] is not precisely one valid frame.
         */
        fun decode(bytes: ByteArray): Frame {
            if (bytes.isEmpty()) throw ProtocolException(ProtocolError.TRUNCATED, "empty frame")
            val type = bytes.u8(0)
            val prefix = prefixLength(type)
            if (bytes.size < prefix) throw ProtocolException(ProtocolError.TRUNCATED, "type $type needs $prefix header bytes")
            val total = prefix + trailerLength(type, bytes)
            if (bytes.size < total) throw ProtocolException(ProtocolError.TRUNCATED, "type $type needs $total bytes, got ${bytes.size}")
            if (bytes.size > total) throw ProtocolException(ProtocolError.MALFORMED, "${bytes.size - total} trailing bytes")
            return when (type) {
                TYPE_HELLO -> {
                    val len = bytes.u16(2)
                    val name = try {
                        bytes.decodeToString(4, 4 + len, throwOnInvalidSequence = true)
                    } catch (e: Exception) {
                        throw ProtocolException(ProtocolError.MALFORMED, "display name is not valid UTF-8")
                    }
                    Hello(name, bytes.u32(4 + len))
                }
                TYPE_ACCEPT -> Accept
                TYPE_AUDIO -> Audio(bytes.u16(1).toUShort(), bytes.u32(3), bytes.copyOfRange(AUDIO_HEADER, total))
                TYPE_PING -> Ping(bytes.u32(1))
                TYPE_PONG -> Pong(bytes.u32(1), bytes.u32(5))
                else -> {
                    val reason = ByeReason.fromCode(bytes.u8(1))
                        ?: throw ProtocolException(ProtocolError.MALFORMED, "unknown BYE reason ${bytes.u8(1)}")
                    Bye(reason)
                }
            }
        }

        /**
         * Number of bytes (type byte included) that must be read before the rest of the frame's
         * length is known. Throws [ProtocolException] for an unknown type.
         */
        internal fun prefixLength(type: Int): Int = when (type) {
            TYPE_HELLO -> 4
            TYPE_ACCEPT -> 2
            TYPE_AUDIO -> AUDIO_HEADER
            TYPE_PING -> 5
            TYPE_PONG -> 9
            TYPE_BYE -> 2
            else -> throw ProtocolException(ProtocolError.UNKNOWN_TYPE, "unknown frame type $type")
        }

        /**
         * Number of bytes that follow the prefix, read from the prefix at the start of [buf].
         * Validates the version and length limits so callers can allocate safely.
         */
        internal fun trailerLength(type: Int, buf: ByteArray): Int {
            if ((type == TYPE_HELLO || type == TYPE_ACCEPT) && buf.u8(1) != PROTOCOL_VERSION) {
                throw ProtocolException(ProtocolError.UNSUPPORTED_VERSION, "version ${buf.u8(1)}")
            }
            return when (type) {
                TYPE_HELLO -> {
                    val len = buf.u16(2)
                    if (len > MAX_DISPLAY_NAME_BYTES) throw ProtocolException(ProtocolError.LENGTH_EXCEEDED, "display name $len bytes")
                    len + 4
                }
                TYPE_AUDIO -> {
                    val len = buf.u16(7)
                    if (len > MAX_AUDIO_PAYLOAD) throw ProtocolException(ProtocolError.LENGTH_EXCEEDED, "audio payload $len bytes")
                    len
                }
                else -> 0
            }
        }
    }
}

/** Reason carried by [Frame.Bye]. */
enum class ByeReason(val code: Int) {
    /** Ordinary hang-up. */
    NORMAL(0),

    /** The peer's protocol version is not supported. */
    VERSION(1),

    /** The callee is already in a call. */
    BUSY(2),

    /** Glare resolution: this side keeps its own outbound connection and drops the other. */
    GLARE(3),

    /** The peer sent something invalid. */
    PROTOCOL_ERROR(4);

    internal companion object {
        fun fromCode(code: Int): ByeReason? = entries.firstOrNull { it.code == code }
    }
}

private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF

private fun ByteArray.u16(i: Int) = (u8(i) shl 8) or u8(i + 1)

private fun ByteArray.u32(i: Int) =
    ((u8(i).toUInt() shl 24) or (u8(i + 1).toUInt() shl 16) or (u8(i + 2).toUInt() shl 8) or u8(i + 3).toUInt())

private fun ByteArray.putU16(i: Int, v: Int) {
    this[i] = (v ushr 8).toByte()
    this[i + 1] = v.toByte()
}

private fun ByteArray.putU32(i: Int, v: UInt) {
    this[i] = (v shr 24).toByte()
    this[i + 1] = (v shr 16).toByte()
    this[i + 2] = (v shr 8).toByte()
    this[i + 3] = v.toByte()
}
