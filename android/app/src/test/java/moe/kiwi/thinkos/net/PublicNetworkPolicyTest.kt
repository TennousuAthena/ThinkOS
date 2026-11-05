package moe.kiwi.thinkos.net

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class PublicNetworkPolicyTest {
    private fun address(value: String) = InetAddress.getByName(value) // All inputs below are numeric literals.

    @Test fun publicMeansGlobalUnicastRatherThanMerelyNotRfc1918() {
        for (value in listOf("8.8.8.8", "1.1.1.1", "100.63.255.255", "100.128.0.1", "172.15.255.255", "172.32.0.1",
            "192.0.1.1", "198.17.255.255", "198.20.0.1", "2001:4860:4860::8888", "2606:4700:4700::1111"))
            assertTrue(value, PublicNetworkPolicy.publicAddress(address(value)))
        for (value in listOf("0.0.0.0", "0.1.2.3", "10.4.5.6", "127.0.0.1", "100.64.0.0", "100.127.255.255",
            "169.254.1.2", "172.16.0.1", "172.31.255.255", "192.168.0.1", "192.0.0.9", "192.0.2.1", "192.88.99.1",
            "198.18.0.1", "198.19.255.255", "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1", "255.255.255.255",
            "::", "::1", "::ffff:127.0.0.1", "fc00::1", "fd00::1", "fe80::1", "ff02::1", "64:ff9b::a00:1",
            "64:ff9b:1::1", "100::1", "2001::1", "2001:2::1", "2001:db8::1", "2002:0a00:0001::1", "3fff::1"))
            assertFalse(value, PublicNetworkPolicy.publicAddress(address(value)))
    }

    @Test fun literalsAndLocalAliasesCannotReachTheRequestOrBeReinterpreted() {
        for (value in listOf("http://8.8.8.8/live", "file:///data/x.ts", "content://media/x", "rtsp://8.8.8.8/live",
            "https://127.0.0.1/live", "https://192.168.1.1/live", "https://100.64.1.1/live", "https://localhost/live",
            "https://camera.local./live", "https://stream.internal/live", "https://127.1/live", "https://2130706433/live",
            "https://0177.0.0.1/live", "https://user:pass@stream.test/live", "https://stream.test/live#fragment"))
            assertThrows(value, PublicNetworkPolicy.Blocked::class.java) { PublicNetworkPolicy.validate(value) }
        PublicNetworkPolicy.validate("https://8.8.8.8/live")
        PublicNetworkPolicy.validate("https://stream.test/live") // The worker must resolve aliases, never the parser/main thread.
    }

    @Test fun emptyOrMixedDnsAnswersAreRejectedAsAWhole() {
        val public = address("8.8.8.8")
        for (answers in listOf(emptyList(), listOf(public, address("10.0.0.1")), listOf(address("::1"), public)))
            assertThrows(PublicNetworkPolicy.Blocked::class.java) { PublicNetworkPolicy.requirePublic(answers) }
        PublicNetworkPolicy.requirePublic(listOf(public, address("2606:4700:4700::1111")))
    }
}
