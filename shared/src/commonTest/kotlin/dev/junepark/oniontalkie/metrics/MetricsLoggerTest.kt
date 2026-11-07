package dev.junepark.oniontalkie.metrics

import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricsLoggerTest {
    private val header = "t_s,rtt_ms,jitter_ms,loss_pct,late,plc,buffer_ms,tx_kbps,rx_kbps,circuit_id,hops"

    @Test
    fun headerIsExact() {
        assertEquals(header, MetricsLogger.CSV_HEADER)
    }

    @Test
    fun percentilesUseNearestRank() {
        val oneToTwenty = (1..20).map { it.toDouble() }.shuffled(Random(1))
        assertEquals(10.0, percentile(oneToTwenty, 50))
        assertEquals(19.0, percentile(oneToTwenty, 95))
        assertEquals(2.0, percentile(listOf(4.0, 1.0, 3.0, 2.0), 50))
        assertEquals(4.0, percentile(listOf(4.0, 1.0, 3.0, 2.0), 95))
        assertEquals(7.0, percentile(listOf(7.0), 50))
        assertEquals(7.0, percentile(listOf(7.0), 95))
    }

    @Test
    fun percentilesOfEmptyInputAreNull() {
        assertEquals(null, percentile(emptyList(), 50))
        assertEquals(null, percentile(emptyList(), 95))
    }

    @Test
    fun csvRowsAreWrittenInColumnOrder() {
        val csv = Buffer()
        val logger = MetricsLogger(csv) { Buffer() }
        logger.record(
            CallStats(
                tSeconds = 3, rttMs = 123.456, jitterMs = 1.2, lossPct = 100.0 / 6, late = 2, plc = 1,
                bufferMs = 60.0, txKbps = 24.0, rxKbps = 23.5, circuitId = "42", hops = 3,
            ),
        )
        logger.record(CallStats(tSeconds = 4)) // unavailable values stay blank
        assertEquals(
            "$header\n" +
                "3,123.46,1.20,16.67,2,1,60.00,24.00,23.50,42,3\n" +
                "4,,0.00,0.00,0,0,0.00,0.00,0.00,,\n",
            csv.readString(),
        )
    }

    @Test
    fun statsFlowPublishesLatestRow() {
        val logger = MetricsLogger(Buffer()) { Buffer() }
        assertEquals(CallStats(), logger.stats.value)
        val row = CallStats(tSeconds = 1, rttMs = 80.0)
        logger.record(row)
        assertEquals(row, logger.stats.value)
    }

    @Test
    fun summaryHasP50AndP95() {
        val summary = Buffer()
        val logger = MetricsLogger(Buffer()) { summary }
        for (i in 1..20) {
            logger.record(
                CallStats(
                    tSeconds = i.toLong(), rttMs = i * 10.0, jitterMs = i.toDouble(), lossPct = if (i == 20) 5.0 else 0.0,
                    late = if (i == 1) 2 else 0, plc = if (i == 2) 1 else 0, bufferMs = 60.0,
                    txKbps = 24.0, rxKbps = 20.0 + i / 100.0,
                ),
            )
        }
        logger.close()
        logger.close() // idempotent
        assertEquals(
            """
            {
              "samples": 20,
              "duration_s": 20,
              "late_total": 2,
              "plc_total": 1,
              "metrics": {
                "rtt_ms": {"p50": 100.00, "p95": 190.00},
                "jitter_ms": {"p50": 10.00, "p95": 19.00},
                "loss_pct": {"p50": 0.00, "p95": 0.00},
                "buffer_ms": {"p50": 60.00, "p95": 60.00},
                "tx_kbps": {"p50": 24.00, "p95": 24.00},
                "rx_kbps": {"p50": 20.10, "p95": 20.19}
              }
            }

            """.trimIndent(),
            summary.readString(),
        )
    }

    @Test
    fun summaryOfEmptyCallHasNullPercentiles() {
        val summary = Buffer()
        MetricsLogger(Buffer()) { summary }.close()
        val json = summary.readString()
        assertTrue("\"samples\": 0" in json)
        assertTrue("\"rtt_ms\": {\"p50\": null, \"p95\": null}" in json)
    }

    @Test
    fun openWritesFilesUnderCalls() {
        val dir = Path(SystemTemporaryDirectory, "oniontalkie-test-${Random.nextLong().toULong()}")
        SystemFileSystem.createDirectories(dir)
        try {
            val logger = MetricsLogger.open(dir, "2026-11-07T12:30:00Z", "abcd1234")
            logger.record(CallStats(tSeconds = 1, rttMs = 50.0, circuitId = "9", hops = 3))
            logger.close()

            val stem = "2026-11-07T12-30-00Z-abcd1234"
            val csv = SystemFileSystem.source(Path(dir, "calls", "$stem.csv")).buffered().readString()
            assertEquals("$header\n1,50.00,0.00,0.00,0,0,0.00,0.00,0.00,9,3\n", csv)
            val json = SystemFileSystem.source(Path(dir, "calls", "$stem.summary.json")).buffered().readString()
            assertTrue("\"rtt_ms\": {\"p50\": 50.00, \"p95\": 50.00}" in json)
        } finally {
            val calls = Path(dir, "calls")
            if (SystemFileSystem.exists(calls)) {
                SystemFileSystem.list(calls).forEach { SystemFileSystem.delete(it) }
                SystemFileSystem.delete(calls)
            }
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun fileStemIsFilesystemSafe() {
        assertEquals("2026-11-07T12-30-00Z-ab-cd", MetricsLogger.callFileStem("2026-11-07T12:30:00Z", "ab/cd"))
    }
}
