package dev.junepark.oniontalkie.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PairingPayloadTest {
    private val onion = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad"
    private val key = ByteArray(32) { (it * 7 + 1).toByte() }
    private val keyText = Base32.encode(key)

    private fun failure(text: String): PairingError =
        assertIs<PairingParseResult.Failure>(PairingPayload.parse(text)).error

    @Test
    fun roundTrip() {
        val payload = PairingPayload(onion, key, "June's phone")
        val text = payload.encode()
        assertEquals("onion-talkie:v1;$onion.onion;$keyText;June's phone", text)
        val parsed = assertIs<PairingParseResult.Success>(PairingPayload.parse(text)).payload
        assertEquals(payload, parsed)
        assertEquals(1, parsed.version)
    }

    @Test
    fun longestPayloadStaysWithinLimit() {
        val name = "가".repeat(PairingPayload.MAX_DISPLAY_NAME)
        val text = PairingPayload(onion, key, name).encode()
        assertTrue(text.length <= PairingPayload.MAX_LENGTH, "length ${text.length}")
        assertEquals(name, assertIs<PairingParseResult.Success>(PairingPayload.parse(text)).payload.displayName)
    }

    @Test
    fun displayNameMayContainSemicolons() {
        val text = PairingPayload(onion, key, "a;b;c").encode()
        assertEquals("a;b;c", assertIs<PairingParseResult.Success>(PairingPayload.parse(text)).payload.displayName)
    }

    @Test
    fun upperCaseOnionAndKeyAreAccepted() {
        val text = "onion-talkie:v1;${onion.uppercase()}.onion;${keyText.lowercase()};X"
        assertEquals(onion, assertIs<PairingParseResult.Success>(PairingPayload.parse(text)).payload.onion)
    }

    @Test
    fun tamperedChecksumIsRejected() {
        // Flip one character inside the public-key part and one inside the checksum part.
        for (i in listOf(10, 52)) {
            val flipped = if (onion[i] == 'a') 'b' else 'a'
            val bad = onion.substring(0, i) + flipped + onion.substring(i + 1)
            assertEquals(PairingError.BAD_ONION_CHECKSUM, failure("onion-talkie:v1;$bad.onion;$keyText;X"), "index $i")
        }
    }

    @Test
    fun malformedOnionIsRejected() {
        assertEquals(PairingError.INVALID_ONION, failure("onion-talkie:v1;${onion.drop(1)}.onion;$keyText;X"))
        assertEquals(PairingError.INVALID_ONION, failure("onion-talkie:v1;${onion.dropLast(1)}1.onion;$keyText;X"))
    }

    @Test
    fun badKeysAreRejected() {
        assertEquals(PairingError.INVALID_KEY, failure("onion-talkie:v1;$onion.onion;${keyText.dropLast(1)};X"))
        assertEquals(PairingError.INVALID_KEY, failure("onion-talkie:v1;$onion.onion;${keyText}AA;X"))
        assertEquals(PairingError.INVALID_KEY, failure("onion-talkie:v1;$onion.onion;${keyText.dropLast(1)}!;X"))
    }

    @Test
    fun structuralProblemsAreRejected() {
        assertEquals(PairingError.NOT_A_PAIRING_PAYLOAD, failure("https://example.com"))
        assertEquals(PairingError.UNSUPPORTED_VERSION, failure("onion-talkie:v2;$onion.onion;$keyText;X"))
        assertEquals(PairingError.MALFORMED, failure("onion-talkie:v1;$onion.onion;$keyText"))
        assertEquals(PairingError.MALFORMED, failure("onion-talkie:v1;$onion;$keyText;X"))
        assertEquals(PairingError.MALFORMED, failure("onion-talkie:v1;$onion.onion;$keyText;" + "x".repeat(70)))
        assertEquals(PairingError.INVALID_DISPLAY_NAME, failure("onion-talkie:v1;$onion.onion;$keyText;"))
        assertEquals(PairingError.INVALID_DISPLAY_NAME, failure("onion-talkie:v1;$onion.onion;$keyText;a\nb"))
        assertEquals(PairingError.INVALID_DISPLAY_NAME, failure("onion-talkie:v1;$onion.onion;$keyText; padded"))
    }

    @Test
    fun constructorRejectsInvalidFields() {
        assertFailsWith<IllegalArgumentException> { PairingPayload(onion, ByteArray(31), "X") }
        assertFailsWith<IllegalArgumentException> { PairingPayload(onion, key, "") }
        assertFailsWith<IllegalArgumentException> { PairingPayload("a".repeat(56), key, "X") }
    }

    @Test
    fun generatedOnionFromPublicKeyValidates() {
        val host = OnionAddress.fromPublicKey(ByteArray(32) { it.toByte() })
        assertEquals(OnionAddress.Check.VALID, OnionAddress.check(host))
        assertIs<PairingParseResult.Success>(PairingPayload.parse(PairingPayload(host, key, "X").encode()))
    }
}
