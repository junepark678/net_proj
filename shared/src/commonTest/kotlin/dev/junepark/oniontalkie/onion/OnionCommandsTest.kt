package dev.junepark.oniontalkie.onion

import dev.junepark.oniontalkie.identity.KEY
import dev.junepark.oniontalkie.identity.ONION
import dev.junepark.oniontalkie.identity.OTHER_KEY
import dev.junepark.oniontalkie.identity.decodeBase32
import dev.junepark.oniontalkie.identity.encodeBase32
import dev.junepark.oniontalkie.tor.TorReply
import dev.junepark.oniontalkie.tor.TorReplyLine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal val SERVICE_KEY = "ED25519-V3:" + "A".repeat(86) + "=="
internal val SERVICE_ID = ONION.removeSuffix(".onion")

class OnionCommandsTest {
    private val peers = listOf(PeerAuthKey(OTHER_KEY), PeerAuthKey(KEY.lowercase()))

    @Test fun firstRunRequestsANewKeyAndKeepsItSecretOnlyForTor() {
        assertEquals(
            "ADD_ONION NEW:ED25519-V3 Port=9999,127.0.0.1:4242 ClientAuthV3=$KEY ClientAuthV3=$OTHER_KEY",
            formatAddOnion(null, peers, 4242),
        )
    }

    @Test fun laterRunsReuseTheBlobAndDiscardTheEchoedKey() {
        assertEquals(
            "ADD_ONION $SERVICE_KEY Flags=DiscardPK Port=9999,127.0.0.1:4242 ClientAuthV3=$KEY",
            formatAddOnion(SERVICE_KEY, listOf(PeerAuthKey(KEY), PeerAuthKey(KEY.lowercase())), 4242),
        )
    }

    @Test fun refusesAnUnauthenticatedServiceAndMalformedInputs() {
        assertFailsWith<IllegalArgumentException> { formatAddOnion(null, emptyList(), 4242) }
        assertFailsWith<IllegalArgumentException> { formatAddOnion("ED25519-V3:x\r\nGETINFO", peers, 4242) }
        assertFailsWith<IllegalArgumentException> { formatAddOnion(null, peers, 0) }
        assertFailsWith<IllegalArgumentException> { PeerAuthKey("short") }
        assertFailsWith<IllegalArgumentException> { PeerAuthKey("$KEY Flags=Detach") }
        assertFalse(validServiceKeyBlob("ED25519-V3:" + "A".repeat(87)))
        assertFalse(validServiceKeyBlob("RSA1024:" + "A".repeat(86) + "=="))
    }

    @Test fun delOnionNeedsAChecksummedServiceId() {
        assertEquals("DEL_ONION $SERVICE_ID", formatDelOnion(SERVICE_ID))
        assertFailsWith<IllegalArgumentException> { formatDelOnion("$SERVICE_ID EXTRA") }
        assertFailsWith<IllegalArgumentException> { formatDelOnion("a".repeat(56)) }
    }

    @Test fun parsesServiceIdAndPrivateKeyAndRedactsThem() {
        val reply = TorReply(250, listOf(
            TorReplyLine("ServiceID=$SERVICE_ID"), TorReplyLine("PrivateKey=$SERVICE_KEY"), TorReplyLine("OK"),
        ))
        val result = parseAddOnionReply(reply)
        assertEquals(ONION, result.onion)
        assertEquals(SERVICE_KEY, result.privateKey)
        assertFalse(result.toString().contains(SERVICE_KEY))
        val discarded = parseAddOnionReply(TorReply(250, listOf(TorReplyLine("ServiceID=$SERVICE_ID"), TorReplyLine("OK"))))
        assertNull(discarded.privateKey)
        assertFailsWith<IllegalStateException> { parseAddOnionReply(TorReply(250, listOf(TorReplyLine("OK")))) }
        assertFailsWith<IllegalStateException> {
            parseAddOnionReply(TorReply(250, listOf(TorReplyLine("ServiceID=$SERVICE_ID"), TorReplyLine("PrivateKey=junk"))))
        }
    }

    @Test fun clientAuthFileMatchesTorFormat() {
        assertEquals("$SERVICE_ID.auth_private", clientAuthFileName(ONION))
        assertEquals("$SERVICE_ID.auth_private", clientAuthFileName(SERVICE_ID))
        assertEquals("$SERVICE_ID:descriptor:x25519:$OTHER_KEY\n", clientAuthFileContents(ONION, OTHER_KEY.lowercase()))
        assertFailsWith<IllegalArgumentException> { clientAuthFileName("../../etc/passwd") }
        assertFailsWith<IllegalArgumentException> { clientAuthFileContents(ONION, "short") }
    }

    @Test fun base32EncodingMatchesRfc4648AndRoundTrips() {
        assertEquals("", encodeBase32(ByteArray(0)))
        assertEquals("MY", encodeBase32("f".encodeToByteArray()))
        assertEquals("MZXQ", encodeBase32("fo".encodeToByteArray()))
        assertEquals("MZXW6YTBOI", encodeBase32("foobar".encodeToByteArray()))
        val bytes = ByteArray(32) { (it * 37 + 5).toByte() }
        val text = encodeBase32(bytes)
        assertEquals(52, text.length)
        assertContentEquals(bytes, decodeBase32(text))
        assertTrue(text.all { it in 'A'..'Z' || it in '2'..'7' })
    }
}
