package dev.junepark.oniontalkie.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetricsSummaryTest {
    @Test
    fun percentilesUseNearestRankOnUnsortedValues() {
        val values = (20 downTo 1).map(Int::toDouble)
        assertEquals(10.0, percentile(values, 0.50))
        assertEquals(19.0, percentile(values, 0.95))
        assertEquals(20.0, percentile(values, 1.0))
    }

    @Test
    fun emptySingletonAndRepeatedValuesAreWellDefined() {
        assertNull(percentile(emptyList(), 0.50))
        assertNull(percentile(emptyList(), 0.95))
        assertEquals(42.0, percentile(listOf(42.0), 0.50))
        assertEquals(42.0, percentile(listOf(42.0), 0.95))
        assertEquals(5.0, percentile(listOf(5.0, 5.0, 5.0), 0.95))
    }

    @Test
    fun summaryUsesSampledValuesAndSkipsUnavailableMeasurements() {
        val samples = (1..20).map { CallStats(jitterMs = it.toDouble(), rttMs = if (it == 20) 100.0 else null) }
        val summary = summaryJson(samples, 20.0)
        assertTrue(summary.contains("\"samples\": 20"))
        assertTrue(summary.contains("\"duration_s\": 20.0"))
        assertTrue(summary.contains("\"jitter_ms\": {\"p50\": 10.0, \"p95\": 19.0}"))
        assertTrue(summary.contains("\"rtt_ms\": {\"p50\": 100.0, \"p95\": 100.0}"))
        assertTrue(summary.contains("\"hops\": {\"p50\": null, \"p95\": null}"))
    }

    @Test
    fun summarySeparatesIntervalPercentilesFromCumulativeTotals() {
        val samples = listOf(
            CallStats(lossPct = 50.0, late = 2, plc = 3),
            CallStats(
                lossPct = 25.0, late = 3, plc = 5,
                intervalLossPct = 0.0, intervalLate = 1, intervalPlc = 2,
            ),
        )
        val summary = summaryJson(samples, 2.0)
        assertTrue(summary.contains("\"late_total\": 3"))
        assertTrue(summary.contains("\"plc_total\": 5"))
        assertTrue(summary.contains("\"loss_pct\": {\"p50\": 0.0, \"p95\": 50.0}"))
        assertTrue(summary.contains("\"late\": {\"p50\": 1.0, \"p95\": 2.0}"))
        assertTrue(summary.contains("\"plc\": {\"p50\": 2.0, \"p95\": 3.0}"))
        val empty = summaryJson(emptyList(), 0.0)
        assertTrue(empty.contains("\"late_total\": 0"))
        assertTrue(empty.contains("\"plc_total\": 0"))
    }
}
