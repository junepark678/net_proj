package dev.junepark.oniontalkie.tor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the packaged Tor daemon authenticates and reaches 100% bootstrap on Android ART. */
@RunWith(AndroidJUnit4::class)
class AndroidTorProcessSmokeTest {
    @Test(timeout = 260_000)
    fun packagedTorBootstrapsWithinFourMinutes() = runBlocking {
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
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        var logJob: Job? = null

        try {
            withTimeout(BOOTSTRAP_TIMEOUT_MILLIS) {
                val logFlow = tor.start()
                val completeEvent = CompletableDeferred<Unit>()
                logJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    logFlow.collect { line ->
                        synchronized(logs) {
                            logs += line.text
                            if (logs.size > MAX_LOG_LINES) logs.removeAt(0)
                        }
                        if (line.text.contains(BOOTSTRAP_COMPLETE_LINE)) completeEvent.complete(Unit)
                    }
                }

                withTimeout(BOOTSTRAP_TIMEOUT_MILLIS) { completeEvent.await() }

                assertTrue("Tor must expose a valid control port", tor.controlPort in 1..65_535)
                assertTrue("Tor must expose a valid SOCKS port", tor.socksPort in 1..65_535)
            }
        } catch (failure: Throwable) {
            val lastLogs = synchronized(logs) { logs.takeLast(MAX_LOG_LINES).joinToString(" | ") }
            throw AssertionError(
                "Packaged Tor did not bootstrap within four minutes. Last Tor logs: $lastLogs",
                failure,
            )
        } finally {
            tor.stop()
            logJob?.cancelAndJoin()
            dataDirectory.deleteRecursively()
        }
    }

    private companion object {
        const val BOOTSTRAP_TIMEOUT_MILLIS = 4 * 60_000L
        const val MAX_LOG_LINES = 40
        const val BOOTSTRAP_COMPLETE_LINE = "Bootstrapped 100%"
    }
}
