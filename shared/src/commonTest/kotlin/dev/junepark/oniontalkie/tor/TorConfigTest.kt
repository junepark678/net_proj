package dev.junepark.oniontalkie.tor

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class TorConfigTest {
    @Test
    fun torrcBindsBothPortsToLoopbackAndLetsTheOsChoosePorts() {
        val config = TorConfig(dataDir = "/tmp/Onion Dial/data")
        val rendered = renderTorrc(config, "/tmp/Onion Dial/control-port")

        assertContains(rendered, "SocksPort 127.0.0.1:auto")
        assertContains(rendered, "ControlPort 127.0.0.1:auto")
        assertContains(rendered, "CookieAuthentication 1")
        assertContains(rendered, "ControlPortWriteToFile \"/tmp/Onion Dial/control-port\"")
        assertContains(rendered, "DataDirectory \"/tmp/Onion Dial/data\"")
        assertContains(rendered, "ClientOnionAuthDir \"/tmp/Onion Dial/data/client-auth\"")
        assertEquals(config.dataDir, config.dataDirectory)
        assertFalse("9050" in rendered || "9051" in rendered)
    }

    @Test
    fun torrcPathEscapingPreservesWindowsSeparatorsAndQuotes() {
        assertEquals("\"C:\\\\Onion Dial\\\\peer\\\"s data\"", quoteTorrcPath("C:\\Onion Dial\\peer\"s data"))
    }

    @Test
    fun configurationRejectsNewlinesAndInvalidDeadlines() {
        assertFailsWith<IllegalArgumentException> { TorConfig("/tmp/unsafe\nClientOnly 0") }
        assertFailsWith<IllegalArgumentException> { TorConfig("/tmp/tor", shutdownTimeoutMillis = 5_001) }
    }
}
