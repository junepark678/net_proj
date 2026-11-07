package dev.junepark.oniontalkie.onion

import dev.junepark.oniontalkie.identity.IdentityStore
import dev.junepark.oniontalkie.identity.KEY
import dev.junepark.oniontalkie.identity.MemoryStorage
import dev.junepark.oniontalkie.identity.ONION
import dev.junepark.oniontalkie.identity.OTHER_KEY
import dev.junepark.oniontalkie.identity.OTHER_ONION
import dev.junepark.oniontalkie.tor.TorCommandException
import dev.junepark.oniontalkie.tor.TorControl
import dev.junepark.oniontalkie.tor.TranscriptTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnionServiceTest {
    private class Fixture(scope: TestScope) {
        val transport = TranscriptTransport()
        val storage = MemoryStorage()
        val service = OnionService(
            TorControl(transport, scope.backgroundScope), IdentityStore(storage), OnionKeyStore(storage), "June", 4242,
        )
    }

    @Test fun firstRunCreatesAndPersistsTheKeyAndIdentity() = runTest {
        val f = Fixture(this)
        val result = async { f.service.publish(listOf(PeerAuthKey(KEY))) }
        f.transport.expect(
            "ADD_ONION NEW:ED25519-V3 Port=9999,127.0.0.1:4242 ClientAuthV3=$KEY",
            "250-ServiceID=${SERVICE_ID}", "250-PrivateKey=$SERVICE_KEY", "250 OK",
        )
        assertEquals(ONION, result.await())
        assertEquals(ONION, f.service.address.value)
        assertTrue(SERVICE_KEY in f.storage.files.getValue("onion-service-key.json"))
        assertTrue(ONION in f.storage.files.getValue("identity.json"))
        val pair = IdentityStore(f.storage).loadOrCreate { error("exists") }
        assertEquals(52, pair.clientAuthPub.length)

        val unpublish = async { f.service.unpublish() }
        f.transport.expect("DEL_ONION $SERVICE_ID", "250 OK")
        unpublish.await()
        assertNull(f.service.address.value)
    }

    @Test fun laterRunsReuseTheKeyAndRepublishReplacesTheService() = runTest {
        val f = Fixture(this)
        f.storage.files["onion-service-key.json"] = """{"privateKey":"$SERVICE_KEY"}"""
        val first = async { f.service.publish(listOf(PeerAuthKey(KEY))) }
        f.transport.expect(
            "ADD_ONION $SERVICE_KEY Flags=DiscardPK Port=9999,127.0.0.1:4242 ClientAuthV3=$KEY",
            "250-ServiceID=$SERVICE_ID", "250 OK",
        )
        first.await()
        val second = async { f.service.publish(listOf(PeerAuthKey(KEY), PeerAuthKey(OTHER_KEY))) }
        f.transport.expect("DEL_ONION $SERVICE_ID", "250 OK")
        f.transport.expect(
            "ADD_ONION $SERVICE_KEY Flags=DiscardPK Port=9999,127.0.0.1:4242 ClientAuthV3=$KEY ClientAuthV3=$OTHER_KEY",
            "250-ServiceID=$SERVICE_ID", "250 OK",
        )
        assertEquals(ONION, second.await())
    }

    @Test fun noPeersPublishesWithAnUnusableDecoyKeyNeverAnOpenService() = runTest {
        val f = Fixture(this)
        val result = async { f.service.publish(emptyList()) }
        val command = f.transport.output.receive()
        assertTrue(Regex("ADD_ONION NEW:ED25519-V3 Port=9999,127.0.0.1:4242 ClientAuthV3=[A-Z2-7]{52}").matches(command), command)
        f.transport.feed("250-ServiceID=$SERVICE_ID", "250-PrivateKey=$SERVICE_KEY", "250 OK")
        result.await()
    }

    @Test fun failedAddOnionLeavesNothingPersistedOrPublished() = runTest {
        val f = Fixture(this)
        val result = async { runCatching { f.service.publish(listOf(PeerAuthKey(KEY))) } }
        f.transport.expect(
            "ADD_ONION NEW:ED25519-V3 Port=9999,127.0.0.1:4242 ClientAuthV3=$KEY", "512 Bad port",
        )
        assertTrue(result.await().exceptionOrNull() is TorCommandException)
        assertTrue(f.storage.files.isEmpty())
        assertNull(f.service.address.value)
    }

    @Test fun keyWriteFailureRemovesTheServiceAgain() = runTest {
        val f = Fixture(this)
        f.storage.failWrites = true
        val result = async { runCatching { f.service.publish(listOf(PeerAuthKey(KEY))) } }
        f.transport.expect(
            "ADD_ONION NEW:ED25519-V3 Port=9999,127.0.0.1:4242 ClientAuthV3=$KEY",
            "250-ServiceID=$SERVICE_ID", "250-PrivateKey=$SERVICE_KEY", "250 OK",
        )
        f.transport.expect("DEL_ONION $SERVICE_ID", "250 OK")
        assertTrue(result.await().exceptionOrNull() is IllegalStateException)
        assertNull(f.service.address.value)
    }

    @Test fun identityForAnotherServiceIsRejected() = runTest {
        val f = Fixture(this)
        f.storage.files["onion-service-key.json"] = """{"privateKey":"$SERVICE_KEY"}"""
        f.storage.files["identity.json"] =
            """{"onion":"$OTHER_ONION","clientAuthPub":"$KEY","clientAuthPrivate":"$OTHER_KEY","displayName":"June"}"""
        val result = async { runCatching { f.service.publish(listOf(PeerAuthKey(KEY))) } }
        f.transport.expect(
            "ADD_ONION $SERVICE_KEY Flags=DiscardPK Port=9999,127.0.0.1:4242 ClientAuthV3=$KEY",
            "250-ServiceID=$SERVICE_ID", "250 OK",
        )
        f.transport.expect("DEL_ONION $SERVICE_ID", "250 OK")
        assertTrue(result.await().exceptionOrNull() is IllegalStateException)
    }

    @Test fun unpublishToleratesAServiceTorAlreadyForgot() = runTest {
        val f = Fixture(this)
        f.storage.files["onion-service-key.json"] = """{"privateKey":"$SERVICE_KEY"}"""
        val publish = async { f.service.publish(listOf(PeerAuthKey(KEY))) }
        f.transport.expect(
            "ADD_ONION $SERVICE_KEY Flags=DiscardPK Port=9999,127.0.0.1:4242 ClientAuthV3=$KEY",
            "250-ServiceID=$SERVICE_ID", "250 OK",
        )
        publish.await()
        val unpublish = async { f.service.unpublish() }
        f.transport.expect("DEL_ONION $SERVICE_ID", "552 Unknown Onion Service id")
        unpublish.await()
        assertNull(f.service.address.value)
    }

    @Test fun corruptStoredKeyIsReportedNotReplaced() = runTest {
        val f = Fixture(this)
        f.storage.files["onion-service-key.json"] = """{"privateKey":"junk"}"""
        assertFailsWith<IllegalArgumentException> { f.service.publish(listOf(PeerAuthKey(KEY))) }
        assertEquals("""{"privateKey":"junk"}""", f.storage.files["onion-service-key.json"])
    }

    @Test fun clientAuthInstallerWritesTorFormatFiles() = runTest {
        val storage = MemoryStorage()
        val identity = IdentityStore(storage)
        identity.loadOrCreate { dev.junepark.oniontalkie.identity.Identity(ONION, KEY, OTHER_KEY, "June") }
        val files = mutableMapOf<String, String>()
        val dir = object : ClientAuthDir {
            override suspend fun write(fileName: String, contents: String) { files[fileName] = contents }
            override suspend fun delete(fileName: String) { files.remove(fileName) }
        }
        val installer = ClientAuthInstaller(identity, dir)
        installer.install(OTHER_ONION)
        val peer = OTHER_ONION.removeSuffix(".onion")
        assertEquals(mapOf("$peer.auth_private" to "$peer:descriptor:x25519:$OTHER_KEY\n"), files)
        installer.remove(OTHER_ONION)
        assertTrue(files.isEmpty())
    }
}
