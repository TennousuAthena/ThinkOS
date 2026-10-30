package moe.kiwi.thinkos.media

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException

class MediaLibraryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val own by lazy { temporary.newFolder("own") }
    private val shared by lazy { temporary.newFolder("shared") }
    private fun access(permission: Boolean = false) = LibraryAccess(listOf(own), listOf(shared)) { permission }
    private fun request(kind: LibraryKind = LibraryKind.AUDIO, recursive: Boolean = false) = LibraryRequest(kind, null, recursive)
    private fun media(directory: File, name: String): File = File(directory, name).apply { parentFile!!.mkdirs(); writeText("test metadata reader") }
    private fun scanner(permission: Boolean = false, limits: LibraryLimits = LibraryLimits(),
        read: (File, LibraryKind, String) -> LibraryMetadata? = { _, _, mime -> LibraryMetadata(mime = mime) }) =
        LibraryScanner(access(permission), read, limits)

    @Test fun ownExternalLibraryNeverRequiresTheSharedStoragePermission() {
        media(own, "second.MP3")
        media(own, "first.flac")
        val guarded = LibraryAccess(listOf(own), listOf(shared)) { error("Own storage must not consult a permission") }
        val result = LibraryScanner(guarded, { _, _, mime -> LibraryMetadata(mime = mime, duration = 123L) }).list(request(), own)
        assertEquals(listOf("first", "second"), result.items.map { it["title"] })
        assertEquals(2, result.scanned)
        assertFalse(result.truncated)
        assertEquals(result.items[0]["url"], result.items[0]["src"])
        assertEquals(123L, result.items[0]["duration"])
        assertTrue((result.items[0]["art"] as String).startsWith("embedded:"))
        assertEquals(2L, (result.toHost()["items"] as List<*>).size.toLong())
    }

    @Test fun permissionPreflightDoesNotGrantPrivateAppSettingsOrShortCircuitUnsafeUrls() {
        val policy = access()
        assertFalse(policy.requiresStoragePermission(own))
        assertTrue(policy.requiresStoragePermission(shared))
        val settings = temporary.newFolder("app-files", "settings")
        media(settings, "server-token.mp3")
        assertThrows(SecurityException::class.java) { policy.authorize(settings) }
        assertThrows(SecurityException::class.java) { policy.requiresStoragePermission(settings) }
        assertFalse(policy.requiresStoragePermissionForUrls(listOf("https://example.com/a.mp3", own.toURI().toString())))
        assertTrue(policy.requiresStoragePermissionForUrls(listOf(shared.toURI().toString())))
        assertThrows(SecurityException::class.java) {
            policy.requiresStoragePermissionForUrls(listOf(shared.toURI().toString(), settings.toURI().toString()))
        }
    }

    @Test fun publicStorageRequiresPermissionAndPermissionNeverGrantsPrivateStorage() {
        val dcim = File(shared, "DCIM").apply { mkdirs() }
        media(dcim, "photo.jpg")
        assertThrows(SecurityException::class.java) { scanner().list(request(LibraryKind.IMAGE), dcim) }
        assertEquals(1, scanner(true).list(request(LibraryKind.IMAGE), dcim).items.size)
        val private = temporary.newFolder("other-app")
        media(private, "private.mp3")
        assertThrows(SecurityException::class.java) { scanner(true).list(request(), private) }
        val sibling = File(own.parentFile, own.name + "-other").apply { mkdirs() }
        assertThrows(SecurityException::class.java) { scanner(true).list(request(), sibling) }
    }

    @Test fun recursiveScanSkipsOtherApplicationsDataAndObbBranches() {
        media(shared, "DCIM/allowed.jpg")
        media(shared, "Android/data/other.app/files/secret.jpg")
        media(shared, "Android/obb/other.app/secret.jpg")
        val result = scanner(true).list(request(LibraryKind.IMAGE, true), shared)
        assertEquals(1, result.items.size)
        assertEquals("allowed", result.items.single()["title"])
        assertThrows(SecurityException::class.java) {
            scanner(true).list(request(LibraryKind.IMAGE), File(shared, "Android/data/other.app/files"))
        }
        // An app's own files root remains permitted inside Android/data without a public grant.
        val ownExternal = File(shared, "Android/data/our.app/files").apply { mkdirs() }
        media(ownExternal, "mine.mp3")
        val ownAccess = LibraryAccess(listOf(ownExternal), listOf(shared)) { false }
        assertEquals(1, LibraryScanner(ownAccess, { _, _, mime -> LibraryMetadata(mime = mime) })
            .list(request(), ownExternal).items.size)
    }

    @Test fun symbolicFilesAndDirectoryCyclesAreNeverFollowed() {
        val outside = temporary.newFolder("outside")
        val secret = media(outside, "secret.mp3")
        media(own, "mine.mp3")
        Files.createSymbolicLink(File(own, "escape.mp3").toPath(), secret.toPath())
        Files.createSymbolicLink(File(own, "escape-folder").toPath(), outside.toPath())
        Files.createSymbolicLink(File(own, "cycle").toPath(), own.toPath())
        val visited = mutableListOf<File>()
        val result = scanner(read = { file, _, mime -> visited.add(file); LibraryMetadata(mime = mime) })
            .list(request(recursive = true), own)
        assertEquals(listOf("mine.mp3"), visited.map { it.name })
        assertEquals(1, result.items.size)
        assertEquals(4, result.scanned)
    }

    @Test fun wrongKindsUnsupportedAndDamagedFilesDoNotHideGoodFiles() {
        media(own, "good.mp3")
        media(own, "broken.flac")
        media(own, "exception.wav")
        media(own, "photo.jpg")
        media(own, "notes.txt")
        val result = scanner(read = { file, _, mime ->
            when (file.name) { "broken.flac" -> null; "exception.wav" -> throw IllegalArgumentException("corrupt")
                else -> LibraryMetadata(mime = mime) }
        }).list(request(), own)
        assertEquals(1, result.items.size)
        assertEquals("good", result.items.single()["title"])
        assertEquals("", result.items.single()["artist"])
        assertNull(result.items.single()["duration"])
        val images = scanner().list(request(LibraryKind.IMAGE), own)
        assertEquals("photo", images.items.single()["title"])
        assertEquals("", images.items.single()["art"])
    }

    @Test fun itemEntryDepthAndTextBudgetsBoundTheWork() {
        repeat(8) { media(own, "track$it.mp3") }
        var reads = 0
        val items = scanner(limits = LibraryLimits(items = 3), read = { _, _, mime -> reads++; LibraryMetadata(mime = mime) })
            .list(request(), own)
        assertEquals(3, items.items.size)
        assertEquals(3, reads)
        assertTrue(items.truncated)
        val entries = scanner(limits = LibraryLimits(entries = 2)).list(request(), own)
        assertEquals(2, entries.scanned)
        assertTrue(entries.truncated)
        val text = scanner(limits = LibraryLimits(textBytes = 1)).list(request(), own)
        assertTrue(text.items.isEmpty())
        assertTrue(text.truncated)
        val deep = temporary.newFolder("deep")
        media(deep, "one/two/hidden.mp3")
        val policy = LibraryAccess(listOf(deep), emptyList()) { false }
        val bounded = LibraryScanner(policy, { _, _, mime -> LibraryMetadata(mime = mime) }, LibraryLimits(depth = 1))
            .list(request(recursive = true), deep)
        assertTrue(bounded.items.isEmpty())
        assertTrue(bounded.truncated)
    }

    @Test fun nonRecursiveScanOnlyListsTheChosenDirectory() {
        media(own, "top.mp3")
        media(own, "nested/inside.mp3")
        assertEquals(listOf("top"), scanner().list(request(), own).items.map { it["title"] })
        assertEquals(2, scanner().list(request(recursive = true), own).items.size)
    }

    @Test fun cancellationBeforeAndDuringMetadataStopsImmediately() {
        media(own, "track.mp3")
        try {
            Thread.currentThread().interrupt()
            assertThrows(CancellationException::class.java) { scanner().list(request(), own) }
        } finally { Thread.interrupted() }
        try {
            assertThrows(CancellationException::class.java) {
                scanner(read = { _, _, _ -> Thread.currentThread().interrupt(); LibraryMetadata() }).list(request(), own)
            }
        } finally { Thread.interrupted() }
    }

    @Test fun optionsOnlyAcceptExplicitLocalDirectoriesAndRealBooleans() {
        val values = mapOf("kind" to "image", "dir" to "file:///sdcard/My%20Photos", "recursive" to true)
        val parsed = LibraryRequest.parse(values)
        assertEquals(LibraryKind.IMAGE, parsed.kind)
        assertEquals(File("/sdcard/My Photos"), parsed.directory)
        assertTrue(parsed.recursive)
        assertNull(LibraryRequest.parse(mapOf("kind" to "audio")).directory)
        for (bad in listOf(mapOf("kind" to "any"), mapOf("kind" to "audio", "recursive" to 1),
            mapOf("kind" to "audio", "dir" to "relative"), mapOf("kind" to "audio", "dir" to "file://remote/Music"),
            mapOf("kind" to "audio", "dir" to "file://remote:bad/Music"),
            mapOf("kind" to "audio", "dir" to "https://example.com/music"),
            mapOf("kind" to "audio", "dir" to "/sdcard/\u0000Music"), mapOf("kind" to "audio", "extra" to true))) {
            assertThrows(IllegalArgumentException::class.java) { LibraryRequest.parse(bad) }
        }
    }

    @Test fun metadataIsBoundedWithoutLoadingCoverImages() {
        media(own, "long title.mp3")
        val result = scanner(read = { _, _, mime -> LibraryMetadata("T".repeat(10000), "A".repeat(10000), "B".repeat(10000), mime) })
            .list(request(), own)
        val item = result.items.single()
        assertEquals(256, (item["title"] as String).length)
        assertEquals(256, (item["artist"] as String).length)
        assertEquals(256, (item["album"] as String).length)
        assertTrue((item["url"] as String).contains("long%20title.mp3"))
    }
}
