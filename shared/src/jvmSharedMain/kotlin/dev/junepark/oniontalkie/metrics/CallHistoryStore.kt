package dev.junepark.oniontalkie.metrics

import dev.junepark.oniontalkie.identity.Contact
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reads completed local CSV/summary pairs. Call from an I/O dispatcher. */
fun loadCallHistory(dataDirectory: String, contacts: List<Contact> = emptyList()): List<CallHistoryEntry> {
    val callsDirectory = Paths.get(dataDirectory).resolve("calls")
    if (!Files.isDirectory(callsDirectory)) return emptyList()
    val csvFiles = mutableListOf<Path>()
    Files.newDirectoryStream(callsDirectory, "*.csv").use { files -> files.forEach(csvFiles::add) }
    val contactsByShortId = contacts.associateBy { it.onion.removeSuffix(".onion").take(12).lowercase() }

    return csvFiles.sortedByDescending { it.fileName.toString() }.mapNotNull { csv ->
        runCatching {
            val filename = csv.fileName.toString()
            val id = filename.removeSuffix(".csv")
            val peerShort = id.substringAfterLast('-').lowercase()
            val summaryPath = callsDirectory.resolve(id).resolve("summary.json")
            if (!Files.isRegularFile(summaryPath)) return@runCatching null
            val summaryText = Files.readAllBytes(summaryPath).toString(Charsets.UTF_8)
            val summary = Json.parseToJsonElement(summaryText).jsonObject
            val duration = summary.number("duration_s") ?: return@runCatching null
            CallHistoryEntry(
                id = id,
                peerLabel = contactsByShortId[peerShort]?.displayName ?: peerShort,
                timestampUtc = id.substringBeforeLast('-'),
                durationSeconds = duration,
                rttP50Ms = summary.percentile("rtt_ms"),
                jitterP50Ms = summary.percentile("jitter_ms"),
                lossP50Pct = summary.percentile("loss_pct"),
                bufferP50Ms = summary.percentile("buffer_ms"),
                csvPath = csv.toAbsolutePath().normalize().toString(),
            )
        }.getOrNull()
    }.take(MAX_HISTORY_ENTRIES)
}

private fun JsonObject.number(name: String): Double? = this[name]?.jsonPrimitive?.doubleOrNull

private fun JsonObject.percentile(name: String): Double? = this[name]
    ?.jsonObject
    ?.get("p50")
    ?.jsonPrimitive
    ?.doubleOrNull

private const val MAX_HISTORY_ENTRIES = 100
