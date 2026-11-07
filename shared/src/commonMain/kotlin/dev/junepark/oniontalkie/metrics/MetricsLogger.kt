package dev.junepark.oniontalkie.metrics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString

/**
 * Appends one CSV row per [record] to a per-call file, publishes the latest row on [stats]
 * and writes a p50/p95 `summary.json` on [close]. Create it with [open] for real files, or
 * pass sinks directly (e.g. in tests).
 *
 * Not thread-safe: call from the same coroutine that drives [CallMetricsCollector.sample].
 *
 * @param csv destination of the CSV rows; the header is written immediately.
 * @param summary lazily opens the sink for `summary.json`; invoked once from [close].
 */
class MetricsLogger(
    private val csv: Sink,
    private val summary: () -> Sink,
) {
    private val rows = mutableListOf<CallStats>()
    private val _stats = MutableStateFlow(CallStats())
    private var closed = false

    /** Latest snapshot; drives the live metrics screen. */
    val stats: StateFlow<CallStats> = _stats

    init {
        csv.writeString(CSV_HEADER + "\n")
        csv.flush()
    }

    /** Appends [row] to the CSV (flushed immediately), publishes it on [stats] and keeps it for the summary. */
    fun record(row: CallStats) {
        check(!closed) { "MetricsLogger is closed" }
        csv.writeString(toCsvRow(row) + "\n")
        csv.flush()
        rows += row
        _stats.value = row
    }

    /** Writes `summary.json` and closes both files. Safe to call more than once. */
    fun close() {
        if (closed) return
        closed = true
        csv.close()
        val out = summary()
        try {
            out.writeString(summaryJson(rows))
        } finally {
            out.close()
        }
    }

    companion object {
        /** Exact CSV header, in column order. */
        const val CSV_HEADER = "t_s,rtt_ms,jitter_ms,loss_pct,late,plc,buffer_ms,tx_kbps,rx_kbps,circuit_id,hops"

        /**
         * Opens `<appDataDir>/calls/<iso>-<peer>.csv` (creating the directory) and, on [close],
         * `<iso>-<peer>.summary.json` beside it.
         *
         * @param isoTimestamp call start as ISO-8601, e.g. `2026-11-07T12:30:00Z`; `:` becomes `-`
         *   so the name is valid on every filesystem.
         * @param peerShort short peer identifier (e.g. first characters of the onion address).
         */
        fun open(appDataDir: Path, isoTimestamp: String, peerShort: String): MetricsLogger {
            val callsDir = Path(appDataDir, "calls")
            SystemFileSystem.createDirectories(callsDir)
            val stem = callFileStem(isoTimestamp, peerShort)
            return MetricsLogger(
                csv = SystemFileSystem.sink(Path(callsDir, "$stem.csv")).buffered(),
                summary = { SystemFileSystem.sink(Path(callsDir, "$stem.summary.json")).buffered() },
            )
        }

        /** File name stem `<iso>-<peer>` with characters outside `[A-Za-z0-9._-]` replaced by `-`. */
        internal fun callFileStem(isoTimestamp: String, peerShort: String): String =
            "$isoTimestamp-$peerShort".map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-") it else '-' }
                .joinToString("")

        internal fun toCsvRow(s: CallStats): String = listOf(
            s.tSeconds.toString(),
            s.rttMs?.let { formatFixed(it) } ?: "",
            formatFixed(s.jitterMs) ?: "",
            formatFixed(s.lossPct) ?: "",
            s.late.toString(),
            s.plc.toString(),
            formatFixed(s.bufferMs) ?: "",
            formatFixed(s.txKbps) ?: "",
            formatFixed(s.rxKbps) ?: "",
            s.circuitId ?: "",
            s.hops?.toString() ?: "",
        ).joinToString(",")
    }
}
