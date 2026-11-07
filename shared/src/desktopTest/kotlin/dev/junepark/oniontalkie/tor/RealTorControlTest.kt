package dev.junepark.oniontalkie.tor

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in test against a running Tor: set `TOR_CONTROL_PORT` (e.g. 9051) and `TOR_COOKIE_FILE`
 * (path to its control_auth_cookie) to enable; silently passes otherwise so CI needs no Tor.
 */
class RealTorControlTest {
    @Test
    fun authenticatesAndReachesFullBootstrap() {
        val port = System.getenv("TOR_CONTROL_PORT")?.toIntOrNull() ?: return
        val cookie = File(System.getenv("TOR_COOKIE_FILE") ?: return).readBytes()
        runBlocking {
            val control = TorControl(SocketControlTransport.connect("127.0.0.1", port), this)
            try {
                val info = control.protocolInfo()
                println("PROTOCOLINFO: $info")
                control.authenticate(cookie, info.methods)
                println("version: ${control.getInfo("version")}")
                control.setEvents(TorControl.DEFAULT_EVENTS)
                control.refreshBootstrap()
                val seen = mutableListOf<TorEvent>()
                val collector = launch { control.events.collect { seen += it } }
                withTimeout(120_000) { control.bootstrap.first { it == 100 } }
                println("bootstrap=${control.bootstrap.value}, events seen: ${seen.size}")
                seen.take(5).forEach { println("  $it") }
                assertEquals(100, control.bootstrap.value)
                assertTrue(control.getInfo("status/bootstrap-phase").contains("PROGRESS=100"))
                control.signal(TorSignal.NEWNYM)
                collector.cancel()
            } finally {
                control.close()
            }
        }
    }
}
