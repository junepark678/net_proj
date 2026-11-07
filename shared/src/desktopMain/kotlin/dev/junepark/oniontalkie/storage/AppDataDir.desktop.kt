package dev.junepark.oniontalkie.storage

import java.io.File

/** `$XDG_DATA_HOME/onion-talkie`, else `~/.local/share/onion-talkie` (Linux), `~/.onion-talkie` elsewhere. */
actual fun appDataDir(): String {
    val home = System.getProperty("user.home")
    val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
    val base = when {
        xdg != null -> File(xdg, "onion-talkie")
        System.getProperty("os.name").orEmpty().contains("linux", ignoreCase = true) ->
            File(home, ".local/share/onion-talkie")
        else -> File(home, ".onion-talkie")
    }
    return base.path
}
