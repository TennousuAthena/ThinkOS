package moe.kiwi.thinkos

import java.io.File

/** Used only by tests and androidTest; never linked into a main APK. */
internal object CallWebRtcProbePolicy {
    private const val TARGET = "moe.kiwi.thinkos.regfixture"
    private val candidate = Regex("lib(?:lk)?jingle_peerconnection_so\\.so")
    private val names = setOf("libjingle_peerconnection_so.so", "liblkjingle_peerconnection_so.so")

    fun requirePackages(target: String, test: String) {
        require(target == TARGET && test == "$TARGET.test") { "Independent CALL-0 fixture UID required" }
    }

    fun requireDeniedPermissions(cameraDenied: Boolean, microphoneDenied: Boolean) {
        require(cameraDenied && microphoneDenied) { "CAMERA and RECORD_AUDIO must already be denied" }
    }

    fun requireFresh(maps: String) {
        require(paths(maps).isEmpty()) { "One variant per fresh test process" }
    }

    fun requireNewMapping(before: String, after: String, verified: File): String {
        requireFresh(before)
        val expected = verified.canonicalFile
        require(expected.isAbsolute && expected.name in names)
        val loaded = paths(after)
        require(loaded.isNotEmpty()) { "Expected native library was not mapped" }
        require(loaded.all { File(it).canonicalFile == expected }) { "Mapped library differs from verified native file" }
        return expected.path
    }

    /** Missing decoder scalability metadata is distinct from an empty list. */
    fun scalabilityModes(value: Any?): List<String>? {
        if (value == null) return null
        require(value is List<*> && value.size <= 64) { "Invalid scalability modes container" }
        return value.map {
            require(it is String && it.toByteArray(Charsets.UTF_8).size <= 128 && it.none { char -> char.isISOControl() }) {
                "Invalid scalability mode text"
            }
            it
        }
    }

    private fun paths(maps: String): Set<String> {
        require(maps.length <= 2*1024*1024)
        val found = linkedSetOf<String>()
        var count = 0
        for (line in maps.lineSequence()) {
            require(++count <= 32768 && line.length <= 8192)
            if (!candidate.containsMatchIn(line)) continue
            val fields = line.trim().split(Regex("\\s+"), limit = 6)
            require(fields.size == 6)
            val path = fields[5]
            require(path.startsWith('/') && path.none { it.isISOControl() } && File(path).name in names) {
                "Ambiguous, deleted or non-file native mapping"
            }
            found.add(path)
        }
        return found
    }
}
