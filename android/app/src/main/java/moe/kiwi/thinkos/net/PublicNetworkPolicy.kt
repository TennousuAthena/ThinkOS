package moe.kiwi.thinkos.net

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/** A caller may narrow media to public HTTPS, independently of the cleartext setting.
 * Address checks use bytes, never hostname reverse lookup. The special-purpose
 * ranges are conservatively excluded (IANA IPv4/IPv6 Special-Purpose Registries).
 * https://www.iana.org/assignments/iana-ipv4-special-registry/
 * https://www.iana.org/assignments/iana-ipv6-special-registry/
 * The ordinary LAN media policy does not use this narrower gate. */
internal object PublicNetworkPolicy {
    class Blocked(reason: String) : IllegalArgumentException("Public-only media policy: $reason")

    fun validate(url: String) {
        try { validate(URI(url)) }
        catch (error: Blocked) { throw error }
        catch (error: Exception) { throw Blocked("invalid URL") }
    }

    fun validate(uri: URI) {
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null ||
            uri.port != -1 && uri.port !in 1..65535) throw Blocked("public HTTPS required")
        val host = uri.host.removePrefix("[").removeSuffix("]").trimEnd('.').lowercase(Locale.ROOT)
        if ('%' in host || host == "localhost" || host.endsWith(".localhost") ||
            listOf("local", "internal", "lan", "home", "onion").any { host == it || host.endsWith(".$it") })
            throw Blocked("local or special-use host")
        // This fast literal check performs no DNS on the plugin/main thread.
        // Noncanonical numeric forms must not get a second interpretation.
        if (host.all { it in '0'..'9' || it == '.' }) {
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { val number = it.toIntOrNull(); number == null || number !in 0..255 || it != number.toString() })
                throw Blocked("noncanonical address")
            requirePublic(listOf(InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray())))
        }
    }

    fun requirePublic(addresses: List<InetAddress>) {
        if (addresses.isEmpty() || addresses.any { !publicAddress(it) }) throw Blocked("non-public address")
    }

    fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress || address is Inet6Address && address.scopeId != 0) return false
        val b = address.address.map { it.toInt() and 255 }
        return when (b.size) {
            4 -> !(b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224 ||
                b[0] == 100 && b[1] in 64..127 || b[0] == 169 && b[1] == 254 ||
                b[0] == 172 && b[1] in 16..31 || b[0] == 192 && (b[1] == 168 ||
                    b[1] == 0 && b[2] in setOf(0, 2) || b[1] == 88 && b[2] == 99) ||
                b[0] == 198 && (b[1] in 18..19 || b[1] == 51 && b[2] == 100) ||
                b[0] == 203 && b[1] == 0 && b[2] == 113)
            // Accept ordinary global unicast only. Reject mapped/NAT64,
            // Teredo/6to4, IETF assignments, documentation and scoped forms.
            16 -> (b[0] and 0xe0) == 0x20 &&
                !(b[0] == 0x20 && b[1] == 1 && (b[2] < 2 || b[2] == 0x0d && b[3] == 0xb8) ||
                    b[0] == 0x20 && b[1] == 2 || b[0] == 0x3f && b[1] == 0xff && (b[2] and 0xf0) == 0)
            else -> false
        }
    }

    fun blocked(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(8).any {
        it is Blocked || it.message?.contains("Public-only media policy:") == true
    }
}
