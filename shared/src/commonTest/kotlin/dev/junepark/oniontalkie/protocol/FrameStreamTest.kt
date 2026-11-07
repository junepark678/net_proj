package dev.junepark.oniontalkie.protocol

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FrameStreamTest {
    private fun readAll(reader: FrameReader): List<Frame> = runSuspend {
        buildList { while (true) add(reader.readFrame() ?: break) }
    }

    private fun stream(frames: List<Frame>) = frames.fold(ByteArray(0)) { acc, f -> acc + f.encode() }

    @Test
    fun readsFramesRegardlessOfChunking() {
        val random = Random(99)
        val frames = List(300) { randomFrame(random) }
        val bytes = stream(frames)
        for (chunk in listOf(1, 2, 3, 7, 100, 4096, 100_000)) {
            assertEquals(frames, readAll(FrameReader(ChunkedSource(bytes, chunk))), "chunk=$chunk")
        }
        repeat(20) { seed ->
            val source = ChunkedSource(bytes, 50, Random(seed))
            assertEquals(frames, readAll(FrameReader(source)), "random chunks seed=$seed")
        }
    }

    @Test
    fun cleanEndOfStreamReturnsNull() {
        assertNull(runSuspend { FrameReader(ChunkedSource(ByteArray(0), 1)).readFrame() })
    }

    @Test
    fun endOfStreamMidFrameIsTruncated() {
        val bytes = Frame.Audio(1u, 2u, ByteArray(40)).encode()
        for (cut in 1 until bytes.size) {
            val reader = FrameReader(ChunkedSource(bytes.copyOf(cut), 5))
            val e = assertFailsWith<ProtocolException>("cut=$cut") { runSuspend { reader.readFrame() } }
            assertEquals(ProtocolError.TRUNCATED, e.error)
        }
    }

    @Test
    fun readerStaysFailedAfterProtocolError() {
        val bytes = byteArrayOf(0x63) + Frame.Ping(1u).encode()
        val reader = FrameReader(ChunkedSource(bytes, 100))
        val first = assertFailsWith<ProtocolException> { runSuspend { reader.readFrame() } }
        assertEquals(ProtocolError.UNKNOWN_TYPE, first.error)
        // The valid frame after the garbage must not be delivered.
        val second = assertFailsWith<ProtocolException> { runSuspend { reader.readFrame() } }
        assertEquals(first, second)
    }

    @Test
    fun oversizedAudioIsRejectedWithoutReadingPayload() {
        // Header claims 65535 payload bytes but the stream ends right after it.
        val header = byteArrayOf(3, 0, 1, 0, 0, 0, 1, 0xFF.toByte(), 0xFF.toByte())
        val e = assertFailsWith<ProtocolException> { runSuspend { FrameReader(ChunkedSource(header, 100)).readFrame() } }
        assertEquals(ProtocolError.LENGTH_EXCEEDED, e.error)
    }

    @Test
    fun fuzzRandomStreamsOnlyThrowProtocolException() {
        val random = Random(0xFEED)
        repeat(10_000) {
            val bytes = random.nextBytes(random.nextInt(0, 80))
            if (bytes.isNotEmpty() && random.nextBoolean()) bytes[0] = random.nextInt(0, 9).toByte()
            val reader = FrameReader(ChunkedSource(bytes, 8, random))
            try {
                readAll(reader)
            } catch (_: ProtocolException) {
            }
        }
    }

    @Test
    fun writerSendsOneWritePerFrameAndFlushesEach() {
        val sink = RecordingSink()
        val writer = FrameWriter(sink)
        val frames = listOf(Frame.Hello("june", 48_000u), Frame.Accept, Frame.Audio(1u, 20u, ByteArray(60)), Frame.Ping(5u))
        runSuspend { frames.forEach { writer.write(it) } }
        assertEquals(frames.size, sink.writes.size)
        assertEquals(frames.size, sink.flushes)
        frames.forEachIndexed { i, f -> assertContentEquals(f.encode(), sink.writes[i]) }
    }

    @Test
    fun writerOutputIsReadableByReader() {
        val random = Random(5)
        val frames = List(100) { randomFrame(random) }
        val sink = RecordingSink()
        runSuspend { FrameWriter(sink).let { w -> frames.forEach { w.write(it) } } }
        assertEquals(frames, readAll(FrameReader(ChunkedSource(sink.allBytes(), 13))))
    }

    @Test
    fun helloWithUnknownVersionIsAnsweredWithByeVersion() {
        // version 2, name "x", rate 48000 — a future layout we must not try to interpret.
        val hello = byteArrayOf(1, 2, 0, 1, 'x'.code.toByte(), 0, 0, 0xBB.toByte(), 0x80.toByte())
        val sink = RecordingSink()
        val e = assertFailsWith<ProtocolException> {
            runSuspend { readHelloOrReject(FrameReader(ChunkedSource(hello, 3)), FrameWriter(sink)) }
        }
        assertEquals(ProtocolError.UNSUPPORTED_VERSION, e.error)
        assertContentEquals(Frame.Bye(ByeReason.VERSION).encode(), sink.allBytes())
        assertEquals(Frame.Bye(ByeReason.VERSION), Frame.decode(sink.allBytes()))
    }

    @Test
    fun helloWithSupportedVersionIsReturnedWithoutReply() {
        val hello = Frame.Hello("june", 48_000u)
        val sink = RecordingSink()
        val got = runSuspend { readHelloOrReject(FrameReader(ChunkedSource(hello.encode(), 2)), FrameWriter(sink)) }
        assertEquals(hello, got)
        assertEquals(0, sink.writes.size)
    }

    @Test
    fun nonHelloFirstFrameIsUnexpected() {
        val sink = RecordingSink()
        val e = assertFailsWith<ProtocolException> {
            runSuspend { readHelloOrReject(FrameReader(ChunkedSource(Frame.Ping(1u).encode(), 9)), FrameWriter(sink)) }
        }
        assertEquals(ProtocolError.UNEXPECTED_FRAME, e.error)
        assertEquals(0, sink.writes.size)
    }
}
