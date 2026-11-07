package dev.junepark.oniontalkie.onion

/** Desktop key storage uses the file's existing owner-only permissions. */
actual fun createOnionServiceKeyProtector(): OnionServiceKeyProtector = object : OnionServiceKeyProtector {
    override fun protect(privateKey: String): String = "plaintext-v1:$privateKey"

    override fun unprotect(stored: String): String {
        require(stored.startsWith("plaintext-v1:")) { "Unsupported desktop service-key protection format" }
        return stored.removePrefix("plaintext-v1:")
    }
}
