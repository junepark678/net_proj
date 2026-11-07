package dev.junepark.oniontalkie

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/** Audio format shared by the desktop capture and playback paths. */
const val AUDIO_SAMPLE_RATE = 48_000
const val AUDIO_FRAME_SAMPLES = 960
const val AUDIO_FRAME_MILLIS = 20
const val AUDIO_PCM_BYTES_PER_FRAME = AUDIO_FRAME_SAMPLES * 2

private const val PLAYBACK_BUFFER_MILLIS = 60

val DESKTOP_AUDIO_FORMAT = AudioFormat(
    AudioFormat.Encoding.PCM_SIGNED,
    AUDIO_SAMPLE_RATE.toFloat(),
    16,
    1,
    2,
    AUDIO_SAMPLE_RATE.toFloat(),
    false,
)

data class AudioDevice(val name: String, val vendor: String, val description: String)

/** A blocking source of 20 ms, 48 kHz mono PCM16 frames. */
interface AudioInput : AutoCloseable {
    fun open(): Result<Unit>
    fun readFrame(): Result<ShortArray>
    override fun close()
}

/** A blocking sink for 20 ms, 48 kHz mono PCM16 frames. */
interface AudioOutput : AutoCloseable {
    fun open(): Result<Unit>
    fun writeFrame(frame: ShortArray): Result<Unit>
    val underrunCount: Long
    override fun close()
}

/**
 * Enumerates available mixers. A null device name selects the system default;
 * explicit names are matched against [Mixer.Info.name].
 */
object DesktopAudioDevices {
    fun listInputs(): List<AudioDevice> = listDevices(DataLine.Info(TargetDataLine::class.java, DESKTOP_AUDIO_FORMAT))

    fun listOutputs(): List<AudioDevice> = listDevices(DataLine.Info(SourceDataLine::class.java, DESKTOP_AUDIO_FORMAT))

    private fun listDevices(lineInfo: DataLine.Info): List<AudioDevice> = AudioSystem.getMixerInfo()
        .filter { info -> runCatching { AudioSystem.getMixer(info).isLineSupported(lineInfo) }.getOrDefault(false) }
        .map { AudioDevice(it.name, it.vendor, it.description) }
}

fun listInputs(): List<AudioDevice> = DesktopAudioDevices.listInputs()

fun listOutputs(): List<AudioDevice> = DesktopAudioDevices.listOutputs()

/** Desktop counterpart for the common factory introduced by issue #13. */
fun createAudioInput(deviceName: String? = null): AudioInput = DesktopAudioInput(deviceName)

fun createAudioOutput(deviceName: String? = null): AudioOutput = DesktopAudioOutput(deviceName)

class DesktopAudioInput(private val deviceName: String? = null) : AudioInput {
    private var line: TargetDataLine? = null

    override fun open(): Result<Unit> = runCatching {
        check(line == null) { "Audio input is already open" }
        val info = DataLine.Info(TargetDataLine::class.java, DESKTOP_AUDIO_FORMAT)
        val target = openLine(deviceName, info) as TargetDataLine
        target.open(DESKTOP_AUDIO_FORMAT, AUDIO_PCM_BYTES_PER_FRAME * 3)
        target.start()
        line = target
    }

    override fun readFrame(): Result<ShortArray> = runCatching {
        val input = checkNotNull(line) { "Audio input is not open" }
        val bytes = ByteArray(AUDIO_PCM_BYTES_PER_FRAME)
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            check(count > 0) { "Audio input closed while reading" }
            offset += count
        }
        ShortArray(AUDIO_FRAME_SAMPLES) { index ->
            val low = bytes[index * 2].toInt() and 0xff
            val high = bytes[index * 2 + 1].toInt()
            ((high shl 8) or low).toShort()
        }
    }

    override fun close() {
        line?.let { it.stop(); it.close() }
        line = null
    }
}

class DesktopAudioOutput(private val deviceName: String? = null) : AudioOutput {
    private var line: SourceDataLine? = null
    private var underruns = 0L
    private var hasWrittenFrame = false

    override val underrunCount: Long get() = underruns

    override fun open(): Result<Unit> = runCatching {
        check(line == null) { "Audio output is already open" }
        val info = DataLine.Info(SourceDataLine::class.java, DESKTOP_AUDIO_FORMAT)
        val target = openLine(deviceName, info) as SourceDataLine
        val bufferBytes = AUDIO_SAMPLE_RATE * 2 * PLAYBACK_BUFFER_MILLIS / 1000
        target.open(DESKTOP_AUDIO_FORMAT, bufferBytes)
        target.start()
        line = target
        underruns = 0
        hasWrittenFrame = false
    }

    override fun writeFrame(frame: ShortArray): Result<Unit> = runCatching {
        require(frame.size == AUDIO_FRAME_SAMPLES) { "Expected $AUDIO_FRAME_SAMPLES PCM samples" }
        val output = checkNotNull(line) { "Audio output is not open" }
        // available() is free space. A nearly empty queue means playback ran
        // out between frames; do not count the initial empty buffer as an underrun.
        if (hasWrittenFrame && output.available() >= output.bufferSize - AUDIO_PCM_BYTES_PER_FRAME) underruns++
        val bytes = ByteArray(AUDIO_PCM_BYTES_PER_FRAME)
        frame.forEachIndexed { index, sample ->
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample.toInt() ushr 8).toByte()
        }
        var offset = 0
        while (offset < bytes.size) {
            val count = output.write(bytes, offset, bytes.size - offset)
            check(count > 0) { "Audio output closed while writing" }
            offset += count
        }
        hasWrittenFrame = true
    }

    override fun close() {
        line?.let { it.drain(); it.stop(); it.close() }
        line = null
        hasWrittenFrame = false
    }
}

private fun openLine(deviceName: String?, lineInfo: DataLine.Info): DataLine {
    if (deviceName == null) return AudioSystem.getLine(lineInfo) as DataLine
    val mixerInfo = AudioSystem.getMixerInfo().firstOrNull { it.name == deviceName }
        ?: error("Audio device '$deviceName' was not found")
    val mixer = AudioSystem.getMixer(mixerInfo)
    check(mixer.isLineSupported(lineInfo)) { "Audio device '$deviceName' does not support 48 kHz mono PCM16" }
    return mixer.getLine(lineInfo) as DataLine
}
