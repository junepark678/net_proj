package dev.junepark.oniontalkie.tor

import java.security.SecureRandom

internal actual fun secureControlNonce(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
