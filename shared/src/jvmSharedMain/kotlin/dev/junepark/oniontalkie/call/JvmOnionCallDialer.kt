package dev.junepark.oniontalkie.call

import dev.junepark.oniontalkie.onion.AuthenticatedCallHandshake
import dev.junepark.oniontalkie.protocol.FrameChannel
import dev.junepark.oniontalkie.tor.DialResult
import dev.junepark.oniontalkie.tor.TorDialer
import kotlinx.coroutines.withTimeout

/** Dials through Tor, then proves the remote service belongs to the selected paired contact. */
class JvmOnionCallDialer(
    private val tor: TorDialer,
    private val authenticator: AuthenticatedCallHandshake,
    private val virtualPort: Int = 9999,
) : CallDialer {
    override suspend fun dial(peerOnion: String): FrameChannel {
        return when (val result = tor.connect(peerOnion, virtualPort)) {
            is DialResult.Connected -> {
                try {
                    withTimeout(AUTHENTICATION_TIMEOUT_MILLIS) {
                        authenticator.outgoing(peerOnion, result.channel)
                    }
                } catch (failure: Throwable) {
                    runCatching { result.channel.close() }
                    throw failure
                }
            }
            DialResult.PeerOffline, DialResult.Timeout -> throw PeerOfflineException()
            DialResult.AuthRequired -> throw IllegalStateException("Tor rejected the paired client key")
            is DialResult.Error -> throw IllegalStateException("Tor dial failed: ${result.message}")
        }
    }

    private companion object {
        const val AUTHENTICATION_TIMEOUT_MILLIS = 10_000L
    }
}
