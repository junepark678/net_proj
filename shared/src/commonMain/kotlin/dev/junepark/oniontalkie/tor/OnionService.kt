package dev.junepark.oniontalkie.tor

import dev.junepark.oniontalkie.identity.Identity
import dev.junepark.oniontalkie.identity.IdentityStore
import dev.junepark.oniontalkie.identity.PairingPayload
import dev.junepark.oniontalkie.identity.directoryStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * One persistent service on an authenticated, dedicated [TorControl] connection. Own one instance
 * per identity directory. The caller owns the loopback listener, Tor process and control lifetime.
 * A successful publish acknowledges ADD_ONION, not descriptor upload; observe control.events
 * for HS_DESC UPLOADED. Closing the control connection also removes the service (no Detach).
 */
class OnionService private constructor(
    private val control: TorControl,
    private val localPort: Int,
    val identity: Identity,
) {
    private val mutex = Mutex()
    private val address = MutableStateFlow<String?>(null)
    private var publishedKeys: List<String>? = null

    /** Locally acknowledged publication, null when unpublished. Close via unpublish before control. */
    val onionAddress: StateFlow<String?> = address.asStateFlow()

    /**
     * Publish with the complete current peer list. Read ContactStore.list() on each online
     * transition, mapping clientAuthPub to PeerAuthKey. Calling again with changed keys removes
     * and re-adds the same service; a failed replacement leaves it offline. With no peers only
     * our own client key is authorized, so an unpaired device never publishes a public service.
     */
    suspend fun publish(peers: List<PeerAuthKey>): String = mutex.withLock {
        val keys = peers.map { it.publicKey }.ifEmpty { listOf(identity.clientAuthPub.uppercase()) }
            .distinct().sorted()
        if (address.value != null && publishedKeys == keys) return@withLock identity.onion
        removePublished()
        val reply = exchange(addOnionCommand(
            requireNotNull(identity.onionServicePrivateKey), localPort, keys.map(::PeerAuthKey),
        ))
        try {
            val onion = serviceAddress(reply)
            check(onion == identity.onion) { "Tor returned a different onion for the persisted service key" }
            address.value = onion
            publishedKeys = keys
            onion
        } catch (failure: Exception) {
            // A successful but malformed ADD_ONION may have created a service with an unknown ID.
            // Closing this dedicated connection guarantees that no untracked service remains.
            control.close()
            throw failure
        }
    }

    /** Idempotent while offline. A negative DEL_ONION reply retains state so the caller can retry. */
    suspend fun unpublish(): Unit = mutex.withLock { removePublished() }

    private suspend fun removePublished() {
        val onion = address.value ?: return
        exchange("DEL_ONION ${onion.removeSuffix(".onion")}")
        address.value = null
        publishedKeys = null
    }

    private suspend fun exchange(line: String): TorReply = try {
        control.command(line)
    } catch (cancelled: CancellationException) {
        // TorControl drains cancelled commands independently. We cannot know whether ADD/DEL
        // took effect, so detach neither the service nor the operation from its owner.
        control.close()
        address.value = null
        publishedKeys = null
        throw cancelled
    } catch (failure: Exception) {
        if (failure is TorCommandException) throw failure
        control.close()
        address.value = null
        publishedKeys = null
        throw failure
    }

    companion object {
        /**
         * Load the existing identity or obtain Tor's service key on first launch. The first-run
         * service authorizes only our new x25519 key and is removed before returning, leaving this
         * instance offline but ready to export identity.pairingPayload(). All secret keys and the
         * onion address are persisted together by IdentityStore, with owner-only platform storage.
         * Older identities missing the service key fail explicitly rather than rotating identity.
         */
        suspend fun open(
            control: TorControl,
            localPort: Int,
            identityStore: IdentityStore = IdentityStore(directoryStorage()),
            displayName: String = "Onion Talkie",
        ): OnionService {
            require(localPort in 1..65535) { "Invalid local listener port" }
            var createdService: String? = null
            try {
                val identity = identityStore.loadOrCreate {
                    val clientKeys = generateClientAuthKeyPair()
                    val reply = control.command(addOnionCommand(
                        "NEW:ED25519-V3", localPort, listOf(PeerAuthKey(clientKeys.publicKey)),
                    ))
                    val onion = serviceAddress(reply)
                    createdService = onion
                    val key = replyField(reply, "PrivateKey")
                    validateServiceKey(key)
                    Identity(onion, clientKeys.publicKey, clientKeys.privateKey, displayName, key)
                }
                validateServiceKey(checkNotNull(identity.onionServicePrivateKey) {
                    "Persisted identity has no onion service key; refusing to replace it"
                })
                check(clientAuthPublicKey(identity.clientAuthPrivate) == identity.clientAuthPub.uppercase()) {
                    "Persisted x25519 keys do not form a pair"
                }
                return OnionService(control, localPort, identity)
            } catch (failure: Exception) {
                // Also handles cancellation between sending NEW and receiving its ServiceID,
                // malformed replies, and failed persistence. Never leave a transient service up.
                control.close()
                createdService = null
                throw failure
            } finally {
                if (createdService != null) {
                    // Once persisted, finish cleanup even if the caller is cancelled. Earlier
                    // failures disconnect above; failed deletion also disconnects below.
                    withContext(NonCancellable) {
                        try {
                            withTimeout(10_000) {
                                control.command("DEL_ONION ${createdService.removeSuffix(".onion")}")
                            }
                        } catch (failure: Exception) {
                            control.close()
                            // A failed first-run cleanup must not return a seemingly usable owner.
                            throw failure
                        }
                    }
                }
            }
        }
    }
}

private fun replyField(reply: TorReply, name: String): String =
    reply.lines.singleOrNull { it.data == null && it.text.startsWith("$name=") }
        ?.text?.substringAfter('=') ?: throw TorProtocolException("ADD_ONION omitted a unique $name field")

private fun serviceAddress(reply: TorReply): String {
    val address = replyField(reply, "ServiceID") + ".onion"
    // Use the same v3 checksum validation as pairing; no untrusted reply becomes a command.
    PairingPayload(address, "A".repeat(52), "Service")
    return address
}
