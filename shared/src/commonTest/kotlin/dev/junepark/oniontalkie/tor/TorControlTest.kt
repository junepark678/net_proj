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
    @Test fun cookieAuthSubscribesAndTracksBootstrapEvents() = runTest {
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        val phases = mutableListOf<Int>()
        val events = mutableListOf<TorEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { control.bootstrap.collect { phases += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { control.events.collect { events += it } }
        val auth = async { control.authenticate(ByteArray(32) { it.toByte() }) }
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
        transport.expect("AUTHENTICATE " + "ff".repeat(32), "250 OK")
        transport.expect("SETEVENTS STATUS_CLIENT CIRC HS_DESC", "250 OK")
        transport.expect("GETINFO status/bootstrap-phase", "250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\"", "250 OK")
        auth.await()
        assertEquals(100, control.bootstrap.value)
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

    @Test fun badCookieAndAuthenticationRequiredAreMapped() = runTest {
        for (code in listOf(514, 515)) {
            val transport = TranscriptTransport()
            val control = TorControl(transport, backgroundScope)
            val error = async { assertFailsWith<TorCommandException> { control.authenticate(ByteArray(32)) } }
            transport.expect("AUTHENTICATE " + "00".repeat(32), "$code Authentication failed")
            assertTrue(error.await().isAuthenticationFailure)
            assertEquals(code, error.await().reply.code)
            assertTrue(transport.output.tryReceive().isFailure)
            control.close()
        }
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

    @Test fun commandTimeoutClosesInsteadOfReusingALateReply() = runTest {
        val transport = TranscriptTransport()
        val control = TorControl(transport, backgroundScope)
        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
            withTimeout(50) { control.getInfo("version") }
        }
        assertTrue(transport.closed)
        assertFailsWith<Exception> { control.signal(TorSignal.NEWNYM) }
        assertEquals("GETINFO version", transport.output.receive())
        assertTrue(transport.output.tryReceive().isFailure)
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
        assertTrue(transport.output.tryReceive().isFailure)
        control.close()
    }
}
