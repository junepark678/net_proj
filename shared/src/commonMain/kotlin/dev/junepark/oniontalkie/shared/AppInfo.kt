package dev.junepark.oniontalkie.shared

import kotlinx.serialization.Serializable

/** Static product identity shared by every platform. Placeholder until real code lands. */
@Serializable
data class AppInfo(val name: String, val applicationId: String) {
    companion object {
        val Current = AppInfo(name = "Onion Talkie", applicationId = "dev.junepark.oniontalkie")
    }
}
