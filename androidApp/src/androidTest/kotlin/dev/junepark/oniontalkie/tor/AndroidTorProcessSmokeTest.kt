package dev.junepark.oniontalkie.tor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the packaged Guardian Project Tor daemon starts on Android ART. */
@RunWith(AndroidJUnit4::class)
class AndroidTorProcessSmokeTest {
    @Test(timeout = 90_000)
    fun packagedTorStartsAndDiscoversAuthenticatedLoopbackListeners() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dataDirectory = File(context.cacheDir, "tor-process-smoke").apply {
            deleteRecursively()
            check(mkdirs()) { "Could not create private Tor smoke-test directory" }
        }
        val config = TorConfig.forAndroid(context).copy(
            dataDir = dataDirectory.absolutePath,
            startupTimeoutMillis = 60_000,
        )
        val tor = TorProcess(config)

        try {
            tor.start()

            assertTrue("Tor must expose a valid control port", tor.controlPort in 1..65_535)
            assertTrue("Tor must expose a valid SOCKS port", tor.socksPort in 1..65_535)
        } finally {
            tor.stop()
            dataDirectory.deleteRecursively()
        }
    }
}
