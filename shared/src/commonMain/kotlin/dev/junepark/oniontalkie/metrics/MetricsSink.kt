package dev.junepark.oniontalkie.metrics

import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString

/** Storage owned by one [MetricsLogger]. Methods may throw on I/O failure. */
interface MetricsSink {
    /** Appends and flushes a header or complete CSV row, including its newline. */
    fun appendCsv(text: String)

    /** Writes the complete summary on hangup. */
    fun writeSummary(json: String)

    /** Releases the CSV writer; called even if final output fails. */
    fun close()
}

/**
 * Local UTF-8 storage using kotlinx-io on Android and desktop.
 *
 * [startedAtIso] is a UTC ISO timestamp with second or fractional-second precision;
 * colons are removed for portable filenames. [peerShort] is 1–32 ASCII letters,
 * digits, underscores or hyphens. Existing call files are rejected.
 * Each CSV has a companion directory containing its own `summary.json`, so
 * finishing a later call does not overwrite earlier summaries.
 * Construct and use this sink on an I/O dispatcher, through [MetricsLogger].
 */
class FileMetricsSink(appData: String, startedAtIso: String, peerShort: String) : MetricsSink {
    /** `<appData>/calls/<iso-timestamp>-<peer-short>.csv`. */
    val csvPath: Path

    /** `<appData>/calls/<iso-timestamp>-<peer-short>/summary.json`. */
    val summaryPath: Path

    private val csv: Sink

    init {
        require(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z").matches(startedAtIso)) {
            "Expected a UTC ISO timestamp"
        }
        require(Regex("[A-Za-z0-9_-]{1,32}").matches(peerShort)) { "Invalid peer short name" }
        val calls = Path(appData, "calls")
        val stem = "${startedAtIso.replace(":", "")}-$peerShort"
        csvPath = Path(calls, "$stem.csv")
        val summaryDirectory = Path(calls, stem)
        summaryPath = Path(summaryDirectory, "summary.json")
        check(!SystemFileSystem.exists(csvPath) && !SystemFileSystem.exists(summaryDirectory)) {
            "Call files already exist: $stem"
        }
        SystemFileSystem.createDirectories(summaryDirectory)
        csv = SystemFileSystem.sink(csvPath).buffered()
    }

    override fun appendCsv(text: String) {
        csv.writeString(text)
        csv.flush()
    }

    override fun writeSummary(json: String) {
        SystemFileSystem.sink(summaryPath).buffered().use { it.writeString(json) }
    }

    override fun close() = csv.close()
}
