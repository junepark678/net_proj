package dev.junepark.oniontalkie.call

import dev.junepark.oniontalkie.protocol.Frame
import dev.junepark.oniontalkie.protocol.FrameChannel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow

/*
 * Narrow dependency contracts of [CallEngine]. They are deliberately small so that the Tor
 * presence owner (#7), the SOCKS5 dialer (#8) and the audio pipeline (#15) can each be adapted to
 * them in a few lines without the engine knowing anything about Tor, sockets or audio devices.
 */

/**
 * "Go online" (#7): publishes the onion service and starts listening on its local port.
 * The adapter owns Tor, `ADD_ONION`, client authorization and the local port.
 */
interface PresenceService {
    /**
     * Publishes the service and starts listening. Throws if that fails.
     * On cancellation an implementation must undo any partial publication; a [ListeningService]
     * that was already produced is closed by the engine, never leaked.
     */
    suspend fun goOnline(): ListeningService
}

/** A published, listening onion service. The engine calls [close] at most once. */
interface ListeningService {
    /** This device's own v3 onion address (with or without `.onion`; any case). */
    val onion: String

    /**
     * Suspends until the next inbound TCP connection, or returns null once the listener has shut down.
     * Throws if the listener fails. It must be cancellable without losing an accepted connection
     * (a connection that cannot be returned must be closed by the implementation).
     */
    suspend fun accept(): IncomingConnection?

    /** Unpublishes and stops listening. */
    suspend fun close()
}

/**
 * One accepted inbound stream. The wire protocol's HELLO carries only a display name, so a listener
 * normally cannot tell who is calling and leaves [peerOnion] null; an adapter that can identify the
 * caller may set it, which lets the engine tell a glare collision from an unrelated caller.
 */
class IncomingConnection(val channel: FrameChannel, val peerOnion: String? = null)

/** Outbound connections (#8). Adapts `TorDialer.connect(onion, 9999)` one-to-one. */
interface CallDialer {
    /**
     * Opens a stream to [onion] (lowercase, `<56 chars>.onion`) and returns within a bounded time.
     * When cancelled, an implementation must close any channel it has already obtained.
     */
    suspend fun connect(onion: String): DialOutcome
}

/** Result of [CallDialer.connect]; mirrors the failure classes of #8 so the adapter is a plain `when`. */
sealed class DialOutcome {
    /** The channel is owned by the engine from here on. */
    class Connected(val channel: FrameChannel) : DialOutcome()

    /** Descriptor not found or rendezvous failed: the peer is not online. Distinct UI state. */
    data object PeerOffline : DialOutcome()

    /** The peer rejected our client authorization (we are not, or no longer, paired). */
    data object AuthRequired : DialOutcome()

    /** The dialer's own bounded wait ran out. */
    data object Timeout : DialOutcome()

    /** Any other transport failure. */
    data class Failed(val message: String) : DialOutcome()
}

/**
 * The audio side of an active call, supplied by #15. [CallEngine] runs it only between ACCEPT and
 * teardown, so it never sees a half-established call.
 */
fun interface CallMedia {
    /**
     * Runs capture/encode and receive/decode until cancelled. Cancellation means the call ended; the
     * implementation releases its audio devices in a `finally`. Throwing any other exception ends the
     * call with [EndReason.MEDIA_ERROR]; returning normally leaves the call up without media.
     * Only one call's media runs at a time: the next call's `run` starts after the previous one's
     * cleanup has finished.
     */
    suspend fun run(link: MediaLink)

    companion object {
        /** Carries no audio; the call stays up with keepalives only. */
        val Silent: CallMedia = CallMedia { awaitCancellation() }
    }
}

/** The media's view of one call's transport. The engine itself keeps PING/PONG/BYE/handshake. */
interface MediaLink {
    val peer: PeerInfo

    /** Received AUDIO frames in arrival order. If the collector is slow the oldest frames are dropped. */
    val incomingAudio: Flow<Frame.Audio>

    /** Sends one AUDIO frame. Throws if the call has ended; calls from several coroutines are serialized. */
    suspend fun sendAudio(seq: UShort, tsMs: UInt, payload: ByteArray)
}
