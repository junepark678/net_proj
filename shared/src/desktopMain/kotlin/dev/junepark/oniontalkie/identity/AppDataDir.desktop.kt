package dev.junepark.oniontalkie.identity

import java.io.File

/** Uses $XDG_DATA_HOME/onion-talkie, falling back to ~/.local/share/onion-talkie. */
actual fun appDataDir(): String {
    val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
        ?: File(System.getProperty("user.home"), ".local/share").path
    return File(base, "onion-talkie").path
}
