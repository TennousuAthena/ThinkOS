package moe.kiwi.thinkos.net

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.URI

class LocalNetworkPolicyTest {
    @Test fun pinningPreservesEncodedPathsAndQueryValues() {
        val uri = URI("http://music.local:4533/rest/a%2Fb?password=a%26b%3Dc&song=x%2By")
        val pinned = LocalNetworkPolicy.pin(uri, InetAddress.getByName("192.168.1.10"))
        assertEquals("192.168.1.10", pinned.host)
        assertEquals(4533, pinned.port)
        assertEquals(uri.rawPath, pinned.rawPath)
        assertEquals(uri.rawQuery, pinned.rawQuery)
    }
    @Test fun privateRangesAndLoopbackAreAllowed() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.1.1", "192.168.2.1", "169.254.1.1", "::1", "fd00::1", "fe80::1").forEach {
            assertTrue(it, LocalNetworkPolicy.privateAddress(InetAddress.getByName(it)))
        }
    }
    @Test fun publicAndUnspecifiedAddressesAreRejected() {
        listOf("0.0.0.0", "8.8.8.8", "172.32.1.1", "224.0.0.1", "2001:4860:4860::8888", "::").forEach {
            assertFalse(it, LocalNetworkPolicy.privateAddress(InetAddress.getByName(it)))
        }
    }
    @Test fun mixedDnsAnswersCannotBypassLanRestriction() {
        try { LocalNetworkPolicy.requirePrivate(arrayOf(InetAddress.getByName("192.168.1.1"), InetAddress.getByName("8.8.8.8"))); fail("mixed DNS") }
        catch (_: IllegalArgumentException) {}
    }
    @Test fun credentialsAndNonHttpSchemesAreRejected() {
        listOf("file:///tmp/a", "ftp://host/x", "http://user:pass@localhost/").forEach {
            try { LocalNetworkPolicy.validate(URI(it)); fail(it) } catch (_: IllegalArgumentException) {}
        }
    }
}
