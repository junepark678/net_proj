package dev.junepark.oniontalkie.tools

import dev.junepark.oniontalkie.audio.AudioFormat
import dev.junepark.oniontalkie.audio.createAudioInput
import dev.junepark.oniontalkie.audio.createAudioOutput
import dev.junepark.oniontalkie.audio.createOpusCodec

/** Manual desktop microphone → Opus → speaker sanity check; requires audio hardware. */
fun main() {
    val input = createAudioInput()
    val output = createAudioOutput()
    val codec = createOpusCodec()
    try {
        input.start()
        output.start()
        println("Looping microphone through Opus. Press Ctrl+C to stop.")
        while (true) {
            val frame = input.read() ?: break
            check(frame.size == AudioFormat.SAMPLES_PER_FRAME)
            val packet = codec.encode(frame)
            val decoded = ShortArray(AudioFormat.SAMPLES_PER_FRAME)
            codec.decode(packet, decoded)
            output.write(decoded)
        }
    } finally {
        input.stop()
        output.stop()
        println("Playback underruns: ${output.underrunCount}")
    }
}
