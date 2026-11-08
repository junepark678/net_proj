package dev.junepark.oniontalkie.tor

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

/** Opt-in live verification; ordinary unit tests require neither Tor nor network access. */
class TorControlSmokeTest {
    @Test
    fun cookieAuthenticationAndBootstrapAgainstRealTor() = runBlocking {
        val binary = System.getenv("TOR_CONTROL_SMOKE_BINARY")
        assumeTrue("Set TOR_CONTROL_SMOKE_BINARY to enable the live Tor smoke test", !binary.isNullOrBlank())
        val directory = Files.createTempDirectory("onion-talkie-control-")
        val process = TorProcess(TorConfig(
            dataDir = directory.toString(),
            executablePath = binary,
            startupTimeoutMillis = 45_000,
        ))
        val logs = mutableListOf<String>()
        var logJob: kotlinx.coroutines.Job? = null
        var control: TorControl? = null
        try {
            val logFlow = withTimeout(50_000) { process.start() }
            logJob = launch(start = CoroutineStart.UNDISPATCHED) { logFlow.collect { logs += it.text } }

            val cookieFile = directory.resolve("control_auth_cookie")
            val controlClient = TorControl(
                JvmControlTransport.connect(port = process.controlPort),
                this,
            )
            control = controlClient
            val phases = mutableListOf<Int>()
            val bootstrapEvents = mutableListOf<Int>()
            val completedEvent = CompletableDeferred<Unit>()
            val progress = launch(start = CoroutineStart.UNDISPATCHED) {
                controlClient.bootstrap.collect { phases += it }
            }
            val events = launch(start = CoroutineStart.UNDISPATCHED) {
                controlClient.events.collect {
                    if (it is TorEvent.Bootstrap) {
                        bootstrapEvents += it.progress
                        if (it.progress == 100) completedEvent.complete(Unit)
                    }
                }
            }
            try {
                withTimeout(10_000) { controlClient.authenticate(Files.readAllBytes(cookieFile)) }
                println("Cookie authentication succeeded; Tor ${controlClient.getInfo("version")}; SOCKS ${process.socksPort}")
                withTimeout(180_000) {
                    controlClient.bootstrap.first { it == 100 }
                    completedEvent.await()
                }
                assertTrue(phases.any { it in 1..99 }, "No intermediate bootstrap progress: $phases")
                assertTrue(100 in bootstrapEvents, "No 100% STATUS_CLIENT event: $bootstrapEvents")
                assertEquals(100, controlClient.bootstrap.value)
                val missing = assertFailsWith<TorCommandException> { controlClient.getInfo("onion-talkie/nonexistent") }
                assertEquals(552, missing.reply.code)
                controlClient.setEvents()
                controlClient.setEvents(TorEventType.STATUS_CLIENT, TorEventType.CIRC, TorEventType.HS_DESC)
                controlClient.signal(TorSignal.NEWNYM)
                controlClient.signal(TorSignal.SHUTDOWN)
                println("Bootstrap phases: $phases; STATUS_CLIENT phases: $bootstrapEvents; GETINFO/SETEVENTS/NEWNYM/SHUTDOWN succeeded")
            } finally {
                progress.cancelAndJoin()
                events.cancelAndJoin()
            }
        } catch (failure: Throwable) {
            println("Tor logs retained from $directory: ${logs.takeLast(40).joinToString(" | ")}")
            throw failure
        } finally {
            control?.close()
            logJob?.cancelAndJoin()
            process.stop()
        }
    }
}
