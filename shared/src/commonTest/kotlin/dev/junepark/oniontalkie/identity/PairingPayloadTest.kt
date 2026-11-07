package dev.junepark.oniontalkie.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal const val ONION = "aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dyp3kead.onion"
internal const val OTHER_ONION = "aebagbafaydqqcikbmga2dqpcaireeyuculbogazdinryhi6d4qcmeqd.onion"
internal const val KEY = "AAAQEAYEAUDAOCAJBIFQYDIOB4IBCEQTCQKRMFYYDENBWHA5DYPQ"
internal const val OTHER_KEY = "AEBAGBAFAYDQQCIKBMGA2DQPCAIREEYUCULBOGAZDINRYHI6D4QA"
internal fun payload(name: String = "June") = PairingPayload(ONION, KEY, name)

class PairingPayloadTest {
    @Test
    fun roundTripPreservesUnicodeAndSpaces() {
        val original = payload("준의 전화 📞")
        assertEquals("onion-talkie:v1;$ONION;$KEY;준의 전화 📞", original.encode())
        assertEquals(original, assertIs<PairingResult.Valid>(PairingPayload.parse(original.encode())).payload)
        assertTrue(original.encode().length <= 200)
    }

    @Test
    fun independentOnionVectorsAreAccepted() {
        // Fixtures generated independently with Python hashlib.sha3_256 and base64.b32encode.
        assertIs<PairingResult.Valid>(PairingPayload.parse(payload().encode()))
        assertIs<PairingResult.Valid>(PairingPayload.parse(PairingPayload(OTHER_ONION, OTHER_KEY, "Peer").encode()))
    }

    @Test
    fun tamperedChecksumAndVersionAreRejected() {
        val checksumChanged = ONION.replaceRange(52, 53, if (ONION[52] == 'a') "b" else "a")
        invalid("onion-talkie:v1;$checksumChanged;$KEY;June", PairingError.Onion)
        invalid("onion-talkie:v1;${ONION.replaceRange(55, 56, "e")};$KEY;June", PairingError.Onion)
        invalid("onion-talkie:v1;${"a".repeat(16)}.onion;$KEY;June", PairingError.Onion)
        invalid("onion-talkie:v1;${ONION.uppercase()};$KEY;June", PairingError.Onion)
    }

    @Test
    fun keyMustBeExactly32CanonicalBase32Bytes() {
        for (key in listOf(KEY.dropLast(1), KEY + "A", KEY + "====", KEY.dropLast(1) + "R", KEY.replace('K', '\u212A'), "0".repeat(52))) {
            invalid("onion-talkie:v1;$ONION;$key;June", PairingError.ClientAuthKey)
        }
        val lower = PairingPayload(ONION, KEY.lowercase(), "Peer")
        assertEquals(lower, assertIs<PairingResult.Valid>(PairingPayload.parse(lower.encode())).payload)
    }

    @Test
    fun framingAndDisplayNamesAreStrict() {
        invalid("", PairingError.Format)
        invalid(payload().encode().replace("v1;", "v2;"), PairingError.Version)
        invalid(payload().encode().replace("onion-talkie:", "other:"), PairingError.Format)
        invalid(payload().encode() + ";extra", PairingError.Format)
        for (name in listOf("", "  ", "new\nline", "tab\tname", "\u0000", "\uD800")) {
            invalid("onion-talkie:v1;$ONION;$KEY;$name", PairingError.DisplayName)
        }
        assertFailsWith<IllegalArgumentException> { payload("semi;colon") }
        assertFailsWith<IllegalArgumentException> { PairingPayload(ONION, KEY, "June", version = 2) }
    }

    @Test
    fun lengthBoundaryIncludesUtf8ByteBudget() {
        val budget = 200 - payload("x").encode().length + 1
        val maximum = payload("x".repeat(budget))
        assertEquals(200, maximum.encode().length)
        assertIs<PairingResult.Valid>(PairingPayload.parse(maximum.encode()))
        invalid(maximum.encode() + "x", PairingError.TooLong)
        invalid("onion-talkie:v1;$ONION;$KEY;${"준".repeat(budget)}", PairingError.TooLong)
        assertFailsWith<IllegalArgumentException> { payload("x".repeat(budget + 1)) }
    }

    private fun invalid(text: String, reason: PairingError) {
        assertEquals(reason, assertIs<PairingResult.Invalid>(PairingPayload.parse(text)).reason)
    }
}
