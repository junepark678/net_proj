package dev.junepark.oniontalkie.call

import dev.junepark.oniontalkie.identity.validOnion
import dev.junepark.oniontalkie.protocol.Frame
import dev.junepark.oniontalkie.protocol.FrameChannel

/** UI snapshots. PeerOffline is idle: retry, another call, or goOffline is allowed. */
sealed interface CallState {
    data object Offline : CallState
    data class Online(val lastEnd: CallEndReason? = null) : CallState
    data class Dialing(val peerOnion: String) : CallState
    /** The name is absent until a valid HELLO arrives; accept may be requested meanwhile. */
    data class Ringing(val peerOnion: String, val displayName: String? = null) : CallState
    data class InCall(val peerOnion: String) : CallState
    data class PeerOffline(val peerOnion: String) : CallState
}

/** Local outcomes; TIMEOUT is not a wire BYE code in protocol v1. */
enum class CallEndReason {
    NORMAL, DECLINED, BUSY, GLARE, TIMEOUT, CANCELLED, CONNECTION_LOST, PROTOCOL_ERROR, FAILURE,
}

/**
 * #7 adapter: publish presence and return a listener ready to accept on the local service port.
 * A cancelled/failed open must release any partially acquired resources. No Tor API is duplicated.
 */
fun interface CallPresence {
    suspend fun open(): CallListener
}

/**
 * Owned by the engine after open returns. accept must be cancellable and transfer each fresh
 * channel exactly once. close withdraws presence and unblocks accept, including on cancellation.
 * An accept cancelled before delivering its result must release that result's channel itself.
 */
interface CallListener {
    suspend fun accept(): IncomingCall
    suspend fun close()
}

/**
 * peerOnion must be the verified paired identity supplied by the adapter, never a socket source
 * address or HELLO display name. Protocol v1 HELLO does not authenticate/provide an onion identity;
 * adapters must resolve that association before delivery (a remaining integration requirement).
 */
data class IncomingCall(val peerOnion: String, val channel: FrameChannel)

/**
 * #8 adapter: connect through Tor and transfer a fresh, cancellable FrameChannel to the engine.
 * Throw PeerOfflineException only for descriptor/rendezvous unavailability; other errors remain
 * failures. Cancellation before delivery must close any acquired socket in the adapter.
 */
fun interface CallDialer {
    suspend fun dial(peerOnion: String): FrameChannel
}

class PeerOfflineException(message: String = "Peer is not online") : Exception(message)

/** Creates one media owner at acceptance; creation must be quick and must not start I/O. */
fun interface CallSessionFactory {
    fun create(peerOnion: String): CallSession
}

/**
 * Pluggable #15 media boundary. The engine owns framing, the single reader and serialized writes.
 * run owns capture/encoding until cancelled or EOF; use sendAudio for encoded packets only.
 * receive must enqueue promptly for playout, without waiting for capture or calling the engine.
 * close releases all media resources once, after run/receive have stopped, even on cancellation.
 * Implementations must support cancellation; no transport, Tor, or audio device API is invented here.
 */
interface CallSession {
    suspend fun run(sendAudio: suspend (Frame.Audio) -> Unit)
    suspend fun receive(frame: Frame.Audio)
    suspend fun close()
}

/** Use the existing checksum validator as well as ASCII-only case normalization for glare. */
internal fun normalizeOnion(value: String): String {
    require(value.all { it.code < 128 }) { "Onion addresses must be ASCII" }
    val normalized = value.lowercase().let { if (it.endsWith(".onion")) it else "$it.onion" }
    require(validOnion(normalized)) { "Expected a checksum-valid v3 onion address" }
    return normalized
}
