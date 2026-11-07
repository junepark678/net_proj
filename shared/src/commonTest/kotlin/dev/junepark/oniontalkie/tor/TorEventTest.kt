package dev.junepark.oniontalkie.tor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TorEventTest {
    private fun event(line: String) = parseEvent(TorReply(650, listOf(line)))

    @Test
    fun bootstrapEvent() {
        val e = event("STATUS_CLIENT NOTICE BOOTSTRAP PROGRESS=10 TAG=conn_done SUMMARY=\"Connected to a relay\"")
        assertEquals(TorEvent.Bootstrap("NOTICE", 10, "conn_done", "Connected to a relay"), e)
    }

    @Test
    fun bootstrapPhaseFromGetInfoValue() {
        val b = parseBootstrap(splitArgs("NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\""))
        assertEquals(100, b?.progress)
        assertNull(parseBootstrap(splitArgs("NOTICE CIRCUIT_ESTABLISHED")))
    }

    @Test
    fun nonBootstrapStatusClientIsOther() {
        assertIs<TorEvent.Other>(event("STATUS_CLIENT NOTICE CIRCUIT_ESTABLISHED"))
    }

    @Test
    fun circuitWithPath() {
        val e = assertIs<TorEvent.Circuit>(
            event("CIRC 1000 EXTENDED \$AAA~guard,\$BBB=mid BUILD_FLAGS=NEED_CAPACITY PURPOSE=GENERAL TIME_CREATED=2026-01-01T00:00:00.1"),
        )
        assertEquals("1000", e.id)
        assertEquals("EXTENDED", e.status)
        assertEquals(listOf("\$AAA~guard", "\$BBB=mid"), e.path)
        assertEquals("GENERAL", e.purpose)
        assertEquals("NEED_CAPACITY", e.args["BUILD_FLAGS"])
        assertNull(e.reason)
    }

    @Test
    fun circuitWithoutPathAndWithReason() {
        val launched = assertIs<TorEvent.Circuit>(event("CIRC 7 LAUNCHED BUILD_FLAGS=ONEHOP_TUNNEL PURPOSE=HS_CLIENT_REND"))
        assertEquals(emptyList(), launched.path)
        assertEquals("HS_CLIENT_REND", launched.purpose)

        val failed = assertIs<TorEvent.Circuit>(event("CIRC 7 FAILED \$AAA~g PURPOSE=GENERAL REASON=TIMEOUT"))
        assertEquals("TIMEOUT", failed.reason)
        assertEquals(listOf("\$AAA~g"), failed.path)
    }

    @Test
    fun hsDescWithDescriptorIdAndReason() {
        val e = assertIs<TorEvent.HsDesc>(
            event("HS_DESC FAILED abcdefghij NO_AUTH \$HSDIR~dir descid123 REASON=NOT_FOUND"),
        )
        assertEquals("FAILED", e.action)
        assertEquals("abcdefghij", e.address)
        assertEquals("NO_AUTH", e.authType)
        assertEquals("\$HSDIR~dir", e.hsDir)
        assertEquals("descid123", e.descriptorId)
        assertEquals("NOT_FOUND", e.reason)
    }

    @Test
    fun hsDescWithoutDescriptorId() {
        val e = assertIs<TorEvent.HsDesc>(event("HS_DESC REQUESTED abcdefghij NO_AUTH UNKNOWN"))
        assertEquals("UNKNOWN", e.hsDir)
        assertNull(e.descriptorId)
    }

    @Test
    fun unknownAndMalformedEventsAreOther() {
        assertEquals(TorEvent.Other("NS", "NS stuff"), event("NS stuff"))
        assertIs<TorEvent.Other>(event("CIRC 1"))
    }
}
