package dev.junepark.oniontalkie.audio

import android.media.AudioAttributes
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

actual fun createAudioInput(): AudioInput = AndroidAudioInput()

actual fun createAudioOutput(): AudioOutput = AndroidAudioOutput()

private class AndroidAudioInput : AudioInput {
    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    @Synchronized
    override fun start() {
        check(!running) { "Audio input is already started" }
        val minBufferBytes = AudioRecord.getMinBufferSize(
            AudioFormat.SAMPLE_RATE_HZ,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBufferBytes > 0) { "AudioRecord rejected 48 kHz mono PCM16 (error $minBufferBytes)" }

        val platformFormat = android.media.AudioFormat.Builder()
            .setSampleRate(AudioFormat.SAMPLE_RATE_HZ)
            .setChannelMask(android.media.AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val candidate = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(platformFormat)
            .setBufferSizeInBytes(minBufferBytes * 2)
            .build()
        check(candidate.state == AudioRecord.STATE_INITIALIZED) {
            candidate.release()
            "AudioRecord could not initialize"
        }

        recorder = candidate
        try {
            enableAvailableEffects(candidate.audioSessionId)
            candidate.startRecording()
            check(candidate.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord did not enter recording state"
            }
            running = true
        } catch (failure: Throwable) {
            releaseEffects()
            recorder = null
            runCatching { candidate.release() }
            throw failure
        }
    }

    override fun read(): ShortArray? {
        check(running) { "Audio input must be started before read" }
        val activeRecorder = recorder ?: return null
        val frame = ShortArray(AudioFormat.SAMPLES_PER_FRAME)
        var offset = 0
        while (offset < frame.size) {
            if (!running) return null
            val count = try {
                activeRecorder.read(frame, offset, frame.size - offset, AudioRecord.READ_BLOCKING)
            } catch (failure: IllegalStateException) {
                if (!running) return null
                throw failure
            }
            when {
                count > 0 -> offset += count
                count == 0 && running -> continue
                !running -> return null
                else -> error("AudioRecord.read failed with code $count")
            }
        }
        return frame
    }

    @Synchronized
    override fun stop() {
        running = false
        val activeRecorder = recorder
        recorder = null
        if (activeRecorder != null) {
            if (activeRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { activeRecorder.stop() }
            }
            releaseEffects()
            activeRecorder.release()
        } else {
            releaseEffects()
        }
    }

    private fun enableAvailableEffects(sessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching { AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } }
                .getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching { NoiseSuppressor.create(sessionId)?.apply { enabled = true } }
                .getOrNull()
        }
    }

    private fun releaseEffects() {
        echoCanceler?.let { runCatching { it.release() } }
        noiseSuppressor?.let { runCatching { it.release() } }
        echoCanceler = null
        noiseSuppressor = null
    }
}

private class AndroidAudioOutput : AudioOutput {
    private val pendingFrames = ArrayBlockingQueue<ShortArray>(OUTPUT_QUEUE_FRAMES)
    @Volatile private var running = false
    @Volatile private var workerFailure: Throwable? = null
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    private var lastUnderruns = 0L

    override val underrunCount: Long
        get() {
            val activeTrack = track ?: return lastUnderruns
            return try {
                maxOf(lastUnderruns, activeTrack.underrunCount.toLong())
            } catch (_: IllegalStateException) {
                lastUnderruns
            }
        }

    @Synchronized
    override fun start() {
        check(!running) { "Audio output is already started" }
        val minBufferBytes = AudioTrack.getMinBufferSize(
            AudioFormat.SAMPLE_RATE_HZ,
            android.media.AudioFormat.CHANNEL_OUT_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBufferBytes > 0) { "AudioTrack rejected 48 kHz mono PCM16 (error $minBufferBytes)" }

        val platformFormat = android.media.AudioFormat.Builder()
            .setSampleRate(AudioFormat.SAMPLE_RATE_HZ)
            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val candidate = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(platformFormat)
            .setBufferSizeInBytes(maxOf(minBufferBytes, AudioFormat.SAMPLES_PER_FRAME * 2 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        check(candidate.state == AudioTrack.STATE_INITIALIZED) {
            candidate.release()
            "AudioTrack could not initialize"
        }

        try {
            candidate.play()
            track = candidate
            pendingFrames.clear()
            workerFailure = null
            running = true
            worker = Thread({ writeLoop(candidate) }, "onion-dial-audio-track").apply {
                isDaemon = true
                start()
            }
        } catch (failure: Throwable) {
            running = false
            track = null
            runCatching { candidate.release() }
            throw failure
        }
    }

    /** Copies and queues a frame; the call returns immediately without waiting for device writes. */
    override fun write(pcm: ShortArray) {
        require(pcm.size == AudioFormat.SAMPLES_PER_FRAME) { "Expected one 20 ms PCM frame" }
        check(running) { "Audio output must be started before write" }
        workerFailure?.let { throw IllegalStateException("AudioTrack writer failed", it) }

        val ownedFrame = pcm.copyOf()
        if (!pendingFrames.offer(ownedFrame)) {
            pendingFrames.poll() // Keep the newest speech close to the live edge.
            if (!pendingFrames.offer(ownedFrame)) {
                throw IllegalStateException("AudioTrack queue stopped while accepting a frame")
            }
        }
    }

    @Synchronized
    override fun stop() {
        val activeTrack = track
        val activeWorker = worker
        running = false
        pendingFrames.clear()
        activeWorker?.interrupt()
        if (activeWorker != null && activeWorker !== Thread.currentThread()) {
            runCatching { activeWorker.join(STOP_JOIN_MS) }
        }

        if (activeTrack != null) {
            lastUnderruns = try {
                maxOf(lastUnderruns, activeTrack.underrunCount.toLong())
            } catch (_: IllegalStateException) {
                lastUnderruns
            }
            runCatching { activeTrack.pause() }
            runCatching { activeTrack.flush() }
            runCatching { activeTrack.stop() }
            activeTrack.release()
        }
        track = null
        worker = null
        if (activeWorker != null && activeWorker !== Thread.currentThread()) {
            runCatching { activeWorker.join(STOP_JOIN_MS) }
        }
    }

    private fun writeLoop(activeTrack: AudioTrack) {
        try {
            while (running || pendingFrames.isNotEmpty()) {
                val frame = pendingFrames.poll(QUEUE_POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                var offset = 0
                while (running && offset < frame.size) {
                    val written = activeTrack.write(
                        frame,
                        offset,
                        frame.size - offset,
                        AudioTrack.WRITE_NON_BLOCKING,
                    )
                    when {
                        written > 0 -> offset += written
                        written == 0 -> Thread.sleep(WRITE_RETRY_MS)
                        !running -> break
                        else -> throw IllegalStateException("AudioTrack.write failed with code $written")
                    }
                }
            }
        } catch (failure: InterruptedException) {
            if (running) workerFailure = failure
            Thread.currentThread().interrupt()
        } catch (failure: Throwable) {
            if (running) workerFailure = failure
        }
    }

    private companion object {
        const val OUTPUT_QUEUE_FRAMES = 3
        const val QUEUE_POLL_MS = 20L
        const val WRITE_RETRY_MS = 1L
        const val STOP_JOIN_MS = 250L
    }
}
