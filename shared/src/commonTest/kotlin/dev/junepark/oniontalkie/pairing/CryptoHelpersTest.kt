package dev.junepark.oniontalkie.pairing

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CryptoHelpersTest {
    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun sha3256MatchesKnownVectors() {
        assertEquals("a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a", hex(Sha3.sha3256(ByteArray(0))))
        assertEquals("3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532", hex(Sha3.sha3256("abc".encodeToByteArray())))
        // 200 bytes spans two 136-byte blocks; vector from Python hashlib.sha3_256
        assertEquals(
            "cce34485baf2bf2aca99b94833892a4f52896d3d153f7b840cc4f9fe695f1387",
            hex(Sha3.sha3256(ByteArray(200) { 'a'.code.toByte() })),
        )
        // exactly one rate block of input forces an extra padding block
        assertEquals(32, Sha3.sha3256(ByteArray(136)).size)
    }

    @Test
    fun base32RoundTripsAndRejectsNonCanonicalInput() {
        assertEquals("MZXW6YTBOI", Base32.encode("foobar".encodeToByteArray()))
        assertContentEquals("foobar".encodeToByteArray(), Base32.decode("mzxw6ytboi"))
        val key = ByteArray(32) { it.toByte() }
        assertEquals(52, Base32.encode(key).length)
        assertContentEquals(key, Base32.decode(Base32.encode(key)))
        assertNull(Base32.decode("MZXW6YTBO1")) // '1' is not in the alphabet
        assertNull(Base32.decode("MZXW6YTBOJ")) // non-zero trailing bits
    }

    @Test
    fun realOnionAddressesPassTheChecksum() {
        // Published v3 addresses (DuckDuckGo, Tor Project); checksums cross-checked with Python hashlib.
        assertEquals(OnionAddress.Check.VALID, OnionAddress.check("duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad"))
        assertEquals(OnionAddress.Check.VALID, OnionAddress.check("2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid"))
    }
}
