package dev.junepark.oniontalkie.tor

import dev.junepark.oniontalkie.identity.Identity
import dev.junepark.oniontalkie.identity.KEY
import dev.junepark.oniontalkie.identity.MemoryStorage
import dev.junepark.oniontalkie.identity.ONION
import dev.junepark.oniontalkie.identity.OTHER_KEY
import dev.junepark.oniontalkie.identity.OTHER_ONION
import dev.junepark.oniontalkie.identity.decodeBase32
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal const val SERVICE_KEY = "ED25519-V3:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0+Pw=="
// RFC 7748 section 6.1, encoded independently using Python base64.b32encode.
internal const val ALICE_PRIVATE = "O4DW2CTTDCSX2PAWYFZFDMTGIXPUYL4H5PAJSKVRO752KHNZFQVA"
internal const val ALICE_PUBLIC = "QUQPACMJGCTVI5ELPXOLIPXXLIG36OQNEY4BV5HLUSUY5KU3JZVA"

internal fun serviceIdentity(key: String? = SERVICE_KEY) = Identity(ONION, ALICE_PUBLIC, ALICE_PRIVATE, "June", key)

class OnionKeysTest {
    @Test fun commandsUseV3AuthLoopbackAndOneArgumentPerPeer() {
        assertEquals(
            "ADD_ONION NEW:ED25519-V3 Flags=V3Auth Port=9999,127.0.0.1:12345 ClientAuthV3=$KEY ClientAuthV3=$OTHER_KEY",
            addOnionCommand("NEW:ED25519-V3", 12345, listOf(PeerAuthKey(KEY.lowercase()), PeerAuthKey(OTHER_KEY))),
        )
        val reused = addOnionCommand(SERVICE_KEY, 65535, listOf(PeerAuthKey(KEY)))
        assertEquals("ADD_ONION $SERVICE_KEY Flags=V3Auth Port=9999,127.0.0.1:65535 ClientAuthV3=$KEY", reused)
        assertFalse(reused.contains("Detach"))
        assertFalse(reused.contains("DiscardPK"))
        assertFalse(reused.contains('\n'))
        assertFalse(reused.contains('\r'))
    }

    @Test fun malformedKeysAndPortsNeverEnterCommands() {
        for (key in listOf(KEY + "=", KEY.dropLast(1), KEY.dropLast(1) + "R", "ſ" + KEY.drop(1), KEY + "\r\nQUIT")) {
            assertFailsWith<IllegalArgumentException> { PeerAuthKey(key) }
        }
        for (port in listOf(-1, 0, 65536)) {
            assertFailsWith<IllegalArgumentException> { addOnionCommand(SERVICE_KEY, port, listOf(PeerAuthKey(KEY))) }
        }
        assertFailsWith<IllegalArgumentException> { addOnionCommand(SERVICE_KEY, 1234, emptyList()) }
        for (key in listOf("RSA1024:abc", "ED25519-V3:abc", SERVICE_KEY + " Port=22", SERVICE_KEY + "\nQUIT", SERVICE_KEY.dropLast(3) + "x==")) {
            assertFailsWith<IllegalArgumentException> { addOnionCommand(key, 1234, listOf(PeerAuthKey(KEY))) }
        }
        validateServiceKey(SERVICE_KEY.trimEnd('='))
    }

    @Test fun rawX25519KeysMatchRfc7748AndCanonicalBase32() {
        assertEquals(ALICE_PUBLIC, clientAuthPublicKey(ALICE_PRIVATE))
        assertEquals(ALICE_PUBLIC, clientAuthPublicKey(ALICE_PRIVATE.lowercase()))
        val raw = ByteArray(32) { it.toByte() }
        assertEquals(KEY, encodeBase32(raw))
        assertContentEquals(raw, decodeBase32(KEY))
        assertEquals("A".repeat(52), encodeBase32(ByteArray(32)))
        assertEquals("7".repeat(51) + "Q", encodeBase32(ByteArray(32) { -1 }))
        val first = generateClientAuthKeyPair()
        val second = generateClientAuthKeyPair()
        assertNotEquals(first.privateKey, second.privateKey)
        assertEquals(32, decodeBase32(first.privateKey)?.size)
        assertEquals(32, decodeBase32(first.publicKey)?.size)
        assertEquals(first.publicKey, clientAuthPublicKey(first.privateKey))
        assertFalse(first.toString().contains(first.privateKey))
    }

    @Test fun authPrivateFilesContainOurPrivateHalfForEachPeer() = runTest {
        val storage = MemoryStorage()
        val auth = ClientOnionAuth(storage)
        val identity = serviceIdentity()
        for (peer in listOf(ONION, OTHER_ONION)) auth.writeForPeer(peer, identity)
        for (peer in listOf(ONION, OTHER_ONION)) {
            val stem = peer.removeSuffix(".onion")
            assertEquals("$stem:descriptor:x25519:$ALICE_PRIVATE\n", storage.files["$stem.auth_private"])
        }
        assertFailsWith<IllegalArgumentException> { auth.writeForPeer("../bad.onion", identity) }
        assertEquals(2, storage.files.size)
        assertFalse(identity.pairingPayload().encode().contains(SERVICE_KEY))
        assertFalse(identity.toString().contains(SERVICE_KEY))
        assertTrue(identity.pairingPayload().encode().contains(ALICE_PUBLIC))
    }
}
