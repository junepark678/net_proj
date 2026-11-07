package dev.junepark.oniontalkie.tor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class TorParsingTest {
    @Test fun parsesSingleMultilineAndDataReplies() = runTest {
        val transport = TranscriptTransport()
        transport.feed("250 OK", "251 Nothing to do", "552-first", "552 last",
            "250+first=", "..", "...dot", ".", "250+second=", ".", "250 OK")
        val reader = ReplyReader(transport)
        assertEquals(TorReply(250, listOf(TorReplyLine("OK"))), reader.read {})
        assertEquals(251, reader.read {}.code)
        assertEquals(TorReply(552, listOf(TorReplyLine("first"), TorReplyLine("last"))), reader.read {})
        assertEquals(TorReply(250, listOf(
            TorReplyLine("first=", listOf(".", "..dot")), TorReplyLine("second=", emptyList()), TorReplyLine("OK"),
        )), reader.read {})
    }

    @Test fun rejectsMalformedOrChangingStatusHeaders() = runTest {
        for (lines in listOf(listOf("25 OK"), listOf("abc OK"), listOf("250?bad"), listOf("099 bad"), listOf("250-a", "251 b"))) {
            val transport = TranscriptTransport()
            transport.feed(*lines.toTypedArray())
            assertFailsWith<TorProtocolException> { ReplyReader(transport).read {} }
        }
    }

    @Test fun parsesQuotedAndEscapedEventFields() {
        val event = parse("STATUS_CLIENT WARN BOOTSTRAP SUMMARY=\"A \\\"quote\\\" and \\134slash\\nline\" TAG=loading PROGRESS=45 UNKNOWN=kept")
        val bootstrap = assertIs<TorEvent.Bootstrap>(event)
        assertEquals(45, bootstrap.progress)
        assertEquals("A \"quote\" and \\slash\nline", bootstrap.summary)
        assertEquals("kept", bootstrap.fields["UNKNOWN"])
    }

    @Test fun circuitsCanLackPathsAndDescriptorIdsAreOptional() {
        val circuit = assertIs<TorEvent.Circuit>(parse("CIRC 2 LAUNCHED PURPOSE=GENERAL"))
        assertEquals(emptyList(), circuit.path)
        assertEquals("GENERAL", circuit.fields["PURPOSE"])
        val descriptor = assertIs<TorEvent.HiddenServiceDescriptor>(parse("HS_DESC RECEIVED address NO_AUTH \$dir abc123 REPLICA=0"))
        assertEquals("abc123", descriptor.descriptorId)
        assertEquals("\$dir", descriptor.directory)
        assertEquals("0", descriptor.fields["REPLICA"])
    }

    @Test fun unknownOrMalformedEventsArePreserved() {
        for (line in listOf("FUTURE extra", "CIRC", "HS_DESC FAILED", "STATUS_CLIENT NOTICE OTHER PROGRESS=50",
            "STATUS_CLIENT NOTICE BOOTSTRAP PROGRESS=101", "STATUS_CLIENT NOTICE BOOTSTRAP PROGRESS=bad",
            "STATUS_CLIENT NOTICE BOOTSTRAP PROGRESS=1 SUMMARY=\"unclosed")) {
            assertEquals(line, assertIs<TorEvent.Unknown>(parse(line)).raw.lines.single().text)
        }
    }

    private fun parse(line: String): TorEvent = parseEvent(TorReply(650, listOf(TorReplyLine(line))))
}
