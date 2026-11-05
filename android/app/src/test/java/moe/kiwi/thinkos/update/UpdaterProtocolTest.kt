package moe.kiwi.thinkos.update

import org.junit.Assert.*
import org.junit.Test

class UpdaterProtocolTest {
    private val status = """OK {"protocol":1,"keys":0,"transaction":false,"id":"","phase":0,"result":0,"rollback_attempted":false,"modules":[]}"""
    @Test fun requestsAreFixedSingleBoundedAsciiLines() {
        assertEquals("STAGE\n", UpdaterProtocol.request("STAGE").toString(Charsets.US_ASCII))
        assertEquals("SET_TIMEZONE Europe/Kiev\n", UpdaterProtocol.request("SET_TIMEZONE Europe/Kiev").toString(Charsets.US_ASCII))
        for (value in listOf("RUN id", "HELLO\nREBOOT", "GRANT_LANGUAGE another.package", "SET_TIMEZONE Etc/UTC;id", "SET_TIMEZONE " + "A".repeat(65)))
            assertThrows(IllegalArgumentException::class.java) { UpdaterProtocol.request(value) }
    }
    @Test fun setupMutationsRequireARecentRealMonotonicTap() {
        assertTrue(UpdaterProtocol.fresh(1_000, 6_000))
        assertFalse(UpdaterProtocol.fresh(1_000, 6_001))
        assertFalse(UpdaterProtocol.fresh(1_000, 999))
        assertFalse(UpdaterProtocol.fresh(-1, 0))
    }
    @Test fun readOnlyAndSetupStayBoundedWhileDistributionWaitsForTheRootExecutionEnvelope() {
        for (line in listOf("HELLO", "STATUS", "HEALTHY", "REBOOT", "GRANT_LANGUAGE", "SET_TIMEZONE Europe/Kiev"))
            assertEquals(2000, UpdaterProtocol.timeoutFor(line))
        for (line in listOf("BEGIN", "STAGE", "INSTALL_APK", "ROLLBACK"))
            assertEquals(190000, UpdaterProtocol.timeoutFor(line))
    }
    @Test fun oldKeylessStatusIsUsableWithoutInventingSetupCapabilities() {
        val value = UpdaterProtocol.status(status)
        assertEquals(0, value.keys)
        assertNull(value.setup)
        assertFalse(value.transaction)
    }
    @Test fun statusRejectsDuplicateKeysModulesAndUnboundedOrUnknownContent() {
        val module = """{"id":"timesync","code":1,"floor":0,"staged":0}"""
        for (value in listOf(status.replace("\"protocol\":1", "\"protocol\":1,\"protocol\":1"),
            status.replace("\"modules\":[]", "\"modules\":[$module,$module]"),
            status.replace("\"phase\":0", "\"phase\":999"),
            status.replace("\"id\":\"\"", "\"id\":\"${"a".repeat(33)}\""),
            status.dropLast(1) + ",\"untrusted\":true}", "OK " + " ".repeat(4096)))
            assertThrows(Exception::class.java) { UpdaterProtocol.status(value) }
    }
}
