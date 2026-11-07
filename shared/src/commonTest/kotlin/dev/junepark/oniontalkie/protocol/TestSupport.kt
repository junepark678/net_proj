package dev.junepark.oniontalkie.protocol

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.random.Random

/** Runs [block] to completion; the in-memory channels below never suspend, so this is synchronous. */
fun <T> runSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return checkNotNull(result) { "block suspended" }.getOrThrow()
}

/** Serves [data] in chunks of at most [maxChunk] bytes (random sizes from [random] if given). */
class ChunkedSource(
    private val data: ByteArray,
    private val maxChunk: Int,
    private val random: Random? = null,
) : ByteSource {
    private var pos = 0

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (pos >= data.size) return -1
        val limit = if (random != null) random.nextInt(1, maxChunk + 1) else maxChunk
        val n = minOf(length, limit, data.size - pos)
        data.copyInto(buffer, offset, pos, pos + n)
        pos += n
        return n
    }
}

/** Records each write call separately and counts flushes. */
class RecordingSink : ByteSink {
    val writes = mutableListOf<ByteArray>()
    var flushes = 0

    override suspend fun write(bytes: ByteArray) {
        writes += bytes
    }

    override suspend fun flush() {
        flushes++
    }

    fun allBytes(): ByteArray = writes.fold(ByteArray(0)) { acc, b -> acc + b }
}

fun randomFrame(random: Random): Frame = when (random.nextInt(6)) {
    0 -> Frame.Hello(randomName(random), random.nextInt().toUInt())
    1 -> Frame.Accept
    2 -> Frame.Audio(
        random.nextInt(0x10000).toUShort(),
        random.nextInt().toUInt(),
        random.nextBytes(random.nextInt(0, Frame.MAX_AUDIO_PAYLOAD + 1)),
    )
    3 -> Frame.Ping(random.nextInt().toUInt())
    4 -> Frame.Pong(random.nextInt().toUInt(), random.nextInt().toUInt())
    else -> Frame.Bye(ByeReason.entries.random(random))
}

private val nameParts = listOf("a", "b", "X", " ", "0", "한", "글", "é", "✓", "😀")

/** At most 60 parts of at most 4 UTF-8 bytes each, so always within [Frame.MAX_DISPLAY_NAME_BYTES]. */
private fun randomName(random: Random): String =
    buildString { repeat(random.nextInt(0, 60)) { append(nameParts[random.nextInt(nameParts.size)]) } }
