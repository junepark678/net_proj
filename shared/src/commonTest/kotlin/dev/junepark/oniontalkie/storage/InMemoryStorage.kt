package dev.junepark.oniontalkie.storage

/** Test fake for [Storage]. */
class InMemoryStorage(var text: String? = null) : Storage {
    override fun read(): String? = text
    override fun write(text: String) {
        this.text = text
    }
}
