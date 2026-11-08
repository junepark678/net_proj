package dev.junepark.oniontalkie.tor

/** Maps a SOCKS5 reply byte (RFC 1928 plus Tor's ExtendedErrors 0xF0..0xF7) to a failed [DialResult]. */
internal object Socks5Replies {
    fun failure(reply: Int): DialResult = when (reply) {
        0xF0, 0xF2, 0xF3, 0xF7 -> DialResult.PeerOffline
        0xF4, 0xF5 -> DialResult.AuthRequired
        0x06 -> DialResult.Timeout
        else -> DialResult.Error(reply, describe(reply))
    }

    fun describe(reply: Int): String = when (reply) {
        0x01 -> "general SOCKS server failure"
        0x02 -> "connection not allowed by ruleset"
        0x03 -> "network unreachable"
        0x04 -> "host unreachable"
        0x05 -> "connection refused"
        0x06 -> "TTL expired"
        0x07 -> "command not supported"
        0x08 -> "address type not supported"
        0xF0 -> "onion service descriptor not found"
        0xF1 -> "onion service descriptor invalid"
        0xF2 -> "onion service introduction failed"
        0xF3 -> "onion service rendezvous failed"
        0xF4 -> "onion service client authorization required"
        0xF5 -> "onion service client authorization failed"
        0xF6 -> "onion service address is invalid"
        0xF7 -> "onion service introduction timed out"
        else -> "unknown SOCKS5 reply 0x${reply.toString(16).padStart(2, '0')}"
    }
}
