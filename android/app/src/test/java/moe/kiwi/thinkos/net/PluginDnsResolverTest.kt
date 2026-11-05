package moe.kiwi.thinkos.net

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PluginDnsResolverTest {
    private fun ip(last: Int): InetAddress = InetAddress.getByAddress(byteArrayOf(203.toByte(), 0, 113, last.toByte()))
    private fun ipv6(): InetAddress = InetAddress.getByAddress(ByteArray(16).apply { this[0] = 0x20; this[1] = 0x01; this[15] = 1 })
    private fun error(future: Future<*>): String {
        try {
            future.get(2, TimeUnit.SECONDS)
            fail("Expected a bounded DNS failure")
        } catch (error: ExecutionException) {
            assertTrue(error.cause is PluginDnsResolver.DnsFailure)
            return requireNotNull(error.cause?.message)
        }
        throw AssertionError("Unreachable")
    }
    private fun backend(lookup: (String) -> List<InetAddress>, canonical: (InetAddress) -> String? = { null }) =
        object : PluginDnsResolver.Backend {
            override fun lookup(host: String) = lookup.invoke(host)
            override fun canonical(address: InetAddress) = canonical.invoke(address)
        }

    @Test fun acceptsDnsNamesWithoutInterpretingUriOrNumericInputs() {
        assertEquals("all.api.radio-browser.info", PluginDnsResolver.normalizeHost("ALL.api.radio-browser.info."))
        assertEquals("localhost", PluginDnsResolver.normalizeHost("localhost"))
        assertEquals("xn--bcher-kva.example", PluginDnsResolver.normalizeHost("xn--bcher-kva.example"))
        val longest = listOf("a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(61)).joinToString(".")
        assertEquals(253, longest.length)
        assertEquals(longest, PluginDnsResolver.normalizeHost(longest))
        for (host in listOf("", ".", "a..example", "-bad.example", "bad-.example", "a".repeat(64) + ".example",
            longest + ".", "https://host.example", "host.example:443", "user@host.example", "host/path", "host?x",
            "host#x", "host\\path", "host example", "host\u0000.example", "host\n.example", "bücher.example", "_api._tcp.example",
            "127.0.0.1", "127.1", "2130706433", "0177.0.0.1", "0x7f000001", "0x7f.0.0.1", "[::1]", "::1")) {
            assertNull(host, PluginDnsResolver.normalizeHost(host))
        }
    }

    @Test fun invalidNamesNeverReachAnyBackendAndMessagesContainNoInput() {
        val calls = AtomicInteger()
        PluginDnsResolver(backend({ calls.incrementAndGet(); listOf(ip(1)) })).use { resolver ->
            assertEquals("dns invalid host", error(resolver.resolve("https://secret.example/private")))
            assertEquals(0, calls.get())
        }
    }

    @Test fun returnsAtMostEightUniqueNumericAddressesIncludingBothFamilies() {
        val v6 = ipv6()
        val answers = listOf(ip(1), ip(1), v6, v6) + (2..30).map(::ip)
        PluginDnsResolver(backend({ answers })).use { resolver ->
            val result = resolver.resolve("pool.example").get(2, TimeUnit.SECONDS)
            assertEquals(8, result.size)
            assertEquals(8, result.map { it.address }.toSet().size)
            assertEquals(ip(1).hostAddress, result[0].address)
            assertEquals(v6.hostAddress, result[1].address)
            assertTrue(result.all { it.name == null })
        }
    }

    @Test fun canonicalPtrMustBeValidAndForwardConfirmedAgainstThatExactAddress() {
        val queries = Collections.synchronizedList(mutableListOf<String>())
        val a = ip(1); val b = ip(2); val c = ip(3); val d = ip(4)
        PluginDnsResolver(backend({ host ->
            queries.add(host)
            when (host) {
                "pool.example" -> listOf(a, b, c, d)
                "good.example" -> listOf(ip(99), a)
                "forged.example" -> listOf(ip(99))
                "missing.example" -> throw IllegalStateException("private DNS diagnostics must stay private")
                else -> error("An invalid PTR must never trigger a forward lookup")
            }
        }, { address -> when (address) {
            a -> "GOOD.Example."
            b -> "forged.example"
            c -> "https://invalid.example/"
            else -> "missing.example"
        } })).use { resolver ->
            val result = resolver.resolve("pool.example").get(2, TimeUnit.SECONDS)
            assertEquals("good.example", result[0].name)
            assertTrue(result.drop(1).all { it.name == null })
            assertEquals(listOf("pool.example", "good.example", "forged.example", "missing.example"), queries.toList())
        }
    }

    @Test fun reverseFailureKeepsAddressAndLookupFailureIsSanitized() {
        PluginDnsResolver(backend({ listOf(ip(1)) }, { throw IllegalStateException("original host and resolver detail") })).use { resolver ->
            assertEquals(listOf(PluginDnsResolver.Entry(ip(1).hostAddress!!)), resolver.resolve("host.example").get(2, TimeUnit.SECONDS))
        }
        PluginDnsResolver(backend({ throw IllegalStateException("secret host/credentials") })).use { resolver ->
            assertEquals("dns unavailable", error(resolver.resolve("host.example")))
        }
        PluginDnsResolver(backend({ emptyList() })).use { resolver ->
            assertEquals("dns unavailable", error(resolver.resolve("host.example")))
        }
    }

    private class StuckBackend : PluginDnsResolver.Backend {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val names = Collections.synchronizedList(mutableListOf<String>())
        val threads = Collections.synchronizedSet(mutableSetOf<Long>())
        override fun lookup(host: String): List<InetAddress> {
            names.add(host)
            threads.add(Thread.currentThread().id)
            if (host.startsWith("stuck")) {
                entered.countDown()
                // Model a libc DNS call that ignores Future.cancel(true).
                while (release.count != 0L) {
                    try { release.await() } catch (_: InterruptedException) { }
                }
            }
            return listOf(InetAddress.getByAddress(byteArrayOf(1, 2, 3, 4)))
        }
        override fun canonical(address: InetAddress): String? = null
    }

    @Test fun queueAndRunningLookupsShareDeadlineWithoutGrowingStuckDnsThreads() {
        val backend = StuckBackend()
        PluginDnsResolver(backend, timeoutMillis = 500).use { resolver ->
            try {
                val first = resolver.resolve("stuck1.example")
                val second = resolver.resolve("stuck2.example")
                assertTrue(backend.entered.await(2, TimeUnit.SECONDS))
                val queued = resolver.resolve("queued1.example")
                val alsoQueued = resolver.resolve("queued2.example")
                repeat(12) { assertEquals("dns busy", error(resolver.resolve("busy.example"))) }
                for (future in listOf(first, second, queued, alsoQueued)) assertEquals("dns timeout", error(future))
                assertEquals(setOf("stuck1.example", "stuck2.example"), backend.names.toSet())
                assertEquals(2, backend.threads.size)
                // Expired queue entries were removed even though both workers
                // remain occupied. This request is accepted, not falsely busy.
                val fresh = resolver.resolve("fresh.example")
                backend.release.countDown()
                assertEquals("1.2.3.4", fresh.get(2, TimeUnit.SECONDS).single().address)
                assertFalse(backend.names.any { it.startsWith("queued") })
                assertEquals(2, backend.threads.size)
            } finally { backend.release.countDown() }
        }
    }

    @Test fun cancellingAnUnloadedPluginsQueuedRequestFreesItsSlotAndNeverCallsDnsForIt() {
        val backend = StuckBackend()
        PluginDnsResolver(backend).use { resolver ->
            try {
                resolver.resolve("stuck1.example"); resolver.resolve("stuck2.example")
                assertTrue(backend.entered.await(2, TimeUnit.SECONDS))
                val cancelled = resolver.resolve("unloaded.example")
                val queued = resolver.resolve("other.example")
                assertEquals("dns busy", error(resolver.resolve("full.example")))
                assertTrue(cancelled.cancel(true))
                assertTrue(cancelled.isCancelled)
                val replacement = resolver.resolve("replacement.example")
                backend.release.countDown()
                queued.get(2, TimeUnit.SECONDS)
                replacement.get(2, TimeUnit.SECONDS)
                assertFalse("unloaded.example" in backend.names)
                assertFalse(cancelled.cancel(true))
            } finally { backend.release.countDown() }
        }
    }

    @Test fun slowPtrAndItsForwardConfirmationCannotExtendTheWholeRequestDeadline() {
        for (slowReverse in listOf(true, false)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fun block() {
                entered.countDown()
                while (release.count != 0L) {
                    try { release.await() } catch (_: InterruptedException) { }
                }
            }
            PluginDnsResolver(backend({ host ->
                if (host == "ptr.example" && !slowReverse) block()
                listOf(ip(1))
            }, {
                if (slowReverse) block()
                "ptr.example"
            }), timeoutMillis = 300).use { resolver ->
                try {
                    val result = resolver.resolve("pool.example")
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    assertEquals("dns timeout", error(result))
                } finally { release.countDown() }
            }
        }
    }

    @Test fun closeCancelsRequestsAndCannotStartAnotherDnsWorker() {
        val backend = StuckBackend()
        val resolver = PluginDnsResolver(backend)
        try {
            val pending = resolver.resolve("stuck1.example")
            resolver.close()
            assertTrue(pending.isCancelled)
            try { pending.get(); fail("Cancellation must remain observable") } catch (_: CancellationException) { }
            assertEquals("dns unavailable", error(resolver.resolve("new.example")))
        } finally {
            backend.release.countDown()
            resolver.close()
        }
    }
}
