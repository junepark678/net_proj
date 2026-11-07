package dev.junepark.oniontalkie.audio

import javax.sound.sampled.AudioFormat as JavaAudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

private const val PLAYBACK_BUFFER_MILLIS = 60
private const val PCM_BYTES_PER_FRAME = AudioFormat.SAMPLES_PER_FRAME * 2

private val desktopPcmFormat = JavaAudioFormat(
    JavaAudioFormat.Encoding.PCM_SIGNED,
    AudioFormat.SAMPLE_RATE_HZ.toFloat(),
    16,
    1,
    2,
    AudioFormat.SAMPLE_RATE_HZ.toFloat(),
    false,
)

data class AudioDevice(val name: String, val vendor: String, val description: String)

/** Available Java Sound capture and playback mixers. */
object DesktopAudioDevices {
    fun listInputs(): List<AudioDevice> = listDevices(DataLine.Info(TargetDataLine::class.java, desktopPcmFormat))
    fun listOutputs(): List<AudioDevice> = listDevices(DataLine.Info(SourceDataLine::class.java, desktopPcmFormat))

    private fun listDevices(info: DataLine.Info): List<AudioDevice> = AudioSystem.getMixerInfo()
        .filter { mixer -> runCatching { AudioSystem.getMixer(mixer).isLineSupported(info) }.getOrDefault(false) }
        .map { AudioDevice(it.name, it.vendor, it.description) }
}

class DesktopAudioInput(private val deviceName: String? = null) : AudioInput {
    private var line: TargetDataLine? = null

    override fun start() {
        check(line == null) { "Audio input is already started" }
        val info = DataLine.Info(TargetDataLine::class.java, desktopPcmFormat)
        val target = openLine(deviceName, info) as TargetDataLine
        try {
            target.open(desktopPcmFormat, PCM_BYTES_PER_FRAME * 3)
            target.start()
            line = target
        } catch (failure: Throwable) {
            target.close()
            throw failure
        }
    }

    override fun read(): ShortArray? {
        val input = checkNotNull(line) { "Audio input is not started" }
        val bytes = ByteArray(PCM_BYTES_PER_FRAME)
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) return null
            check(count > 0) { "Audio input returned no data" }
            offset += count
        }
        return ShortArray(AudioFormat.SAMPLES_PER_FRAME) { index ->
            val low = bytes[index * 2].toInt() and 0xff
            val high = bytes[index * 2 + 1].toInt()
            ((high shl 8) or low).toShort()
        }
    }

    override fun stop() {
        val current = line ?: return
        line = null
        current.stop()
        current.close()
    }
}

class DesktopAudioOutput(private val deviceName: String? = null) : AudioOutput {
    private var line: SourceDataLine? = null
    private var hasWrittenFrame = false
    private var underruns = 0L

    /** Approximate count of writes that found the Java Sound queue empty after playback began. */
    val underrunCount: Long get() = underruns

    override fun start() {
        check(line == null) { "Audio output is already started" }
        val info = DataLine.Info(SourceDataLine::class.java, desktopPcmFormat)
        val target = openLine(deviceName, info) as SourceDataLine
        try {
            val bufferBytes = AudioFormat.SAMPLE_RATE_HZ * 2 * PLAYBACK_BUFFER_MILLIS / 1_000
            target.open(desktopPcmFormat, bufferBytes)
            target.start()
            line = target
            hasWrittenFrame = false
            underruns = 0
        } catch (failure: Throwable) {
            target.close()
            throw failure
        }
    }

    override fun write(pcm: ShortArray) {
        require(pcm.size == AudioFormat.SAMPLES_PER_FRAME) {
            "Expected ${AudioFormat.SAMPLES_PER_FRAME} PCM samples"
        }
        val output = checkNotNull(line) { "Audio output is not started" }
        if (hasWrittenFrame && output.available() >= output.bufferSize - PCM_BYTES_PER_FRAME) underruns++
        val bytes = ByteArray(PCM_BYTES_PER_FRAME)
        pcm.forEachIndexed { index, sample ->
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

    override fun stop() {
        val current = line ?: return
        line = null
        current.drain()
        current.stop()
        current.close()
        hasWrittenFrame = false
    }
}

actual fun createAudioInput(): AudioInput = DesktopAudioInput()
actual fun createAudioOutput(): AudioOutput = DesktopAudioOutput()

private fun openLine(deviceName: String?, info: DataLine.Info): DataLine {
    if (deviceName == null) return AudioSystem.getLine(info) as DataLine
    val mixerInfo: Mixer.Info = AudioSystem.getMixerInfo().firstOrNull { it.name == deviceName }
        ?: error("Audio device '$deviceName' was not found")
    val mixer = AudioSystem.getMixer(mixerInfo)
    check(mixer.isLineSupported(info)) { "Audio device '$deviceName' does not support 48 kHz mono PCM16" }
    return mixer.getLine(info) as DataLine
}
