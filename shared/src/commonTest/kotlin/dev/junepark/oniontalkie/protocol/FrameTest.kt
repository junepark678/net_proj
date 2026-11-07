package dev.junepark.oniontalkie.protocol

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FrameTest {
    private val frames = listOf(
        Frame.Hello(1u, "한🎙", 24000u),
        Frame.Accept(1u),
        Frame.Audio(0xFEDCu, 0x89ABCDEFu, byteArrayOf(0, -1, 127)),
        Frame.Ping(0xFFFFFFFFu),
        Frame.Pong(0x01020304u, 0xFEDCBA98u),
        Frame.Bye(ByeReason.NORMAL),
        Frame.Bye(ByeReason.VERSION),
        Frame.Bye(ByeReason.BUSY),
        Frame.Bye(ByeReason.GLARE),
        Frame.Bye(ByeReason.PROTOCOL_ERROR),
    )

    @Test
    fun goldenVectorsMatchArchitecture() {
        val vectors = listOf(
            "01 01 00 07 ED 95 9C F0 9F 8E 99 00 00 5D C0",
            "02 01",
            "03 FE DC 89 AB CD EF 00 03 00 FF 7F",
            "04 FF FF FF FF",
            "05 01 02 03 04 FE DC BA 98",
            "06 00",
            "06 01",
            "06 02",
            "06 03",
            "06 04",
        )
        assertEquals(frames.size, vectors.size)
        frames.zip(vectors).forEach { (frame, vector) ->
            val bytes = vector.split(' ').map { it.toInt(16).toByte() }.toByteArray()
            assertContentEquals(bytes, FrameCodec.encode(frame))
            assertEquals(frame, FrameCodec.decode(bytes))
        }
    }

    @Test
    fun tenThousandRandomFramesRoundTrip() {
        val random = Random(14)
        repeat(10000) {
            val frame = randomFrame(random)
            assertEquals(frame, FrameCodec.decode(FrameCodec.encode(frame)))
        }
    }

    @Test
    fun tenThousandMutatedValidFramesOnlyProduceTypedErrors() {
        val random = Random(0xF023)
        val boundaryFrames = frames + listOf(
            Frame.Hello(1u, "", 0u),
            Frame.Hello(1u, "🎙".repeat(64), UInt.MAX_VALUE),
            Frame.Audio(0u, 0u, ByteArray(0)),
            Frame.Audio(UShort.MAX_VALUE, UInt.MAX_VALUE, ByteArray(1275)),
        )
        repeat(10000) { iteration ->
            val frame = if (iteration % 2 == 0) boundaryFrames[(iteration / 2) % boundaryFrames.size]
                else randomFrame(random)
            val bytes = FrameCodec.encode(frame)
            val mutated = when (iteration % 3) {
                0 -> bytes.copyOf().also {
                    val index = random.nextInt(it.size)
                    it[index] = (it[index].toInt() xor random.nextInt(1, 256)).toByte()
                }
                1 -> bytes.copyOf(random.nextInt(bytes.size))
                else -> bytes + random.nextBytes(random.nextInt(1, 4))
            }
            try {
                val decoded = FrameCodec.decode(mutated)
                assertContentEquals(mutated, FrameCodec.encode(decoded))
                assertEquals(decoded, FrameCodec.decode(FrameCodec.encode(decoded)))
            } catch (_: ProtocolException) {
                // Any unexpected exception fails the test.
            }
        }
    }

    @Test
    fun tenThousandRandomByteStringsOnlyProduceTypedErrors() {
        val random = Random(1400)
        repeat(10000) { iteration ->
            val bytes = random.nextBytes(random.nextInt(1500))
            // Also exercise valid type bytes, rather than mostly rejecting the first byte.
            if (bytes.isNotEmpty() && iteration % 2 == 0) bytes[0] = random.nextInt(1, 7).toByte()
            try {
                assertContentEquals(bytes, FrameCodec.encode(FrameCodec.decode(bytes)))
            } catch (_: ProtocolException) {
                // Any other exception fails this test.
            }
        }
    }

    @Test
    fun audioAndNameLengthBoundaries() {
        for (size in listOf(0, 1, 1275)) {
            val frame = Frame.Audio(UShort.MAX_VALUE, UInt.MAX_VALUE, ByteArray(size))
            assertEquals(frame, FrameCodec.decode(FrameCodec.encode(frame)))
        }
        assertProtocol(ProtocolException.Error.INVALID_LENGTH) {
            FrameCodec.encode(Frame.Audio(0u, 0u, ByteArray(1276)))
        }
        for (name in listOf("a".repeat(256), "🎙".repeat(64), "한".repeat(85) + "a")) {
            val maxName = Frame.Hello(1u, name, UInt.MAX_VALUE)
            assertEquals(maxName, FrameCodec.decode(FrameCodec.encode(maxName)))
        }
        val emptyName = Frame.Hello(1u, "", 0u)
        assertEquals(emptyName, FrameCodec.decode(FrameCodec.encode(emptyName)))
        for (name in listOf("a".repeat(257), "🎙".repeat(64) + "a", "한".repeat(86))) {
            assertProtocol(ProtocolException.Error.INVALID_LENGTH) { FrameCodec.encode(Frame.Hello(1u, name, 0u)) }
            val nameBytes = name.encodeToByteArray()
            val bytes = byteArrayOf(1, 1, (nameBytes.size shr 8).toByte(), nameBytes.size.toByte()) +
                nameBytes + ByteArray(4)
            assertProtocol(ProtocolException.Error.INVALID_LENGTH) { FrameCodec.decode(bytes) }
            assertProtocol(ProtocolException.Error.INVALID_LENGTH) { FrameCodec.decode(bytes.copyOf(4)) }
        }
    }

    @Test
    fun invalidUtf8AndUnpairedSurrogatesAreRejected() {
        for (name in listOf(byteArrayOf(0x80.toByte()), byteArrayOf(0xC0.toByte(), 0xAF.toByte()),
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()), byteArrayOf(0xF0.toByte()))) {
            val bytes = byteArrayOf(1, 1, 0, name.size.toByte()) + name + ByteArray(4)
            assertProtocol(ProtocolException.Error.INVALID_UTF8) { FrameCodec.decode(bytes) }
        }
        assertProtocol(ProtocolException.Error.INVALID_UTF8) {
            FrameCodec.encode(Frame.Hello(1u, "\uD800", 0u))
        }
    }

    @Test
    fun rejectsTruncationTrailingBytesUnknownTypesAndReasons() {
        frames.forEach { frame ->
            val bytes = FrameCodec.encode(frame)
            for (length in bytes.indices) {
                assertProtocol(ProtocolException.Error.TRUNCATED) { FrameCodec.decode(bytes.copyOf(length)) }
            }
            assertProtocol(ProtocolException.Error.TRAILING_DATA) { FrameCodec.decode(bytes + 0.toByte()) }
        }
        for (type in listOf(0, 7, 128, 255)) {
            assertProtocol(ProtocolException.Error.UNKNOWN_TYPE) { FrameCodec.decode(byteArrayOf(type.toByte())) }
        }
        assertProtocol(ProtocolException.Error.UNKNOWN_REASON) { FrameCodec.decode(byteArrayOf(6, -1)) }
    }

    @Test
    fun readerHandlesEverySingleSplitAndOneByteChunks() = runSuspending {
        frames.forEach { frame ->
            val bytes = FrameCodec.encode(frame)
            for (split in 1..bytes.size) {
                val channel = MemoryChannel(bytes, chunks = listOf(split, bytes.size))
                assertEquals(frame, FrameReader(channel).read())
                assertEquals(bytes.size, channel.position)
            }
            assertEquals(frame, FrameReader(MemoryChannel(bytes, maxChunk = 1)).read())
        }
    }

    @Test
    fun readerConsumesConcatenatedFramesWithRandomChunking() = runSuspending {
        val random = Random(1414)
        val expected = List(1000) { frames[random.nextInt(frames.size)] }
        val bytes = expected.fold(ByteArray(0)) { acc, frame -> acc + FrameCodec.encode(frame) }
        val channel = MemoryChannel(bytes, chunks = List(bytes.size) { random.nextInt(1, 32) })
        val reader = FrameReader(channel)
        expected.forEach { assertEquals(it, reader.read()) }
        assertNull(reader.read())
        val readsAtEof = channel.reads
        assertNull(reader.read())
        assertEquals(readsAtEof, channel.reads)
        assertEquals(bytes.size, channel.position)
    }

    @Test
    fun readerClosesOnEveryTruncatedFrame() = runSuspending {
        frames.forEach { frame ->
            val bytes = FrameCodec.encode(frame)
            for (length in 1 until bytes.size) {
                val channel = MemoryChannel(bytes.copyOf(length), maxChunk = 1)
                val reader = FrameReader(channel)
                val error = assertFailsWith<ProtocolException> { reader.read() }
                assertEquals(ProtocolException.Error.TRUNCATED, error.error)
                assertEquals(1, channel.closes)
                val reads = channel.reads
                repeat(3) { assertSame(error, assertFailsWith<ProtocolException> { reader.read() }) }
                assertEquals(reads, channel.reads)
                assertEquals(1, channel.closes)
            }
        }
        assertNull(FrameReader(MemoryChannel(ByteArray(0))).read())
    }

    @Test
    fun oversizedAudioIsRejectedBeforePayloadIsRead() = runSuspending {
        val bytes = byteArrayOf(3, 0, 0, 0, 0, 0, 0, -1, -1) + ByteArray(65535)
        val channel = MemoryChannel(bytes)
        val error = assertFailsWith<ProtocolException> { FrameReader(channel).read() }
        assertEquals(ProtocolException.Error.INVALID_LENGTH, error.error)
        assertEquals(9, channel.position)
        assertEquals(1, channel.closes)
    }

    @Test
    fun oversizedNameIsRejectedBeforePayloadIsRead() = runSuspending {
        for (length in listOf(257, 65535)) {
            val bytes = byteArrayOf(1, 1, (length shr 8).toByte(), length.toByte()) + ByteArray(length + 4)
            val channel = MemoryChannel(bytes, maxChunk = 1)
            val reader = FrameReader(channel)
            val error = assertFailsWith<ProtocolException> { reader.read() }
            assertEquals(ProtocolException.Error.INVALID_LENGTH, error.error)
            assertEquals(4, channel.position)
            assertSame(error, assertFailsWith<ProtocolException> { reader.read() })
            assertEquals(1, channel.closes)
        }
    }

    @Test
    fun malformedInputClosesWithoutSendingBye() = runSuspending {
        val cases = listOf(byteArrayOf(7), byteArrayOf(6, 5), byteArrayOf(1, 1, 0, 1, -1, 0, 0, 0, 0))
        for (bytes in cases) {
            val channel = MemoryChannel(bytes)
            val reader = FrameReader(channel)
            val error = assertFailsWith<ProtocolException> { reader.read() }
            val reads = channel.reads
            repeat(3) { assertSame(error, assertFailsWith<ProtocolException> { reader.read() }) }
            assertEquals(reads, channel.reads)
            assertEquals(1, channel.closes)
            assertTrue(channel.writes.isEmpty())
        }
    }

    @Test
    fun unsupportedHelloSendsVersionByeFlushesAndCloses() = runSuspending {
        for (version in listOf(0, 2, 255)) {
            val channel = MemoryChannel(FrameCodec.encode(Frame.Hello(version.toUByte(), "peer", 24000u)), maxChunk = 1)
            val reader = FrameReader(channel)
            val error = assertFailsWith<ProtocolException> { reader.read() }
            assertEquals(ProtocolException.Error.VERSION, error.error)
            assertContentEquals(byteArrayOf(6, 1), channel.writes.single())
            assertEquals(listOf("write", "flush", "close"), channel.events)
            assertSame(error, assertFailsWith<ProtocolException> { reader.read() })
            assertEquals(2, channel.position)
        }
    }

    @Test
    fun unsupportedHelloVersionTakesPriorityOverMalformedBody() = runSuspending {
        for (version in listOf(0, 2, 255)) {
            val bodies = listOf(
                byteArrayOf(),
                byteArrayOf(0),
                byteArrayOf(0, 1),
                byteArrayOf(0, 1, -1, 0, 0, 0, 0),
                byteArrayOf(-1, -1),
            )
            for (body in bodies) {
                val bytes = byteArrayOf(1, version.toByte()) + body
                assertProtocol(ProtocolException.Error.VERSION) { FrameCodec.decode(bytes) }
                val channel = MemoryChannel(bytes, maxChunk = 1)
                val reader = FrameReader(channel)
                val error = assertFailsWith<ProtocolException> { reader.read() }
                assertEquals(ProtocolException.Error.VERSION, error.error)
                assertEquals(2, channel.position)
                assertContentEquals(byteArrayOf(6, 1), channel.writes.single())
                assertEquals(listOf("write", "flush", "close"), channel.events)
                repeat(3) { assertSame(error, assertFailsWith<ProtocolException> { reader.read() }) }
                assertEquals(2, channel.reads)
                assertEquals(1, channel.closes)
            }
        }
    }

    @Test
    fun unsupportedAcceptVersionIsRejected() = runSuspending {
        for (version in listOf(0, 2, 255)) {
            val bytes = byteArrayOf(2, version.toByte())
            assertProtocol(ProtocolException.Error.VERSION) { FrameCodec.decode(bytes) }
            val channel = MemoryChannel(bytes)
            val reader = FrameReader(channel)
            val error = assertFailsWith<ProtocolException> { reader.read() }
            assertEquals(ProtocolException.Error.VERSION, error.error)
            assertSame(error, assertFailsWith<ProtocolException> { reader.read() })
            assertEquals(1, channel.closes)
            assertTrue(channel.writes.isEmpty())
        }
    }

    @Test
    fun transportReadFailuresAreLatched() = runSuspending {
        val failure = IllegalStateException("transport read failed")
        val channel = MemoryChannel(byteArrayOf(4), readFailure = failure)
        val reader = FrameReader(channel)
        repeat(3) { assertSame(failure, assertFailsWith<IllegalStateException> { reader.read() }) }
        assertEquals(2, channel.reads)
        assertEquals(1, channel.position)
        assertEquals(1, channel.closes)
    }

    @Test
    fun negotiationAndMalformedErrorsSurviveCleanupFailures() = runSuspending {
        val channel = MemoryChannel(FrameCodec.encode(Frame.Hello(2u, "", 0u)), failWrite = true, failClose = true)
        val error = assertFailsWith<ProtocolException> { FrameReader(channel).read() }
        assertEquals(ProtocolException.Error.VERSION, error.error)
        assertEquals(2, error.suppressedExceptions.size)
        assertEquals(1, channel.closes)
    }

    @Test
    fun readerHandlesMaximumNameAndAudio() = runSuspending {
        for (frame in listOf(Frame.Hello(1u, "🎙".repeat(64), 24000u), Frame.Audio(0u, 0u, ByteArray(1275)))) {
            assertEquals(frame, FrameReader(MemoryChannel(FrameCodec.encode(frame), maxChunk = 3)).read())
        }
    }

    @Test
    fun writerUsesOneWriteAndFlushPerFrame() = runSuspending {
        val channel = MemoryChannel(ByteArray(0))
        val writer = FrameWriter(channel)
        frames.forEach { writer.write(it) }
        assertEquals(frames.size, channel.writes.size)
        frames.zip(channel.writes).forEach { (frame, bytes) -> assertContentEquals(FrameCodec.encode(frame), bytes) }
        assertEquals(List(frames.size) { listOf("write", "flush") }.flatten(), channel.events)
        val events = channel.events.toList()
        assertFailsWith<ProtocolException> { writer.write(Frame.Audio(0u, 0u, ByteArray(1276))) }
        assertEquals(events, channel.events)
    }

    @Test
    fun tenThousandRandomStreamsOnlyProduceTypedErrors() = runSuspending {
        val random = Random(141414)
        repeat(10000) { iteration ->
            val bytes = random.nextBytes(random.nextInt(300))
            if (bytes.isNotEmpty() && iteration % 2 == 0) bytes[0] = random.nextInt(1, 7).toByte()
            val channel = MemoryChannel(bytes, maxChunk = random.nextInt(1, 20))
            val reader = FrameReader(channel)
            try {
                while (reader.read() != null) { /* Consume all complete frames. */ }
            } catch (_: ProtocolException) {
                assertEquals(1, channel.closes)
            }
        }
    }

    private fun randomFrame(random: Random): Frame = when (random.nextInt(6)) {
        0 -> Frame.Hello(FrameCodec.VERSION, randomName(random), random.nextInt().toUInt())
        1 -> Frame.Accept(FrameCodec.VERSION)
        2 -> Frame.Audio(random.nextInt(65536).toUShort(), random.nextInt().toUInt(),
            random.nextBytes(random.nextInt(FrameCodec.MAX_AUDIO_BYTES + 1)))
        3 -> Frame.Ping(random.nextInt().toUInt())
        4 -> Frame.Pong(random.nextInt().toUInt(), random.nextInt().toUInt())
        else -> Frame.Bye(ByeReason.entries[random.nextInt(ByeReason.entries.size)])
    }

    private fun randomName(random: Random): String = buildString {
        repeat(random.nextInt(40)) { append(listOf("a", "\u0000", "한", "🎙", "é")[random.nextInt(5)]) }
    }

    private fun assertProtocol(error: ProtocolException.Error, block: () -> Unit) {
        assertEquals(error, assertFailsWith<ProtocolException>(block = block).error)
    }

    // Fake channels complete inline; this runner requires no coroutine runtime dependency.
    private fun runSuspending(block: suspend () -> Unit) {
        var completed: Result<Unit>? = null
        block.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) { completed = result }
        })
        checkNotNull(completed) { "Test channel unexpectedly suspended" }.getOrThrow()
    }

    private class MemoryChannel(
        private val input: ByteArray,
        private val maxChunk: Int = Int.MAX_VALUE,
        private val chunks: List<Int> = emptyList(),
        private val failWrite: Boolean = false,
        private val failClose: Boolean = false,
        private val readFailure: Exception? = null,
    ) : FrameChannel {
        var position = 0
        var reads = 0
        var closes = 0
        val writes = mutableListOf<ByteArray>()
        val events = mutableListOf<String>()
        private var nextChunk = 0
        private var chunkRemaining = 0

        override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int {
            reads++
            if (reads > 1) readFailure?.let { throw it }
            if (position == input.size) return -1
            if (chunkRemaining == 0) chunkRemaining = chunks.getOrNull(nextChunk++) ?: maxChunk
            val count = minOf(chunkRemaining, length, input.size - position)
            input.copyInto(destination, offset, position, position + count)
            position += count
            chunkRemaining -= count
            return count
        }

        override suspend fun write(bytes: ByteArray) {
            events += "write"
            if (failWrite) error("write failed")
            writes += bytes.copyOf()
        }

        override suspend fun flush() { events += "flush" }

        override suspend fun close() {
            events += "close"
            closes++
            if (failClose) error("close failed")
        }
    }
}
