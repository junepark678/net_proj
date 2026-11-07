package dev.junepark.oniontalkie.identity

import android.content.Context

/** Initializes the private storage path before the first call to directoryStorage(). */
object AndroidAppData {
    @Volatile
    private var filesDirectory: String? = null

    /** Call from Application.onCreate; only the path is retained, never the supplied context. */
    fun init(context: Context) {
        filesDirectory = context.applicationContext.filesDir.absolutePath
    }

    internal fun requireFilesDir(): String = checkNotNull(filesDirectory) {
        "AndroidAppData.init(context) must be called before using appDataDir() or directoryStorage()"
    }
}

actual fun appDataDir(): String = AndroidAppData.requireFilesDir()
