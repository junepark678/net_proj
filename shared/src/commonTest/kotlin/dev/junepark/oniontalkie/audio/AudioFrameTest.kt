package dev.junepark.oniontalkie.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AudioFrameTest {
    @Test
    fun equalityAndHashCodeUsePcmContentsAndMetadata() {
        val pcm = ShortArray(960) { it.toShort() }
        val frame = AudioFrame(7, 140, pcm)
        val equal = AudioFrame(7, 140, pcm.copyOf())
        assertEquals(frame, equal)
        assertEquals(frame.hashCode(), equal.hashCode())
        assertEquals(1, setOf(frame, equal).size)
        assertNotEquals(frame, frame.copy(seq = 8))
        assertNotEquals(frame, frame.copy(tsMs = 160))
        assertNotEquals(frame, frame.copy(pcm = pcm.copyOf().also { it[0] = -1 }))
    }
}
