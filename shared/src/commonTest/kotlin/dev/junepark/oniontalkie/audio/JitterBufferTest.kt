package dev.junepark.oniontalkie.audio

import dev.junepark.oniontalkie.protocol.Frame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JitterBufferTest {
    @Test
    fun steadyArrivalsPrimeAtMinimumDelayAndPlayInSequenceOrder() {
        val clock = ManualClock()
        val buffer = JitterBuffer(clockMs = { clock.nowMs })

        repeat(3) { sequence ->
            clock.nowMs = sequence * 20L
            buffer.put(frame(sequence))
            if (sequence < 2) assertNull(buffer.pull())
        }

        assertEquals(60, buffer.metrics.targetDelayMs)
        assertEquals(0, payloadSequence(buffer.pull()))
        assertEquals(1, payloadSequence(buffer.pull()))
        assertEquals(2, payloadSequence(buffer.pull()))
        assertEquals(0L, buffer.metrics.late)
    }

    @Test
    fun p95InterArrivalDeviationRaisesTargetAndExpiresAfterFiveSeconds() {
        val clock = ManualClock()
        val buffer = JitterBuffer(clockMs = { clock.nowMs })
        buffer.put(frame(0))
        clock.nowMs = 300
        buffer.put(frame(1))

        assertEquals(280, buffer.metrics.targetDelayMs)

        clock.nowMs += 5_001
        assertEquals(60, buffer.metrics.targetDelayMs)
    }

    @Test
    fun sustainedUnderrunRebuffersAndRecoversFromTorLikeLatencyJump() {
        val clock = ManualClock()
        val buffer = JitterBuffer(clockMs = { clock.nowMs })
        prime(buffer, clock)

        // Drain the primed frames, then simulate a delivery pause. The first two late frames raise
        // the target; the third sustained underrun resets the cursor before later frames arrive.
        for (sequence in 1..2) {
            clock.nowMs += 20
            assertTrue(buffer.pull() != null)
        }
        for (sequence in 3..5) {
            clock.nowMs += 20
            buffer.pull()
            buffer.put(frame(sequence))
        }
        for (sequence in 6..9) {
            clock.nowMs += 20
            buffer.put(frame(sequence))
        }

        // The first two packets arrived behind the old cursor and raised the target. The remaining
        // burst arrives after re-priming began and must be buffered instead of discarded forever.
        assertEquals(2L, buffer.metrics.late)
        assertTrue(buffer.metrics.plc >= 3)
        assertTrue(buffer.metrics.targetDelayMs > JitterBuffer.DEFAULT_MIN_MS)
        assertTrue(buffer.metrics.targetDelayMs <= 1_500)
        assertEquals(5, payloadSequence(buffer.pull()))
    }

    @Test
    fun fivePercentLossProducesOnePlcPerMissingFrame() {
        val clock = ManualClock()
        val buffer = JitterBuffer(clockMs = { clock.nowMs })
        val missing = setOf(19, 39, 59, 79, 99)

        for (sequence in 0 until 100) {
            clock.nowMs = sequence * 20L
            if (sequence !in missing) buffer.put(frame(sequence))
            buffer.pull()
        }
        repeat(2) {
            clock.nowMs += 20
            buffer.pull()
        }

        // Two null ticks prime the three-frame minimum. Two drain ticks reach the final omitted
        // sequence after the 60 ms startup span, so all five losses have reached playout.
        assertEquals(7L, buffer.metrics.plc)
        assertEquals(0L, buffer.metrics.late)
    }

    @Test
    fun reorderedAndDuplicateFramesArePlayedExactlyOnce() {
        val clock = ManualClock()
        val buffer = JitterBuffer(clockMs = { clock.nowMs })
        buffer.put(frame(0))
        buffer.put(frame(2))
        buffer.put(frame(2)) // Duplicate while queued.
        clock.nowMs = 5
        buffer.put(frame(1)) // Reordering is repaired before playout starts.
        assertEquals(0, payloadSequence(buffer.pull()))
        buffer.put(frame(2)) // A duplicate after playout is still ignored, not counted late.

        assertEquals(1, payloadSequence(buffer.pull()))
        assertEquals(2, payloadSequence(buffer.pull()))
        assertEquals(0L, buffer.metrics.late)
        assertEquals(0L, buffer.metrics.drops)
    }

    @Test
    fun sequenceWraparoundKeepsPacketsInOrder() {
        val clock = ManualClock()
        val buffer = JitterBuffer(clockMs = { clock.nowMs })
        buffer.put(frame(65_534))
        clock.nowMs = 20
        buffer.put(frame(65_535))
        clock.nowMs = 40
        buffer.put(frame(0))

        assertEquals(65_534, payloadSequence(buffer.pull()))
        assertEquals(65_535, payloadSequence(buffer.pull()))
        assertEquals(0, payloadSequence(buffer.pull()))
    }

    @Test
    fun bufferIsBoundedAndNonQuietCatchupDropsAtMostOncePerTwoSeconds() {
        val clock = ManualClock()
        val buffer = JitterBuffer(clockMs = { clock.nowMs })
        repeat(10) { buffer.put(frame(it)) }
        buffer.put(frame(75)) // Outside the 1,500 ms queue window.
        assertEquals(1L, buffer.metrics.drops)
        assertTrue(buffer.metrics.bufferMs <= 1_500)

        assertEquals(0, payloadSequence(buffer.pull()))
        assertEquals(2L, buffer.metrics.drops) // One frame dropped to catch up.
        clock.nowMs = 20
        buffer.pull()
        assertEquals(2L, buffer.metrics.drops)
        clock.nowMs = 2_000
        buffer.pull()
        assertEquals(3L, buffer.metrics.drops)
    }

    @Test
    fun initialReorderingCannotExpandTheQueuePastItsMaximumWindow() {
        val clock = ManualClock()
        val buffer = JitterBuffer(clockMs = { clock.nowMs })
        buffer.put(frame(100))
        buffer.put(frame(0))
        buffer.put(frame(200))

        assertEquals(2L, buffer.metrics.drops)
        assertEquals(20, buffer.metrics.bufferMs)
    }

    @Test
    fun quietCatchupCanDropOneFrameOnEachPlayoutTick() {
        val clock = ManualClock()
        val buffer = JitterBuffer(clockMs = { clock.nowMs })
        repeat(8) { buffer.put(frame(it)) }

        assertEquals(0, payloadSequence(buffer.pull(silenceLike = true)))
        assertEquals(1L, buffer.metrics.drops)
        assertEquals(2, payloadSequence(buffer.pull(silenceLike = true)))
        assertEquals(2L, buffer.metrics.drops)
    }

    @Test
    fun metricsListenerReceivesBufferLatePlcAndDropSnapshots() {
        val clock = ManualClock()
        val snapshots = mutableListOf<JitterBufferMetrics>()
        val buffer = JitterBuffer(clockMs = { clock.nowMs }, metricsListener = snapshots::add)
        buffer.put(frame(0))
        buffer.pull()

        assertFalse(snapshots.isEmpty())
        assertEquals(20, snapshots.last().bufferMs)
        assertEquals(1L, snapshots.last().plc)
        assertEquals(0L, snapshots.last().drops)
    }

    private fun prime(buffer: JitterBuffer, clock: ManualClock) {
        repeat(3) { sequence ->
            clock.nowMs = sequence * 20L
            buffer.put(frame(sequence))
        }
        assertEquals(0, payloadSequence(buffer.pull()))
    }

    private fun frame(sequence: Int) = Frame.Audio(
        seq = sequence.toUShort(),
        tsMs = (sequence * 20).toUInt(),
        payload = byteArrayOf((sequence shr 8).toByte(), sequence.toByte()),
    )

    private fun payloadSequence(payload: ByteArray?): Int {
        assertTrue(payload != null)
        assertEquals(2, payload.size)
        return (payload[0].toUByte().toInt() shl 8) or payload[1].toUByte().toInt()
    }

    private class ManualClock(var nowMs: Long = 0)
}
