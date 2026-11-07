package dev.junepark.oniontalkie.protocol

/**
 * Writes [Frame]s to a [ByteSink]. Each frame is serialized into one buffer, handed to the sink
 * in a single write and flushed immediately, so frames are never delayed or split at this layer
 * (the stream has Nagle disabled). Not thread-safe: callers sending from several coroutines
 * must serialize calls to [write].
 */
class FrameWriter(private val sink: ByteSink) {
    /** Sends [frame] and flushes. */
    suspend fun write(frame: Frame) {
        sink.write(frame.encode())
        sink.flush()
    }
}
