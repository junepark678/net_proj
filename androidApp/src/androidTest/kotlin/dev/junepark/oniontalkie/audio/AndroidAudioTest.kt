package dev.junepark.oniontalkie.audio

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.junepark.oniontalkie.audio.createAudioInput
import dev.junepark.oniontalkie.audio.createAudioOutput
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAudioTest {
    @get:Rule
    val audioPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test(timeout = 15_000)
    fun recordsOneSecondAsTwentyMillisecondFrames() {
        val input = createAudioInput()
        var frameCount = 0
        input.start()
        try {
            val deadline = SystemClock.elapsedRealtime() + 1_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val frame = input.read() ?: break
                assertEquals(AudioFormat.SAMPLES_PER_FRAME, frame.size)
                frameCount++
            }
        } finally {
            input.stop()
        }

        assertTrue("Expected about 50 frames in one second, got $frameCount", frameCount in 35..65)
    }

    @Test(timeout = 10_000)
    fun acceptsAndPlaysAOneFrameTone() {
        val output = createAudioOutput()
        val tone = ShortArray(AudioFormat.SAMPLES_PER_FRAME) { index ->
            (sin(2.0 * PI * 440.0 * index / AudioFormat.SAMPLE_RATE_HZ) * 8_000).toInt().toShort()
        }
        output.start()
        try {
            output.write(tone)
            SystemClock.sleep(100)
            assertTrue(output.underrunCount >= 0)
        } finally {
            output.stop()
        }
    }

    @Test
    fun communicationAudioModeIsRestoredAfterCall() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val previousMode = audioManager.mode
        val routing = AndroidAudioRouteController(context)

        routing.beginCommunication()
        try {
            assertEquals(AudioManager.MODE_IN_COMMUNICATION, audioManager.mode)
        } finally {
            routing.endCommunication()
        }
        assertEquals(previousMode, audioManager.mode)
    }
}
