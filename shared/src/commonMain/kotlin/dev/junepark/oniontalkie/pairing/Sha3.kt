package dev.junepark.oniontalkie.pairing

/** Minimal SHA3-256 (FIPS 202); `commonMain` has no JDK `MessageDigest`, and rend-spec-v3 needs it. */
internal object Sha3 {
    private const val RATE = 136 // bytes: (1600 - 2 * 256) / 8

    private val ROUND_CONSTANTS = listOf(
        0x0000000000000001uL, 0x0000000000008082uL, 0x800000000000808AuL, 0x8000000080008000uL,
        0x000000000000808BuL, 0x0000000080000001uL, 0x8000000080008081uL, 0x8000000000008009uL,
        0x000000000000008AuL, 0x0000000000000088uL, 0x0000000080008009uL, 0x000000008000000AuL,
        0x000000008000808BuL, 0x800000000000008BuL, 0x8000000000008089uL, 0x8000000000008003uL,
        0x8000000000008002uL, 0x8000000000000080uL, 0x000000000000800AuL, 0x800000008000000AuL,
        0x8000000080008081uL, 0x8000000000008080uL, 0x0000000080000001uL, 0x8000000080008008uL,
    ).map { it.toLong() }.toLongArray()

    private val ROTATIONS = intArrayOf(
        1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14, 27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44,
    )
    private val LANES = intArrayOf(
        10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4, 15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1,
    )

    /** Returns the 32-byte SHA3-256 digest of [input]. */
    fun sha3256(input: ByteArray): ByteArray {
        val state = LongArray(25)
        // pad10*1 with the SHA-3 domain bits (0x06 ... 0x80) up to a multiple of the rate
        val padded = ByteArray((input.size / RATE + 1) * RATE)
        input.copyInto(padded)
        padded[input.size] = 0x06
        padded[padded.size - 1] = (padded[padded.size - 1].toInt() or 0x80).toByte()

        for (block in 0 until padded.size / RATE) {
            for (lane in 0 until RATE / 8) {
                var word = 0L
                for (i in 7 downTo 0) {
                    word = (word shl 8) or (padded[block * RATE + lane * 8 + i].toLong() and 0xFF)
                }
                state[lane] = state[lane] xor word
            }
            keccakF(state)
        }

        val out = ByteArray(32)
        for (i in out.indices) out[i] = (state[i / 8] ushr (8 * (i % 8))).toByte()
        return out
    }

    private fun keccakF(st: LongArray) {
        val bc = LongArray(5)
        for (round in 0 until 24) {
            for (i in 0 until 5) bc[i] = st[i] xor st[i + 5] xor st[i + 10] xor st[i + 15] xor st[i + 20]
            for (i in 0 until 5) {
                val t = bc[(i + 4) % 5] xor bc[(i + 1) % 5].rotateLeft(1)
                for (j in 0 until 25 step 5) st[j + i] = st[j + i] xor t
            }
            var t = st[1]
            for (i in 0 until 24) {
                val j = LANES[i]
                val next = st[j]
                st[j] = t.rotateLeft(ROTATIONS[i])
                t = next
            }
            for (j in 0 until 25 step 5) {
                for (i in 0 until 5) bc[i] = st[j + i]
                for (i in 0 until 5) st[j + i] = st[j + i] xor (bc[(i + 1) % 5].inv() and bc[(i + 2) % 5])
            }
            st[0] = st[0] xor ROUND_CONSTANTS[round]
        }
    }
}
