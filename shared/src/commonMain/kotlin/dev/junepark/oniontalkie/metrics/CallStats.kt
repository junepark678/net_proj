package dev.junepark.oniontalkie.metrics

/**
 * A per-call snapshot. Times are milliseconds, rates are decimal kilobits/second.
 * Loss, late and PLC counts are cumulative; interval-prefixed fields and rates
 * cover the last sample interval. CSV uses the interval fields.
 * A null RTT or circuit field means that measurement is unavailable.
 */
data class CallStats(
    val elapsedSeconds: Double = 0.0,
    val rttMs: Double? = null,
    val jitterMs: Double = 0.0,
    val lossPct: Double = 0.0,
    val late: Long = 0,
    val plc: Long = 0,
    val bufferMs: Double = 0.0,
    val txKbps: Double = 0.0,
    val rxKbps: Double = 0.0,
    val circuitId: String? = null,
    val hops: Int? = null,
    val intervalLossPct: Double = lossPct,
    val intervalLate: Long = late,
    val intervalPlc: Long = plc,
)

/** Persisted per-call summary used by the history screen and CSV export action. */
data class CallHistoryEntry(
    val id: String,
    val peerLabel: String,
    val timestampUtc: String,
    val durationSeconds: Double,
    val rttP50Ms: Double?,
    val jitterP50Ms: Double?,
    val lossP50Pct: Double?,
    val bufferP50Ms: Double?,
    val csvPath: String,
)
