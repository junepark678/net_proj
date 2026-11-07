package dev.junepark.oniontalkie.storage

import android.content.Context

/** Holds the application context so common code can resolve the app-private files directory. */
object AndroidAppData {
    private var filesDir: String? = null

    /** Call once from `Application.onCreate` before any storage is used. */
    fun init(context: Context) {
        filesDir = context.applicationContext.filesDir.absolutePath
    }

    internal fun requireFilesDir(): String =
        checkNotNull(filesDir) { "AndroidAppData.init(context) must be called first" }
}

/** The app-private `filesDir` (set via [AndroidAppData.init]). */
actual fun appDataDir(): String = AndroidAppData.requireFilesDir()
