package moe.kiwi.thinkos.image

import java.io.File
import java.net.URI

/** Per-runtime-generation grant. Replacing a plugin revokes all requests from its old instance. */
internal class ImageOwner(val directory: File, val permissions: Set<String>)

internal sealed class ImageSource {
    data class Asset(val path: String) : ImageSource()
    data class Local(val path: String) : ImageSource()
    data class Remote(val url: String) : ImageSource()

    companion object {
        fun parse(src: String, permissions: Set<String>): ImageSource {
            require(src.isNotBlank() && src.length <= 2048 && src.none { it.code < 32 || it.code == 127 }) { "Invalid image source" }
            if (src.startsWith("asset:")) {
                val path = src.removePrefix("asset:")
                require(path.startsWith("assets/") && '\\' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Image asset must be inside this plugin's assets/" }
                return Asset(path)
            }
            if (src.startsWith("https://") || src.startsWith("http://")) {
                require("network" in permissions) { "network permission required for remote images" }
                val uri = URI(src)
                moe.kiwi.thinkos.net.LocalNetworkPolicy.validate(uri)
                require(uri.fragment == null) { "Image URL must not contain a fragment" }
                return Remote(src)
            }
            require("library" in permissions) { "library permission required for local images" }
            if (src.startsWith('/')) return Local(src)
            val uri = URI(src)
            require(uri.scheme == "file" && (uri.rawAuthority.isNullOrEmpty() || uri.rawAuthority.equals("localhost", true)) &&
                uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path?.startsWith('/') == true) {
                "Unsupported image source"
            }
            return Local(src)
        }
    }
}
