package moe.kiwi.thinkos

import android.content.Context
import android.os.Build
import moe.kiwi.thinkos.update.UpdateRepairPresence
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Future explicit owned instrumentation phase; not wired to any current fixed runner.
 * Does not install, abandon sessions, send updater commands or touch production transactions. */
internal object UpdateRepairApi27Probe {
    fun appOnlyBoundaries(context: Context): Map<String, Any> {
        check(Build.VERSION.SDK_INT == 27)
        check(Build.SUPPORTED_ABIS.contains("armeabi-v7a"))
        // The recovery path must distinguish a truly absent endpoint on the ordinary device.
        val presence = UpdateRepairPresence.read()
        check(presence == UpdateRepairPresence.State.ABSENT) { "Run this app-only phase only on a device without updater" }
        val directory = File(context.cacheDir, "repair-probe-${UUID.randomUUID()}")
        check(directory.mkdir())
        try {
            val raw = File(directory, "raw").apply { outputStream().use { it.write(byteArrayOf(0, 1, 2)) } }
            fun attributes(file: File) = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val old = attributes(raw)
            check(old.isRegularFile && !old.isSymbolicLink && old.fileKey() != null)
            val renamed = File(directory, "retained")
            Files.move(raw.toPath(), renamed.toPath(), StandardCopyOption.ATOMIC_MOVE)
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
            val now = attributes(renamed)
            check(now.fileKey() == old.fileKey() && now.lastModifiedTime() == old.lastModifiedTime())
            check(renamed.readBytes().contentEquals(byteArrayOf(0, 1, 2)))
            check(!raw.exists())
            return mapOf("api" to 27, "armv7" to true, "updater" to "absent", "file_key" to true,
                "atomic_rename" to true, "directory_fsync" to true)
        } finally {
            // Only the random cache directory and the two fixed names created above.
            check(directory.list().orEmpty().all { it in setOf("raw", "retained") })
            directory.listFiles().orEmpty().forEach { check(!Files.isSymbolicLink(it.toPath()) && it.isFile && it.delete()) }
            check(directory.delete())
        }
    }
}
