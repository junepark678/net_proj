package dev.junepark.oniontalkie.protocol

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FrameCodecTest {
    private fun hex(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun helloLayoutIsBigEndian() {
        // 한 = ED 95 9C (3 bytes)
        val bytes = Frame.Hello("a한", 0x0000BB80u).encode()
        assertContentEquals(hex(1, 1, 0, 4, 'a'.code, 0xED, 0x95, 0x9C, 0, 0, 0xBB, 0x80), bytes)
        assertEquals(Frame.Hello("a한", 48_000u), Frame.decode(bytes))
    }

    @Test
    fun acceptLayout() {
        assertContentEquals(hex(2, 1), Frame.Accept.encode())
        assertEquals(Frame.Accept, Frame.decode(hex(2, 1)))
    }

    @Test
    fun audioLayoutIsBigEndian() {
        val frame = Frame.Audio(0x0102u, 0x03040506u, hex(0xAA, 0xBB, 0xCC))
        val bytes = frame.encode()
        assertContentEquals(hex(3, 1, 2, 3, 4, 5, 6, 0, 3, 0xAA, 0xBB, 0xCC), bytes)
        assertEquals(frame, Frame.decode(bytes))
    }

    @Test
    fun pingPongByeLayouts() {
        assertContentEquals(hex(4, 0xFF, 0xFF, 0xFF, 0xFE), Frame.Ping(0xFFFFFFFEu).encode())
        assertEquals(Frame.Ping(0xFFFFFFFEu), Frame.decode(hex(4, 0xFF, 0xFF, 0xFF, 0xFE)))
        assertContentEquals(hex(5, 0, 0, 0, 1, 0, 0, 1, 0), Frame.Pong(1u, 256u).encode())
        assertEquals(Frame.Pong(1u, 256u), Frame.decode(hex(5, 0, 0, 0, 1, 0, 0, 1, 0)))
        assertContentEquals(hex(6, 1), Frame.Bye(ByeReason.VERSION).encode())
        assertEquals(Frame.Bye(ByeReason.VERSION), Frame.decode(hex(6, 1)))
    }

    @Test
    fun everyFrameTypeRoundTrips() {
        val frames = listOf(
            Frame.Hello("", 0u),
            Frame.Hello("june", 48_000u),
            Frame.Accept,
            Frame.Audio(0u, 0u, ByteArray(0)),
            Frame.Audio(65535u, UInt.MAX_VALUE, Random(1).nextBytes(60)),
            Frame.Ping(7u),
            Frame.Pong(UInt.MAX_VALUE, 0u),
        ) + ByeReason.entries.map { Frame.Bye(it) }
        for (f in frames) assertEquals(f, Frame.decode(f.encode()), "$f")
    }

    @Test
    fun randomFramesRoundTrip() {
        val random = Random(14)
        repeat(2_000) {
            val f = randomFrame(random)
            assertEquals(f, Frame.decode(f.encode()), "$f")
        }
    }

    @Test
    fun audioPayloadLimitIs1275() {
        val max = Frame.Audio(1u, 1u, ByteArray(1275))
        assertEquals(max, Frame.decode(max.encode()))
        assertFailsWith<IllegalArgumentException> { Frame.Audio(1u, 1u, ByteArray(1276)) }
        // 1276 on the wire is rejected before any payload is needed.
        val oversized = hex(3, 0, 1, 0, 0, 0, 1, 0x04, 0xFC) + ByteArray(1276)
        assertEquals(ProtocolError.LENGTH_EXCEEDED, assertFailsWith<ProtocolException> { Frame.decode(oversized) }.error)
    }

    @Test
    fun displayNameLimit() {
        val max = Frame.Hello("x".repeat(Frame.MAX_DISPLAY_NAME_BYTES), 1u)
        assertEquals(max, Frame.decode(max.encode()))
        assertFailsWith<IllegalArgumentException> { Frame.Hello("x".repeat(Frame.MAX_DISPLAY_NAME_BYTES + 1), 1u) }
        assertEquals(ProtocolError.LENGTH_EXCEEDED, error(hex(1, 1, 0xFF, 0xFF)))
    }

    @Test
    fun malformedInputIsRejectedWithTypedError() {
        assertEquals(ProtocolError.TRUNCATED, error(ByteArray(0)))
        assertEquals(ProtocolError.UNKNOWN_TYPE, error(hex(0)))
        assertEquals(ProtocolError.UNKNOWN_TYPE, error(hex(7, 0, 0)))
        assertEquals(ProtocolError.TRUNCATED, error(hex(4, 0, 0)))
        assertEquals(ProtocolError.TRUNCATED, error(hex(3, 0, 1, 0, 0, 0, 1, 0, 5, 1, 2)))
        assertEquals(ProtocolError.MALFORMED, error(hex(6, 0, 0)))
        assertEquals(ProtocolError.MALFORMED, error(hex(6, 99)))
        assertEquals(ProtocolError.MALFORMED, error(hex(1, 1, 0, 1, 0xFF, 0, 0, 0, 0)))
        assertEquals(ProtocolError.UNSUPPORTED_VERSION, error(hex(1, 2, 0, 0, 0, 0, 0, 0)))
        assertEquals(ProtocolError.UNSUPPORTED_VERSION, error(hex(2, 0)))
    }

    private fun error(bytes: ByteArray) = assertFailsWith<ProtocolException> { Frame.decode(bytes) }.error

    @Test
    fun fuzzRandomBytesOnlyThrowsProtocolException() {
        val random = Random(0xF022)
        var decoded = 0
        repeat(10_000) {
            val bytes = random.nextBytes(random.nextInt(0, 64))
            // Bias the type byte so the per-type paths are reached often.
            if (bytes.isNotEmpty() && random.nextBoolean()) bytes[0] = random.nextInt(0, 9).toByte()
            try {
                Frame.decode(bytes)
                decoded++
            } catch (_: ProtocolException) {
            }
        }
        check(decoded > 0) { "fuzzer never produced a valid frame; bias is broken" }
    }

    @Test
    fun fuzzMutatedValidFramesOnlyThrowsProtocolException() {
        val random = Random(0xF023)
        repeat(10_000) {
            val bytes = randomFrame(random).encode()
            val mutated = when (random.nextInt(3)) {
                0 -> bytes.copyOf().also { it[random.nextInt(it.size)] = random.nextInt().toByte() }
                1 -> bytes.copyOf(random.nextInt(bytes.size + 1))
                else -> bytes + random.nextBytes(random.nextInt(1, 4))
            }
            try {
                Frame.decode(mutated)
            } catch (_: ProtocolException) {
            }
        }
    }
}
