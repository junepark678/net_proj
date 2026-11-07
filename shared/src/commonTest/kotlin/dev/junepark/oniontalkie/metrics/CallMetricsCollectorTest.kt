package dev.junepark.oniontalkie.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CallMetricsCollectorTest {
    private val eps = 1e-9

    private fun CallMetricsCollector.receive(seq: Int, ts: Long, arrival: Long, bytes: Int = 60) =
        onAudioReceived(seq, ts, arrival, bytes)

    @Test
    fun jitterMatchesRfc3550HandComputation() {
        // Sender ts 0,20,40,60 ms; arrival 100,125,140,170 ms.
        // D = 5, -5, 10  ->  J = 5/16 = 0.3125; += (5-0.3125)/16 -> 0.60546875;
        // += (10-0.60546875)/16 -> 1.192626953125
        val c = CallMetricsCollector(startMs = 0)
        c.receive(0, 0, 100)
        assertEquals(0.0, c.sample(1000).jitterMs, eps) // first packet has no transit delta
        c.receive(1, 20, 125)
        assertEquals(0.3125, c.sample(2000).jitterMs, eps)
        c.receive(2, 40, 140)
        assertEquals(0.60546875, c.sample(3000).jitterMs, eps)
        c.receive(3, 60, 170)
        assertEquals(1.192626953125, c.sample(4000).jitterMs, eps)
    }

    @Test
    fun jitterHandlesSenderTimestampWrap() {
        val c = CallMetricsCollector(0)
        c.receive(0, 0xFFFFFFF6L, 100) // 10 ms before wrap
        c.receive(1, 10L, 120) // sender delta is 20 ms across the wrap -> D = 0
        assertEquals(0.0, c.sample(1000).jitterMs, eps)
    }

    @Test
    fun lossIsPerIntervalFromSequenceGaps() {
        val c = CallMetricsCollector(0)
        listOf(0, 1, 2, 4, 5).forEach { c.receive(it, it * 20L, it * 20L) }
        // expected 6, received 5 -> 1/6
        assertEquals(100.0 / 6, c.sample(1000).lossPct, eps)
        listOf(6, 7, 10).forEach { c.receive(it, it * 20L, it * 20L) }
        // interval: expected 10-5 = 5, received 3 -> 40%
        assertEquals(40.0, c.sample(2000).lossPct, eps)
        assertEquals(0.0, c.sample(3000).lossPct, eps) // nothing arrived, nothing expected
    }

    @Test
    fun lossHandlesSequenceWrapAndReordering() {
        val wrap = CallMetricsCollector(0)
        listOf(65534, 65535, 0, 1).forEach { wrap.receive(it, 0, 0) }
        assertEquals(0.0, wrap.sample(1000).lossPct, eps)

        val reordered = CallMetricsCollector(0)
        listOf(0, 2, 1, 3).forEach { reordered.receive(it, 0, 0) }
        assertEquals(0.0, reordered.sample(1000).lossPct, eps)

        val wrapLoss = CallMetricsCollector(0)
        listOf(65535, 1).forEach { wrapLoss.receive(it, 0, 0) } // seq 0 missing: 1 of 3
        assertEquals(100.0 / 3, wrapLoss.sample(1000).lossPct, eps)
    }

    @Test
    fun rttFromPong() {
        val c = CallMetricsCollector(0)
        assertNull(c.sample(1000).rttMs)
        c.onPong(echoedTsMs = 1000, nowMs = 1042)
        assertEquals(42.0, c.sample(2000).rttMs)
        c.onPong(echoedTsMs = 5000, nowMs = 4000) // echo from the future is ignored
        assertEquals(42.0, c.sample(3000).rttMs)
        c.onPong(echoedTsMs = 0xFFFFFFFBL, nowMs = 5) // 32-bit wrap: 10 ms
        assertEquals(10.0, c.sample(4000).rttMs)
    }

    @Test
    fun bitrateCountersAndGaugesPerInterval() {
        val c = CallMetricsCollector(startMs = 500)
        repeat(50) { c.onAudioSent(60) } // 3000 B in 1 s = 24 kbit/s
        repeat(25) { c.receive(it, it * 20L, it * 20L, bytes = 60) } // 1500 B = 12 kbit/s
        c.onLateFrame()
        c.onLateFrame()
        c.onPlcFrame()
        c.setBufferMs(80.0)
        c.setCircuit("17", 3)
        val s = c.sample(1500)
        assertEquals(1, s.tSeconds)
        assertEquals(24.0, s.txKbps, eps)
        assertEquals(12.0, s.rxKbps, eps)
        assertEquals(2, s.late)
        assertEquals(1, s.plc)
        assertEquals(80.0, s.bufferMs)
        assertEquals("17", s.circuitId)
        assertEquals(3, s.hops)

        val next = c.sample(2500) // counters reset, gauges persist
        assertEquals(2, next.tSeconds)
        assertEquals(0.0, next.txKbps)
        assertEquals(0, next.late)
        assertEquals(0, next.plc)
        assertEquals(80.0, next.bufferMs)
        c.setCircuit(null, null)
        assertNull(c.sample(3500).hops)
    }
}
