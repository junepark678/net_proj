package dev.junepark.oniontalkie.metrics

import kotlin.math.ceil

/** Nearest-rank percentile. Empty input has no percentile (JSON null). */
internal fun percentile(values: List<Double>, fraction: Double): Double? {
    require(fraction > 0.0 && fraction <= 1.0)
    if (values.isEmpty()) return null
    return values.sorted()[ceil(fraction * values.size).toInt() - 1]
}

internal fun summaryJson(samples: List<CallStats>, durationSeconds: Double): String {
    val metrics = linkedMapOf<String, (CallStats) -> Double?>(
        "rtt_ms" to { it.rttMs },
        "jitter_ms" to { it.jitterMs },
        "loss_pct" to { it.intervalLossPct },
        "late" to { it.intervalLate.toDouble() },
        "plc" to { it.intervalPlc.toDouble() },
        "buffer_ms" to { it.bufferMs },
        "tx_kbps" to { it.txKbps },
        "rx_kbps" to { it.rxKbps },
        "hops" to { it.hops?.toDouble() },
    )
    return buildString {
        append("{\n  \"samples\": ${samples.size},\n  \"duration_s\": $durationSeconds")
        append(",\n  \"late_total\": ${samples.lastOrNull()?.late ?: 0}")
        append(",\n  \"plc_total\": ${samples.lastOrNull()?.plc ?: 0}")
        for ((name, select) in metrics) {
            val values = samples.mapNotNull(select)
            append(",\n  \"$name\": {\"p50\": ${percentile(values, 0.50)}, \"p95\": ${percentile(values, 0.95)}}")
        }
        append("\n}\n")
    }
}
