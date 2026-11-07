package dev.junepark.oniontalkie.onion

import dev.junepark.oniontalkie.identity.IdentityStore
import dev.junepark.oniontalkie.identity.directoryStorage
import dev.junepark.oniontalkie.tor.JvmControlTransport
import dev.junepark.oniontalkie.tor.TorControl
import dev.junepark.oniontalkie.tor.TorEvent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

/**
 * Live check against a real Tor: publish with client authorization, wait for the descriptor
 * upload (HS_DESC UPLOADED), then DEL_ONION. Skipped unless a control port is reachable:
 *  - `TOR_CONTROL_SMOKE_BINARY=/path/to/tor` starts an isolated Tor (needs the public Tor network), or
 *  - `TOR_CONTROL_PORT=<port>` and `TOR_CONTROL_COOKIE_FILE=<path>` use a running Tor you own.
 */
class OnionServiceTorTest {
    @Test fun publishUploadsDescriptorAndUnpublishes() {
        val binary = System.getenv("TOR_CONTROL_SMOKE_BINARY")?.takeIf { it.isNotBlank() }
        val externalPort = System.getenv("TOR_CONTROL_PORT")?.toIntOrNull()
        val externalCookie = System.getenv("TOR_CONTROL_COOKIE_FILE")?.takeIf { it.isNotBlank() }
        assumeTrue(
            "Set TOR_CONTROL_SMOKE_BINARY, or TOR_CONTROL_PORT with TOR_CONTROL_COOKIE_FILE, to run against Tor",
            binary != null || (externalPort != null && externalCookie != null),
        )
        val work = Files.createTempDirectory("onion-talkie-onion-")
        val process = binary?.let { launchTor(it, work) }
        try {
            runBlocking {
                val port: Int
                val cookie: Path
                if (process != null) {
                    val portFile = work.resolve("control-port")
                    cookie = work.resolve("control-cookie")
                    withTimeout(10_000) {
                        while (!Files.exists(portFile) || !Files.exists(cookie)) {
                            check(process.isAlive) { "Tor exited; see $work" }
                            delay(25)
                        }
                    }
                    port = Files.readString(portFile).trim().substringAfterLast(':').toInt()
                } else {
                    port = externalPort!!
                    cookie = Path.of(externalCookie!!)
                }
                val transport = try {
                    JvmControlTransport.connect(port = port)
                } catch (e: java.io.IOException) {
                    assumeTrue("No Tor control port reachable on $port", false)
                    return@runBlocking
                }
                val control = TorControl(transport, this)
                val uploaded = CompletableDeferred<String>()
                val listener = launch(start = CoroutineStart.UNDISPATCHED) {
                    control.events.collect {
                        if (it is TorEvent.HiddenServiceDescriptor && it.action == "UPLOADED") uploaded.complete(it.address)
                    }
                }
                try {
                    withTimeout(10_000) { control.authenticate(Files.readAllBytes(cookie)) }
                    withTimeout(180_000) { control.bootstrap.first { it == 100 } }
                    val data = directoryStorage(work.resolve("data").toString())
                    val service = OnionService(
                        control, IdentityStore(data), OnionKeyStore(data), "Tester", localPort = 45999,
                    )
                    val peer = ClientAuthKeyPair.generate()
                    val onion = service.publish(listOf(PeerAuthKey(peer.publicKey)))
                    assertEquals(onion, service.address.value)
                    assertTrue(onion.endsWith(".onion") && onion.length == 62)
                    val address = withTimeout(120_000) { uploaded.await() }
                    assertEquals(onion.removeSuffix(".onion"), address)
                    assertNotNull(data.read("onion-service-key.json"))

                    service.unpublish()
                    assertNull(service.address.value)
                    // The key survives, so the same address comes back on the next run.
                    assertEquals(onion, service.publish(emptyList()))
                    service.unpublish()
                } finally {
                    listener.cancel()
                    control.close()
                }
            }
        } finally {
            process?.destroy()
        }
    }

    private fun launchTor(binary: String, directory: Path): Process = ProcessBuilder(
        binary, "--DataDirectory", directory.resolve("tor").toString(), "--ControlPort", "auto",
        "--ControlPortWriteToFile", directory.resolve("control-port").toString(), "--CookieAuthentication", "1",
        "--CookieAuthFile", directory.resolve("control-cookie").toString(), "--SocksPort", "0",
        "--Log", "notice file ${directory.resolve("tor.log")}",
    ).redirectErrorStream(true).redirectOutput(directory.resolve("process.log").toFile()).start()
}
