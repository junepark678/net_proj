package dev.junepark.oniontalkie.tor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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
        val logs = mutableListOf<String>()
        var logJob: Job? = null
        var progressJob: Job? = null
        var eventJob: Job? = null
        var control: TorControl? = null

        try {
            withTimeout(BOOTSTRAP_TIMEOUT_MILLIS) {
                val logFlow = tor.start()
                logJob = launch(start = CoroutineStart.UNDISPATCHED) { logFlow.collect { logs += it.text } }
                val client = TorControl(JvmControlTransport.connect(port = tor.controlPort), this)
                control = client
                val phases = mutableListOf<Int>()
                val completeEvent = CompletableDeferred<Unit>()
                progressJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    client.bootstrap.collect { phases += it }
                }
                eventJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    client.events.collect { event ->
                        if (event is TorEvent.Bootstrap && event.progress == 100) completeEvent.complete(Unit)
                    }
                }

                withTimeout(CONTROL_AUTH_TIMEOUT_MILLIS) {
                    client.authenticate(Files.readAllBytes(File(config.dataDir, CONTROL_COOKIE_FILE).toPath()))
                }
                client.bootstrap.first { it == 100 }
                withTimeout(1_000) { completeEvent.await() }

                assertTrue("Tor must expose a valid control port", tor.controlPort in 1..65_535)
                assertTrue("Tor must expose a valid SOCKS port", tor.socksPort in 1..65_535)
                assertTrue("Tor bootstrap phases should include progress", phases.any { it in 1..100 })
                assertEquals(100, client.bootstrap.value)
            }
        } catch (failure: Throwable) {
            throw AssertionError(
                "Packaged Tor did not bootstrap within four minutes. Last Tor logs: ${logs.takeLast(MAX_LOG_LINES).joinToString(" | ")}",
                failure,
            )
        } finally {
            eventJob?.cancelAndJoin()
            progressJob?.cancelAndJoin()
            control?.close()
            logJob?.cancelAndJoin()
            tor.stop()
            dataDirectory.deleteRecursively()
        }
    }

    private companion object {
        const val BOOTSTRAP_TIMEOUT_MILLIS = 4 * 60_000L
        const val CONTROL_AUTH_TIMEOUT_MILLIS = 10_000L
        const val CONTROL_COOKIE_FILE = "control_auth_cookie"
        const val MAX_LOG_LINES = 40
    }
}
