package dev.junepark.oniontalkie

import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformTest {
    @Test
    fun platformHasAName() {
        assertTrue(platformName().isNotBlank())
    }
}
