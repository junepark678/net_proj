package dev.junepark.oniontalkie.tor

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

/** Opt-in live process test; set TOR_PROCESS_SMOKE_BINARY to a Tor executable. */
class TorProcessSmokeTest {
    @Test
    fun startsDiscoversRandomPortsStopsAndStartsAgain() = runBlocking {
        val binary = System.getenv("TOR_PROCESS_SMOKE_BINARY")
        assumeTrue("Set TOR_PROCESS_SMOKE_BINARY to enable the live Tor process test", !binary.isNullOrBlank())
        val dataDir = Files.createTempDirectory("onion-dial-tor-process-").toAbsolutePath()
        val process = TorProcess(TorConfig(
            dataDir = dataDir.toString(),
            executablePath = binary,
            startupTimeoutMillis = 45_000,
        ))

        try {
            withTimeout(50_000) { process.start() }
            assertTrue(process.controlPort in 1..65535)
            assertTrue(process.socksPort in 1..65535)
            process.stop()

            withTimeout(50_000) { process.start() }
            assertTrue(process.controlPort in 1..65535)
            assertTrue(process.socksPort in 1..65535)
        } finally {
            process.stop()
        }
    }

    @Test
    fun discoversAndStartsTheVerifiedDesktopBundleWithoutAnOverride() = runBlocking {
        assumeTrue("TOR_PROCESS_BINARY overrides bundled discovery", System.getenv("TOR_PROCESS_BINARY").isNullOrBlank())
        val bundled = locateBundledDesktopBinary()
        assumeTrue("Fetch the verified Tor bundle to enable this test", bundled?.canExecute() == true)

        val directory = Files.createTempDirectory("onion-dial-tor-bundle-")
        val process = TorProcess(TorConfig(dataDir = directory.toString()))
        try {
            withTimeout(50_000) { process.start() }
            assertTrue(process.controlPort in 1..65535)
            assertTrue(process.socksPort in 1..65535)
        } finally {
            process.stop()
        }
    }
}
