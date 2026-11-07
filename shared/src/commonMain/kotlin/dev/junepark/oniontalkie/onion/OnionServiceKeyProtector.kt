package dev.junepark.oniontalkie.onion

/** Platform boundary for protecting Tor's portable service-key blob at rest. */
interface OnionServiceKeyProtector {
    /** Returns an opaque, versioned representation suitable for persistent storage. */
    fun protect(privateKey: String): String

    /** Restores a Tor `ED25519-V3:` key from a representation created by [protect]. */
    fun unprotect(stored: String): String
}

/** Android encrypts with an Android Keystore AES key; desktop relies on owner-only file permissions. */
expect fun createOnionServiceKeyProtector(): OnionServiceKeyProtector
