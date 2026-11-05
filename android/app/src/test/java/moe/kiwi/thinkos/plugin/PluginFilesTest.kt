package moe.kiwi.thinkos.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PluginFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val content = "{}\n".toByteArray()
    private fun index(): JSONObject = JSONObject("""{"id":"probe","version":"1.0.0","digest":"e52ad792c9cfdd084aa054903dd13150c03cd8b751c24acfe4a8e12172633490","files":[{"path":"manifest.json","size":3,"sha256":"ca3d163bab055381827226140568f3bef7eaac187cebd76878e0b63e9e442356"}]}""")
    private fun reject(json: JSONObject) { assertThrows(IllegalArgumentException::class.java) { PluginFiles.parse(json) } }

    @Test fun canonicalDigestMatchesIndependentPythonFixture() {
        val parsed = PluginFiles.parse(index())
        assertEquals("probe", parsed.id)
        assertEquals(3L, parsed.files.single().size)
        val target = File(temporary.root, "installed")
        PluginFiles.copy(parsed, target) { content.inputStream() }
        assertArrayEquals(content, File(target, "manifest.json").readBytes())
        assertEquals(parsed.digest, PluginFiles.readDirectory(target).digest)
    }

    @Test fun corruptOrGrowingFilesNeverLeavePartialInstallation() {
        for (bad in listOf("[]\n", "{}\nextra", "{}")) {
            val target = File(temporary.root, "candidate")
            assertThrows(IllegalArgumentException::class.java) {
                PluginFiles.copy(PluginFiles.parse(index()), target) { bad.byteInputStream() }
            }
            assertFalse(target.exists())
        }
    }

    @Test fun cannotOverwriteExistingInstallation() {
        val target = temporary.newFolder("existing")
        File(target, "manifest.json").writeText("old")
        assertThrows(IllegalArgumentException::class.java) {
            PluginFiles.copy(PluginFiles.parse(index()), target) { content.inputStream() }
        }
        assertEquals("old", File(target, "manifest.json").readText())
    }

    @Test fun traversalAbsoluteEmptyAndControlPathsAreRejected() {
        for (path in listOf("../escape.lua", "/escape.lua", "a/../b.lua", "a//b.lua", "a/./b.lua", "a\\b.lua", "a\u0000b.lua", "a\nb.lua")) {
            val json = index()
            json.getJSONArray("files").getJSONObject(0).put("path", path)
            reject(json)
        }
    }

    @Test fun duplicateCaseCollidingAndUnsortedPathsAreRejected() {
        for (paths in listOf(listOf("manifest.json", "manifest.json"), listOf("assets/Face.png", "assets/face.png"),
            listOf("assets/Face/a.png", "assets/face/b.png"), listOf("main.lua", "assets/a.png"))) {
            val json = index()
            json.put("files", JSONArray().apply {
                paths.forEach { path -> put(JSONObject().put("path", path).put("size", 3).put("sha256", PluginFiles.sha256(content))) }
            })
            reject(json)
        }
    }

    @Test fun indexTamperingAndOversizeDeclarationsAreRejected() {
        reject(index().put("digest", "0".repeat(64)))
        reject(index().put("unexpected", true))
        for (size in listOf(-1.0, .5, Double.MAX_VALUE, (256 * 1024 + 1).toDouble())) {
            val json = index()
            json.getJSONArray("files").getJSONObject(0).put("size", size)
            reject(json)
        }
        val missing = index().put("files", JSONArray())
        reject(missing)
    }

    @Test fun missingAndUnlistedFilesAreRejected() {
        val directory = temporary.newFolder("source")
        File(directory, "index.json").writeText(index().toString())
        assertThrows(IllegalArgumentException::class.java) { PluginFiles.readDirectory(directory) }
        File(directory, "manifest.json").writeBytes(content)
        File(directory, "surprise.lua").writeText("bad")
        assertThrows(IllegalArgumentException::class.java) { PluginFiles.readDirectory(directory) }
    }

    @Test fun replacementGapKeepsPreviousPublicationVisible() {
        val dev = temporary.newFolder("dev")
        val published = File(dev, "probe").apply { mkdir() }
        val previous = File(dev, ".previous-probe")
        val stage = File(dev, ".stage-probe-new").apply { mkdir() }
        assertEquals(published, PluginFiles.developmentDirectories(dev, setOf("probe")).single().second)
        assertTrue(published.renameTo(previous))
        assertEquals(previous, PluginFiles.developmentDirectories(dev, setOf("probe")).single().second)
        assertTrue(stage.renameTo(published))
        assertEquals(published, PluginFiles.developmentDirectories(dev, setOf("probe")).single().second)
        previous.deleteRecursively()
        published.deleteRecursively()
        assertTrue(PluginFiles.developmentDirectories(dev, setOf("probe")).isEmpty())
    }

    @Test fun cleanupNeverFollowsDirectorySymlinks() {
        val outside = temporary.newFolder("outside")
        File(outside, "keep.txt").writeText("keep")
        val staging = temporary.newFolder("staging")
        java.nio.file.Files.createSymbolicLink(File(staging, "escape").toPath(), outside.toPath())
        PluginFiles.deleteTree(staging)
        assertFalse(staging.exists())
        assertEquals("keep", File(outside, "keep.txt").readText())
    }

    @Test fun symlinkCannotReadOutsideSource() {
        val directory = temporary.newFolder("links")
        File(directory, "index.json").writeText(index().toString())
        val outside = temporary.newFile("secret")
        outside.writeBytes(content)
        java.nio.file.Files.createSymbolicLink(File(directory, "manifest.json").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { PluginFiles.readDirectory(directory) }
        assertThrows(IllegalArgumentException::class.java) { PluginFiles.openDirectory(directory, "manifest.json") }
    }

    private fun indexWith(extra: String): JSONObject {
        val bytes = "{}\n".toByteArray()
        fun hash(value: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it.toInt() and 255) }
        val paths = listOf("manifest.json", extra).sorted()
        val canonical = paths.joinToString("") { it + "\u0000" + hash(bytes) + "\n" }
        return JSONObject().put("id", "license-probe").put("version", "1.0.0").put("digest", hash(canonical.toByteArray()))
            .put("files", JSONArray(paths.map { JSONObject().put("path", it).put("size", bytes.size).put("sha256", hash(bytes)) }))
    }

    @Test fun fixedRootLegalTextsAreDistributedWithoutOpeningMarkdownGenerally() {
        for (name in listOf("LICENSE", "LICENSE.txt", "LICENSE.md", "COPYING", "NOTICE", "NOTICE.txt")) {
            assertTrue(PluginFiles.parse(indexWith(name)).files.any { it.path == name })
        }
        for (name in listOf("README.md", "LICENSE.html", "docs/LICENSE.md", "assets/NOTICE.txt")) {
            assertThrows(IllegalArgumentException::class.java) { PluginFiles.parse(indexWith(name)) }
        }
    }

    @Test fun storeMetadataAndScriptsNeverReachTheDevice() {
        for (name in listOf("store.json", "store/main.lua", "store/views/main.json", "store/LICENSE.md")) {
            assertThrows(IllegalArgumentException::class.java) { PluginFiles.parse(indexWith(name)) }
        }
    }
}
