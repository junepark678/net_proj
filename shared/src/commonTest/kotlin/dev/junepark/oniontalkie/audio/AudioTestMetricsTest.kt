package dev.junepark.oniontalkie.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AudioTestMetricsTest {
    @Test
    fun snrMeasuresNoiseAndCompensatesForCodecDelay() {
        assertEquals(20.0, snrDb(shortArrayOf(100, -100), shortArrayOf(90, -90)), 1e-10)
        assertEquals(
            Double.POSITIVE_INFINITY,
            snrDb(shortArrayOf(100, -100, 50), shortArrayOf(0, 0, 100, -100), delaySamples = 2),
        )
        assertEquals(Double.POSITIVE_INFINITY, snrDb(ShortArray(4), ShortArray(4)))
    }

    @Test
    fun snrRejectsNegativeDelayAndEmptyOverlap() {
        assertFailsWith<IllegalArgumentException> { snrDb(shortArrayOf(1), shortArrayOf(1), -1) }
        assertFailsWith<IllegalArgumentException> { snrDb(shortArrayOf(1), shortArrayOf(1), 1) }
        assertFailsWith<IllegalArgumentException> { snrDb(shortArrayOf(), shortArrayOf(1)) }
    }
}
