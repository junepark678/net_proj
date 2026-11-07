package dev.junepark.oniontalkie.call

import dev.junepark.oniontalkie.protocol.ByeReason
import dev.junepark.oniontalkie.protocol.Frame
import dev.junepark.oniontalkie.protocol.FrameCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CallEngineTest {
    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun Rig.lastCall() = (state as CallState.Online).lastCall

    private suspend fun Rig.startCall(): Remote {
        // Outgoing call to SMALL, answered with ACCEPT.
        val remote = answerDialsWith()
        engine.dial(SMALL)
        settle()
        remote.send(Frame.Accept(FrameCodec.VERSION))
        settle()
        assertIs<CallState.InCall>(state)
        return remote
    }

    // ---- Offline → Online → Offline ------------------------------------------------------------

    @Test
    fun offlineGoesOnlineThroughGoingOnline() = runTest {
        val rig = Rig(this)
        assertEquals(CallState.Offline(), rig.state)
        val gate = CompletableDeferred<Unit>().also { rig.presence.gate = it }
        rig.engine.goOnline()
        runCurrent()
        assertEquals(CallState.GoingOnline, rig.state)
        gate.complete(Unit)
        runCurrent()
        assertEquals(CallState.Online(MID), rig.state)
        assertEquals(1, rig.presence.calls)
    }

    @Test
    fun failedPublicationFallsBackToOfflineWithError() = runTest {
        val rig = Rig(this)
        rig.presence.failure = IllegalStateException("tor down")
        rig.goOnline()
        assertEquals(CallState.Offline("tor down"), rig.state)
        // Retry succeeds.
        rig.presence.failure = null
        rig.goOnline()
        assertEquals(CallState.Online(MID), rig.state)
    }

    @Test
    fun goOfflineStopsListeningExactlyOnce() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        rig.engine.goOffline()
        rig.engine.goOffline()
        rig.settle()
        assertEquals(CallState.Offline(), rig.state)
        assertEquals(1, rig.listening.closeCount)
    }

    @Test
    fun goOfflineWhilePublishingClosesTheLateService() = runTest {
        val rig = Rig(this)
        val gate = CompletableDeferred<Unit>().also { rig.presence.gate = it }
        rig.presence.swallowCancellation = true // returns a service even though it was cancelled
        rig.engine.goOnline()
        runCurrent()
        rig.engine.goOffline()
        runCurrent()
        assertEquals(CallState.Offline(), rig.state)
        gate.complete(Unit)
        runCurrent()
        assertEquals(CallState.Offline(), rig.state)
        assertEquals(1, rig.listening.closeCount)
    }

    @Test
    fun goOfflineWhilePublishingCancelsTheAttempt() = runTest {
        val rig = Rig(this)
        rig.presence.gate = CompletableDeferred()
        rig.engine.goOnline()
        runCurrent()
        rig.engine.goOffline()
        runCurrent()
        assertEquals(CallState.Offline(), rig.state)
        // A fresh attempt works.
        rig.presence.gate = null
        rig.goOnline()
        assertEquals(CallState.Online(MID), rig.state)
    }

    @Test
    fun listenerFailureTakesTheEngineOfflineAndEndsTheCall() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val remote = rig.startCall()
        rig.listening.fail(FakeIoException("socket gone"))
        rig.settle()
        assertEquals(CallState.Offline("socket gone"), rig.state)
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.NORMAL)), remote.byes)
        rig.assertAllClosedOnce()
        assertEquals(1, rig.listening.closeCount)
    }

    @Test
    fun commandsInTheWrongStateAreIgnored() = runTest {
        val rig = Rig(this)
        rig.engine.dial(SMALL)
        rig.engine.accept()
        rig.engine.decline()
        rig.engine.hangUp()
        rig.engine.dismiss()
        rig.settle()
        assertEquals(CallState.Offline(), rig.state)
        rig.goOnline()
        rig.engine.accept()
        rig.engine.hangUp()
        rig.settle()
        assertEquals(CallState.Online(MID), rig.state)
        assertTrue(rig.dialer.dialed.isEmpty())
    }

    // ---- Dialing -------------------------------------------------------------------------------

    @Test
    fun dialSendsHelloAndAcceptStartsTheCall() = runTest {
        val media = RecordingMedia()
        val rig = Rig(this, media = media)
        rig.goOnline()
        val remote = rig.answerDialsWith()
        rig.engine.dial(SMALL.removeSuffix(".onion").uppercase())
        rig.settle()
        assertEquals(listOf(SMALL), rig.dialer.dialed)
        assertEquals(CallState.Dialing(SMALL), rig.state)
        assertEquals(listOf<Frame>(Frame.Hello(FrameCodec.VERSION, "Me", 24_000u)), remote.received)
        assertEquals(0, media.runs)

        remote.send(Frame.Accept(FrameCodec.VERSION))
        rig.settle()
        assertEquals(CallState.InCall(PeerInfo(SMALL, null), CallDirection.OUTGOING), rig.state)
        assertEquals(1, media.runs)
        // ACCEPT is ours only when we are the callee.
        assertTrue(remote.received.none { it is Frame.Accept })
    }

    @Test
    fun handshakeTimesOutAfterTenSecondsWithoutAccept() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val remote = rig.answerDialsWith()
        rig.engine.dial(SMALL)
        rig.settle()
        advance(9_999)
        assertEquals(CallState.Dialing(SMALL), rig.state)
        advance(1)
        assertEquals(EndReason.HANDSHAKE_TIMEOUT, rig.lastCall()?.reason)
        assertEquals(CallDirection.OUTGOING, rig.lastCall()?.direction)
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.NORMAL)), remote.byes)
        rig.assertAllClosedOnce()
    }

    @Test
    fun handshakeClockStartsWhenHelloIsWrittenNotWhenDialStarts() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val remote = rig.answerDialsWith()
        val slow = CompletableDeferred<Unit>()
        val connect = rig.dialer.script
        rig.dialer.script = { slow.await(); connect(it) }
        rig.engine.dial(SMALL)
        rig.settle()
        advance(20_000) // slow connect, longer than the handshake window
        assertEquals(CallState.Dialing(SMALL), rig.state)
        slow.complete(Unit)
        rig.settle()
        advance(9_999)
        assertEquals(CallState.Dialing(SMALL), rig.state)
        remote.send(Frame.Accept(FrameCodec.VERSION))
        rig.settle()
        assertIs<CallState.InCall>(rig.state)
    }

    @Test
    fun peerOfflineIsADistinctStateThatBehavesLikeIdle() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        rig.dialer.script = { DialOutcome.PeerOffline }
        rig.engine.dial(SMALL)
        rig.settle()
        assertEquals(CallState.PeerOffline(SMALL), rig.state)

        // Can be dialed again from PeerOffline...
        val remote = rig.answerDialsWith()
        rig.engine.dial(SMALL)
        rig.settle()
        assertEquals(CallState.Dialing(SMALL), rig.state)
        remote.send(Frame.Accept(FrameCodec.VERSION))
        rig.settle()
        assertIs<CallState.InCall>(rig.state)
    }

    @Test
    fun peerOfflineStillRingsAndCanBeDismissed() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        rig.dialer.script = { DialOutcome.PeerOffline }
        rig.engine.dial(SMALL)
        rig.settle()
        rig.engine.dismiss()
        rig.settle()
        assertEquals(CallState.Online(MID), rig.state)

        rig.engine.dial(SMALL)
        rig.settle()
        assertEquals(CallState.PeerOffline(SMALL), rig.state)
        rig.inbound().send(Frame.Hello(FrameCodec.VERSION, "Sam", 24_000u))
        rig.settle()
        assertEquals(CallState.Ringing(PeerInfo(null, "Sam")), rig.state)
    }

    @Test
    fun otherDialFailuresReturnToOnlineWithTheReason() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        for ((outcome, detail) in listOf(
            DialOutcome.AuthRequired to "Peer requires authorization",
            DialOutcome.Timeout to "Timed out connecting",
            DialOutcome.Failed("socks exploded") to "socks exploded",
        )) {
            rig.dialer.script = { outcome }
            rig.engine.dial(SMALL)
            rig.settle()
            val end = rig.lastCall()
            assertEquals(EndReason.DIAL_FAILED, end?.reason)
            assertEquals(detail, end?.detail)
        }
        // A dialer that throws is a failure, not a crash.
        rig.dialer.script = { throw FakeIoException("boom") }
        rig.engine.dial(SMALL)
        rig.settle()
        assertEquals(EndReason.DIAL_FAILED, rig.lastCall()?.reason)
        assertEquals("boom", rig.lastCall()?.detail)
    }

    @Test
    fun aDialerThatNeverReturnsIsBoundedByTheConnectTimeout() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        rig.dialer.script = { awaitCancellation() }
        rig.engine.dial(SMALL)
        rig.settle()
        advance(44_999)
        assertEquals(CallState.Dialing(SMALL), rig.state)
        advance(1)
        assertEquals(EndReason.DIAL_FAILED, rig.lastCall()?.reason)
    }

    @Test
    fun calleeAnswersWithByeNormalOrBusyOrVersion() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        for ((reason, expected) in listOf(
            ByeReason.NORMAL to EndReason.PEER_DECLINED,
            ByeReason.BUSY to EndReason.PEER_BUSY,
            ByeReason.VERSION to EndReason.VERSION_MISMATCH,
            ByeReason.PROTOCOL_ERROR to EndReason.PROTOCOL_ERROR,
        )) {
            val remote = rig.answerDialsWith()
            rig.engine.dial(SMALL)
            rig.settle()
            remote.send(Frame.Bye(reason))
            rig.settle()
            assertEquals(expected, rig.lastCall()?.reason, "$reason")
        }
        rig.assertAllClosedOnce()
    }

    @Test
    fun connectionDroppedBeforeAcceptIsConnectionLost() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val remote = rig.answerDialsWith()
        rig.engine.dial(SMALL)
        rig.settle()
        remote.drop()
        rig.settle()
        assertEquals(EndReason.CONNECTION_LOST, rig.lastCall()?.reason)
        rig.assertAllClosedOnce()
    }

    @Test
    fun anUnexpectedFrameBeforeAcceptIsAProtocolError() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val remote = rig.answerDialsWith()
        rig.engine.dial(SMALL)
        rig.settle()
        remote.send(Frame.Ping(1u))
        rig.settle()
        assertEquals(EndReason.PROTOCOL_ERROR, rig.lastCall()?.reason)
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.PROTOCOL_ERROR)), remote.byes)
    }

    @Test
    fun hangUpWhileConnectingCancelsTheDialer() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        var cancelled = false
        rig.dialer.script = {
            try { awaitCancellation() } finally { cancelled = true }
        }
        rig.engine.dial(SMALL)
        rig.settle()
        rig.engine.hangUp()
        rig.settle()
        assertTrue(cancelled)
        assertEquals(EndReason.LOCAL_HANGUP, rig.lastCall()?.reason)
    }

    @Test
    fun streamThatArrivesAfterHangUpIsClosedNotLeaked() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val remote = rig.answerDialsWith()
        val connect = rig.dialer.script
        val gate = CompletableDeferred<Unit>()
        rig.dialer.script = { gate.await(); connect(it) }
        rig.engine.dial(SMALL)
        rig.settle()
        // The dialer finishes in the same instant the user hangs up; its result reaches the engine after
        // the hang-up and must be closed rather than adopted.
        gate.complete(Unit)
        rig.engine.hangUp()
        rig.settle()
        assertEquals(EndReason.LOCAL_HANGUP, rig.lastCall()?.reason)
        assertTrue(remote.endOfStream)
        assertTrue(remote.received.isEmpty()) // never said HELLO on a call nobody wants
        rig.assertAllClosedOnce()
    }

    @Test
    fun hangUpAfterHelloSendsByeAndClosesOnce() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val remote = rig.answerDialsWith()
        rig.engine.dial(SMALL)
        rig.settle()
        rig.engine.hangUp()
        rig.engine.hangUp()
        rig.settle()
        assertEquals(EndReason.LOCAL_HANGUP, rig.lastCall()?.reason)
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.NORMAL)), remote.byes)
        rig.assertAllClosedOnce()
    }

    @Test
    fun invalidOrOwnOnionIsRejectedWithoutDialing() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        for (bad in listOf("not-an-onion", "", MID, MID.uppercase())) {
            rig.engine.dial(bad)
            rig.settle()
            assertEquals(EndReason.INVALID_PEER, rig.lastCall()?.reason, bad)
        }
        assertTrue(rig.dialer.dialed.isEmpty())
    }

    // ---- Ringing -------------------------------------------------------------------------------

    @Test
    fun incomingHelloRingsAndAcceptStartsTheCall() = runTest {
        val media = RecordingMedia()
        val rig = Rig(this, media = media)
        rig.goOnline()
        val remote = rig.inbound()
        assertEquals(CallState.Online(MID), rig.state) // no HELLO yet
        remote.send(Frame.Hello(FrameCodec.VERSION, "Sam", 24_000u))
        rig.settle()
        assertEquals(CallState.Ringing(PeerInfo(null, "Sam")), rig.state)
        assertTrue(remote.received.isEmpty()) // nothing is sent while ringing

        rig.engine.accept()
        rig.settle()
        assertEquals(CallState.InCall(PeerInfo(null, "Sam"), CallDirection.INCOMING), rig.state)
        assertEquals(Frame.Accept(FrameCodec.VERSION), remote.received.first())
        assertEquals(1, media.runs)
    }

    @Test
    fun declineSendsByeNormalAndReturnsOnline() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val remote = rig.inbound()
        remote.send(Frame.Hello(FrameCodec.VERSION, "Sam", 24_000u))
        rig.settle()
        rig.engine.decline()
        rig.engine.decline()
        rig.settle()
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.NORMAL)), remote.received)
        assertEquals(EndReason.LOCAL_HANGUP, rig.lastCall()?.reason)
        assertEquals(CallDirection.INCOMING, rig.lastCall()?.direction)
        rig.assertAllClosedOnce()
    }

    @Test
    fun ringingEndsAfterThirtySecondsAndNotBefore() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val remote = rig.inbound()
        remote.send(Frame.Hello(FrameCodec.VERSION, "Sam", 24_000u))
        rig.settle()
        advance(29_999)
        assertIs<CallState.Ringing>(rig.state)
        advance(1)
        assertEquals(EndReason.RING_TIMEOUT, rig.lastCall()?.reason)
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.NORMAL)), remote.byes)
        rig.assertAllClosedOnce()
    }

    @Test
    fun callerHangingUpWhileRingingEndsTheRing() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val remote = rig.inbound()
        remote.send(Frame.Hello(FrameCodec.VERSION, "Sam", 24_000u))
        rig.settle()
        remote.send(Frame.Bye(ByeReason.NORMAL))
        rig.settle()
        assertEquals(EndReason.REMOTE_HANGUP, rig.lastCall()?.reason)
        // Accepting afterwards is ignored.
        rig.engine.accept()
        rig.settle()
        assertIs<CallState.Online>(rig.state)
        rig.assertAllClosedOnce()
    }

    @Test
    fun callerDroppingWhileRingingEndsTheRing() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val remote = rig.inbound()
        remote.send(Frame.Hello(FrameCodec.VERSION, "Sam", 24_000u))
        rig.settle()
        remote.drop()
        rig.settle()
        assertEquals(EndReason.REMOTE_HANGUP, rig.lastCall()?.reason)
        rig.assertAllClosedOnce()
    }

    @Test
    fun incomingWithWrongVersionGetsByeVersionAndNoRing() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val remote = rig.inbound()
        // Raw HELLO with version 9.
        remote.channel.write(byteArrayOf(1, 9, 0, 0, 0, 0, 0, 0))
        rig.settle()
        assertEquals(CallState.Online(MID), rig.state)
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.VERSION)), remote.byes)
        rig.assertAllClosedOnce()
    }

    @Test
    fun streamThatNeverSaysHelloIsDroppedAndFreesTheSlot() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val silent = rig.inbound("silent")
        rig.settle()
        // While it occupies the single slot, another stream gets BUSY.
        val second = rig.inbound("second")
        rig.settle()
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.BUSY)), second.byes)
        advance(10_000)
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.PROTOCOL_ERROR)), silent.byes)
        // Slot is free again.
        val third = rig.inbound("third")
        third.send(Frame.Hello(FrameCodec.VERSION, "Sam", 24_000u))
        rig.settle()
        assertIs<CallState.Ringing>(rig.state)
    }

    @Test
    fun nonHelloFirstFrameIsAProtocolError() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val remote = rig.inbound()
        remote.send(Frame.Ping(5u))
        rig.settle()
        assertEquals(CallState.Online(MID), rig.state)
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.PROTOCOL_ERROR)), remote.byes)
    }

    // ---- Busy ----------------------------------------------------------------------------------

    @Test
    fun secondConcurrentConnectionGetsBusyWhileRinging() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val first = rig.inbound("first")
        first.send(Frame.Hello(FrameCodec.VERSION, "Sam", 24_000u))
        rig.settle()
        val second = rig.inbound("second")
        second.send(Frame.Hello(FrameCodec.VERSION, "Kim", 24_000u))
        rig.settle()
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.BUSY)), second.received)
        assertTrue(second.endOfStream)
        assertEquals(CallState.Ringing(PeerInfo(null, "Sam")), rig.state)
        assertTrue(first.received.isEmpty())
    }

    @Test
    fun secondConcurrentConnectionGetsBusyDuringACallAndTheCallSurvives() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val callee = rig.startCall()
        val intruder = rig.inbound("intruder")
        rig.settle()
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.BUSY)), intruder.received)
        assertIs<CallState.InCall>(rig.state)
        assertFalse(callee.endOfStream)
    }

    @Test
    fun unrelatedCallerIsBusyWhileDialingWhenTheListenerKnowsWhoItIs() = runTest {
        val rig = Rig(this, own = BIG)
        rig.goOnline()
        val outbound = rig.answerDialsWith()
        rig.engine.dial(SMALL)
        rig.settle()
        val stranger = rig.inbound("stranger", peerOnion = MID)
        rig.settle()
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.BUSY)), stranger.received)
        assertEquals(CallState.Dialing(SMALL), rig.state)
        assertFalse(outbound.endOfStream)
    }

    // ---- Glare ---------------------------------------------------------------------------------

    @Test
    fun glareSmallerOnionKeepsItsOutboundCall() = runTest {
        val rig = Rig(this, own = SMALL)
        rig.goOnline()
        val outbound = rig.answerDialsWith("out")
        rig.engine.dial(BIG)
        rig.settle()
        val inbound = rig.inbound("in")
        inbound.send(Frame.Hello(FrameCodec.VERSION, "Peer", 24_000u))
        rig.settle()
        // The inbound stream is refused with GLARE and closed...
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.GLARE)), inbound.received)
        assertTrue(inbound.endOfStream)
        // ...and the outbound call carries on.
        assertEquals(CallState.Dialing(BIG), rig.state)
        assertTrue(outbound.byes.isEmpty())
        outbound.send(Frame.Accept(FrameCodec.VERSION))
        rig.settle()
        assertEquals(CallState.InCall(PeerInfo(BIG, null), CallDirection.OUTGOING), rig.state)
        rig.engine.hangUp()
        rig.settle()
        rig.assertAllClosedOnce()
    }

    @Test
    fun glareLargerOnionDropsOutboundAndAcceptsInbound() = runTest {
        val media = RecordingMedia()
        val rig = Rig(this, own = BIG, media = media)
        rig.goOnline()
        val outbound = rig.answerDialsWith("out")
        rig.engine.dial(SMALL)
        rig.settle()
        val inbound = rig.inbound("in")
        inbound.send(Frame.Hello(FrameCodec.VERSION, "Peer", 24_000u))
        rig.settle()
        // Outbound dropped with GLARE; inbound accepted without ringing.
        assertEquals(listOf<Frame>(Frame.Hello(FrameCodec.VERSION, "Me", 24_000u), Frame.Bye(ByeReason.GLARE)), outbound.received)
        assertTrue(outbound.endOfStream)
        assertEquals(Frame.Accept(FrameCodec.VERSION), inbound.received.first())
        assertEquals(CallState.InCall(PeerInfo(SMALL, "Peer"), CallDirection.INCOMING), rig.state)
        assertEquals(1, media.runs)
        rig.engine.hangUp()
        rig.settle()
        rig.assertAllClosedOnce()
    }

    @Test
    fun glareLargerOnionDropsADialStillConnecting() = runTest {
        val rig = Rig(this, own = BIG)
        rig.goOnline()
        var cancelled = false
        rig.dialer.script = {
            try { awaitCancellation() } finally { cancelled = true }
        }
        rig.engine.dial(SMALL)
        rig.settle()
        val inbound = rig.inbound("in", peerOnion = SMALL)
        inbound.send(Frame.Hello(FrameCodec.VERSION, "Peer", 24_000u))
        rig.settle()
        assertTrue(cancelled)
        assertEquals(CallState.InCall(PeerInfo(SMALL, "Peer"), CallDirection.INCOMING), rig.state)
        assertEquals(Frame.Accept(FrameCodec.VERSION), inbound.received.first())
        rig.engine.hangUp()
        rig.settle()
        rig.assertAllClosedOnce()
    }

    @Test
    fun largerSideThatGetsByeGlareWaitsForTheInboundCall() = runTest {
        val rig = Rig(this, own = BIG)
        rig.goOnline()
        val outbound = rig.answerDialsWith("out")
        rig.engine.dial(SMALL)
        rig.settle()
        // The smaller peer refuses our stream because it is dialing us; its own call is still in flight.
        outbound.send(Frame.Bye(ByeReason.GLARE))
        rig.settle()
        assertEquals(CallState.Dialing(SMALL), rig.state)
        assertTrue(outbound.endOfStream)
        advance(5_000)
        val inbound = rig.inbound("in")
        inbound.send(Frame.Hello(FrameCodec.VERSION, "Peer", 24_000u))
        rig.settle()
        assertEquals(CallState.InCall(PeerInfo(SMALL, "Peer"), CallDirection.INCOMING), rig.state)
        assertEquals(Frame.Accept(FrameCodec.VERSION), inbound.received.first())
        rig.engine.hangUp()
        rig.settle()
        rig.assertAllClosedOnce()
    }

    @Test
    fun largerSideGivesUpWhenTheExpectedInboundNeverComes() = runTest {
        val rig = Rig(this, own = BIG)
        rig.goOnline()
        val outbound = rig.answerDialsWith("out")
        rig.engine.dial(SMALL)
        rig.settle()
        outbound.send(Frame.Bye(ByeReason.GLARE))
        rig.settle()
        advance(9_999)
        assertEquals(CallState.Dialing(SMALL), rig.state)
        advance(1)
        assertEquals(EndReason.HANDSHAKE_TIMEOUT, rig.lastCall()?.reason)
    }

    @Test
    fun smallerSideThatGetsByeGlareIsAProtocolError() = runTest {
        val rig = Rig(this, own = SMALL)
        rig.goOnline()
        val outbound = rig.answerDialsWith("out")
        rig.engine.dial(BIG)
        rig.settle()
        outbound.send(Frame.Bye(ByeReason.GLARE))
        rig.settle()
        assertEquals(EndReason.PROTOCOL_ERROR, rig.lastCall()?.reason)
    }

    /** Two real engines on one in-memory network that dial each other in the same instant. */
    @Test
    fun bothEnginesDialEachOtherAndExactlyOneCallSurvives() = runTest {
        val net = FakeNetwork(this)
        val small = net.node(SMALL)
        val big = net.node(BIG)
        small.goOnline()
        big.goOnline()
        small.engine.dial(BIG)
        big.engine.dial(SMALL)
        runCurrent()
        advance(1_000)

        assertEquals(CallState.InCall(PeerInfo(BIG, null), CallDirection.OUTGOING), small.state)
        assertEquals(CallState.InCall(PeerInfo(SMALL, "n-bbb"), CallDirection.INCOMING), big.state)
        // Still one call two seconds later: keepalives flow on the surviving stream.
        advance(20_000)
        assertIs<CallState.InCall>(small.state)
        assertIs<CallState.InCall>(big.state)

        small.engine.hangUp()
        runCurrent()
        assertIs<CallState.Online>(small.state)
        assertIs<CallState.Online>(big.state)
        assertEquals(EndReason.REMOTE_HANGUP, (big.state as CallState.Online).lastCall?.reason)
        net.assertEveryStreamClosedOnce()
    }

    // ---- In call: keepalive, inactivity, teardown ----------------------------------------------

    @Test
    fun pingsEveryTwoSecondsAndPeerPingsGetPongs() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val remote = rig.startCall()
        assertEquals(1, remote.pings.size) // immediate first ping
        advance(1_999)
        assertEquals(1, remote.pings.size)
        advance(1)
        assertEquals(2, remote.pings.size)
        advance(6_000)
        assertEquals(5, remote.pings.size)

        remote.send(Frame.Ping(1234u))
        rig.settle()
        val pong = remote.received.filterIsInstance<Frame.Pong>().single()
        assertEquals(1234u, pong.echoedTsMs)
    }

    @Test
    fun silentPeerEndsTheCallWithTimeoutAtFifteenSeconds() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val remote = rig.startCall()
        advance(14_999)
        assertIs<CallState.InCall>(rig.state)
        advance(1)
        assertEquals(EndReason.TIMEOUT, rig.lastCall()?.reason)
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.NORMAL)), remote.byes)
        rig.assertAllClosedOnce()
    }

    @Test
    fun pongAndAudioExtendTheInactivityDeadlineButPeerPingsDoNot() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val remote = rig.startCall()
        advance(10_000)
        remote.send(Frame.Pong(0u, 0u))
        rig.settle()
        advance(14_999) // t = 24_999, deadline is 25_000
        assertIs<CallState.InCall>(rig.state)
        remote.send(Frame.Audio(1u, 20u, ByteArray(60)))
        rig.settle()
        advance(14_999) // deadline moved to 39_999
        remote.send(Frame.Ping(7u)) // a PING is not liveness evidence
        rig.settle()
        assertIs<CallState.InCall>(rig.state)
        advance(1)
        assertEquals(EndReason.TIMEOUT, rig.lastCall()?.reason)
    }

    @Test
    fun cleanByeFromPeerReturnsOnlineIdle() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val remote = rig.startCall()
        remote.send(Frame.Bye(ByeReason.NORMAL))
        rig.settle()
        assertEquals(CallState.Online(MID, CallEnd(PeerInfo(SMALL, null), EndReason.REMOTE_HANGUP, CallDirection.OUTGOING)), rig.state)
        // No BYE back: the peer already hung up.
        assertTrue(remote.byes.isEmpty())
        rig.assertAllClosedOnce()
        // Ready for the next call.
        val next = rig.inbound("next")
        next.send(Frame.Hello(FrameCodec.VERSION, "Sam", 24_000u))
        rig.settle()
        assertIs<CallState.Ringing>(rig.state)
    }

    @Test
    fun localHangUpSendsByeNormal() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val remote = rig.startCall()
        rig.engine.hangUp()
        rig.settle()
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.NORMAL)), remote.byes)
        assertEquals(EndReason.LOCAL_HANGUP, rig.lastCall()?.reason)
        assertTrue(remote.endOfStream)
        rig.assertAllClosedOnce()
    }

    @Test
    fun streamDroppedMidCallIsConnectionLost() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val remote = rig.startCall()
        remote.drop()
        rig.settle()
        assertEquals(EndReason.CONNECTION_LOST, rig.lastCall()?.reason)
        rig.assertAllClosedOnce()
    }

    @Test
    fun handshakeFrameInsideACallIsAProtocolError() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val remote = rig.startCall()
        remote.send(Frame.Hello(FrameCodec.VERSION, "x", 0u))
        rig.settle()
        assertEquals(EndReason.PROTOCOL_ERROR, rig.lastCall()?.reason)
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.PROTOCOL_ERROR)), remote.byes)
    }

    @Test
    fun malformedBytesInACallEndItAsAProtocolError() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val remote = rig.startCall()
        remote.channel.write(byteArrayOf(99))
        rig.settle()
        assertEquals(EndReason.PROTOCOL_ERROR, rig.lastCall()?.reason)
        rig.assertAllClosedOnce()
    }

    // ---- Media ---------------------------------------------------------------------------------

    @Test
    fun mediaReceivesAudioAndCanSend() = runTest {
        val media = RecordingMedia()
        val rig = Rig(this, media = media)
        rig.goOnline()
        val remote = rig.startCall()
        remote.send(Frame.Audio(1u, 20u, byteArrayOf(1, 2, 3)))
        remote.send(Frame.Audio(2u, 40u, byteArrayOf(4)))
        rig.settle()
        assertEquals(listOf(1.toUShort(), 2.toUShort()), media.audio.map { it.seq })
        media.link!!.sendAudio(9u, 99u, byteArrayOf(7))
        rig.settle()
        assertEquals(Frame.Audio(9u, 99u, byteArrayOf(7)), remote.received.filterIsInstance<Frame.Audio>().single())
        assertEquals(SMALL, media.link!!.peer.onion)
    }

    @Test
    fun mediaFailureEndsTheCallWithMediaError() = runTest {
        val media = RecordingMedia().also { it.failure = IllegalStateException("no mic") }
        val rig = Rig(this, media = media)
        rig.goOnline()
        val remote = rig.answerDialsWith()
        rig.engine.dial(SMALL)
        rig.settle()
        remote.send(Frame.Accept(FrameCodec.VERSION))
        rig.settle()
        assertEquals(EndReason.MEDIA_ERROR, rig.lastCall()?.reason)
        assertEquals("no mic", rig.lastCall()?.detail)
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.NORMAL)), remote.byes)
        rig.assertAllClosedOnce()
    }

    @Test
    fun mediaIsCancelledAtTeardownAndNextCallWaitsForItsCleanup() = runTest {
        val media = RecordingMedia()
        val rig = Rig(this, media = media)
        rig.goOnline()
        rig.startCall()
        rig.engine.hangUp()
        rig.settle()
        assertEquals(1, media.cleanups)
        rig.startCall()
        assertEquals(2, media.runs)
        assertEquals(1, media.maxConcurrent)
    }

    @Test
    fun sendingAudioAfterTheCallEndedFails() = runTest {
        val media = RecordingMedia()
        val rig = Rig(this, media = media)
        rig.goOnline()
        rig.startCall()
        val link = media.link!!
        rig.engine.hangUp()
        rig.settle()
        var failed = false
        try {
            link.sendAudio(1u, 1u, ByteArray(1))
        } catch (_: ConnectionClosedException) {
            failed = true
        }
        assertTrue(failed)
    }

    // ---- Cancellation and duplicate teardown ---------------------------------------------------

    @Test
    fun hangUpAndPeerByeRacingCloseTheStreamOnce() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val remote = rig.startCall()
        rig.engine.hangUp()
        rig.engine.hangUp()
        remote.send(Frame.Bye(ByeReason.NORMAL))
        rig.settle()
        assertIs<CallState.Online>(rig.state)
        rig.assertAllClosedOnce()
    }

    @Test
    fun timeoutAndLocalHangUpAtTheSameInstantCloseOnce() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        rig.startCall()
        advanceTimeBy(15_000)
        rig.engine.hangUp() // queued before the timer event is handled
        runCurrent()
        assertIs<CallState.Online>(rig.state)
        rig.assertAllClosedOnce()
    }

    @Test
    fun goOfflineDuringACallHangsUpAndClosesEverythingOnce() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val remote = rig.startCall()
        val waiting = rig.inbound("waiting") // pending, no HELLO yet
        rig.settle()
        rig.engine.goOffline()
        rig.engine.goOffline()
        rig.settle()
        assertEquals(CallState.Offline(), rig.state)
        assertEquals(listOf<Frame>(Frame.Bye(ByeReason.NORMAL)), remote.byes)
        assertTrue(waiting.endOfStream)
        assertEquals(1, rig.listening.closeCount)
        rig.assertAllClosedOnce()
    }

    @Test
    fun cancellingTheEngineScopeMidCallClosesEverythingOnce() = runTest {
        val job = Job(coroutineContext[Job])
        val engineScope = CoroutineScope(coroutineContext + job)
        val rig = Rig(this, engineScope = engineScope)
        rig.goOnline()
        val remote = rig.startCall()
        engineScope.cancel()
        runCurrent()
        assertEquals(CallState.Offline(), rig.state)
        assertTrue(remote.endOfStream)
        assertEquals(1, rig.listening.closeCount)
        rig.assertAllClosedOnce()
    }

    @Test
    fun cancellingTheScopeWhileDialingAndWhileRingingClosesEveryStream() = runTest {
        for (ringing in listOf(false, true)) {
            val engineScope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            val rig = Rig(this, engineScope = engineScope)
            rig.goOnline()
            if (ringing) {
                rig.inbound().send(Frame.Hello(FrameCodec.VERSION, "Sam", 24_000u))
            } else {
                rig.answerDialsWith()
                rig.engine.dial(SMALL)
            }
            rig.settle()
            engineScope.cancel()
            runCurrent()
            assertEquals(CallState.Offline(), rig.state)
            rig.assertAllClosedOnce()
            assertEquals(1, rig.listening.closeCount)
        }
    }

    @Test
    fun closeIsIdempotentAndWaitsForTransportsToClose() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        rig.startCall()
        rig.engine.close()
        rig.engine.close()
        assertEquals(CallState.Offline(), rig.state)
        rig.assertAllClosedOnce()
        assertEquals(1, rig.listening.closeCount)
        // Commands after close are harmless.
        rig.engine.goOnline()
        rig.engine.dial(SMALL)
        runCurrent()
        assertEquals(CallState.Offline(), rig.state)
    }

    @Test
    fun stuckTransportCannotBlockTheNextCallOrTheFinalClose() = runTest {
        val rig = Rig(this)
        rig.goOnline()
        val remote = rig.startCall()
        rig.channels.first().stallWrites = true // the BYE can never be written
        rig.engine.hangUp()
        rig.settle()
        assertIs<CallState.Online>(rig.state) // state does not wait for the transport
        assertEquals(0, rig.channels.first().closeCount)
        advance(2_000) // BYE budget exhausted
        assertEquals(1, rig.channels.first().closeCount)
        assertTrue(remote.byes.isEmpty())
    }

    @Test
    fun configRejectsNonPositiveTimeouts() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { CallEngineConfig(pingIntervalMs = 0) }
    }

    @Test
    fun canonicalOnionAcceptsCaseAndSuffixVariantsOnly() {
        assertEquals(SMALL, canonicalOnion(SMALL.uppercase()))
        assertEquals(SMALL, canonicalOnion(" " + SMALL.removeSuffix(".onion") + " "))
        assertEquals(null, canonicalOnion("b".repeat(55)))
        assertEquals(null, canonicalOnion("1".repeat(56)))
    }
}
