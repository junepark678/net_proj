package dev.junepark.oniontalkie.tor

import dev.junepark.oniontalkie.identity.IdentityStore
import dev.junepark.oniontalkie.identity.KEY
import dev.junepark.oniontalkie.identity.MemoryStorage
import dev.junepark.oniontalkie.identity.ONION
import dev.junepark.oniontalkie.identity.OTHER_KEY
import dev.junepark.oniontalkie.identity.OTHER_ONION
import dev.junepark.oniontalkie.identity.Storage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OnionServiceTest {
    @Test fun firstLaunchPersistsAllKeysAndCleansUpThenReopensWithoutTorCommands() = runTest {
        val storage = MemoryStorage()
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        val opening = async { OnionService.open(control, 1234, IdentityStore(storage), "June") }
        val command = transport.output.receive()
        assertTrue(command.startsWith("ADD_ONION NEW:ED25519-V3 Flags=V3Auth Port=9999,127.0.0.1:1234 ClientAuthV3="))
        val initialPub = command.substringAfter("ClientAuthV3=")
        PeerAuthKey(initialPub)
        transport.feed("250-ServiceID=${ONION.removeSuffix(".onion")}", "250-PrivateKey=$SERVICE_KEY", "250 OK")
        transport.expect("DEL_ONION ${ONION.removeSuffix(".onion")}", "250 OK")
        val first = opening.await()
        assertNull(first.onionAddress.value)
        assertEquals(initialPub, first.identity.clientAuthPub)
        assertEquals(initialPub, clientAuthPublicKey(first.identity.clientAuthPrivate))
        assertEquals(SERVICE_KEY, first.identity.onionServicePrivateKey)
        assertEquals(setOf("identity.json"), storage.files.keys)
        val reopened = OnionService.open(control, 4321, IdentityStore(storage), "Ignored")
        assertEquals(first.identity.onion, reopened.identity.onion)
        assertEquals(first.identity.clientAuthPrivate, reopened.identity.clientAuthPrivate)
        assertEquals(SERVICE_KEY, reopened.identity.onionServicePrivateKey)
        assertEquals("June", reopened.identity.displayName)
        assertTrue(transport.output.tryReceive().isFailure)
        control.close()
    }

    @Test fun publishReusesKeyAndIdempotentlyReplacesTheFullPeerSet() = runTest {
        val storage = MemoryStorage()
        val store = IdentityStore(storage)
        store.loadOrCreate { serviceIdentity() }
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        val service = OnionService.open(control, 1234, store)
        val peers = listOf(PeerAuthKey(KEY), PeerAuthKey(OTHER_KEY))
        val publishing = async { service.publish(peers) }
        transport.expect(addOnionCommand(SERVICE_KEY, 1234, peers.sortedBy { it.publicKey }),
            "250-ServiceID=${ONION.removeSuffix(".onion")}", "250 OK")
        assertEquals(ONION, publishing.await())
        assertEquals(ONION, service.onionAddress.value)
        assertEquals(ONION, service.publish(peers.reversed() + peers))
        assertTrue(transport.output.tryReceive().isFailure)
        val replacement = async { service.publish(listOf(PeerAuthKey(KEY))) }
        transport.expect("DEL_ONION ${ONION.removeSuffix(".onion")}", "250 OK")
        transport.expect(addOnionCommand(SERVICE_KEY, 1234, listOf(PeerAuthKey(KEY))),
            "250-ServiceID=${ONION.removeSuffix(".onion")}", "250 OK")
        replacement.await()
        val removing = async { service.unpublish() }
        transport.expect("DEL_ONION ${ONION.removeSuffix(".onion")}", "250 OK")
        removing.await()
        assertNull(service.onionAddress.value)
        service.unpublish()
        assertTrue(transport.output.tryReceive().isFailure)
        val empty = async { service.publish(emptyList()) }
        transport.expect(addOnionCommand(SERVICE_KEY, 1234, listOf(PeerAuthKey(ALICE_PUBLIC))),
            "250-ServiceID=${ONION.removeSuffix(".onion")}", "250 OK")
        empty.await()
        control.close()
    }

    @Test fun failedPersistenceClosesConnectionWithoutOverwritingIdentity() = runTest {
        val storage = MemoryStorage().apply { failWrites = true }
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        val opening = async {
            assertFailsWith<IllegalStateException> { OnionService.open(control, 1234, IdentityStore(storage)) }
        }
        transport.output.receive()
        transport.feed("250-ServiceID=${ONION.removeSuffix(".onion")}", "250-PrivateKey=$SERVICE_KEY", "250 OK")
        opening.await()
        assertTrue(transport.closed)
        assertTrue(storage.files.isEmpty())
    }

    @Test fun missingPrivateKeyOnFirstLaunchNeverSavesAnIncompleteIdentity() = runTest {
        val storage = MemoryStorage()
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        val opening = async {
            assertFailsWith<TorProtocolException> { OnionService.open(control, 1234, IdentityStore(storage)) }
        }
        transport.output.receive()
        transport.feed("250-ServiceID=${ONION.removeSuffix(".onion")}", "250 OK")
        opening.await()
        assertTrue(transport.closed)
        assertTrue(storage.files.isEmpty())
    }

    @Test fun failedFirstRunDeletionPreservesKeysButNeverReturnsAUsableOwner() = runTest {
        val storage = MemoryStorage()
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        val opening = async {
            assertFailsWith<TorCommandException> { OnionService.open(control, 1234, IdentityStore(storage)) }
        }
        transport.output.receive()
        transport.feed("250-ServiceID=${ONION.removeSuffix(".onion")}", "250-PrivateKey=$SERVICE_KEY", "250 OK")
        transport.expect("DEL_ONION ${ONION.removeSuffix(".onion")}", "552 Not removed")
        opening.await()
        assertTrue(transport.closed)
        assertEquals(SERVICE_KEY, IdentityStore(storage).loadOrCreate { error("Already saved") }.onionServicePrivateKey)
    }

    @Test fun cancelledPublicationDisconnectsSoNoUnknownServiceCanSurvive() = runTest {
        val store = IdentityStore(MemoryStorage())
        store.loadOrCreate { serviceIdentity() }
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        val service = OnionService.open(control, 1234, store)
        val publishing = async { service.publish(listOf(PeerAuthKey(KEY))) }
        transport.output.receive()
        publishing.cancel()
        publishing.join()
        assertTrue(transport.closed)
        assertNull(service.onionAddress.value)
    }

    @Test fun cancelledFirstRunDuringWriteStillLeavesRecoverableKeysAndRemovesService() = runTest {
        val entered = CompletableDeferred<Unit>()
        val stored = MemoryStorage()
        val storage = object : Storage {
            override suspend fun read(name: String) = stored.read(name)
            override suspend fun write(name: String, contents: String) {
                stored.files[name] = contents
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        val opening = async { OnionService.open(control, 1234, IdentityStore(storage)) }
        transport.output.receive()
        transport.feed("250-ServiceID=${ONION.removeSuffix(".onion")}", "250-PrivateKey=$SERVICE_KEY", "250 OK")
        entered.await()
        opening.cancel()
        opening.join()
        assertTrue(transport.closed)
        val saved = IdentityStore(stored).loadOrCreate { error("Must not rotate saved keys") }
        assertEquals(SERVICE_KEY, saved.onionServicePrivateKey)
    }

    @Test fun missingServiceKeyOrMismatchedClientKeysAreNeverRotated() = runTest {
        for (identity in listOf(serviceIdentity(null),
            dev.junepark.oniontalkie.identity.Identity(ONION, KEY, ALICE_PRIVATE, "June", SERVICE_KEY))) {
            val storage = MemoryStorage()
            IdentityStore(storage).loadOrCreate { identity }
            val before = storage.files.toMap()
            val transport = TranscriptTransport()
            val control = TorControl(transport, backgroundScope)
            assertFailsWith<IllegalStateException> { OnionService.open(control, 1234, IdentityStore(storage)) }
            assertEquals(before, storage.files)
            assertTrue(transport.output.tryReceive().isFailure)
            assertTrue(transport.closed)
        }
    }

    @Test fun malformedOrMismatchedSuccessfulAddClosesTheConnection() = runTest {
        for (lines in listOf(
            listOf("250 OK"),
            listOf("250-ServiceID=${OTHER_ONION.removeSuffix(".onion")}", "250 OK"),
            listOf("250-ServiceID=invalid", "250 OK"),
            listOf("250-ServiceID=${ONION.removeSuffix(".onion")}", "250-ServiceID=${ONION.removeSuffix(".onion")}", "250 OK"),
        )) {
            val store = IdentityStore(MemoryStorage())
            store.loadOrCreate { serviceIdentity() }
            val transport = TranscriptTransport()
            val control = TorControl(transport, backgroundScope)
            val service = OnionService.open(control, 1234, store)
            val publishing = async { assertFailsWith<Exception> { service.publish(listOf(PeerAuthKey(KEY))) } }
            transport.output.receive()
            transport.feed(*lines.toTypedArray())
            publishing.await()
            assertTrue(transport.closed)
            assertNull(service.onionAddress.value)
        }
    }

    @Test fun negativeRepliesRetainKnownStateAndFailedReplacementLeavesOffline() = runTest {
        val store = IdentityStore(MemoryStorage())
        store.loadOrCreate { serviceIdentity() }
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        val service = OnionService.open(control, 1234, store)
        val publishing = async { service.publish(listOf(PeerAuthKey(KEY))) }
        transport.output.receive()
        transport.feed("250-ServiceID=${ONION.removeSuffix(".onion")}", "250 OK")
        publishing.await()
        val failedDelete = async { assertFailsWith<TorCommandException> { service.unpublish() } }
        transport.expect("DEL_ONION ${ONION.removeSuffix(".onion")}", "552 Not removed")
        failedDelete.await()
        assertEquals(ONION, service.onionAddress.value)
        assertFalse(transport.closed)
        val failedReplace = async {
            assertFailsWith<TorCommandException> { service.publish(listOf(PeerAuthKey(OTHER_KEY))) }
        }
        transport.expect("DEL_ONION ${ONION.removeSuffix(".onion")}", "250 OK")
        transport.output.receive()
        transport.feed("512 Invalid ADD_ONION")
        failedReplace.await()
        assertNull(service.onionAddress.value)
        assertFalse(transport.closed)
        control.close()
        runCurrent()
    }
}
