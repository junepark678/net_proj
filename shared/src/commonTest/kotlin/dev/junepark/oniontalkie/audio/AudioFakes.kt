package dev.junepark.oniontalkie.audio

import dev.junepark.oniontalkie.codec.OpusCodec

/** Hardware-free capture; the source returns null at EOF. Source buffers are copied. */
class FakeAudioInput(private val pcmSource: () -> ShortArray?) : AudioInput {
    private var started = false

    override fun start() {
        check(!started)
        started = true
    }

    override fun read(): ShortArray? {
        check(started)
        val pcm = pcmSource() ?: return null
        require(pcm.size == AudioFormat.SAMPLES_PER_FRAME)
        return pcm.copyOf()
    }

    override fun stop() {
        started = false
    }
}

/** Records a copy of each submitted frame, optionally simulating playback work. */
class CapturingAudioOutput(private val onWrite: () -> Unit = {}) : AudioOutput {
    private var started = false
    private val captured = mutableListOf<ShortArray>()
    val frames: List<ShortArray> get() = captured.toList()

    override fun start() {
        check(!started)
        started = true
    }

    override fun write(pcm: ShortArray) {
        check(started)
        require(pcm.size == AudioFormat.SAMPLES_PER_FRAME)
        onWrite()
        captured.add(pcm.copyOf())
    }

    override fun stop() {
        started = false
    }
}

/**
 * Test-only signed PCM transport with 12-bit quantization. This is NOT Opus:
 * finite quantization noise exercises the SNR assertion while #10 is pending.
 */
class FakeOpusCodec(
    private val onEncode: () -> Unit = {},
    private val onDecode: () -> Unit = {},
) : OpusCodec {
    override fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size == AudioFormat.SAMPLES_PER_FRAME)
        onEncode()
        return ByteArray(pcm.size * 2).also { packet ->
            pcm.forEachIndexed { i, sample ->
                val quantized = sample.toInt() / 16 * 16
                packet[2 * i] = quantized.toByte()
                packet[2 * i + 1] = (quantized shr 8).toByte()
            }
        }
    }

    override fun decode(packet: ByteArray?, out: ShortArray): Int {
        require(out.size == AudioFormat.SAMPLES_PER_FRAME)
        onDecode()
        if (packet == null) {
            out.fill(0)
        } else {
            require(packet.size == out.size * 2)
            out.indices.forEach { i ->
                out[i] = ((packet[2 * i].toInt() and 0xff) or
                    ((packet[2 * i + 1].toInt() and 0xff) shl 8)).toShort()
            }
        }
        return out.size
    }
}
