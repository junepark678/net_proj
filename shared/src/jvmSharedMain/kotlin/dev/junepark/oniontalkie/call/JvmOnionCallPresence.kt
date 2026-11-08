package dev.junepark.oniontalkie.call

import dev.junepark.oniontalkie.identity.ContactStore
import dev.junepark.oniontalkie.identity.IdentityStore
import dev.junepark.oniontalkie.onion.AuthenticatedCallHandshake
import dev.junepark.oniontalkie.onion.ClientAuthInstaller
import dev.junepark.oniontalkie.onion.OnionKeyStore
import dev.junepark.oniontalkie.onion.OnionService
import dev.junepark.oniontalkie.onion.authKey
import dev.junepark.oniontalkie.onion.directoryClientAuthDir
import dev.junepark.oniontalkie.protocol.FrameChannel
import dev.junepark.oniontalkie.tor.TorControl
import dev.junepark.oniontalkie.tor.TorSignal
import dev.junepark.oniontalkie.tor.SocketFrameChannel
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Local TCP rendezvous for a Tor v3 onion service, with mutual pairing-key verification before
 * handing a channel to [CallEngine]. Construct against an already authenticated Tor control port.
 */
class JvmOnionCallPresence(
    private val control: TorControl,
    private val identities: IdentityStore,
    private val keyStore: OnionKeyStore,
    private val contacts: ContactStore,
    dataDirectory: String,
    private val displayName: String,
    private val virtualPort: Int = 9999,
) : CallPresence {
    private val mutex = Mutex()
    private val authDir = directoryClientAuthDir("${dataDirectory.trimEnd('/', '\\')}/client-auth")
    private val installer = ClientAuthInstaller(identities, authDir)
    private val authenticator = AuthenticatedCallHandshake(identities, contacts)
    private var listener: AuthenticatedListener? = null
    private var service: OnionService? = null
    private var installedPeers: Set<String> = emptySet()

    /** Prepares the socket and publishes the onion service; its address becomes the local identity. */
    suspend fun prepare(): String = mutex.withLock {
        listener?.let { return@withLock checkNotNull(service?.address?.value) }
        val tcp = LocalTcpCallListener.bind(authenticator)
        val onionService = OnionService(
            control = control,
            identityStore = identities,
            keyStore = keyStore,
            displayName = displayName,
            localPort = tcp.port,
            virtualPort = virtualPort,
        )
        try {
            val address = onionService.publish(contacts.list().map { it.authKey() })
            reconcileClientAuth(contacts.list().map { it.onion }.toSet())
            control.signal(TorSignal.RELOAD)
            val owned = AuthenticatedListener(tcp, onionService)
            listener = owned
            service = onionService
            address
        } catch (failure: Throwable) {
            runCatching { tcp.close() }
            runCatching { onionService.unpublish() }
            installedPeers.forEach { peer -> runCatching { installer.remove(peer) } }
            installedPeers = emptySet()
            runCatching { control.signal(TorSignal.RELOAD) }
            throw failure
        }
    }

    /** Refreshes both Tor's authorized service keys and the local dial authorization files. */
    suspend fun refreshContacts() = mutex.withLock {
        if (listener == null) return@withLock
        val currentService = checkNotNull(service)
        currentService.publish(contacts.list().map { it.authKey() })
        reconcileClientAuth(contacts.list().map { it.onion }.toSet())
        control.signal(TorSignal.RELOAD)
    }

    override suspend fun open(): CallListener {
        prepare()
        return mutex.withLock { checkNotNull(listener) }
    }

    private suspend fun reconcileClientAuth(desired: Set<String>) {
        identities.loadOrCreate { error("Onion service identity was not created") }
        desired.sorted().forEach { installer.install(it) }
        (installedPeers - desired).forEach { peer -> installer.remove(peer) }
        installedPeers = desired
    }

    private inner class AuthenticatedListener(
        private val tcp: LocalTcpCallListener,
        private val onionService: OnionService,
    ) : CallListener {
        private val closed = Mutex()
        private var isClosed = false

        override suspend fun accept(): IncomingCall = tcp.accept()

        override suspend fun close() = withContext(NonCancellable) {
            closed.withLock {
                if (isClosed) return@withLock
                isClosed = true
                tcp.close()
                mutex.withLock {
                    if (listener === this@AuthenticatedListener) {
                        try {
                            onionService.unpublish()
                        } finally {
                            installedPeers.forEach { peer -> runCatching { installer.remove(peer) } }
                            installedPeers = emptySet()
                            runCatching { control.signal(TorSignal.RELOAD) }
                            listener = null
                            service = null
                        }
                    }
                }
            }
        }
    }

}

private class LocalTcpCallListener private constructor(
    private val server: ServerSocketChannel,
    private val authenticator: AuthenticatedCallHandshake,
) {
    private val pending = ConcurrentHashMap.newKeySet<SocketChannel>()
    val port: Int = (server.localAddress as InetSocketAddress).port

    suspend fun accept(): IncomingCall {
        while (true) {
            currentCoroutineContext().ensureActive()
            val socket = try {
                runInterruptible(Dispatchers.IO) { server.accept() }
            } catch (failure: Exception) {
                // Closing the server to cancel a blocking accept can surface as
                // ClosedByInterruptException. Preserve coroutine cancellation semantics.
                currentCoroutineContext().ensureActive()
                throw failure
            }
            pending.add(socket)
            var delivered = false
            try {
                socket.configureBlocking(true)
                val channel: FrameChannel = SocketFrameChannel(socket)
                val peer = withTimeoutOrNull(AUTHENTICATION_TIMEOUT_MILLIS) {
                    authenticator.incoming(channel)
                }
                if (peer == null) continue
                pending.remove(socket)
                delivered = true
                return IncomingCall(peer.contact.onion, peer.channel)
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw cancelled
            } catch (_: Exception) {
                // An unauthenticated or malformed local connection is dropped; keep listening.
            } finally {
                if (!delivered) {
                    pending.remove(socket)
                    runCatching { socket.close() }
                }
            }
        }
    }

    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        runCatching { server.close() }
        pending.toList().forEach { socket -> runCatching { socket.close() } }
        pending.clear()
    }

    companion object {
        fun bind(authenticator: AuthenticatedCallHandshake): LocalTcpCallListener {
            val server = ServerSocketChannel.open()
            try {
                server.bind(InetSocketAddress("127.0.0.1", 0))
                server.configureBlocking(true)
                return LocalTcpCallListener(server, authenticator)
            } catch (failure: Throwable) {
                runCatching { server.close() }
                throw failure
            }
        }

        private const val AUTHENTICATION_TIMEOUT_MILLIS = 10_000L
    }
}
