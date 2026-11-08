package dev.junepark.oniontalkie.tor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

internal class TranscriptTransport : ControlTransport {
    val input = Channel<String>(Channel.UNLIMITED)
    val output = Channel<String>(Channel.UNLIMITED)
    var closed = false
    override suspend fun readLine(): String? = input.receiveCatching().getOrNull()
    override suspend fun writeLine(line: String) { check(!closed); output.send(line) }
    override fun close() { closed = true; input.close() }
    fun feed(vararg lines: String) { lines.forEach { check(input.trySend(it).isSuccess) } }
    suspend fun expect(command: String, vararg reply: String) {
        assertEquals(command, output.receive())
        feed(*reply)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TorControlTest {
    // Independently computed with Python hmac/hashlib; shared with candidate PR #48.
    private val serverHash = "7780ba467c566548b61da5b927043df643cc6fb18e6258718f47591a09909213"
    private val clientHash = "ecd5845c1ed9b11354fed620d989e9f34243606e0f7f97c0f3c8a9303a06d468"
    private val clientNonce = "aa".repeat(32)
    private val serverNonce = "bb".repeat(32)

    private suspend fun TranscriptTransport.advertiseMethods(methods: String = "COOKIE") {
        expect("PROTOCOLINFO 1", "250-PROTOCOLINFO 1",
            "250-AUTH METHODS=$methods COOKIEFILE=\"/ignored/control cookie\"",
            "250-VERSION Tor=\"0.4.9.12\"", "250 OK")
    }

    @Test fun cookieAuthSubscribesAndTracksBootstrapEvents() = runTest {
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        val phases = mutableListOf<Int>()
        val events = mutableListOf<TorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { control.bootstrap.collect { phases += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { control.events.collect { events += it } }
        val auth = async { control.authenticate(ByteArray(32) { it.toByte() }) }
        transport.advertiseMethods()
        transport.expect("AUTHENTICATE 000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "250 OK")
        transport.expect("SETEVENTS STATUS_CLIENT CIRC HS_DESC", "250 OK")
        transport.expect("GETINFO status/bootstrap-phase", "250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=5 TAG=conn SUMMARY=\"Connecting to a relay\"", "250 OK")
        auth.await()
        transport.feed("650 STATUS_CLIENT NOTICE BOOTSTRAP PROGRESS=50 TAG=loading_descriptors SUMMARY=\"Loading relay descriptors\"")
        runCurrent()
        transport.feed("650 STATUS_CLIENT NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\"")
        runCurrent()
        assertEquals(listOf(0, 5, 50, 100), phases)
        assertEquals(100, control.bootstrap.value)
        assertEquals(listOf(50, 100), events.filterIsInstance<TorEvent.Bootstrap>().map { it.progress })
        assertEquals("Loading relay descriptors", assertIs<TorEvent.Bootstrap>(events.first()).summary)
        control.close()
        assertTrue(transport.closed)
    }

    @Test fun alreadyBootstrappedTorIsInitializedFromGetInfo() = runTest {
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        val auth = async { control.authenticate(ByteArray(32) { 0xff.toByte() }) }
        transport.advertiseMethods("HASHEDPASSWORD,COOKIE")
        transport.expect("AUTHENTICATE " + "ff".repeat(32), "250 OK")
        transport.expect("SETEVENTS STATUS_CLIENT CIRC HS_DESC", "250 OK")
        transport.expect("GETINFO status/bootstrap-phase", "250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\"", "250 OK")
        auth.await()
        assertEquals(100, control.bootstrap.value)
        control.close()
    }

    @Test fun controlReaderTerminationCanBeAwaited() = runTest {
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        val ended = backgroundScope.async { control.awaitTermination() }

        transport.input.close()
        runCurrent()

        assertIs<TorConnectionClosedException>(ended.await())
        assertTrue(transport.closed)
    }

    @Test fun closedProcessLogStreamFailsBootstrapCollection() = runTest {
        val control = TorControl(TranscriptTransport(), backgroundScope)
        val failure = assertFailsWith<TorProcessException> {
            control.collectBootstrapUntilTorTerminates(emptyFlow()) { }
        }
        assertEquals("Tor process exited unexpectedly", failure.message)
        control.close()
    }

    @Test fun bootstrapSnapshotCannotOverwriteANewerEvent() = runTest {
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        val info = async { control.getInfo("status/bootstrap-phase") }
        transport.expect("GETINFO status/bootstrap-phase",
            "250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=50 TAG=loading SUMMARY=\"Loading\"",
            "250 OK",
            "650 STATUS_CLIENT NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\"",
        )
        info.await()
        runCurrent()
        assertEquals(100, control.bootstrap.value)
        control.close()
    }

    @Test fun asyncEventsAreDemultiplexedBeforeAndInsideReplies() = runTest {
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        val events = mutableListOf<TorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { control.events.collect { events += it } }
        val info = async { control.getInfo("version") }
        transport.expect("GETINFO version",
            "650-CIRC 7 BUILT \$abc=Relay,\$def~Other PURPOSE=GENERAL FUTURE=enabled",
            "650 EXTRA=extension",
            "250-version=0.4.9.12",
            "650 HS_DESC FAILED example NO_AUTH UNKNOWN REASON=NOT_FOUND REPLICA=1",
            "250 OK",
        )
        assertEquals("0.4.9.12", info.await())
        val circuit = assertIs<TorEvent.Circuit>(events[0])
        assertEquals("7", circuit.id)
        assertEquals("BUILT", circuit.status)
        assertEquals(listOf("\$abc=Relay", "\$def~Other"), circuit.path)
        assertEquals(setOf("PURPOSE", "FUTURE"), circuit.fields.keys)
        assertEquals("enabled", circuit.fields["FUTURE"])
        assertEquals(2, circuit.raw.lines.size)
        val descriptor = assertIs<TorEvent.HiddenServiceDescriptor>(events[1])
        assertEquals("NOT_FOUND", descriptor.fields["REASON"])
        assertEquals(null, descriptor.descriptorId)
        control.close()
    }

    @Test fun concurrentCommandsAreSerializedAndAccept251() = runTest {
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        val first = async { control.signal(TorSignal.NEWNYM) }
        val second = async { control.getInfo("version") }
        assertEquals("SIGNAL NEWNYM", transport.output.receive())
        runCurrent()
        assertTrue(transport.output.tryReceive().isFailure)
        transport.feed("251 Operation was unnecessary")
        first.await()
        transport.expect("GETINFO version", "250 version=real-value")
        assertEquals("real-value", second.await())
        control.close()
    }

    @Test fun negativeReplyPreservesCodeLinesAndConnection() = runTest {
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        val error = async { assertFailsWith<TorCommandException> { control.getInfo("missing") } }
        transport.expect("GETINFO missing", "552-Unrecognized key", "552 missing")
        assertEquals(552, error.await().reply.code)
        assertEquals(2, error.await().reply.lines.size)
        assertFalse(error.await().isAuthenticationFailure)
        val next = async { control.setEvents() }
        transport.expect("SETEVENTS", "250 OK")
        next.await()
        assertFalse(transport.closed)
        control.close()
    }

    @Test fun streamEventsCanBeEnabledForDiagnostics() = runTest {
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        val enable = async { control.setEvents(TorEventType.STATUS_CLIENT, TorEventType.CIRC, TorEventType.HS_DESC, TorEventType.STREAM) }
        transport.expect("SETEVENTS STATUS_CLIENT CIRC HS_DESC STREAM", "250 OK")
        enable.await()
        control.close()
    }

    @Test fun badCookieAndAuthenticationRequiredAreMapped() = runTest {
        for (code in listOf(514, 515)) {
            val transport = TranscriptTransport()
            val control = TorControl(transport, backgroundScope)
            val error = async { assertFailsWith<TorCommandException> { control.authenticate(ByteArray(32)) } }
            transport.advertiseMethods()
            transport.expect("AUTHENTICATE " + "00".repeat(32), "$code Authentication failed")
            assertTrue(error.await().isAuthenticationFailure)
            assertEquals(code, error.await().reply.code)
            assertTrue(transport.output.tryReceive().isFailure)
            control.close()
        }
    }

    @Test fun safeCookieIsPreferredAndUsesKnownAnswerProofs() = runTest {
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope) { ByteArray(32) { 0xaa.toByte() } }
        val auth = async { control.authenticate(ByteArray(32) { it.toByte() }) }
        transport.advertiseMethods("COOKIE,SAFECOOKIE,HASHEDPASSWORD")
        transport.expect("AUTHCHALLENGE SAFECOOKIE $clientNonce",
            "250 AUTHCHALLENGE SERVERHASH=${serverHash.uppercase()} SERVERNONCE=${serverNonce.uppercase()}")
        transport.expect("AUTHENTICATE $clientHash", "250 OK")
        transport.expect("SETEVENTS STATUS_CLIENT CIRC HS_DESC", "250 OK")
        transport.expect("GETINFO status/bootstrap-phase",
            "250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\"", "250 OK")
        auth.await()
        assertEquals(100, control.bootstrap.value)
        assertFalse(transport.closed)
        control.close()
    }

    @Test fun invalidSafeCookieChallengeClosesWithoutSendingCredentialsOrDowngrading() = runTest {
        val challenges = listOf(
            "250 AUTHCHALLENGE SERVERHASH=${"00".repeat(32)} SERVERNONCE=$serverNonce",
            "250 AUTHCHALLENGE SERVERHASH=$serverHash",
            "250 AUTHCHALLENGE SERVERNONCE=$serverNonce",
            "250 AUTHCHALLENGE SERVERHASH=${"aa".repeat(31)} SERVERNONCE=$serverNonce",
            "250 AUTHCHALLENGE SERVERHASH=${"aa".repeat(33)} SERVERNONCE=$serverNonce",
            "250 AUTHCHALLENGE SERVERHASH=$serverHash SERVERNONCE=${"bb".repeat(31)}",
            "250 AUTHCHALLENGE SERVERHASH=$serverHash SERVERNONCE=${"bb".repeat(33)}",
            "250 AUTHCHALLENGE SERVERHASH=${"zz".repeat(32)} SERVERNONCE=$serverNonce",
            "250 AUTHCHALLENGE SERVERHASH=$serverHash SERVERNONCE=${"zz".repeat(32)}",
            "250 AUTHCHALLENGE SERVERHASH=$serverHash SERVERNONCE=$serverNonce SERVERHASH=$serverHash",
            "250 OK",
        )
        for (challenge in challenges) {
            val transport = TranscriptTransport()
            val control = TorControl(transport, backgroundScope) { ByteArray(32) { 0xaa.toByte() } }
            val error = async { assertFailsWith<TorProtocolException> { control.authenticate(ByteArray(32) { it.toByte() }) } }
            transport.advertiseMethods("COOKIE,SAFECOOKIE")
            transport.expect("AUTHCHALLENGE SAFECOOKIE $clientNonce", challenge)
            error.await()
            assertTrue(transport.closed)
            assertTrue(transport.output.tryReceive().isFailure)
        }
    }

    @Test fun rejectedSafeCookieChallengeDoesNotFallBackToCookie() = runTest {
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope) { ByteArray(32) { 0xaa.toByte() } }
        val error = async { assertFailsWith<TorCommandException> { control.authenticate(ByteArray(32)) } }
        transport.advertiseMethods("COOKIE,SAFECOOKIE")
        transport.expect("AUTHCHALLENGE SAFECOOKIE $clientNonce", "515 Challenge rejected")
        assertEquals(515, error.await().reply.code)
        assertTrue(transport.closed)
        assertTrue(transport.output.tryReceive().isFailure)
    }

    @Test fun malformedOrUnsupportedProtocolInfoNeverSendsTheCookie() = runTest {
        for (line in listOf("250 OK", "250 AUTH METHODS=NULL,HASHEDPASSWORD",
            "250 AUTH COOKIEFILE=\"/ignored\"", "250 AUTH METHODS=\"COOKIE",
            "250 AUTH METHODS=COOKIE METHODS=SAFECOOKIE")) {
            val transport = TranscriptTransport()
            val control = TorControl(transport, backgroundScope)
            val error = async { assertFailsWith<TorProtocolException> { control.authenticate(ByteArray(32)) } }
            transport.expect("PROTOCOLINFO 1", line)
            error.await()
            assertTrue(transport.closed)
            assertTrue(transport.output.tryReceive().isFailure)
        }
    }

    @Test fun rawCommandsWaitUntilTheSafeCookieHandshakeFinishes() = runTest {
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope) { ByteArray(32) { 0xaa.toByte() } }
        val auth = async { control.authenticate(ByteArray(32) { it.toByte() }) }
        transport.advertiseMethods("SAFECOOKIE")
        assertEquals("AUTHCHALLENGE SAFECOOKIE $clientNonce", transport.output.receive())
        val queued = async { control.command("GETINFO version") }
        runCurrent()
        assertTrue(transport.output.tryReceive().isFailure)
        transport.feed("250 AUTHCHALLENGE SERVERHASH=$serverHash SERVERNONCE=$serverNonce")
        transport.expect("AUTHENTICATE $clientHash", "250 OK")
        transport.expect("GETINFO version", "250 version=test-tor")
        assertEquals(250, queued.await().code)
        transport.expect("SETEVENTS STATUS_CLIENT CIRC HS_DESC", "250 OK")
        transport.expect("GETINFO status/bootstrap-phase", "250 status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=0")
        auth.await()
        control.close()
    }

    @Test fun multilineGetInfoUnstuffsDataAndIgnoresStatusLookingPayload() = runTest {
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        val info = async { control.getInfo("test/data") }
        transport.expect("GETINFO test/data", "250+test/data=", "first", "..dot", "", "650 NOT_AN_EVENT", ".", "250 OK")
        assertEquals("first\n.dot\n\n650 NOT_AN_EVENT", info.await())
        control.close()
    }

    @Test fun missingGetInfoValueIsAProtocolError() = runTest {
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        val error = async { assertFailsWith<TorProtocolException> { control.getInfo("version") } }
        transport.expect("GETINFO version", "250 OK")
        error.await()
        control.close()
    }

    @Test fun commandTimeoutKeepsTheClientAndEventsUsable() = runTest {
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        val events = mutableListOf<TorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { control.events.collect { events += it } }
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            withTimeout(50) { control.getInfo("version") }
        }
        assertFalse(transport.closed)
        assertEquals("GETINFO version", transport.output.receive())
        val next = async { control.getInfo("version") }
        transport.feed("250-version=late-value",
            "650 STATUS_CLIENT NOTICE BOOTSTRAP PROGRESS=50 TAG=loading SUMMARY=\"Loading\"")
        runCurrent()
        assertEquals(50, control.bootstrap.value)
        assertTrue(transport.output.tryReceive().isFailure)
        transport.feed("250 OK")
        transport.expect("GETINFO version", "250 version=current-value")
        assertEquals("current-value", next.await())
        transport.feed("650 STATUS_CLIENT NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\"")
        runCurrent()
        assertEquals(listOf(50, 100), events.filterIsInstance<TorEvent.Bootstrap>().map { it.progress })
        assertEquals(100, control.bootstrap.value)
        assertFalse(transport.closed)
        control.close()
    }

    @Test fun cancelledCallersDrainCompleteSuccessAndErrorRepliesBeforeTheNextCommand() = runTest {
        for (code in listOf(250, 552)) {
            val transport = TranscriptTransport()
            val control = TorControl(transport, backgroundScope)
            val cancelled = async { control.command("GETINFO test/data") }
            assertEquals("GETINFO test/data", transport.output.receive())
            transport.feed("$code+test/data=", "partial data")
            runCurrent()
            cancelled.cancel()
            cancelled.join()
            assertFalse(transport.closed)
            val next = async { control.getInfo("version") }
            runCurrent()
            assertTrue(transport.output.tryReceive().isFailure)
            transport.feed("..dot", ".", "$code-still draining")
            runCurrent()
            assertTrue(transport.output.tryReceive().isFailure)
            transport.feed("$code " + if (code == 250) "OK" else "Unknown key")
            transport.expect("GETINFO version", "250-version=own-reply", "250 OK")
            assertEquals("own-reply", next.await())
            assertFalse(transport.closed)
            control.close()
        }
    }

    @Test fun cancellingAQueuedCommandDoesNotCloseTheActiveOne() = runTest {
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        val active = async { control.getInfo("version") }
        assertEquals("GETINFO version", transport.output.receive())
        val queued = async { control.signal(TorSignal.NEWNYM) }
        runCurrent()
        queued.cancel()
        queued.join()
        assertFalse(transport.closed)
        transport.feed("250 version=ok")
        assertEquals("ok", active.await())
        runCurrent()
        assertTrue(transport.output.tryReceive().isFailure)
        control.close()
    }

    @Test fun eofAndMalformedRepliesFailPendingCommands() = runTest {
        for (lines in listOf(emptyList(), listOf("not a status"), listOf("250-partial"), listOf("250+data=", "unterminated"))) {
            val transport = TranscriptTransport()
            val control = TorControl(transport, backgroundScope)
            val error = async { assertFailsWith<Exception> { control.getInfo("version") } }
            assertEquals("GETINFO version", transport.output.receive())
            transport.feed(*lines.toTypedArray())
            transport.input.close()
            val failure = error.await()
            if (lines.isEmpty()) assertIs<TorConnectionClosedException>(failure) else assertIs<TorProtocolException>(failure)
            assertTrue(transport.closed)
        }
    }

    @Test fun completeSuccessReplyFollowedByEofIsStillDelivered() = runTest {
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        val pending = async { control.getInfo("version") }
        assertEquals("GETINFO version", transport.output.receive())
        transport.feed("250 version=final")
        transport.input.close()
        assertEquals("final", pending.await())
        assertTrue(transport.closed)
        assertFailsWith<TorConnectionClosedException> { control.getInfo("version") }
    }

    @Test fun completeErrorReplyFollowedByEofIsStillReported() = runTest {
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        val pending = async { assertFailsWith<TorCommandException> { control.command("AUTHENTICATE 00") } }
        assertEquals("AUTHENTICATE 00", transport.output.receive())
        transport.feed("515 Authentication failed: Wrong length on authentication cookie.")
        transport.input.close()
        assertEquals(515, pending.await().reply.code)
        assertTrue(transport.closed)
        assertFailsWith<TorConnectionClosedException> { control.getInfo("version") }
    }

    @Test fun closeUnblocksPendingCommandAndIsIdempotent() = runTest {
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        val error = async { assertFailsWith<TorConnectionClosedException> { control.getInfo("version") } }
        assertEquals("GETINFO version", transport.output.receive())
        control.close()
        control.close()
        error.await()
        assertTrue(transport.closed)
    }

    @Test fun cancellingScopeBeforeReaderStartsStillClosesTransport() = runTest {
        val job = Job()
        val transport = TranscriptTransport()
        val scope = CoroutineScope(coroutineContext + job)
        TorControl(transport, scope)
        scope.cancel()
        runCurrent()
        assertTrue(transport.closed)
    }

    @Test fun invalidArgumentsNeverReachTheWire() = runTest {
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        for (key in listOf("", "version other", "version\r\nSIGNAL SHUTDOWN", "\"version\"", "version\\")) {
            assertFailsWith<IllegalArgumentException> { control.getInfo(key) }
        }
        assertFailsWith<IllegalArgumentException> { control.authenticate(ByteArray(31)) }
        for (line in listOf("", " ", "\t", "GETINFO version\r", "GETINFO version\n", "GETINFO version\r\nSIGNAL SHUTDOWN")) {
            assertFailsWith<IllegalArgumentException> { control.command(line) }
        }
        assertTrue(transport.output.tryReceive().isFailure)
        assertFalse(transport.closed)
        control.close()
    }
}
