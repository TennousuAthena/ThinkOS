package moe.kiwi.thinkos

import android.app.ActivityManager
import android.app.Instrumentation
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import moe.kiwi.thinkos.update.UpdateCatalog
import moe.kiwi.thinkos.update.UpdateFixture
import moe.kiwi.thinkos.update.UpdatePackageVerifier
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile

/** OTA-0 only: official API27 PackageInstaller under the actual app/owner UID.
 * No su/pm shell install, root daemon, or production signing key. The explicit
 * downgrade experiment alone uses API27's hidden allow-downgrade flag. */
internal object InstallPrototype {
    @android.annotation.SuppressLint("UnspecifiedImmutableFlag") // API27 receipt must accept installer fill-in extras.
    @Suppress("DEPRECATION")
    suspend fun run(instrumentation: Instrumentation, directory: File, token: String, downgrade: Boolean = false): Nothing {
        val context = instrumentation.targetContext
        check((context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).getStreamVolume(3) == 0)
        val owner = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        check(owner.isDeviceOwnerApp(context.packageName)) { "OTA-0 requires the already-existing Device Owner" }
        val installed = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        check(Process.myUid() == installed.applicationInfo!!.uid)
        val root = File(directory, "install")
        val metadataFile = File(root, "candidate.json")
        check(metadataFile.length() in 1..16384 && !Files.isSymbolicLink(metadataFile.toPath()))
        val metadata = JSONObject(metadataFile.readText())
        val candidate = File(root, "candidate.apk")
        val name = metadata.getString("version"); val code = metadata.getInt("code")
        check(UpdateCatalog.versionCode(name) == code)
        check(if (downgrade) code == installed.versionCode - 1 else code > installed.versionCode)
        val device = UpdateCatalog.Device(android.os.Build.DEVICE, 27, installed.versionCode,
            UpdateCatalog.sha256(installed.signatures!!.single().toByteArray()))
        val fixture = UpdateFixture()
        val json = fixture.catalog().apply {
            getJSONObject("release").put("version", name).put("code", code).put("device", JSONArray().put(device.name))
                .put("size", metadata.getLong("size")).put("sha256", metadata.getString("sha256")).put("signer", device.signer)
        }
        // The fixture signs only in RAM. Nothing is added to the APK's keyring.
        if (downgrade) {
            // Explicit OTA-0 experiment only. Production's newer-version gate
            // stays intact; this known local APK is checked independently.
            // Do not infer downgrade capability from Build.TYPE. Restrict this
            // experiment to our debuggable target; Android's actual package
            // manager still decides whether downgrade is allowed.
            check(installed.applicationInfo!!.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            check(candidate.isFile && !Files.isSymbolicLink(candidate.toPath()) &&
                candidate.length() == metadata.getLong("size") && candidate.length() in 1..64L*1024*1024)
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            candidate.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count=input.read(buffer); if (count<0) break
                    bytes += count; check(bytes <= metadata.getLong("size")); digest.update(buffer,0,count)
                }
            }
            check(bytes == metadata.getLong("size"))
            check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == metadata.getString("sha256"))
            val archive = context.packageManager.getPackageArchiveInfo(candidate.path, PackageManager.GET_SIGNATURES)
                ?: error("Android rejected the rollback probe APK")
            check(archive.packageName == context.packageName && archive.versionCode == code && archive.versionName == name)
            check(archive.signatures?.singleOrNull()?.let { UpdateCatalog.sha256(it.toByteArray()) } == device.signer)
            check(archive.applicationInfo!!.minSdkVersion == 27 && archive.applicationInfo!!.targetSdkVersion == 27 && archive.splitNames.isNullOrEmpty())
            ZipFile(candidate).use { zip ->
                val entries=zip.entries();var count=0
                while (entries.hasMoreElements()) {
                    val entry=entries.nextElement();check(++count<=16384)
                    if (!entry.isDirectory && entry.name.startsWith("lib/")) check(entry.name.matches(Regex("lib/armeabi-v7a/[^/]+\\.so")))
                }
            }
        } else UpdatePackageVerifier.forContext(context).verify(fixture.verified(json), candidate)
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val before = withContext(Dispatchers.Main) {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            JSONObject().put("pid", Process.myPid()).put("uid", Process.myUid()).put("device_owner", true)
                .put("lock_task", manager.lockTaskModeState).put("signer", device.signer)
                .put("installed_code", installed.versionCode).put("target_code", code).put("target_version", name)
                .put("downgrade_requested", downgrade)
                .put("app_debuggable", installed.applicationInfo!!.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                .put("build_type", android.os.Build.TYPE)
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName); setSize(candidate.length())
            if (downgrade) {
                javaClass.getMethod("setAllowDowngrade", Boolean::class.javaPrimitiveType).invoke(this, true)
                // getAllowDowngrade belongs to SessionInfo, not SessionParams.
                check(javaClass.getField("installFlags").getInt(this) and 0x80 != 0)
                before.put("hidden_downgrade_flag", true)
            }
        }
        val id = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, candidate.length()).use { output ->
                    candidate.inputStream().use { it.copyTo(output, 65536) }; session.fsync(output)
                }
                before.put("session_id", id)
                FileOutputStream(File(root, "request.json")).use { output ->
                    output.write(before.toString().toByteArray()); output.fd.sync()
                }
                // API27 uses a mutable explicit PendingIntent so the installer
                // can fill EXTRA_STATUS. The private receiver records only.
                // Instrumentation teardown can mark the package stopped while
                // it is being replaced. The explicit private callback must still
                // match during that window (available since API12).
                val receipt = Intent(context, InstallProbeReceiver::class.java).setAction(InstallProbeReceiver.ACTION)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES).putExtra("run_id", token)
                val sender = PendingIntent.getBroadcast(context, id, receipt, PendingIntent.FLAG_UPDATE_CURRENT)
                android.util.Log.i("ThinkOS", "SELF_UPDATE_COMMIT session=$id uid=${Process.myUid()} pid=${Process.myPid()} code=$code")
                session.commit(sender.intentSender); committed = true
            }
        } finally { if (!committed) installer.abandonSession(id) }
        // A successful self-update kills this ART process. Finishing the runner
        // here could itself stop the app and invalidate replacement evidence.
        awaitCancellation()
    }
}
