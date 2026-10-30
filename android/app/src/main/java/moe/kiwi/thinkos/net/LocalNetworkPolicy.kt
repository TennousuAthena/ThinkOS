package moe.kiwi.thinkos.net

import java.net.InetAddress
import java.net.URI

/** All cleartext connections must use this gate, including every redirect. */
internal object LocalNetworkPolicy {
    fun validate(uri: URI) {
        require(uri.scheme in setOf("http", "https")) { "Only HTTP(S) URLs are supported" }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null) { "Invalid URL or embedded credentials" }
        require(uri.port == -1 || uri.port in 1..65535) { "Invalid port" }
    }
    fun privateAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isMulticastAddress) return false
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        val bytes = address.address
        return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
    }
    fun requirePrivate(addresses: Array<InetAddress>) {
        require(addresses.isNotEmpty() && addresses.all(::privateAddress)) { "Cleartext HTTP is restricted to local/private addresses" }
    }
    fun pin(uri: URI, address: InetAddress): URI {
        validate(uri)
        require(uri.scheme == "http" && privateAddress(address))
        val host = address.hostAddress!!.let { if (address.address.size == 16) "[$it]" else it }
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        // Decoding and reconstructing components would turn %26 into a query separator.
        return URI("http://$host$port${uri.rawPath.ifEmpty { "/" }}$query")
    }
}
