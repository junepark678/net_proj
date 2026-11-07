package dev.junepark.oniontalkie.identity

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha3Test {
    @Test
    fun independentDigestVectorsCoverPaddingAndMultipleBlocks() {
        val vectors = mapOf(
            0 to "a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a",
            135 to "fded8fd9d6551c601eeb3b7c6bc5e5cfd8aad1d015b7e9aaa9c9b9475231d5e2",
            136 to "cf3ccff92480a29160c2d38317c430e14749bfee1788106957dfe73f8c4930e5",
            137 to "ce9d7dc90913ee5d92745019479a5352c6d6279bef18ed07dc0a83ee8084daca",
            300 to "815c06bbeb8520ce61add33a5f47bc558bf00e6361a5640c972d5d4634c58101",
        )
        for ((size, expected) in vectors) {
            val digest = sha3_256(ByteArray(size) { it.toByte() })
            assertEquals(expected, digest.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') })
        }
    }
}
