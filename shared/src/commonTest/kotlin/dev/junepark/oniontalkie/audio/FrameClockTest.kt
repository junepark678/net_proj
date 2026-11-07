package dev.junepark.oniontalkie.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource

@OptIn(ExperimentalTime::class)
class FrameClockTest {
    @Test
    fun mediaClockAdvancesInTwentyMillisecondStepsAndPacesSender() {
        val time = TestTimeSource()
        val clock = FrameClock(time)
        assertEquals(Duration.ZERO, clock.delayUntilNextFrame())
        repeat(100) { i ->
            val frame = clock.nextFrame(ShortArray(960))
            assertEquals(i, frame.seq)
            assertEquals(i * 20L, frame.tsMs)
            assertEquals(20.milliseconds, clock.delayUntilNextFrame())
            time += 20.milliseconds
            assertEquals(Duration.ZERO, clock.delayUntilNextFrame())
        }
    }

    @Test
    fun latenessDoesNotMakeDelayNegativeOrChangeMediaTimestamps() {
        val time = TestTimeSource()
        val clock = FrameClock(time)
        clock.nextFrame(ShortArray(960))
        time += 5.milliseconds
        assertEquals(15.milliseconds, clock.delayUntilNextFrame())
        time += 100.milliseconds
        assertEquals(Duration.ZERO, clock.delayUntilNextFrame())
        assertEquals(20L, clock.nextFrame(ShortArray(960)).tsMs)
    }

    @Test
    fun sequenceWrapDoesNotResetMediaTime() {
        val clock = FrameClock(TestTimeSource())
        val pcm = ShortArray(960)
        repeat(65536) { clock.nextFrame(pcm) }
        val wrapped = clock.nextFrame(pcm)
        assertEquals(0, wrapped.seq)
        assertEquals(1_310_720L, wrapped.tsMs)
    }

    @Test
    fun invalidFrameDoesNotAdvanceClock() {
        val clock = FrameClock(TestTimeSource())
        assertFailsWith<IllegalArgumentException> { clock.nextFrame(ShortArray(959)) }
        assertEquals(0L, clock.nextFrame(ShortArray(960)).tsMs)
        assertFailsWith<IllegalArgumentException> { AudioFrame(-1, 0, ShortArray(960)) }
        assertFailsWith<IllegalArgumentException> { AudioFrame(65536, 0, ShortArray(960)) }
        assertFailsWith<IllegalArgumentException> { AudioFrame(0, -1, ShortArray(960)) }
    }
}
