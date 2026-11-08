package dev.junepark.oniontalkie.tor

import android.content.Context
import java.io.File

/** Creates a Tor configuration rooted in app-private storage and the packaged native library. */
fun TorConfig.Companion.forAndroid(context: Context): TorConfig {
    val app = context.applicationContext
    val binary = File(app.applicationInfo.nativeLibraryDir, "libtor.so")
    require(binary.isFile) { "The tor-android AAR did not extract libtor.so to ${binary.parent}" }
    return TorConfig(
        dataDir = File(app.filesDir, "tor").absolutePath,
        executablePath = binary.absolutePath,
    )
}
