package dev.junepark.oniontalkie.onion

import dev.junepark.oniontalkie.identity.Identity
import dev.junepark.oniontalkie.identity.IdentityStore
import dev.junepark.oniontalkie.tor.TorCommandException
import dev.junepark.oniontalkie.tor.TorControl
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Publishes our v3 onion service through an authenticated [TorControl], limited to paired peers.
 *
 * The service key is created by Tor on first run (`ADD_ONION NEW:ED25519-V3`), saved in
 * [keyStore] before [publish] returns, and reused afterwards so the address is stable. Android
 * wraps it with an AES-GCM key held by Android Keystore; desktop uses an owner-only file. Our
 * x25519 client-auth keypair is generated once and kept in the [IdentityStore] together with the
 * resulting address. The service is tied to the control connection (no `Detach`), so it
 * disappears if the connection closes; [unpublish] removes it explicitly.
 *
 * [publish] is also the republish seam for `ContactStore`: calling it while published replaces
 * the service with one authorizing the new peer list. Tor has no command to edit the
 * authorized clients of a live service.
 */
class OnionService(
    private val control: TorControl,
    private val identityStore: IdentityStore,
    private val keyStore: OnionKeyStore,
    private val displayName: String,
    private val localPort: Int,
    private val virtualPort: Int = ONION_VIRTUAL_PORT,
) {
    private val mutex = Mutex()
    private val state = MutableStateFlow<String?>(null)

    /** `<56 chars>.onion` while published, otherwise null. */
    val address: StateFlow<String?> = state.asStateFlow()

    /**
     * Adds the service authorizing exactly [peers] and returns its onion address. With no peers
     * the service is still published, so the address can be shown for pairing, but with a random
     * throwaway authorization key whose private half is discarded: nobody can connect. (Without
     * any ClientAuthV3 Tor would serve the service to everyone.)
     * Throws [TorCommandException] on a negative reply.
     */
    suspend fun publish(peers: List<PeerAuthKey>): String = mutex.withLock {
        state.value?.let { removeService(it) }
        val savedKey = keyStore.load()
        val authorized = peers.ifEmpty { listOf(PeerAuthKey(ClientAuthKeyPair.generate().publicKey)) }
        val command = formatAddOnion(savedKey, authorized, localPort, virtualPort)
        var added: AddOnionResult? = null
        try {
            // TorControl finishes an admitted command even if its caller is cancelled. Keep
            // reply handling, durable key/identity writes, and state publication together so
            // cancellation can never strand an untracked ADD_ONION service.
            withContext(NonCancellable) {
                val result = parseAddOnionReply(control.command(command))
                added = result
                state.value = result.onion
                try {
                    if (savedKey == null) {
                        val created = checkNotNull(result.privateKey) { "Tor did not return the new service key" }
                        keyStore.save(created)
                    }
                    val identity = identityStore.loadOrCreate {
                        val pair = ClientAuthKeyPair.generate()
                        Identity(result.onion, pair.publicKey, pair.privateKey, displayName)
                    }
                    check(identity.onion == result.onion) {
                        "The saved identity belongs to a different onion service than the saved key"
                    }
                } catch (failure: Throwable) {
                    runCatching { removeService(result.onion) }
                    throw failure
                }
            }
            coroutineContext.ensureActive()
        } catch (cancelled: CancellationException) {
            added?.let { result ->
                withContext(NonCancellable) { runCatching { removeService(result.onion) } }
            }
            throw cancelled
        }
        checkNotNull(added).onion
    }

    /** Issues `DEL_ONION`. Does nothing when not published. */
    suspend fun unpublish(): Unit = mutex.withLock {
        state.value?.let { removeService(it) }
    }

    private suspend fun removeService(onion: String) {
        try {
            control.command(formatDelOnion(onion.removeSuffix(".onion")))
        } catch (e: TorCommandException) {
            // 552: Tor no longer knows the service (e.g. Tor restarted), which is the goal anyway.
            if (e.reply.code != 552) throw e
        }
        state.value = null
    }
}

/**
 * Installs our client-auth private key for each peer we dial, as Tor's `ClientOnionAuthDir`
 * expects. Tor reads that directory at startup and on reload, so install before launching Tor or
 * reload it afterwards.
 */
class ClientAuthInstaller(private val identityStore: IdentityStore, private val dir: ClientAuthDir) {
    /** Writes `<peer>.auth_private`; [peerOnion] is a validated `.onion` address. */
    suspend fun install(peerOnion: String) {
        val identity = identityStore.loadOrCreate { error("Publish the onion service before dialing peers") }
        dir.write(clientAuthFileName(peerOnion), clientAuthFileContents(peerOnion, identity.clientAuthPrivate))
    }

    suspend fun remove(peerOnion: String) = dir.delete(clientAuthFileName(peerOnion))
}
