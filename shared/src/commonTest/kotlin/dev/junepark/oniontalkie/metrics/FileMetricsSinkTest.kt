package dev.junepark.oniontalkie.metrics

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString

@OptIn(ExperimentalCoroutinesApi::class)
class FileMetricsSinkTest {
    @Test
    fun realCallFilesAreFlushedAndSummariesArePreservedPerCall() = runTest {
        val appData = Path(SystemTemporaryDirectory, "onion-metrics-${Random.nextLong()}")
        try {
            val first = FileMetricsSink(appData.toString(), "2026-11-07T01:02:03.123Z", "peer_abc")
            assertEquals("2026-11-07T010203.123Z-peer_abc.csv", first.csvPath.name)
            assertEquals(Path(appData, "calls"), first.csvPath.parent)
            val logger = MetricsLogger(first, backgroundScope) { testScheduler.currentTime }
            logger.onTransportBytes(sent = 3_000, received = 1_500)
            advanceTimeBy(1_000)
            runCurrent()
            val csv = read(first.csvPath)
            assertEquals(MetricsLogger.CSV_HEADER, csv.lineSequence().first())
            assertEquals("1,,0.00,0.00,0,0,0.00,24.00,12.00,,", csv.lineSequence().drop(1).first())
            logger.close()
            val summary = read(first.summaryPath)
            assertEquals("summary.json", first.summaryPath.name)
            assertTrue(summary.contains("\"tx_kbps\": {\"p50\": 24.0, \"p95\": 24.0}"))

            val second = FileMetricsSink(appData.toString(), "2026-11-07T01:02:04Z", "peer_abc")
            MetricsLogger(second, backgroundScope) { testScheduler.currentTime }.close()
            assertTrue(SystemFileSystem.exists(second.summaryPath))
            assertEquals(summary, read(first.summaryPath))
            assertEquals(csv, read(first.csvPath))
            assertFailsWith<IllegalStateException> {
                FileMetricsSink(appData.toString(), "2026-11-07T01:02:03.123Z", "peer_abc")
            }
            assertEquals(csv, read(first.csvPath))
        } finally {
            deleteTree(appData)
        }
    }

    @Test
    fun unsafeFilenameComponentsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            FileMetricsSink("unused", "2026-11-07T01:02:03Z", "../peer")
        }
        assertFailsWith<IllegalArgumentException> {
            FileMetricsSink("unused", "../2026-11-07", "peer")
        }
    }

    private fun read(path: Path): String = SystemFileSystem.source(path).buffered().use { it.readString() }

    private fun deleteTree(path: Path) {
        if (!SystemFileSystem.exists(path)) return
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
            SystemFileSystem.list(path).forEach(::deleteTree)
        }
        SystemFileSystem.delete(path)
    }
}
