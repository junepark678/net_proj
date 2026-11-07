package dev.junepark.oniontalkie.tor

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

/** Opt-in live verification; ordinary unit tests require neither Tor nor network access. */
class TorControlSmokeTest {
    @Test fun cookieAuthenticationAndBootstrapAgainstRealTor() {
        val binary = System.getenv("TOR_CONTROL_SMOKE_BINARY")
        assumeTrue("Set TOR_CONTROL_SMOKE_BINARY to enable the live Tor smoke test", !binary.isNullOrBlank())
        val directory = Files.createTempDirectory("onion-talkie-control-")
        val portFile = directory.resolve("control-port")
        val cookieFile = directory.resolve("control-cookie")
        val logFile = directory.resolve("tor.log")
        val process = ProcessBuilder(
            binary!!, "--DataDirectory", directory.toString(), "--ControlPort", "auto",
            "--ControlPortWriteToFile", portFile.toString(), "--CookieAuthentication", "1",
            "--CookieAuthFile", cookieFile.toString(), "--SocksPort", "0",
            "--Log", "notice file $logFile",
        ).redirectErrorStream(true).redirectOutput(directory.resolve("process.log").toFile()).start()
        try {
            runBlocking {
                withTimeout(10_000) {
                    while (!Files.exists(portFile) || !Files.exists(cookieFile)) {
                        check(process.isAlive) { "Tor exited; see $directory" }
                        delay(25)
                    }
                }
                val port = Files.readString(portFile).trim().substringAfterLast(':').toInt()
                val control = TorControl(JvmControlTransport.connect(port = port), this)
                val phases = mutableListOf<Int>()
                val bootstrapEvents = mutableListOf<Int>()
                val completedEvent = CompletableDeferred<Unit>()
                val progress = launch(start = CoroutineStart.UNDISPATCHED) { control.bootstrap.collect { phases += it } }
                val events = launch(start = CoroutineStart.UNDISPATCHED) {
                    control.events.collect {
                        if (it is TorEvent.Bootstrap) {
                            bootstrapEvents += it.progress
                            if (it.progress == 100) completedEvent.complete(Unit)
                        }
                    }
                }
                try {
                    withTimeout(10_000) { control.authenticate(Files.readAllBytes(cookieFile)) }
                    println("Cookie authentication succeeded; Tor ${control.getInfo("version")}")
                    withTimeout(180_000) {
                        control.bootstrap.first { it == 100 }
                        completedEvent.await()
                    }
                    assertTrue(phases.any { it in 1..99 }, "No intermediate bootstrap progress: $phases")
                    assertTrue(100 in bootstrapEvents, "No 100% STATUS_CLIENT event: $bootstrapEvents")
                    assertEquals(100, control.bootstrap.value)
                    val missing = assertFailsWith<TorCommandException> { control.getInfo("onion-talkie/nonexistent") }
                    assertEquals(552, missing.reply.code)
                    control.setEvents()
                    control.setEvents(TorEventType.STATUS_CLIENT, TorEventType.CIRC, TorEventType.HS_DESC)
                    control.signal(TorSignal.NEWNYM)
                    control.signal(TorSignal.SHUTDOWN)
                    println("Bootstrap phases: $phases; STATUS_CLIENT phases: $bootstrapEvents; GETINFO/SETEVENTS/NEWNYM/SHUTDOWN succeeded")
                } finally {
                    control.close()
                    progress.cancel()
                    events.cancel()
                }
            }
        } catch (failure: Throwable) {
            println("Tor smoke logs retained at $directory")
            throw failure
        } finally {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }
}
