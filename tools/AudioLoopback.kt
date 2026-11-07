package dev.junepark.oniontalkie.tools

import dev.junepark.oniontalkie.AUDIO_FRAME_SAMPLES
import dev.junepark.oniontalkie.AudioInput
import dev.junepark.oniontalkie.AudioOutput
import dev.junepark.oniontalkie.createAudioInput
import dev.junepark.oniontalkie.createAudioOutput
import dev.junepark.oniontalkie.OpusDecoder
import dev.junepark.oniontalkie.OpusEncoder

/**
 * Manual desktop mic → Opus → speaker sanity check. Compile/run this entry
 * point after #10's OpusCodec has been merged into the shared module.
 */
fun main() {
    val input: AudioInput = createAudioInput()
    val output: AudioOutput = createAudioOutput()
    val captureResult = input.open()
    if (captureResult.isFailure) {
        System.err.println("Could not open microphone: ${captureResult.exceptionOrNull()?.message}")
        input.close()
        return
    }
    val playbackResult = output.open()
    if (playbackResult.isFailure) {
        System.err.println("Could not open speaker: ${playbackResult.exceptionOrNull()?.message}")
        input.close()
        output.close()
        return
    }

    val encoder = OpusEncoder()
    val decoder = OpusDecoder()
    println("Looping microphone through Opus. Press Ctrl+C to stop.")
    try {
        while (true) {
            val frame = input.readFrame().getOrThrow()
            check(frame.size == AUDIO_FRAME_SAMPLES)
            val decoded = ShortArray(AUDIO_FRAME_SAMPLES)
            decoder.decode(encoder.encode(frame), decoded)
            output.writeFrame(decoded).getOrThrow()
        }
    } finally {
        input.close()
        output.close()
        println("Playback underruns: ${output.underrunCount}")
    }
}
