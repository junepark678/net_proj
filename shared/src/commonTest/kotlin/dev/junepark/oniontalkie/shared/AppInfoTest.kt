package dev.junepark.oniontalkie.shared

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class AppInfoTest {
    @Test
    fun applicationIdUsesPackageRoot() {
        assertEquals("dev.junepark.oniontalkie", AppInfo.Current.applicationId)
    }

    @Test
    fun serializationRoundTrips() {
        val json = Json.encodeToString(AppInfo.serializer(), AppInfo.Current)
        assertEquals(AppInfo.Current, Json.decodeFromString(AppInfo.serializer(), json))
    }
}
