package dev.junepark.oniontalkie.call

import dev.junepark.oniontalkie.identity.ONION
import dev.junepark.oniontalkie.identity.OTHER_ONION
import dev.junepark.oniontalkie.protocol.ByeReason
import dev.junepark.oniontalkie.protocol.Frame
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CallEngineTest {
    @Test
    fun uiCollectorObservesTheCompleteCallLifecycle() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        val observed = mutableListOf<CallState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.engine.state.collect { observed += it }
        }
        f.engine.goOnline()
        f.engine.call(f.remote)
        runCurrent()
        f.dialer.outgoing.feed(Frame.Accept(1u))
        runCurrent()
        f.engine.hangUp()
        f.engine.goOffline()
        assertEquals(listOf(
            CallState.Offline, CallState.Online(), CallState.Dialing(f.remote),
            CallState.InCall(f.remote), CallState.Online(CallEndReason.NORMAL), CallState.Offline,
        ), observed)
        f.engine.close()
    }

    @Test
    fun presenceTransitionsAreReusableAndCloseIsTerminal() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        assertEquals(CallState.Offline, f.engine.state.value)
        assertFalse(f.engine.call(f.remote))
        assertFalse(f.engine.accept())
        assertFalse(f.engine.decline())
        assertFalse(f.engine.hangUp())
        assertFailsWith<IllegalArgumentException> { f.engine.call(ONION) }
        f.engine.goOnline()
        assertEquals(CallState.Online(), f.engine.state.value)
        f.engine.goOnline()
        assertEquals(1, f.presence.listeners.size)
        f.engine.goOffline()
        assertEquals(CallState.Offline, f.engine.state.value)
        f.engine.goOffline()
        assertEquals(1, f.presence.listener.closes)
        f.engine.goOnline()
        f.engine.close()
        f.engine.close()
        assertEquals(listOf(1, 1), f.presence.listeners.map { it.closes })
        assertFailsWith<IllegalStateException> { f.engine.goOnline() }
    }

    @Test
    fun outboundHelloAcceptMediaAndHangup() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.engine.goOnline()
        assertTrue(f.engine.call(f.remote.uppercase().removeSuffix(".ONION")))
        assertEquals(CallState.Dialing(f.remote), f.engine.state.value)
        assertFalse(f.engine.call(f.remote))
        runCurrent()
        val channel = f.dialer.outgoing
        assertEquals(listOf(f.remote), f.dialer.requests)
        assertEquals(listOf<Frame>(Frame.Hello(1u, "June", 24_000u)), channel.sent)
        assertTrue(f.media.isEmpty())
        channel.feed(Frame.Accept(1u))
        runCurrent()
        assertEquals(CallState.InCall(f.remote), f.engine.state.value)
        val media = f.media.single()
        assertTrue(media.running)
        assertFalse(f.engine.accept())
        assertFalse(f.engine.decline())
        val packet = Frame.Audio(3u, 40u, byteArrayOf(1, 2))
        media.captured.send(packet)
        channel.feed(packet)
        channel.feed(Frame.Ping(123u))
        runCurrent()
        assertEquals(listOf(packet), media.received)
        assertTrue(packet in channel.sent)
        assertTrue(channel.sent.any { it is Frame.Pong && it.echoedTsMs == 123u })
        assertTrue(f.engine.hangUp())
        assertEquals(CallState.Online(CallEndReason.NORMAL), f.engine.state.value)
        assertEquals(Frame.Bye(ByeReason.NORMAL), channel.sent.last())
        assertEquals(channel.sent.size, channel.flushes)
        assertEquals(1, channel.closes)
        assertEquals(1, media.closes)
        assertFalse(media.running)
        assertFalse(f.engine.hangUp())
        f.engine.close()
    }

    @Test
    fun incomingRingsUntilExplicitAcceptanceAndCleanPeerByeReturnsIdle() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.engine.goOnline()
        val channel = f.incoming()
        runCurrent()
        assertEquals(CallState.Ringing(f.remote, "Peer"), f.engine.state.value)
        assertTrue(channel.sent.isEmpty())
        assertTrue(f.media.isEmpty())
        assertTrue(f.engine.accept())
        assertFalse(f.engine.accept())
        runCurrent()
        assertEquals(CallState.InCall(f.remote), f.engine.state.value)
        assertEquals(Frame.Accept(1u), channel.sent.single())
        channel.feed(Frame.Bye(ByeReason.NORMAL))
        runCurrent()
        assertEquals(CallState.Online(CallEndReason.NORMAL), f.engine.state.value)
        assertEquals(1, channel.closes)
        assertEquals(1, f.media.single().closes)
        assertEquals(1, channel.sent.size) // no BYE echo
        f.engine.close()
    }

    @Test
    fun incomingDeclineAndRemoteWithdrawalReleaseRingingSlot() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.engine.goOnline()
        val declined = f.incoming()
        runCurrent()
        assertTrue(f.engine.decline())
        assertFalse(f.engine.accept())
        runCurrent()
        assertEquals(CallState.Online(CallEndReason.DECLINED), f.engine.state.value)
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.NORMAL)), declined.sent)
        assertEquals(1, declined.closes)
        val withdrawn = f.incoming()
        runCurrent()
        withdrawn.feed(Frame.Bye(ByeReason.NORMAL))
        runCurrent()
        assertEquals(CallState.Online(CallEndReason.NORMAL), f.engine.state.value)
        assertEquals(1, withdrawn.closes)
        assertTrue(f.media.isEmpty())
        f.engine.close()
    }

    @Test
    fun concurrentIncomingIsBusyDuringRingingDialingAndInCall() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.engine.goOnline()
        for (phase in listOf("ringing", "dialing", "inCall")) {
            val original = if (phase == "ringing") f.incoming() else {
                f.dialer.outgoing = FakeChannel()
                assertTrue(f.engine.call(f.remote))
                f.dialer.outgoing
            }
            runCurrent()
            if (phase == "inCall") {
                original.feed(Frame.Accept(1u))
                runCurrent()
            }
            val before = f.engine.state.value
            // Different peer while dialing is busy, rather than glare.
            val extra = f.incoming(if (phase == "dialing") THIRD_ONION else f.remote)
            runCurrent()
            assertEquals(listOf<Frame>(Frame.Bye(ByeReason.BUSY)), extra.sent)
            assertEquals(1, extra.closes)
            assertEquals(before, f.engine.state.value)
            assertEquals(0, original.closes)
            f.engine.hangUp()
            assertEquals(1, original.closes)
        }
        f.engine.close()
    }

    @Test
    fun smallerNormalizedOnionKeepsOutboundOnGlare() = runTest {
        val f = Fixture(backgroundScope, testScheduler, local = ONION.uppercase())
        f.engine.goOnline()
        f.engine.call(f.remote)
        runCurrent()
        val inbound = f.incoming(f.remote.uppercase().removeSuffix(".ONION"))
        runCurrent()
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.GLARE)), inbound.sent)
        assertEquals(1, inbound.closes)
        assertEquals(0, f.dialer.outgoing.closes)
        assertEquals(CallState.Dialing(f.remote), f.engine.state.value)
        f.dialer.outgoing.feed(Frame.Accept(1u))
        runCurrent()
        assertIs<CallState.InCall>(f.engine.state.value)
        f.engine.close()
    }

    @Test
    fun largerNormalizedOnionClosesOutboundAndAcceptsInboundOnGlare() = runTest {
        val f = Fixture(backgroundScope, testScheduler, local = OTHER_ONION, remote = ONION)
        f.engine.goOnline()
        f.engine.call(f.remote.uppercase())
        runCurrent()
        val inbound = f.incoming()
        runCurrent()
        assertEquals(Frame.Bye(ByeReason.GLARE), f.dialer.outgoing.sent.last())
        assertEquals(1, f.dialer.outgoing.closes)
        assertEquals(CallState.Ringing(f.remote, "Peer"), f.engine.state.value)
        assertEquals(0, inbound.closes)
        assertTrue(f.engine.accept())
        runCurrent()
        assertEquals(CallState.InCall(f.remote), f.engine.state.value)
        assertEquals(listOf<Frame>(Frame.Accept(1u)), inbound.sent)
        f.engine.close()
        assertEquals(1, inbound.closes)
        assertEquals(1, f.dialer.outgoing.closes)
    }

    @Test
    fun largerOnionDoesNotApplyGlareToAnEstablishedOutboundCall() = runTest {
        val f = Fixture(backgroundScope, testScheduler, OTHER_ONION, ONION)
        f.engine.goOnline()
        f.engine.call(f.remote)
        runCurrent()
        f.dialer.outgoing.feed(Frame.Accept(1u))
        runCurrent()
        val extra = f.incoming()
        runCurrent()
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.BUSY)), extra.sent)
        assertEquals(CallState.InCall(f.remote), f.engine.state.value)
        assertEquals(0, f.dialer.outgoing.closes)
        f.engine.close()
    }

    @Test
    fun glareDuringSuspendedDialClosesEvenALateTransportResult() = runTest {
        val f = Fixture(backgroundScope, testScheduler, OTHER_ONION, ONION)
        val result = CompletableDeferred<Continuation<dev.junepark.oniontalkie.protocol.FrameChannel>>()
        f.dialer.connect = { suspendCoroutine { result.complete(it) } }
        f.engine.goOnline()
        f.engine.call(f.remote)
        runCurrent()
        val inbound = f.incoming()
        runCurrent()
        // The new slot is already reserved while the old dial finishes unwinding.
        assertEquals(CallState.Ringing(f.remote), f.engine.state.value)
        assertFalse(f.engine.call(f.remote))
        val lateOutbound = FakeChannel()
        result.await().resume(lateOutbound)
        runCurrent()
        assertEquals(1, lateOutbound.closes)
        assertEquals(CallState.Ringing(f.remote, "Peer"), f.engine.state.value)
        f.engine.accept()
        runCurrent()
        assertEquals(CallState.InCall(f.remote), f.engine.state.value)
        f.engine.close()
        assertEquals(1, inbound.closes)
        assertEquals(1, lateOutbound.closes)
    }

    @Test
    fun twoEnginesResolveSimultaneousDialAndHangup() = runTest {
        val small = Fixture(backgroundScope, testScheduler)
        val large = Fixture(backgroundScope, testScheduler, OTHER_ONION, ONION)
        small.engine.goOnline()
        large.engine.goOnline()
        small.dialer.connect = {
            val (outbound, inbound) = FakeChannel.pair()
            large.presence.listener.arrive(ONION, inbound)
            outbound
        }
        large.dialer.connect = {
            val (outbound, inbound) = FakeChannel.pair()
            small.presence.listener.arrive(OTHER_ONION, inbound)
            outbound
        }
        assertTrue(small.engine.call(OTHER_ONION))
        assertTrue(large.engine.call(ONION))
        runCurrent()
        assertIs<CallState.Dialing>(small.engine.state.value)
        assertEquals(CallState.Ringing(ONION, "June"), large.engine.state.value)
        large.engine.accept()
        runCurrent()
        assertIs<CallState.InCall>(small.engine.state.value)
        assertIs<CallState.InCall>(large.engine.state.value)
        val first = async { small.engine.hangUp() }
        val second = async { large.engine.hangUp() }
        first.await()
        second.await()
        assertEquals(CallState.Online(CallEndReason.NORMAL), small.engine.state.value)
        assertEquals(CallState.Online(CallEndReason.NORMAL), large.engine.state.value)
        small.engine.close()
        large.engine.close()
    }

    @Test
    fun helloAcceptDeadlineIsExactlyTenSeconds() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.engine.goOnline()
        f.engine.call(f.remote)
        runCurrent()
        advanceTimeBy(9_999)
        runCurrent()
        assertIs<CallState.Dialing>(f.engine.state.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(CallState.Online(CallEndReason.TIMEOUT), f.engine.state.value)
        assertEquals(1, f.dialer.outgoing.closes)
        assertTrue(f.media.isEmpty())
        f.engine.close()
    }

    @Test
    fun missingHelloCannotReserveTheIncomingSlotForever() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.engine.goOnline()
        val channel = FakeChannel()
        f.presence.listener.arrive(f.remote, channel)
        runCurrent()
        assertEquals(CallState.Ringing(f.remote), f.engine.state.value)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(CallState.Online(CallEndReason.TIMEOUT), f.engine.state.value)
        assertEquals(1, channel.closes)
        f.engine.close()
    }

    @Test
    fun earlyAcceptWaitsForValidHelloAndEarlyDeclineClosesImmediately() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.engine.goOnline()
        val declined = FakeChannel()
        f.presence.listener.arrive(f.remote, declined)
        runCurrent()
        assertTrue(f.engine.decline())
        runCurrent()
        assertEquals(CallState.Online(CallEndReason.DECLINED), f.engine.state.value)
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.NORMAL)), declined.sent)
        assertEquals(1, declined.closes)
        val accepted = FakeChannel()
        f.presence.listener.arrive(f.remote, accepted)
        runCurrent()
        assertTrue(f.engine.accept())
        runCurrent()
        assertTrue(f.media.isEmpty())
        assertTrue(accepted.sent.isEmpty())
        accepted.feed(Frame.Hello(1u, "Peer", 24_000u))
        runCurrent()
        assertEquals(CallState.InCall(f.remote), f.engine.state.value)
        assertEquals(listOf<Frame>(Frame.Accept(1u)), accepted.sent)
        f.engine.close()
        assertEquals(1, accepted.closes)
    }

    @Test
    fun ringDeadlineIsThirtySecondsAfterHello() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.engine.goOnline()
        val channel = f.incoming()
        runCurrent()
        advanceTimeBy(29_999)
        runCurrent()
        assertIs<CallState.Ringing>(f.engine.state.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(CallState.Online(CallEndReason.TIMEOUT), f.engine.state.value)
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.NORMAL)), channel.sent)
        assertEquals(1, channel.closes)
        assertFalse(f.engine.accept())
        f.engine.close()
    }

    @Test
    fun pingsEveryTwoSecondsButIncomingPingDoesNotExtendInactivity() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.engine.goOnline()
        f.engine.call(f.remote)
        runCurrent()
        f.dialer.outgoing.feed(Frame.Accept(1u))
        runCurrent()
        advanceTimeBy(1_999)
        assertTrue(f.dialer.outgoing.sent.none { it is Frame.Ping })
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf<Frame>(Frame.Ping(2_000u)), f.dialer.outgoing.sent.filterIsInstance<Frame.Ping>())
        advanceTimeBy(12_000)
        runCurrent()
        f.dialer.outgoing.feed(Frame.Ping(14_000u))
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertIs<CallState.InCall>(f.engine.state.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(CallState.Online(CallEndReason.TIMEOUT), f.engine.state.value)
        assertEquals((2_000u..14_000u step 2_000).toList(),
            f.dialer.outgoing.sent.filterIsInstance<Frame.Ping>().map { it.tsMs })
        assertEquals(Frame.Bye(ByeReason.NORMAL), f.dialer.outgoing.sent.last())
        assertEquals(1, f.dialer.outgoing.closes)
        assertEquals(1, f.media.single().closes)
        f.engine.close()
    }

    @Test
    fun pongAndAudioEachResetTheFifteenSecondDeadline() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.engine.goOnline()
        f.engine.call(f.remote)
        runCurrent()
        f.dialer.outgoing.feed(Frame.Accept(1u))
        runCurrent()
        for (frame in listOf<Frame>(Frame.Pong(2_000u, 99u), Frame.Audio(0u, 0u, byteArrayOf(1)))) {
            advanceTimeBy(14_000)
            f.dialer.outgoing.feed(frame)
            runCurrent()
            assertIs<CallState.InCall>(f.engine.state.value)
        }
        advanceTimeBy(14_999)
        runCurrent()
        assertIs<CallState.InCall>(f.engine.state.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(CallState.Online(CallEndReason.TIMEOUT), f.engine.state.value)
        f.engine.close()
    }

    @Test
    fun peerOfflineHasDistinctRetryableUiState() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.dialer.connect = { throw PeerOfflineException() }
        f.engine.goOnline()
        f.engine.call(f.remote)
        runCurrent()
        assertEquals(CallState.PeerOffline(f.remote), f.engine.state.value)
        assertTrue(f.media.isEmpty())
        f.dialer.connect = { f.dialer.outgoing }
        assertTrue(f.engine.call(f.remote))
        runCurrent()
        assertIs<CallState.Dialing>(f.engine.state.value)
        f.engine.hangUp()
        f.dialer.connect = { throw PeerOfflineException() }
        f.engine.call(f.remote)
        runCurrent()
        val incoming = f.incoming()
        runCurrent()
        assertIs<CallState.Ringing>(f.engine.state.value)
        f.engine.close()
        assertEquals(1, incoming.closes)
        assertEquals(CallState.Offline, f.engine.state.value)
    }

    @Test
    fun remoteByeReasonsEofAndMalformedFramesTearDownExactlyOnce() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.engine.goOnline()
        for ((reason, expected) in listOf(
            ByeReason.NORMAL to CallEndReason.NORMAL,
            ByeReason.BUSY to CallEndReason.BUSY,
            ByeReason.GLARE to CallEndReason.GLARE,
            ByeReason.VERSION to CallEndReason.PROTOCOL_ERROR,
            ByeReason.PROTOCOL_ERROR to CallEndReason.PROTOCOL_ERROR,
        )) {
            f.dialer.outgoing = FakeChannel()
            f.engine.call(f.remote)
            runCurrent()
            f.dialer.outgoing.feed(Frame.Bye(reason))
            runCurrent()
            assertEquals(CallState.Online(expected), f.engine.state.value)
            assertEquals(1, f.dialer.outgoing.closes)
        }
        for (bytes in listOf(byteArrayOf(), byteArrayOf(7), byteArrayOf(2, 2), byteArrayOf(3, 0))) {
            val channel = FakeChannel()
            f.dialer.outgoing = channel
            f.engine.call(f.remote)
            runCurrent()
            if (bytes.isNotEmpty()) channel.feedBytes(bytes)
            channel.eof()
            runCurrent()
            assertEquals(CallState.Online(if (bytes.isEmpty()) CallEndReason.CONNECTION_LOST
                else CallEndReason.PROTOCOL_ERROR), f.engine.state.value)
            assertEquals(1, channel.closes)
        }
        f.engine.close()
    }

    @Test
    fun unsupportedIncomingHelloUsesReadersVersionByeAndSingleClose() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.engine.goOnline()
        val channel = FakeChannel()
        channel.feed(Frame.Hello(2u, "Peer", 24_000u))
        f.presence.listener.arrive(f.remote, channel)
        runCurrent()
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.VERSION)), channel.sent)
        assertEquals(CallState.Online(CallEndReason.PROTOCOL_ERROR), f.engine.state.value)
        assertEquals(1, channel.closes)
        f.engine.close()
    }

    @Test
    fun validButOutOfOrderFramesAreProtocolErrors() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.engine.goOnline()
        val channel = f.incoming()
        runCurrent()
        channel.feed(Frame.Accept(1u))
        runCurrent()
        assertEquals(CallState.Online(CallEndReason.PROTOCOL_ERROR), f.engine.state.value)
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.PROTOCOL_ERROR)), channel.sent)
        assertEquals(1, channel.closes)
        f.engine.close()
    }

    @Test
    fun ownerCancellationCleansEveryCallPhase() = runTest {
        for (phase in listOf("idle", "connecting", "dialing", "ringing", "inCall")) {
            val ownerJob = Job(backgroundScope.coroutineContext[Job])
            val f = Fixture(CoroutineScope(backgroundScope.coroutineContext + ownerJob), testScheduler)
            f.engine.goOnline()
            var channel: FakeChannel? = null
            when (phase) {
                "connecting" -> {
                    f.dialer.connect = { CompletableDeferred<FakeChannel>().await() }
                    f.engine.call(f.remote)
                }
                "dialing", "inCall" -> {
                    f.engine.call(f.remote)
                    channel = f.dialer.outgoing
                    runCurrent()
                    if (phase == "inCall") channel.feed(Frame.Accept(1u))
                }
                "ringing" -> channel = f.incoming()
            }
            runCurrent()
            ownerJob.cancelAndJoin()
            assertEquals(CallState.Offline, f.engine.state.value, phase)
            assertEquals(1, f.presence.listener.closes, phase)
            channel?.let { assertEquals(1, it.closes, phase) }
            f.media.forEach { assertEquals(1, it.closes, phase) }
            f.engine.close()
        }
    }

    @Test
    fun simultaneousHangupsShutdownAndCancellationDoNotDuplicateCleanup() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.engine.goOnline()
        val channel = f.incoming()
        runCurrent()
        f.engine.accept()
        runCurrent()
        channel.closeGate = CompletableDeferred()
        val first = launch { f.engine.hangUp() }
        val second = launch { f.engine.hangUp() }
        runCurrent()
        val offline = launch { f.engine.goOffline() }
        val close = launch { f.engine.close() }
        runCurrent()
        first.cancel()
        second.cancel()
        offline.cancel()
        close.cancel()
        runCurrent()
        channel.closeGate!!.complete(Unit)
        first.join()
        second.join()
        offline.join()
        close.join()
        assertEquals(CallState.Offline, f.engine.state.value)
        assertEquals(1, channel.closes)
        assertEquals(1, f.media.single().closes)
        assertEquals(1, f.presence.listener.closes)
    }

    @Test
    fun blockedWriterCannotPreventInactivityTeardown() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.engine.goOnline()
        f.engine.call(f.remote)
        runCurrent()
        f.dialer.outgoing.feed(Frame.Accept(1u))
        runCurrent()
        f.dialer.outgoing.writeGate = CompletableDeferred()
        advanceTimeBy(15_000)
        runCurrent()
        // One bounded second to attempt BYE, even if another write owns the mutex.
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(CallState.Online(CallEndReason.TIMEOUT), f.engine.state.value)
        assertEquals(1, f.dialer.outgoing.closes)
        assertEquals(1, f.media.single().closes)
        f.engine.close()
    }

    @Test
    fun failedOrBlockedBusyRejectionDoesNotStopTheListener() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.engine.goOnline()
        val first = f.incoming()
        runCurrent()
        val failed = FakeChannel().also { it.failWrite = true }
        f.presence.listener.arrive(f.remote, failed)
        runCurrent()
        assertEquals(1, failed.closes)
        val blocked = FakeChannel().also { it.writeGate = CompletableDeferred() }
        f.presence.listener.arrive(f.remote, blocked)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, blocked.closes)
        assertEquals(CallState.Ringing(f.remote, "Peer"), f.engine.state.value)
        f.engine.hangUp()
        assertEquals(1, first.closes)
        val next = f.incoming()
        runCurrent()
        assertEquals(CallState.Ringing(f.remote, "Peer"), f.engine.state.value)
        f.engine.close()
        assertEquals(1, next.closes)
        assertEquals(1, f.presence.listener.closes)
    }

    @Test
    fun blockedPongResponseDoesNotHideReceivedLiveness() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.engine.goOnline()
        f.engine.call(f.remote)
        runCurrent()
        val channel = f.dialer.outgoing
        channel.feed(Frame.Accept(1u))
        runCurrent()
        channel.writeGate = CompletableDeferred()
        channel.feed(Frame.Ping(0u))
        runCurrent() // the PONG write is now blocked
        advanceTimeBy(14_000)
        channel.feed(Frame.Pong(0u, 0u))
        channel.feed(Frame.Audio(0u, 0u, byteArrayOf(1)))
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(CallState.InCall(f.remote), f.engine.state.value)
        assertEquals(1, f.media.single().received.size)
        channel.writeGate!!.complete(Unit)
        runCurrent()
        f.engine.hangUp()
        assertEquals(1, channel.closes)
        f.engine.close()
    }

    @Test
    fun readersVersionByeAndUiDeclineSerializeTransportWrites() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.engine.goOnline()
        val channel = FakeChannel()
        channel.writeGate = CompletableDeferred()
        channel.feed(Frame.Hello(2u, "Peer", 24_000u))
        f.presence.listener.arrive(f.remote, channel)
        runCurrent()
        f.engine.decline()
        runCurrent()
        channel.writeGate!!.complete(Unit)
        runCurrent()
        // FakeChannel rejects concurrent raw writes; the reader's version response wins
        // before teardown closes the same transport. Neither close path retries close.
        assertEquals(Frame.Bye(ByeReason.VERSION), channel.sent.first())
        assertEquals(0, channel.concurrentWrites)
        assertEquals(1, channel.closes)
        assertEquals(CallState.Online(CallEndReason.DECLINED), f.engine.state.value)
        f.engine.close()
    }

    @Test
    fun hangupBeforeHelloAndImmediateOfflineStillCloseAdmittedTransports() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.engine.goOnline()
        val beforeHello = FakeChannel()
        f.presence.listener.arrive(f.remote, beforeHello)
        runCurrent()
        assertTrue(f.engine.hangUp())
        assertEquals(1, beforeHello.closes)
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.NORMAL)), beforeHello.sent)
        val queued = FakeChannel()
        f.presence.listener.arrive(f.remote, queued)
        f.engine.goOffline()
        assertEquals(CallState.Offline, f.engine.state.value)
        assertEquals(1, queued.closes)
        f.engine.close()
    }

    @Test
    fun closeFailuresAndDialFailuresDoNotPoisonFutureCalls() = runTest {
        val f = Fixture(backgroundScope, testScheduler)
        f.presence.failure = IllegalStateException("Publication failed")
        assertFailsWith<IllegalStateException> { f.engine.goOnline() }
        assertEquals(CallState.Offline, f.engine.state.value)
        f.presence.failure = null
        f.engine.goOnline()
        f.dialer.connect = { error("SOCKS error other than peer offline") }
        f.engine.call(f.remote)
        runCurrent()
        assertEquals(CallState.Online(CallEndReason.FAILURE), f.engine.state.value)
        val channel = f.incoming()
        runCurrent()
        f.engine.accept()
        runCurrent()
        channel.failClose = true
        f.media.single().failClose = true
        f.engine.hangUp()
        assertEquals(CallState.Online(CallEndReason.NORMAL), f.engine.state.value)
        assertEquals(1, channel.closes)
        assertEquals(1, f.media.single().closes)
        f.engine.close()
    }

    private companion object {
        const val THIRD_ONION = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"
    }
}
