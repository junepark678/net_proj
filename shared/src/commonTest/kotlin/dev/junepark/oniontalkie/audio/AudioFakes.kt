package dev.junepark.oniontalkie.audio

import dev.junepark.oniontalkie.codec.OpusCodec
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** [AudioInput] that serves [pcmSource] sequentially, then reports end of stream. */
class FakeAudioInput(private val pcmSource: ShortArray) : AudioInput {
    private var pos = 0
    var started = false
        private set
    var closed = false
        private set

    override fun start() {
        started = true
    }

    override fun read(buffer: ShortArray): Int {
        if (closed || pos >= pcmSource.size) return -1
        val n = minOf(buffer.size, pcmSource.size - pos)
        pcmSource.copyInto(buffer, 0, pos, pos + n)
        pos += n
        return n
    }

    override fun close() {
        closed = true
    }
}

/** [AudioOutput] that records everything written to it. */
class CapturingAudioOutput : AudioOutput {
    private val blocks = mutableListOf<ShortArray>()
    var started = false
        private set
    var closed = false
        private set

    /** Written blocks, in order. */
    val frames: List<ShortArray> get() = blocks

    /** All written samples concatenated. */
    fun samples(): ShortArray = blocks.fold(ShortArray(0)) { acc, b -> acc + b }

    override fun start() {
        started = true
    }

    override fun write(pcm: ShortArray) {
        blocks += pcm.copyOf()
    }

    override fun close() {
        closed = true
    }
}

/**
 * Stand-in for the real Opus codec: 16-bit PCM packed big-endian with the
 * [dropBits] least-significant bits zeroed, so it is lossy but has a known SNR.
 * A lost packet (`null`) is concealed with silence.
 */
class FakeOpusCodec(private val dropBits: Int = 0) : OpusCodec {
    override fun encode(pcm: ShortArray): ByteArray {
        val out = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = (pcm[i].toInt() shr dropBits) shl dropBits
            out[2 * i] = (v shr 8).toByte()
            out[2 * i + 1] = v.toByte()
        }
        return out
    }

    override fun decode(packet: ByteArray?, out: ShortArray): Int {
        if (packet == null) {
            out.fill(0)
            return AudioFormat.SAMPLES_PER_FRAME
        }
        val n = packet.size / 2
        for (i in 0 until n) {
            out[i] = (((packet[2 * i].toInt() shl 8) or (packet[2 * i + 1].toInt() and 0xFF))).toShort()
        }
        return n
    }
}

/** [seconds] of a [freqHz] sine at 48 kHz with the given peak [amplitude]. */
fun tone(freqHz: Double, seconds: Double, amplitude: Int = 10_000): ShortArray =
    ShortArray((AudioFormat.SAMPLE_RATE_HZ * seconds).roundToInt()) { i ->
        (amplitude * sin(2 * PI * freqHz * i / AudioFormat.SAMPLE_RATE_HZ)).roundToInt().toShort()
    }
