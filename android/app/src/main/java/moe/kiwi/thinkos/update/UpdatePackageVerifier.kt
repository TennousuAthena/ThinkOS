package moe.kiwi.thinkos.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Bounded content checks, then Android's archive signature verification, before READY.
 * No installation side effects. A future installer must reverify immediately before
 * handing the same bytes to PackageInstaller; a past result is not an immutable file. */
internal class UpdatePackageVerifier private constructor(
    private val packageName: String,
    private val installed: () -> UpdateCatalog.Device,
    private val readArchive: (File) -> Archive?
) {
    internal data class Archive(val packageName: String, val versionName: String?, val versionCode: Int,
                                val signers: List<String>, val minSdk: Int, val targetSdk: Int, val split: Boolean)
    internal data class Verified(val file: File, val catalogDigest: String, val sha256: String,
                                 val bytes: Long, val versionCode: Int, val signer: String)
    internal data class Local(val file: File, val identity: UpdateApkIdentity)

    /** Backups come from PackageManager's installed source path, not a URL or
     * persisted path. The installed signer/version and actual archive agree. */
    suspend fun inspectInstalled(source: File): Local = UpdateIo.run { inspectInstalledForRecovery(source) }

    /** Called only on UpdateIo while the install recovery fence is held. */
    internal fun inspectInstalledForRecovery(source: File): Local {
        val device = installed()
        require(source.isFile && !Files.isSymbolicLink(source.toPath()) && source.length() in 1..Fetcher.ArtifactKind.APK.maxBytes)
        val size = source.length(); val hash = hash(source, size)
        val archive = readArchive(source) ?: throw IllegalArgumentException("Android rejected the installed APK")
        val version = requireNotNull(archive.versionName)
        val identity = UpdateApkIdentity(version, archive.versionCode, hash, size, device.signer)
        require(identity.code == device.versionCode && UpdateCatalog.versionCode(version) == identity.code)
        checkArchive(archive, identity, device)
        checkAbis(source); checkContent(source, Fetcher.Artifact(Fetcher.ArtifactKind.APK, size, hash))
        return Local(source.canonicalFile, identity)
    }

    suspend fun verifyInstalled(expected: UpdateApkIdentity, source: File): Local = verifyLocal(expected, source, previous = false)
    suspend fun verifyPrevious(expected: UpdateApkIdentity, source: File): Local = verifyLocal(expected, source, previous = true)

    private suspend fun verifyLocal(expected: UpdateApkIdentity, source: File, previous: Boolean): Local = UpdateIo.run {
        val device = installed()
        require(if (previous) expected.code < device.versionCode else expected.code == device.versionCode)
        require(UpdateCatalog.versionCode(expected.version) == expected.code && expected.signer == device.signer)
        val artifact = Fetcher.Artifact(Fetcher.ArtifactKind.APK, expected.size, expected.sha256)
        checkContent(source, artifact)
        val archive = readArchive(source) ?: throw IllegalArgumentException("Android rejected the local APK")
        checkArchive(archive, expected, device)
        checkAbis(source); checkContent(source, artifact)
        Local(source.canonicalFile, expected)
    }

    private fun checkArchive(archive: Archive, expected: UpdateApkIdentity, device: UpdateCatalog.Device) {
        require(archive.packageName == packageName && archive.versionName == expected.version && archive.versionCode == expected.code)
        require(archive.signers.size == 1 && archive.signers.single() == device.signer && archive.signers.single() == expected.signer)
        require(archive.minSdk == device.sdk && archive.targetSdk == device.sdk && !archive.split)
    }

    suspend fun verify(catalog: UpdateCatalog, file: File): Verified = UpdateIo.run {
        val device = installed()
        require(catalog.ineligible(device) == null) { "Update is no longer eligible" }
        val release = catalog.document.release
        checkContent(file, catalog.apk())
        val archive = readArchive(file) ?: throw IllegalArgumentException("Android rejected the APK archive/signature")
        require(archive.packageName == packageName) { "Unexpected APK package name" }
        require(archive.versionCode == release.code && archive.versionName == release.version) { "APK version differs from signed catalog" }
        require(archive.signers.size == 1 && archive.signers.single() == release.signer && archive.signers.single() == device.signer) {
            "APK signer differs from signed catalog or installed application"
        }
        require(archive.minSdk == device.sdk && archive.targetSdk == device.sdk && !archive.split) { "APK is not a standalone API27 package" }
        checkAbis(file)
        // PackageManager reads by path. Detect changes between the bounded hash
        // and the platform parse; app-private staging and Fetcher locks are still required.
        checkContent(file, catalog.apk())
        Verified(file.canonicalFile, catalog.digest, release.sha256, release.size, release.code, release.signer)
    }

    companion object {
        fun forContext(context: Context): UpdatePackageVerifier {
            val pm = context.packageManager
            val name = context.packageName
            return UpdatePackageVerifier(name, {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(name, PackageManager.GET_SIGNATURES)
                val signers = signers(info)
                require(signers.size == 1) { "Installed application must have exactly one signer" }
                @Suppress("DEPRECATION")
                UpdateCatalog.Device(Build.DEVICE, Build.VERSION.SDK_INT, info.versionCode, signers.single())
            }, { file ->
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES)?.let { info ->
                    val app = info.applicationInfo ?: return@let null
                    @Suppress("DEPRECATION")
                    Archive(info.packageName, info.versionName, info.versionCode, signers(info),
                            app.minSdkVersion, app.targetSdkVersion, !info.splitNames.isNullOrEmpty())
                }
            })
        }

        /** Pure test seam for failure ordering; device tests exercise the real PackageManager above. */
        internal fun forTest(packageName: String, installed: () -> UpdateCatalog.Device, readArchive: (File) -> Archive?) =
            UpdatePackageVerifier(packageName, installed, readArchive)

        @Suppress("DEPRECATION")
        private fun signers(info: PackageInfo): List<String> =
            info.signatures?.map { UpdateCatalog.sha256(it.toByteArray()) }.orEmpty()

        private fun checkContent(file: File, artifact: Fetcher.Artifact) {
            require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() == artifact.bytes) { "Invalid APK file/size" }
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    require(bytes <= artifact.bytes) { "APK grew during verification" }
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(bytes == artifact.bytes && actual == artifact.sha256) { "APK hash differs from signed catalog" }
        }

        private fun hash(file: File, expectedSize: Long): String {
            val digest = MessageDigest.getInstance("SHA-256"); var bytes = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    bytes += count; require(bytes <= expectedSize); digest.update(buffer, 0, count)
                }
            }
            require(bytes == expectedSize)
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }

        private fun checkAbis(file: File) {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                val names = HashSet<String>()
                var count = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    require(++count <= 16_384 && names.add(entry.name)) { "APK has excessive or duplicate ZIP entries" }
                    if (!entry.isDirectory && entry.name.startsWith("lib/")) {
                        val parts = entry.name.split('/')
                        require(parts.size == 3 && parts[1] == "armeabi-v7a" && parts[2].endsWith(".so")) { "APK contains an unsupported ABI/native path" }
                    }
                }
            }
        }
    }
}
