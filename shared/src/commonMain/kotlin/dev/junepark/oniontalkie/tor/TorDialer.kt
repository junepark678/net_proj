package dev.junepark.oniontalkie.tor

import dev.junepark.oniontalkie.protocol.FrameChannel

/** Outcome of dialing a peer's onion service through Tor. Dialing never throws for network failures. */
sealed class DialResult {
    /** The SOCKS5 tunnel to the peer is open; frame it with `FrameReader`/`FrameWriter`. */
    class Connected(val channel: FrameChannel) : DialResult()

    /** The peer is not reachable right now: no descriptor, introduction or rendezvous failed or timed out. */
    data object PeerOffline : DialResult()

    /** The onion service requires client authorization that this device lacks or that was rejected. */
    data object AuthRequired : DialResult()

    /** No usable answer within the dial deadline, or Tor itself reported a timeout (SOCKS5 TTL expired). */
    data object Timeout : DialResult()

    /** Any other failure. [code] is the SOCKS5 reply byte (0..255), or a negative [TorDialer] constant for local failures. */
    data class Error(val code: Int, val message: String) : DialResult()
}

/** Opens outbound connections to onion services. Retries and backoff belong to the caller. */
interface TorDialer {
    /** Dials `<onion>:<port>` through Tor. [onion] is a full `.onion` host name; it is never resolved locally. */
    suspend fun connect(onion: String, port: Int): DialResult

    companion object {
        /** [DialResult.Error.code] for an invalid onion name or port; nothing was sent. */
        const val ERROR_INVALID_ARGUMENT: Int = -1

        /** [DialResult.Error.code] for a local I/O failure (SOCKS port unreachable, connection dropped, EOF). */
        const val ERROR_IO: Int = -2

        /** [DialResult.Error.code] for a malformed or unexpected SOCKS5 message from the proxy. */
        const val ERROR_PROTOCOL: Int = -3

        /** Default deadline for connect plus SOCKS5 handshake, the offline-detection budget. */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 30_000
    }
}
