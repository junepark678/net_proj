package dev.junepark.oniontalkie.tor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplyParserTest {
    private fun parse(vararg lines: String): List<TorReply> {
        val p = ReplyParser()
        return lines.mapNotNull { p.feed(it) }
    }

    @Test
    fun singleLineOk() {
        val r = parse("250 OK").single()
        assertEquals(250, r.status)
        assertEquals(listOf("OK"), r.lines)
        assertTrue(r.isSuccess)
    }

    @Test
    fun multiLineReplyIsOneReply() {
        val r = parse(
            "250-PROTOCOLINFO 1",
            "250-AUTH METHODS=COOKIE,SAFECOOKIE COOKIEFILE=\"/tmp/c\"",
            "250 OK",
        ).single()
        assertEquals(listOf("PROTOCOLINFO 1", "AUTH METHODS=COOKIE,SAFECOOKIE COOKIEFILE=\"/tmp/c\"", "OK"), r.lines)
    }

    @Test
    fun dataBlockIsDotUnstuffed() {
        val r = parse("250+info/x=", "line one", "..starts with dot", ".", "250 OK").single()
        assertEquals(listOf("info/x=\nline one\n.starts with dot", "OK"), r.lines)
    }

    @Test
    fun errorAndUnnecessaryStatuses() {
        val (a, b) = parse("251 Operation was unnecessary", "552 Unrecognized key \"foo\"")
        assertTrue(a.isSuccess)
        assertEquals(251, a.status)
        assertEquals(552, b.status)
        assertTrue(!b.isSuccess)
    }

    @Test
    fun asyncEventsInterleavedBetweenReplies() {
        val replies = parse("650 CIRC 1 BUILT", "250 OK", "650 HS_DESC REQUESTED x NO_AUTH $1")
        assertEquals(listOf(650, 250, 650), replies.map { it.status })
        assertTrue(replies[0].isEvent)
    }

    @Test
    fun malformedLinesThrow() {
        assertFailsWith<TorProtocolException> { ReplyParser().feed("OK") }
        assertFailsWith<TorProtocolException> { ReplyParser().feed("25x OK") }
        assertFailsWith<TorProtocolException> { ReplyParser().feed("250?OK") }
        assertNull(ReplyParser().feed("250-partial"))
    }

    @Test
    fun splitArgsHandlesQuotes() {
        assertEquals(
            listOf("NOTICE", "BOOTSTRAP", "PROGRESS=50", "SUMMARY=Loading \"relay\" info", "X="),
            splitArgs("NOTICE BOOTSTRAP PROGRESS=50 SUMMARY=\"Loading \\\"relay\\\" info\"  X=\"\""),
        )
    }
}
