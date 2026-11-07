package dev.junepark.oniontalkie.onion

import dev.junepark.oniontalkie.identity.encodeBase32
import dev.junepark.oniontalkie.tor.secureControlNonce

/** An x25519 client-authorization keypair, both halves as unpadded uppercase base32 of 32 bytes. */
class ClientAuthKeyPair(val publicKey: String, val privateKey: String) {
    override fun toString() = "ClientAuthKeyPair(publicKey=$publicKey, privateKey=<redacted>)"

    companion object {
        /** Generates a fresh keypair from the platform's secure random source. */
        fun generate(): ClientAuthKeyPair = fromPrivate(secureControlNonce())

        /** Derives the pair from 32 secret bytes, clamping them as RFC 7748 requires. */
        fun fromPrivate(secret: ByteArray): ClientAuthKeyPair {
            require(secret.size == 32) { "An x25519 private key is 32 bytes" }
            val clamped = X25519.clamp(secret)
            return ClientAuthKeyPair(encodeBase32(X25519.publicKey(clamped)), encodeBase32(clamped))
        }
    }
}

/**
 * RFC 7748 x25519 scalar multiplication, ported from TweetNaCl's field arithmetic (16 limbs of
 * 16 bits). Common code cannot reach JDK key agreement, and Android's provider only gained
 * X25519 in API 33. Only the Montgomery ladder with a fixed base point is exposed.
 */
internal object X25519 {
    private val BASE = ByteArray(32).also { it[0] = 9 }

    fun clamp(secret: ByteArray): ByteArray = secret.copyOf().also {
        it[0] = (it[0].toInt() and 248).toByte()
        it[31] = ((it[31].toInt() and 127) or 64).toByte()
    }

    fun publicKey(secret: ByteArray): ByteArray = scalarMult(secret, BASE)

    fun scalarMult(secret: ByteArray, point: ByteArray): ByteArray {
        require(secret.size == 32 && point.size == 32)
        val z = clamp(secret)
        val x = unpack(point)
        val a = LongArray(16).also { it[0] = 1 }
        val b = x.copyOf()
        val c = LongArray(16)
        val d = LongArray(16).also { it[0] = 1 }
        val e = LongArray(16)
        val f = LongArray(16)
        for (i in 254 downTo 0) {
            val r = (z[i ushr 3].toInt() ushr (i and 7)) and 1
            select(a, b, r); select(c, d, r)
            add(e, a, c); sub(a, a, c); add(c, b, d); sub(b, b, d)
            mul(d, e, e); mul(f, a, a); mul(a, c, a); mul(c, b, e)
            add(e, a, c); sub(a, a, c); mul(b, a, a); sub(c, d, f)
            mul(a, c, A24); add(a, a, d); mul(c, c, a); mul(a, d, f)
            mul(d, b, x); mul(b, e, e)
            select(a, b, r); select(c, d, r)
        }
        invert(c, c)
        mul(a, a, c)
        return pack(a)
    }

    private val A24 = LongArray(16).also { it[0] = 0xDB41; it[1] = 1 }

    private fun carry(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            if (i < 15) o[i + 1] += c - 1 else o[0] += 38 * (c - 1)
            o[i] -= c shl 16
        }
    }

    private fun select(p: LongArray, q: LongArray, bit: Int) {
        val mask = (-bit).toLong()
        for (i in 0 until 16) {
            val t = mask and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    private fun pack(n: LongArray): ByteArray {
        val t = n.copyOf()
        val m = LongArray(16)
        carry(t); carry(t); carry(t)
        repeat(2) {
            m[0] = t[0] - 0xffed
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
                m[i - 1] = m[i - 1] and 0xffff
            }
            m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
            val b = ((m[15] shr 16) and 1).toInt()
            m[14] = m[14] and 0xffff
            select(t, m, 1 - b)
        }
        return ByteArray(32) { i -> (if (i % 2 == 0) t[i / 2] else t[i / 2] shr 8).toByte() }
    }

    private fun unpack(n: ByteArray): LongArray {
        val o = LongArray(16) { (n[2 * it].toLong() and 0xff) + ((n[2 * it + 1].toLong() and 0xff) shl 8) }
        o[15] = o[15] and 0x7fff
        return o
    }

    private fun add(o: LongArray, a: LongArray, b: LongArray) { for (i in 0 until 16) o[i] = a[i] + b[i] }
    private fun sub(o: LongArray, a: LongArray, b: LongArray) { for (i in 0 until 16) o[i] = a[i] - b[i] }

    private fun mul(o: LongArray, a: LongArray, b: LongArray) {
        val t = LongArray(31)
        for (i in 0 until 16) for (j in 0 until 16) t[i + j] += a[i] * b[j]
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        for (i in 0 until 16) o[i] = t[i]
        carry(o); carry(o)
    }

    private fun invert(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 253 downTo 0) {
            mul(c, c, c)
            if (a != 2 && a != 4) mul(c, c, i)
        }
        c.copyInto(o)
    }
}
