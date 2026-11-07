package dev.junepark.oniontalkie.onion

import dev.junepark.oniontalkie.identity.decodeBase32
import dev.junepark.oniontalkie.identity.encodeBase32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class X25519Test {
    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test fun rfc7748ScalarMultiplicationVectors() {
        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            X25519.scalarMult(
                hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"),
            ).hex(),
        )
        assertEquals(
            "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957",
            X25519.scalarMult(
                hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"),
                hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"),
            ).hex(),
        )
    }

    @Test fun rfc7748DiffieHellmanPublicKeys() {
        assertEquals(
            "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a",
            X25519.publicKey(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")).hex(),
        )
        assertEquals(
            "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f",
            X25519.publicKey(hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")).hex(),
        )
    }

    @Test fun generatedPairsAreValidBase32AndSecretsStayOutOfToString() {
        val a = ClientAuthKeyPair.generate()
        val b = ClientAuthKeyPair.generate()
        assertEquals(32, decodeBase32(a.publicKey)?.size)
        assertEquals(32, decodeBase32(a.privateKey)?.size)
        assertFalse(a.publicKey == b.publicKey)
        assertFalse(a.toString().contains(a.privateKey))
        val fixed = ClientAuthKeyPair.fromPrivate(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))
        assertEquals(encodeBase32(hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")), fixed.publicKey)
    }
}
