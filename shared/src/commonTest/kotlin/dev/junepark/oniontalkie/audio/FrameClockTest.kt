package dev.junepark.oniontalkie.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FrameClockTest {
    @Test
    fun stampsConsecutiveFramesEvery20Ms() {
        val clock = FrameClock(startMs = 1_000)
        val frames = List(3) { clock.nextFrame(ShortArray(AudioFormat.SAMPLES_PER_FRAME)) }
        assertEquals(listOf(0, 1, 2), frames.map { it.seq })
        assertEquals(listOf(1_000L, 1_020L, 1_040L), frames.map { it.tsMs })
    }

    @Test
    fun msUntilNextCountsDownAndClampsAtZero() {
        val clock = FrameClock(startMs = 0)
        assertEquals(0, clock.msUntilNext(0)) // frame 0 is due immediately
        clock.nextFrame(ShortArray(0))
        assertEquals(15, clock.msUntilNext(5))
        assertEquals(0, clock.msUntilNext(50))
    }

    @Test
    fun framesCompareByContent() {
        assertEquals(AudioFrame(1, 20, shortArrayOf(1, 2)), AudioFrame(1, 20, shortArrayOf(1, 2)))
    }

    @Test
    fun formatConstantsAreConsistent() {
        assertEquals(960, AudioFormat.SAMPLES_PER_FRAME)
    }

    @Test
    fun snrRejectsNoOverlap() {
        assertFailsWith<IllegalArgumentException> { snrDb(ShortArray(4), ShortArray(2), delaySamples = 2) }
        assertTrue(snrDb(shortArrayOf(100, 100), shortArrayOf(0, 100, 100), delaySamples = 1).isInfinite())
    }
}
