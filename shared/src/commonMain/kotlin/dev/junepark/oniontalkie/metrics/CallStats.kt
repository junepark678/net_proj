package dev.junepark.oniontalkie.metrics

/**
 * A per-call snapshot. Times are milliseconds, rates are decimal kilobits/second.
 * Loss, late and PLC counts are cumulative; rates cover the last sample interval.
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
)
