package dev.junepark.oniontalkie.metrics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MetricsLoggerTest {
    @Test
    fun syntheticFramesMatchHandComputedJitterLossAndCsv() = runTest {
        val sink = MemoryMetricsSink()
        val logger = logger(sink)
        logger.onAudioReceived(100, 1_000)
        advanceTimeBy(30)
        logger.onAudioReceived(101, 1_020)
        // D = 30 - 20 = 10; J = 10/16 = 0.625.
        advanceTimeBy(10)
        logger.onAudioReceived(103, 1_060)
        // D = 10 - 40 = -30; J = 0.625 + (30 - 0.625)/16.
        logger.onPlayout(bufferMs = 80.0, late = 2, plc = 3)
        logger.onTransportBytes(sent = 3_000, received = 6_000)
        logger.onCircuitStatus("42", "250+circuit-status=\r\n41 BUILT one,two\r\n42 BUILT one,two,three PURPOSE=HS_CLIENT_REND\r\n.\r\n250 OK")
        advanceTimeBy(960)
        runCurrent()

        assertEquals(2.4609375, logger.stats.value.jitterMs)
        assertEquals(25.0, logger.stats.value.lossPct) // 1 missing / 4 expected.
        assertEquals(24.0, logger.stats.value.txKbps)
        assertEquals(48.0, logger.stats.value.rxKbps)
        assertEquals(3, logger.stats.value.hops)
        assertEquals(
            "t_s,rtt_ms,jitter_ms,loss_pct,late,plc,buffer_ms,tx_kbps,rx_kbps,circuit_id,hops\n" +
                "1,,2.46,25.00,2,3,80.00,24.00,48.00,42,3\n",
            sink.csv.toString(),
        )
        logger.close()
        assertTrue(sink.summary!!.contains("\"jitter_ms\": {\"p50\": 2.4609375, \"p95\": 2.4609375}"))
        assertTrue(sink.closed)
    }

    @Test
    fun pongUsesLocalSendTimeAndIgnoresUnknownOrDuplicateEchoes() = runTest {
        val logger = logger()
        logger.onPong(99)
        assertNull(logger.stats.value.rttMs)
        logger.onPingSent(0xffff_fffe)
        advanceTimeBy(120)
        logger.onPong(0xffff_fffe)
        advanceTimeBy(100)
        logger.onPong(0xffff_fffe)
        logger.onPong(123)
        advanceTimeBy(780)
        runCurrent()
        assertEquals(120.0, logger.stats.value.rttMs)
        logger.close()
    }

    @Test
    fun timestampWrapDoesNotProduceAJitterSpike() = runTest {
        val logger = logger()
        logger.onAudioReceived(0, 0xffff_fff0)
        advanceTimeBy(30)
        logger.onAudioReceived(1, 4) // Sender advanced 20 ms across u32 wrap.
        advanceTimeBy(970)
        runCurrent()
        assertEquals(0.625, logger.stats.value.jitterMs)
        logger.close()
    }

    @Test
    fun constantPeerClockOffsetDoesNotAffectJitter() = runTest {
        val logger = logger()
        logger.onAudioReceived(0, 1_000_000)
        repeat(10) {
            advanceTimeBy(20)
            logger.onAudioReceived(it + 1, 1_000_020L + it * 20)
        }
        advanceTimeBy(800)
        runCurrent()
        assertEquals(0.0, logger.stats.value.jitterMs)
        logger.close()
    }

    @Test
    fun sequenceWrapReorderingAndDuplicatesDoNotInflateLoss() = runTest {
        val logger = logger()
        logger.onAudioReceived(65_534, 0)
        logger.onAudioReceived(0, 40)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(100.0 / 3, logger.stats.value.lossPct, 0.000001)
        logger.onAudioReceived(65_535, 20) // Repairs the missing frame.
        logger.onAudioReceived(0, 40) // Duplicate.
        logger.onAudioReceived(1, 60)
        logger.onAudioReceived(65_533, 0) // Before the first observation.
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0.0, logger.stats.value.lossPct)
        logger.close()
    }

    @Test
    fun sequenceTrackingSurvivesMoreThanOneFullSequenceSpace() = runTest {
        val logger = logger()
        repeat(65_540) { logger.onAudioReceived((65_534 + it) and 0xffff, 0) }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0.0, logger.stats.value.lossPct)
        logger.close()
    }

    @Test
    fun idleIntervalsResetBitrateButPreserveCumulativeCounts() = runTest {
        val sink = MemoryMetricsSink()
        val logger = logger(sink)
        logger.onTransportBytes(sent = 3_000)
        logger.onPlayout(60.0, late = 1, plc = 2)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(24.0, logger.stats.value.txKbps)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0.0, logger.stats.value.txKbps)
        assertEquals(0.0, logger.stats.value.rxKbps)
        assertEquals(1L, logger.stats.value.late)
        assertEquals(2L, logger.stats.value.plc)
        assertEquals(60.0, logger.stats.value.bufferMs)
        assertEquals(0L, logger.stats.value.intervalLate)
        assertEquals(0L, logger.stats.value.intervalPlc)
        assertEquals("2,,0.00,0.00,0,0,60.00,0.00,0.00,,", sink.csv.lines()[2])
        logger.close()
        assertTrue(sink.summary!!.contains("\"late_total\": 1"))
        assertTrue(sink.summary!!.contains("\"plc_total\": 2"))
    }

    @Test
    fun csvLossDescribesEachIntervalWhileStatsRetainCumulativeLoss() = runTest {
        val sink = MemoryMetricsSink()
        val logger = logger(sink)
        logger.onAudioReceived(0, 0)
        logger.onAudioReceived(2, 40)
        advanceTimeBy(1_000)
        runCurrent()
        logger.onAudioReceived(3, 60)
        logger.onAudioReceived(4, 80)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(20.0, logger.stats.value.lossPct)
        assertEquals(0.0, logger.stats.value.intervalLossPct)
        assertEquals("33.33", sink.csv.lines()[1].split(',')[3])
        assertEquals("0.00", sink.csv.lines()[2].split(',')[3])
        logger.close()
    }

    @Test
    fun csvUsesIntegerSecondsAndFixedDecimalsForSmallAndLargeValues() = runTest {
        val sink = MemoryMetricsSink()
        val logger = logger(sink)
        logger.onPlayout(1e9)
        logger.onAudioReceived(0, 0, arrivalMs = 0)
        logger.onAudioReceived(1, 20, arrivalMs = 30)
        logger.onPingSent(7, sentMs = 100)
        logger.onPong(7, arrivalMs = 221)
        logger.onTransportBytes(sent = 1)
        advanceTimeBy(250)
        logger.close()
        assertEquals(
            "0,121.00,0.62,0.00,0,0,1000000000.00,0.03,0.00,,",
            sink.csv.lines()[1],
        )
        assertEquals("0.00", formatFixed(1e-10))
        assertEquals("12.35", formatFixed(12.346))
        assertEquals("100000000000000000000.00", formatFixed(1e20))
    }

    @Test
    fun delayedSinkDoesNotBlockEventRecording() = runTest {
        val enteredSink = CompletableDeferred<Unit>()
        val releaseSink = CompletableDeferred<Unit>()
        val sink = object : MetricsSink {
            override fun appendCsv(text: String) {
                if (text != MetricsLogger.CSV_HEADER + "\n") {
                    enteredSink.complete(Unit)
                    runBlocking { releaseSink.await() }
                }
            }
            override fun writeSummary(json: String) = Unit
            override fun close() = Unit
        }
        val logger = MetricsLogger(sink, CoroutineScope(backgroundScope.coroutineContext + Dispatchers.Default))
        logger.onAudioReceived(0, 0, arrivalMs = 0)
        logger.onPingSent(7, sentMs = 100)
        try {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { enteredSink.await() }
                // The sink remains blocked until all event calls have returned.
                withTimeout(1_000) {
                    logger.onAudioReceived(1, 20, arrivalMs = 30)
                    logger.onPong(7, arrivalMs = 220)
                    logger.onTransportBytes(sent = 100, received = 200)
                    logger.onPlayout(60.0, late = 1, plc = 2)
                }
                assertFalse(releaseSink.isCompleted)
            }
        } finally {
            releaseSink.complete(Unit)
            logger.close()
        }
        assertEquals(0.625, logger.stats.value.jitterMs)
        assertEquals(120.0, logger.stats.value.rttMs)
        assertEquals(1L, logger.stats.value.late)
        assertEquals(2L, logger.stats.value.plc)
        assertTrue(logger.stats.value.txKbps > 0)
    }

    @Test
    fun delayedSamplingUsesActualElapsedTimeForBitrate() = runTest {
        var monotonicMs = 0L
        val logger = MetricsLogger(MemoryMetricsSink(), backgroundScope) { monotonicMs }
        logger.onTransportBytes(sent = 7_500)
        monotonicMs = 2_500 // Simulate a delayed dispatcher wake-up.
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2.5, logger.stats.value.elapsedSeconds)
        assertEquals(24.0, logger.stats.value.txKbps)
        logger.close()
    }

    @Test
    fun closeAtSampleBoundaryDoesNotDuplicateTheLastRow() = runTest {
        val sink = MemoryMetricsSink()
        val logger = logger(sink)
        advanceTimeBy(1_000)
        runCurrent()
        logger.close()
        assertEquals(2, sink.csv.lines().count { it.isNotEmpty() })
        assertTrue(sink.summary!!.contains("\"samples\": 1"))
    }

    @Test
    fun closeInSameMillisecondAsTickPreservesNewEvents() = runTest {
        val sink = MemoryMetricsSink()
        val logger = logger(sink)
        advanceTimeBy(1_000)
        runCurrent()
        logger.onTransportBytes(sent = 3, received = 6)
        logger.onPlayout(80.0, late = 2, plc = 3)
        logger.onAudioReceived(0, 0)
        logger.onAudioReceived(2, 40)
        logger.onPingSent(7, sentMs = 900)
        logger.onPong(7)
        logger.close()
        assertEquals("1,100.00,2.50,33.33,2,3,80.00,24.00,48.00,,", sink.csv.lines()[2])
        assertTrue(sink.summary!!.contains("\"samples\": 2"))
        assertTrue(sink.summary!!.contains("\"late_total\": 2"))
        assertTrue(sink.summary!!.contains("\"plc_total\": 3"))
        assertFalse(sink.csv.contains("Infinity"))
    }

    @Test
    fun immediateClosePreservesEventsBeforeFirstMillisecond() = runTest {
        val sink = MemoryMetricsSink()
        val logger = logger(sink)
        logger.onPlayout(60.0, late = 1, plc = 2)
        logger.close()
        assertEquals("0,,0.00,0.00,1,2,60.00,0.00,0.00,,", sink.csv.lines()[1])
        assertTrue(sink.summary!!.contains("\"late_total\": 1"))
        assertTrue(sink.summary!!.contains("\"plc_total\": 2"))
    }

    @Test
    fun closeFlushesPartialIntervalAndStopsSamplingExactlyOnce() = runTest {
        val sink = MemoryMetricsSink()
        val logger = logger(sink)
        advanceTimeBy(250)
        logger.onTransportBytes(sent = 750)
        logger.close()
        assertEquals(0.25, logger.stats.value.elapsedSeconds)
        assertEquals(24.0, logger.stats.value.txKbps)
        assertEquals(2, sink.csv.lines().count { it.isNotEmpty() })
        assertEquals(1, sink.summaryWrites)
        assertEquals(1, sink.closes)
        logger.close()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, sink.summaryWrites)
        assertEquals(1, sink.closes)
        assertFailsWith<IllegalStateException> { logger.onTransportBytes(sent = 1) }
    }

    @Test
    fun cancelledScopeCanStillBeClosedAndSummarized() = runTest {
        val sink = MemoryMetricsSink()
        val logger = logger(sink)
        advanceTimeBy(500)
        backgroundScope.cancel()
        logger.close()
        assertTrue(sink.closed)
        assertTrue(sink.summary!!.contains("\"duration_s\": 0.5"))
    }

    @Test
    fun emptyCallHasHeaderAndNullPercentiles() = runTest {
        val sink = MemoryMetricsSink()
        val logger = logger(sink)
        logger.close()
        assertEquals(MetricsLogger.CSV_HEADER + "\n", sink.csv.toString())
        assertTrue(sink.summary!!.contains("\"samples\": 0"))
        assertTrue(sink.summary!!.contains("\"rtt_ms\": {\"p50\": null, \"p95\": null}"))
        assertFalse(sink.summary!!.contains("NaN"))
    }

    @Test
    fun slowSinkWritesDoNotSkipSampleSeconds() = runTest {
        // Each row write "costs" 400 ms of wall-clock time that the virtual
        // scheduler never sees; a fixed-deadline sampler still lands on every
        // whole second instead of drifting past some of them.
        var writeCostMs = 0L
        val sink = object : MemoryMetricsSink() {
            override fun appendCsv(text: String) {
                super.appendCsv(text)
                if (text != MetricsLogger.CSV_HEADER + "\n") writeCostMs += 400
            }
        }
        val logger = MetricsLogger(sink, backgroundScope) { testScheduler.currentTime + writeCostMs }
        advanceTimeBy(5_000)
        runCurrent()
        val seconds = sink.csv.lines().drop(1).filter { it.isNotEmpty() }.map { it.substringBefore(',').toInt() }
        assertEquals((1..seconds.size).toList(), seconds)
        assertTrue(seconds.size >= 5)
        logger.close()
    }

    @Test
    fun bookkeepingOnlyEventsDoNotProduceAPartialFinalRow() = runTest {
        val sink = MemoryMetricsSink()
        val logger = logger(sink)
        advanceTimeBy(1_000)
        runCurrent()
        logger.onPingSent(9, sentMs = 1_000)
        logger.onPong(42) // Unknown echo: nothing measured.
        logger.onTransportBytes(sent = 0, received = 0)
        logger.onPlayout(0.0, late = 0, plc = 0) // Unchanged buffer, no events.
        logger.onCircuitStatus(null, null)
        logger.close()
        assertEquals(2, sink.csv.lines().count { it.isNotEmpty() })
        assertTrue(sink.summary!!.contains("\"samples\": 1"))
    }

    @Test
    fun missingCircuitDataClearsHopsAndNeverSelectsAnUnrelatedCircuit() = runTest {
        val logger = logger()
        logger.onCircuitStatus("7", "7 BUILT \$aaa~one,\$bbb=two,\$ccc PURPOSE=HS_CLIENT_REND")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(3, logger.stats.value.hops)
        logger.onCircuitStatus("7", "8 BUILT one,two,three")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("7", logger.stats.value.circuitId)
        assertNull(logger.stats.value.hops)
        logger.onCircuitStatus("7", "7 BUILT PURPOSE=HS_CLIENT_REND")
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(logger.stats.value.hops)
        logger.onCircuitStatus(null, null)
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(logger.stats.value.circuitId)
        logger.close()
    }

    @Test
    fun invalidEventsAreRejectedBeforeChangingState() = runTest {
        val logger = logger()
        assertFailsWith<IllegalArgumentException> { logger.onAudioReceived(-1, 0) }
        assertFailsWith<IllegalArgumentException> { logger.onAudioReceived(0, 0x1_0000_0000) }
        assertFailsWith<IllegalArgumentException> { logger.onPingSent(-1) }
        assertFailsWith<IllegalArgumentException> { logger.onPong(-1) }
        assertFailsWith<IllegalArgumentException> { logger.onTransportBytes(received = -1) }
        assertFailsWith<IllegalArgumentException> { logger.onPlayout(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { logger.onPlayout(0.0, late = -1) }
        assertFailsWith<IllegalArgumentException> { logger.onCircuitStatus("7,8", null) }
        logger.close()
    }

    @Test
    fun sinkIsReleasedWhenSummaryWritingFails() = runTest {
        val sink = object : MetricsSink {
            var closed = false
            override fun appendCsv(text: String) = Unit
            override fun writeSummary(json: String): Unit = error("Disk full")
            override fun close() { closed = true }
        }
        val logger = MetricsLogger(sink, backgroundScope) { testScheduler.currentTime }
        assertFailsWith<IllegalStateException> { logger.close() }
        assertTrue(sink.closed)
    }

    @Test
    fun sinkIsReleasedWhenFinalCsvWritingFails() = runTest {
        val sink = object : MetricsSink {
            var closed = false
            override fun appendCsv(text: String) {
                if (text != MetricsLogger.CSV_HEADER + "\n") error("Disk full")
            }
            override fun writeSummary(json: String): Unit = error("Summary should not be written")
            override fun close() { closed = true }
        }
        val logger = MetricsLogger(sink, backgroundScope) { testScheduler.currentTime }
        advanceTimeBy(250)
        assertFailsWith<IllegalStateException> { logger.close() }
        assertTrue(sink.closed)
    }

    private fun TestScope.logger(sink: MetricsSink = MemoryMetricsSink()): MetricsLogger =
        MetricsLogger(sink, backgroundScope) { testScheduler.currentTime }
}

internal open class MemoryMetricsSink : MetricsSink {
    val csv = StringBuilder()
    var summary: String? = null
    var summaryWrites = 0
    var closes = 0
    val closed get() = closes > 0

    override open fun appendCsv(text: String) { csv.append(text) }
    override fun writeSummary(json: String) {
        summary = json
        summaryWrites++
    }
    override fun close() { closes++ }
}
