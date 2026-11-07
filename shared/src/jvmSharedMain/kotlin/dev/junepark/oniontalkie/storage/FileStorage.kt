package dev.junepark.oniontalkie.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** [Storage] backed by a UTF-8 file; writes go to a temp file first and are moved into place. */
class FileStorage(private val file: File) : Storage {
    /** Convenience for `File(dir, name)`. */
    constructor(dir: String, name: String) : this(File(dir, name))

    override fun read(): String? = if (file.isFile) file.readText() else null

    override fun write(text: String) {
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absolutePath + ".tmp")
        tmp.writeText(text)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
