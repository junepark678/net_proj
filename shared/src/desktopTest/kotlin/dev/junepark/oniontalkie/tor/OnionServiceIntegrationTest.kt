package dev.junepark.oniontalkie.tor

import dev.junepark.oniontalkie.identity.IdentityStore
import dev.junepark.oniontalkie.identity.directoryStorage
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue

/** Uses an explicitly trusted local Tor, without changing its configuration or stopping it. */
class OnionServiceIntegrationTest {
    @Test fun publishWaitForDescriptorUploadAndDelete() = runBlocking {
        val port = System.getenv("TOR_ONION_TEST_CONTROL_PORT")?.toInt() ?: 9051
        val transport = try {
            JvmControlTransport.connect(port = port, connectTimeoutMillis = 1_000)
        } catch (_: IOException) {
            assumeTrue("No Tor control port reachable on 127.0.0.1:$port", false)
            return@runBlocking
        }
        val control = TorControl(transport, this)
        val directory = Files.createTempDirectory("onion-talkie-service-test-")
        var service: OnionService? = null
        try {
            val cookiePath = System.getenv("TOR_ONION_TEST_COOKIE")
            assumeTrue("Set TOR_ONION_TEST_COOKIE to the trusted Tor's 32-byte cookie file", !cookiePath.isNullOrBlank())
            withTimeout(10_000) { control.authenticate(Files.readAllBytes(Paths.get(cookiePath!!))) }
            withTimeout(180_000) { control.bootstrap.first { it == 100 } }
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
                val storage = directoryStorage(directory.toString())
                service = withTimeout(10_000) {
                    OnionService.open(control, listener.localPort, IdentityStore(storage), "Integration")
                }
                val publisher = service
                val expected = publisher.identity.onion.removeSuffix(".onion")
                // HS_DESC events are not replayed. Start collecting before sending ADD_ONION.
                val uploaded = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(180_000) {
                        control.events.first {
                            it is TorEvent.HiddenServiceDescriptor && it.action == "UPLOADED" &&
                                it.address.removeSuffix(".onion") == expected
                        }
                    }
                }
                try {
                    val peer = generateClientAuthKeyPair()
                    assertEquals(publisher.identity.onion, withTimeout(10_000) {
                        publisher.publish(listOf(PeerAuthKey(peer.publicKey)))
                    })
                    uploaded.await()
                    withTimeout(10_000) { publisher.unpublish() }
                    assertNull(publisher.onionAddress.value)
                    assertTrue(expected !in control.getInfo("onions/current").lineSequence().toList())
                    val reopened = OnionService.open(control, listener.localPort, IdentityStore(storage))
                    assertEquals(publisher.identity.onion, reopened.identity.onion)
                    assertEquals(publisher.identity.clientAuthPrivate, reopened.identity.clientAuthPrivate)
                    assertEquals(publisher.identity.onionServicePrivateKey, reopened.identity.onionServicePrivateKey)
                    service = reopened
                    assertEquals(publisher.identity.onion, withTimeout(10_000) {
                        reopened.publish(listOf(PeerAuthKey(peer.publicKey)))
                    })
                    withTimeout(10_000) { reopened.unpublish() }
                    assertNull(reopened.onionAddress.value)
                } finally {
                    uploaded.cancel()
                }
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { withTimeout(5_000) { service?.unpublish() } }
            }
            control.close()
            directory.toFile().deleteRecursively()
        }
    }
}
