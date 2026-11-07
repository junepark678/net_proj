package dev.junepark.oniontalkie.tor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Canned-transcript transport: [respond] maps each written command to the lines Tor would send. */
private class ScriptedTransport(private val respond: (String) -> List<String>) : ControlTransport {
    val incoming = Channel<String?>(Channel.UNLIMITED)
    val written = mutableListOf<String>()
    var closed = false

    override suspend fun readLine(): String? = incoming.receive()

    override suspend fun writeLine(line: String) {
        written += line
        respond(line).forEach { incoming.trySend(it) }
    }

    override fun close() {
        closed = true
        incoming.trySend(null)
    }

    fun push(vararg lines: String) = lines.forEach { incoming.trySend(it) }
}

class TorControlTest {
    private val cookie = ByteArray(32) { it.toByte() }
    private val cookieHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

    // Vectors computed independently with Python's hmac/hashlib for the fixed nonces below.
    private val serverHash = "7780ba467c566548b61da5b927043df643cc6fb18e6258718f47591a09909213"
    private val clientHash = "ecd5845c1ed9b11354fed620d989e9f34243606e0f7f97c0f3c8a9303a06d468"
    private val clientNonceHex = "aa".repeat(32)
    private val serverNonceHex = "bb".repeat(32)

    /** Random whose 32 bytes are all 0xaa so the client nonce is deterministic. */
    private val fixedRandom = object : Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextBytes(array: ByteArray): ByteArray = array.also { it.fill(0xaa.toByte()) }
    }

    private fun TestScope.control(transport: ControlTransport) = TorControl(transport, backgroundScope, fixedRandom)

    @Test
    fun protocolInfoParsesMethodsAndCookieFile() = runTest {
        val t = ScriptedTransport {
            listOf(
                "250-PROTOCOLINFO 1",
                "250-AUTH METHODS=COOKIE,SAFECOOKIE,HASHEDPASSWORD COOKIEFILE=\"/var/lib/tor/control auth cookie\"",
                "250-VERSION Tor=\"0.4.8.9\"",
                "250 OK",
            )
        }
        val info = control(t).protocolInfo()
        assertEquals(setOf("COOKIE", "SAFECOOKIE", "HASHEDPASSWORD"), info.methods)
        assertEquals("/var/lib/tor/control auth cookie", info.cookieFile)
        assertEquals(listOf("PROTOCOLINFO 1"), t.written)
    }

    @Test
    fun safeCookieHandshake() = runTest {
        val t = ScriptedTransport { cmd ->
            when {
                cmd.startsWith("AUTHCHALLENGE SAFECOOKIE $clientNonceHex") ->
                    listOf("250 AUTHCHALLENGE SERVERHASH=$serverHash SERVERNONCE=$serverNonceHex")
                cmd == "AUTHENTICATE $clientHash" -> listOf("250 OK")
                else -> listOf("515 Authentication failed")
            }
        }
        control(t).authenticate(cookie, setOf("COOKIE", "SAFECOOKIE"))
        assertEquals(2, t.written.size)
    }

    @Test
    fun safeCookieRejectsForgedServerHash() = runTest {
        val t = ScriptedTransport {
            listOf("250 AUTHCHALLENGE SERVERHASH=${"00".repeat(32)} SERVERNONCE=$serverNonceHex")
        }
        assertFailsWith<TorControlException> { control(t).authenticate(cookie, setOf("SAFECOOKIE")) }
        assertEquals(1, t.written.size) // never sent AUTHENTICATE
    }

    @Test
    fun plainCookieAuthentication() = runTest {
        val t = ScriptedTransport { if (it == "AUTHENTICATE $cookieHex") listOf("250 OK") else listOf("515 bad") }
        control(t).authenticate(cookie, setOf("COOKIE", "HASHEDPASSWORD"))
        assertEquals(listOf("AUTHENTICATE $cookieHex"), t.written)
    }

    @Test
    fun authenticationFailureMapsToCommandException() = runTest {
        val t = ScriptedTransport { listOf("515 Authentication failed: Wrong length on authentication cookie.") }
        val e = assertFailsWith<TorCommandException> { control(t).authenticate(cookie, setOf("COOKIE")) }
        assertEquals(515, e.status)
    }

    @Test
    fun noUsableAuthMethod() = runTest {
        assertFailsWith<TorControlException> { control(ScriptedTransport { emptyList() }).authenticate(cookie, setOf("NULL")) }
    }

    @Test
    fun getInfoSingleLineAndDataBlock() = runTest {
        val t = ScriptedTransport { cmd ->
            when (cmd) {
                "GETINFO version" -> listOf("250-version=0.4.8.9 (git-abc)", "250 OK")
                "GETINFO config-text" -> listOf("250+config-text=", "SocksPort 9050", "..hidden", ".", "250 OK")
                "GETINFO nope" -> listOf("552 Unrecognized key \"nope\"")
                else -> listOf("510 Unrecognized command")
            }
        }
        val c = control(t)
        assertEquals("0.4.8.9 (git-abc)", c.getInfo("version"))
        assertEquals("SocksPort 9050\n.hidden", c.getInfo("config-text"))
        val e = assertFailsWith<TorCommandException> { c.getInfo("nope") }
        assertEquals(552, e.status)
    }

    @Test
    fun signalsAndSetEvents() = runTest {
        val t = ScriptedTransport { listOf("250 OK") }
        val c = control(t)
        c.signal(TorSignal.NEWNYM)
        c.signal(TorSignal.SHUTDOWN)
        c.setEvents(TorControl.DEFAULT_EVENTS)
        c.setEvents(emptyList())
        assertEquals(
            listOf("SIGNAL NEWNYM", "SIGNAL SHUTDOWN", "SETEVENTS STATUS_CLIENT CIRC HS_DESC", "SETEVENTS"),
            t.written,
        )
    }

    @Test
    fun status251IsSuccess() = runTest {
        val t = ScriptedTransport { listOf("251 Operation was unnecessary") }
        assertEquals(251, control(t).command("SIGNAL NEWNYM").status)
    }

    @Test
    fun commandsRejectNewlines() = runTest {
        assertFailsWith<IllegalArgumentException> { control(ScriptedTransport { emptyList() }).command("GETINFO a\r\nSIGNAL SHUTDOWN") }
    }

    @Test
    fun bootstrapStateFlowTracksEvents() = runTest {
        val t = ScriptedTransport { emptyList() }
        val c = control(t)
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch { c.bootstrap.collect { seen += it } }
        testScheduler.runCurrent()
        for (p in listOf(5, 10, 80, 100)) {
            t.push("650 STATUS_CLIENT NOTICE BOOTSTRAP PROGRESS=$p TAG=x SUMMARY=\"s\"")
            testScheduler.runCurrent()
        }
        assertEquals(listOf(0, 5, 10, 80, 100), seen)
        assertEquals(100, c.bootstrap.first { it == 100 })
        job.cancel()
    }

    @Test
    fun refreshBootstrapReadsGetInfo() = runTest {
        val t = ScriptedTransport {
            listOf("250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\"", "250 OK")
        }
        val c = control(t)
        c.refreshBootstrap()
        assertEquals(100, c.bootstrap.value)
    }

    @Test
    fun eventsAreDemuxedFromReplies() = runTest {
        val t = ScriptedTransport { emptyList() }
        val c = control(t)
        val got = mutableListOf<TorEvent>()
        backgroundScope.launch { c.events.collect { got += it } }
        testScheduler.runCurrent()

        // Events arrive while a command is outstanding and right after its reply (never inside a reply).
        val pending = async { c.getInfo("version") }
        testScheduler.runCurrent()
        t.push(
            "650 CIRC 5 BUILT \$A~a,\$B~b PURPOSE=GENERAL",
            "250-version=1.2.3",
            "250 OK",
            "650 HS_DESC REQUESTED abc NO_AUTH \$H~h",
            "650 NS ignored",
        )
        assertEquals("1.2.3", pending.await())
        testScheduler.runCurrent()

        assertEquals(3, got.size)
        assertIs<TorEvent.Circuit>(got[0])
        assertIs<TorEvent.HsDesc>(got[1])
        assertEquals(TorEvent.Other("NS", "NS ignored"), got[2])
    }

    @Test
    fun concurrentCommandsAreAnsweredInOrder() = runTest {
        val t = ScriptedTransport { emptyList() }
        val c = control(t)
        val a = async { c.getInfo("a") }
        val b = async { c.getInfo("b") }
        testScheduler.runCurrent()
        assertEquals(listOf("GETINFO a", "GETINFO b"), t.written)
        t.push("250-a=1", "250 OK", "250-b=2", "250 OK")
        assertEquals("1", a.await())
        assertEquals("2", b.await())
    }

    @Test
    fun connectionLossFailsPendingAndFutureCommands() = runTest {
        val t = ScriptedTransport { emptyList() }
        val c = control(t)
        val pending = async { runCatching { c.getInfo("version") } }
        testScheduler.runCurrent()
        t.incoming.trySend(null) // EOF
        assertIs<TorConnectionClosedException>(pending.await().exceptionOrNull())
        assertFailsWith<TorConnectionClosedException> { c.signal(TorSignal.NEWNYM) }
    }

    @Test
    fun malformedLineClosesTheConnection() = runTest {
        val t = ScriptedTransport { emptyList() }
        val c = control(t)
        val pending = async { runCatching { c.getInfo("version") } }
        testScheduler.runCurrent()
        t.push("garbage")
        assertIs<TorConnectionClosedException>(pending.await().exceptionOrNull())
    }

    @Test
    fun closeClosesTransportAndRejectsCommands() = runTest {
        val t = ScriptedTransport { listOf("250 OK") }
        val c = control(t)
        c.close()
        assertEquals(true, t.closed)
        assertFailsWith<TorConnectionClosedException> { c.signal(TorSignal.NEWNYM) }
    }
}
