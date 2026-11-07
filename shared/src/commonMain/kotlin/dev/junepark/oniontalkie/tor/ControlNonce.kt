package dev.junepark.oniontalkie.tor

/** A fresh, cryptographically secure 32-byte nonce for the SAFECOOKIE handshake. */
internal expect fun secureControlNonce(): ByteArray
