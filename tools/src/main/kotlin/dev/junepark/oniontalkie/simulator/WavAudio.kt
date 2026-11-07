package dev.junepark.oniontalkie.simulator

import dev.junepark.oniontalkie.audio.AudioFormat
import dev.junepark.oniontalkie.audio.AudioInput
import dev.junepark.oniontalkie.audio.AudioOutput
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import javax.sound.sampled.AudioFormat as JavaAudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/** Reads a WAV, converting it to 48 kHz mono signed 16-bit PCM and pacing frames at 20 ms. */
internal class WavAudioInput(
    private val file: File,
    private val maxFrames: Long,
) : AudioInput {
    private var original: AudioInputStream? = null
    private var converted: AudioInputStream? = null
    private var frameIndex = 0L
    private var sourceEnded = false
    private var nextFrameNanos = 0L

    override fun start() {
        check(original == null) { "WAV input is already started" }
        val source = AudioSystem.getAudioInputStream(file)
        original = source
        val target = JavaAudioFormat(
            JavaAudioFormat.Encoding.PCM_SIGNED,
            AudioFormat.SAMPLE_RATE_HZ.toFloat(),
            16,
            1,
            2,
            AudioFormat.SAMPLE_RATE_HZ.toFloat(),
            false,
        )
        converted = if (source.format.matches(target)) source else try {
            AudioSystem.getAudioInputStream(target, source)
        } catch (failure: Exception) {
            source.close()
            original = null
            throw IOException("Cannot convert ${file.name} to 48 kHz mono 16-bit PCM", failure)
        }
        frameIndex = 0
        sourceEnded = false
        nextFrameNanos = System.nanoTime()
    }

    override fun read(): ShortArray? {
        if (frameIndex >= maxFrames) return null
        paceNextFrame()
        val samples = ShortArray(AudioFormat.SAMPLES_PER_FRAME)
        val bytes = ByteArray(samples.size * 2)
        var count = 0
        if (!sourceEnded) {
            val stream = checkNotNull(converted) { "WAV input is not started" }
            while (count < bytes.size) {
                val read = stream.read(bytes, count, bytes.size - count)
                if (read < 0) {
                    sourceEnded = true
                    break
                }
                if (read == 0) continue
                count += read
            }
        }
        for (index in 0 until count / 2) {
            val low = bytes[index * 2].toInt() and 0xff
            val high = bytes[index * 2 + 1].toInt()
            samples[index] = ((high shl 8) or low).toShort()
        }
        frameIndex += 1
        nextFrameNanos += FRAME_NANOS
        return samples
    }

    override fun stop() {
        val input = converted
        converted = null
        if (input != null && input !== original) runCatching { input.close() }
        original?.let { runCatching { it.close() } }
        original = null
    }

    private fun paceNextFrame() {
        val remaining = nextFrameNanos - System.nanoTime()
        if (remaining <= 0) return
        val millis = remaining / 1_000_000L
        val nanos = (remaining % 1_000_000L).toInt()
        Thread.sleep(millis, nanos)
    }
}

/** Deterministic 440 Hz source used when --wav is omitted. */
internal class ToneAudioInput(private val maxFrames: Long) : AudioInput {
    private var frameIndex = 0L
    private var nextFrameNanos = 0L

    override fun start() {
        frameIndex = 0
        nextFrameNanos = System.nanoTime()
    }

    override fun read(): ShortArray? {
        if (frameIndex >= maxFrames) return null
        val remaining = nextFrameNanos - System.nanoTime()
        if (remaining > 0) Thread.sleep(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
        val samples = ShortArray(AudioFormat.SAMPLES_PER_FRAME) { index ->
            val position = frameIndex * AudioFormat.SAMPLES_PER_FRAME + index
            (10_000.0 * kotlin.math.sin(2.0 * Math.PI * 440.0 * position / AudioFormat.SAMPLE_RATE_HZ)).toInt().toShort()
        }
        frameIndex += 1
        nextFrameNanos += FRAME_NANOS
        return samples
    }

    override fun stop() = Unit
}

/** Writes decoded call audio as standard little-endian PCM WAV. */
internal class WavAudioOutput(private val file: File) : AudioOutput {
    private var output: RandomAccessFile? = null
    private var dataBytes = 0L

    override fun start() {
        check(output == null) { "WAV output is already started" }
        Files.createDirectories(file.toPath().toAbsolutePath().parent)
        output = RandomAccessFile(file, "rw").apply {
            setLength(0)
            write(ByteArray(WAV_HEADER_BYTES))
        }
        dataBytes = 0
    }

    override fun write(pcm: ShortArray) {
        val target = checkNotNull(output) { "WAV output is not started" }
        val bytes = ByteArray(pcm.size * 2)
        pcm.forEachIndexed { index, sample ->
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample.toInt() ushr 8).toByte()
        }
        target.write(bytes)
        dataBytes += bytes.size
    }

    override fun stop() {
        val target = output ?: return
        try {
            target.seek(0)
            writeHeader(target, dataBytes)
        } finally {
            target.close()
            output = null
        }
    }

    private fun writeHeader(target: RandomAccessFile, payloadBytes: Long) {
        target.writeBytes("RIFF")
        target.writeLittleEndianInt((36L + payloadBytes).toInt())
        target.writeBytes("WAVE")
        target.writeBytes("fmt ")
        target.writeLittleEndianInt(16)
        target.writeLittleEndianShort(1)
        target.writeLittleEndianShort(1)
        target.writeLittleEndianInt(AudioFormat.SAMPLE_RATE_HZ)
        target.writeLittleEndianInt(AudioFormat.SAMPLE_RATE_HZ * 2)
        target.writeLittleEndianShort(2)
        target.writeLittleEndianShort(16)
        target.writeBytes("data")
        target.writeLittleEndianInt(payloadBytes.toInt())
    }

    private fun RandomAccessFile.writeLittleEndianInt(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
        write(value ushr 16 and 0xff)
        write(value ushr 24 and 0xff)
    }

    private fun RandomAccessFile.writeLittleEndianShort(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
    }
}

private const val WAV_HEADER_BYTES = 44
private const val FRAME_NANOS = AudioFormat.FRAME_DURATION_MS * 1_000_000L
