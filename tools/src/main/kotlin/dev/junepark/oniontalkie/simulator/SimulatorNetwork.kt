package dev.junepark.oniontalkie.simulator

import dev.junepark.oniontalkie.call.CallDialer
import dev.junepark.oniontalkie.call.CallListener
import dev.junepark.oniontalkie.call.CallPresence
import dev.junepark.oniontalkie.call.IncomingCall
import dev.junepark.oniontalkie.call.PeerOfflineException
import dev.junepark.oniontalkie.protocol.FrameChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory rendezvous that exercises two real [dev.junepark.oniontalkie.call.CallEngine]s. */
internal class SimulatorNetwork(
    private val scope: CoroutineScope,
    private val impairment: ImpairmentConfig,
) {
    private val lifecycle = Mutex()
    private val listeners = mutableMapOf<String, Listener>()
    private val transports = linkedSetOf<ImpairedTransport>()
    private var seedOffset = 0L

    fun presence(onion: String): CallPresence = CallPresence {
        lifecycle.withLock {
            check(onion !in listeners) { "A simulator listener is already online for $onion" }
            Listener(onion).also { listeners[onion] = it }
        }
    }

    fun dialer(localOnion: String): CallDialer = CallDialer { peerOnion ->
        val (remote, seed) = lifecycle.withLock {
            (listeners[peerOnion] ?: throw PeerOfflineException()) to (impairment.seed + seedOffset++)
        }
        val (caller, callee) = ImpairedTransport.pair(scope, impairment.copy(seed = seed))
        lifecycle.withLock { transports.addAll(listOf(caller, callee)) }
        if (!remote.deliver(IncomingCall(localOnion, callee))) {
            caller.close()
            callee.close()
            throw PeerOfflineException()
        }
        caller
    }

    suspend fun close() {
        val (activeListeners, endpoints) = lifecycle.withLock {
            listeners.values.toList() to transports.toList().also { transports.clear() }
        }
        activeListeners.forEach { runCatching { it.close() } }
        endpoints.forEach { runCatching { it.close() } }
    }

    private inner class Listener(private val onion: String) : CallListener {
        private val incoming = Channel<IncomingCall>(Channel.UNLIMITED)
        private val pending = linkedSetOf<FrameChannel>()
        var closing = false

        override suspend fun accept(): IncomingCall = incoming.receive().also {
            lifecycle.withLock { pending.remove(it.channel) }
        }

        suspend fun deliver(call: IncomingCall): Boolean = lifecycle.withLock {
            if (closing) return@withLock false
            pending += call.channel
            if (incoming.trySend(call).isSuccess) true else {
                pending.remove(call.channel)
                false
            }
        }

        override suspend fun close() {
            val unclaimed = lifecycle.withLock {
                if (closing) return@withLock emptyList()
                closing = true
                if (listeners[onion] === this) listeners.remove(onion)
                incoming.cancel()
                pending.toList().also { pending.clear() }
            }
            unclaimed.forEach { runCatching { it.close() } }
        }
    }
}
