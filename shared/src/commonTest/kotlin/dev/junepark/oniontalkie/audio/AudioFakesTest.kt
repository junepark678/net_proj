package dev.junepark.oniontalkie.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AudioFakesTest {
    @Test
    fun inputCopiesSourceBuffersAndHonorsLifecycleAndEof() {
        val source = ShortArray(960) { 42 }
        var reads = 0
        val input = FakeAudioInput { if (reads++ == 0) source else null }
        input.stop()
        assertFailsWith<IllegalStateException> { input.read() }
        input.start()
        assertFailsWith<IllegalStateException> { input.start() }
        val captured = input.read()!!
        source.fill(0)
        assertContentEquals(ShortArray(960) { 42 }, captured)
        assertNull(input.read())
        input.stop()
        input.stop()
        assertFailsWith<IllegalStateException> { input.read() }
        input.start()
        assertNull(input.read())
        input.stop()
    }

    @Test
    fun outputCopiesWritesAndHonorsLifecycle() {
        val output = CapturingAudioOutput()
        val pcm = ShortArray(960) { 42 }
        output.stop()
        assertFailsWith<IllegalStateException> { output.write(pcm) }
        output.start()
        assertFailsWith<IllegalStateException> { output.start() }
        output.write(pcm)
        pcm.fill(0)
        assertContentEquals(ShortArray(960) { 42 }, output.frames.single())
        output.stop()
        output.stop()
        assertFailsWith<IllegalStateException> { output.write(pcm) }
        output.start()
        output.write(pcm)
        assertEquals(2, output.frames.size)
        output.stop()
    }

    @Test
    fun fakesRejectPartialFrames() {
        val input = FakeAudioInput { ShortArray(959) }
        val output = CapturingAudioOutput()
        input.start()
        output.start()
        try {
            assertFailsWith<IllegalArgumentException> { input.read() }
            assertFailsWith<IllegalArgumentException> { output.write(ShortArray(961)) }
        } finally {
            input.stop()
            output.stop()
        }
    }
}
