package dev.junepark.oniontalkie.identity

// FIPS 202 SHA3-256, used only for the public onion-address checksum. Pure Kotlin so Android
// API 26 does not depend on a platform SHA3 provider. Lane index is x + 5*y, little endian.
internal fun sha3_256(input: ByteArray): ByteArray {
    val rate = 136
    val state = LongArray(25)
    val padded = input.copyOf((input.size / rate + 1) * rate)
    padded[input.size] = 0x06
    padded[padded.lastIndex] = (padded.last().toInt() or 0x80).toByte()
    for (offset in padded.indices step rate) {
        for (i in 0 until rate) {
            state[i / 8] = state[i / 8] xor ((padded[offset + i].toLong() and 255) shl (8 * (i % 8)))
        }
        keccak(state)
    }
    return ByteArray(32) { (state[it / 8] ushr (8 * (it % 8))).toByte() }
}

private fun keccak(a: LongArray) {
    val rotations = intArrayOf(
        0, 1, 62, 28, 27, 36, 44, 6, 55, 20, 3, 10, 43, 25, 39,
        41, 45, 15, 21, 8, 18, 2, 61, 56, 14,
    )
    val constants = longArrayOf(
        0x0000000000000001L, 0x0000000000008082L, 0x800000000000808aUL.toLong(),
        0x8000000080008000UL.toLong(), 0x000000000000808bL, 0x0000000080000001L,
        0x8000000080008081UL.toLong(), 0x8000000000008009UL.toLong(), 0x000000000000008aL,
        0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
        0x000000008000808bL, 0x800000000000008bUL.toLong(), 0x8000000000008089UL.toLong(),
        0x8000000000008003UL.toLong(), 0x8000000000008002UL.toLong(), 0x8000000000000080UL.toLong(),
        0x000000000000800aL, 0x800000008000000aUL.toLong(), 0x8000000080008081UL.toLong(),
        0x8000000000008080UL.toLong(), 0x0000000080000001L, 0x8000000080008008UL.toLong(),
    )
    val c = LongArray(5)
    val b = LongArray(25)
    for (constant in constants) {
        for (x in 0..4) c[x] = a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20]
        for (x in 0..4) {
            val d = c[(x + 4) % 5] xor c[(x + 1) % 5].rotateLeft(1)
            for (y in 0..4) a[x + 5 * y] = a[x + 5 * y] xor d
        }
        for (x in 0..4) for (y in 0..4) {
            b[y + 5 * ((2 * x + 3 * y) % 5)] = a[x + 5 * y].rotateLeft(rotations[x + 5 * y])
        }
        for (x in 0..4) for (y in 0..4) {
            a[x + 5 * y] = b[x + 5 * y] xor (b[(x + 1) % 5 + 5 * y].inv() and b[(x + 2) % 5 + 5 * y])
        }
        a[0] = a[0] xor constant
    }
}
