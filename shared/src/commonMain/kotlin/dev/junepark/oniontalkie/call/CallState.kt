package dev.junepark.oniontalkie.call

/**
 * The other party. [onion] is known for outgoing calls (and for incoming ones when the listener can
 * identify the caller); [displayName] is known once a HELLO has been received.
 */
data class PeerInfo(val onion: String?, val displayName: String?)

enum class CallDirection { OUTGOING, INCOMING }

/** Why the last call attempt finished. Surfaced in [CallState.Online.lastCall]. */
enum class EndReason {
    /** We cancelled a dial, declined a ring, or hung up. */
    LOCAL_HANGUP,

    /** The peer sent BYE(NORMAL) or went away while we were ringing. */
    REMOTE_HANGUP,

    /** The callee answered our HELLO with BYE(NORMAL) instead of ACCEPT. */
    PEER_DECLINED,

    /** The callee answered our HELLO with BYE(BUSY). */
    PEER_BUSY,

    /** The incoming call rang for 30 s without an answer. */
    RING_TIMEOUT,

    /** No ACCEPT within 10 s of HELLO. */
    HANDSHAKE_TIMEOUT,

    /** No PONG or AUDIO for 15 s during a call. */
    TIMEOUT,

    /** The stream failed or closed without BYE. */
    CONNECTION_LOST,

    /** Auth required, dialer timeout, or another transport error before any stream existed; see detail. */
    DIAL_FAILED,

    /** The peer speaks another protocol version. */
    VERSION_MISMATCH,

    /** A malformed frame, or a frame that is not valid in the current state. */
    PROTOCOL_ERROR,

    /** The [CallMedia] implementation failed. */
    MEDIA_ERROR,

    /** The requested onion address is malformed or our own. */
    INVALID_PEER,
}

/** Outcome of the previous call attempt, kept until the next dial or [CallEngine.dismiss]. */
data class CallEnd(
    val peer: PeerInfo,
    val reason: EndReason,
    val direction: CallDirection,
    val detail: String? = null,
)

/**
 * What the UI renders. Exactly one value at a time:
 *
 * ```
 * Offline ─goOnline→ GoingOnline ─→ Online ─dial→ Dialing ─ACCEPT→ InCall ─end→ Online
 *    ↑                    │            │              ├─ peer not online → PeerOffline
 *    └──────goOffline─────┴────────────┘              └─ declined/busy/timeout → Online(lastCall)
 *                                      └─inbound HELLO→ Ringing ─accept→ InCall
 * ```
 * [PeerOffline] behaves exactly like [Online] for commands; it only differs so the UI can say
 * "peer not online".
 */
sealed interface CallState {
    /** Not listening. [error] explains why we fell offline unexpectedly (null after a deliberate stop). */
    data class Offline(val error: String? = null) : CallState

    /** Starting Tor/publishing the service after [CallEngine.goOnline]. */
    data object GoingOnline : CallState

    /** Online and idle. [lastCall] is the outcome of the previous attempt, if any. */
    data class Online(val onion: String, val lastCall: CallEnd? = null) : CallState

    /** Outbound call in progress: connecting, HELLO sent, waiting for ACCEPT. [peer] is the dialed onion. */
    data class Dialing(val peer: String) : CallState

    /** The dialed peer is not online (descriptor or rendezvous failure). Idle for command purposes. */
    data class PeerOffline(val peer: String) : CallState

    /** An incoming call is ringing; answer with [CallEngine.accept] or [CallEngine.decline]. */
    data class Ringing(val peer: PeerInfo) : CallState

    /** Media is flowing. */
    data class InCall(val peer: PeerInfo, val direction: CallDirection) : CallState
}

/** Protocol timings. The defaults are the project's contract; tests may shrink them. */
data class CallEngineConfig(
    /** Maximum wait for ACCEPT after HELLO is written, and for an expected glare inbound. */
    val handshakeTimeoutMs: Long = 10_000,
    /** How long an incoming call rings. */
    val ringTimeoutMs: Long = 30_000,
    /** PING period during a call. */
    val pingIntervalMs: Long = 2_000,
    /** A call with no PONG/AUDIO this long ends with [EndReason.TIMEOUT]. */
    val inactivityTimeoutMs: Long = 15_000,
    /** Safety bound on [CallDialer.connect]; the dialer normally gives up sooner (≤ 30 s). */
    val connectTimeoutMs: Long = 45_000,
    /** An accepted stream that never sends HELLO is dropped after this long. */
    val helloWaitMs: Long = 10_000,
    /** Bound on writing the final BYE during teardown. */
    val byeTimeoutMs: Long = 2_000,
    /** Bound on a transport's `close()`. */
    val closeTimeoutMs: Long = 5_000,
    /** Sent in HELLO as `opusRateHint`. */
    val opusRateHint: UInt = 24_000u,
) {
    init {
        require(
            handshakeTimeoutMs > 0 && ringTimeoutMs > 0 && pingIntervalMs > 0 && inactivityTimeoutMs > 0 &&
                connectTimeoutMs > 0 && helloWaitMs > 0 && byeTimeoutMs > 0 && closeTimeoutMs > 0,
        ) { "All timeouts must be positive" }
    }
}
