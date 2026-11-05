package moe.kiwi.thinkos

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.security.MessageDigest

/** Explicit test dependency only. Reflection keeps ordinary test builds free
 * of a WebRTC link dependency. No PeerConnectionFactory/AndroidGlobals init,
 * PeerConnection, audio factory, camera, EGL or actual encoder/decoder creation. */
internal object CallWebRtcFactoryProbe {
    private const val MAX_ASSET = 16384
    private const val MAX_LIBRARY = 10 * 1024 * 1024
    private const val MAX_REPORT = 96 * 1024
    private val hash = Regex("[0-9a-f]{64}")
    private val selected = setOf("android", "android-prefixed", "android-prefixed-stripped")

    internal data class Loaded(val report: JSONObject, val namespace: String, val loader: ClassLoader)

    /** One native trust/load implementation for the explicit metadata and frame phases. */
    internal fun load(test: Context, target: Context, standardFrame: Boolean = false): Loaded {
        check(Build.VERSION.SDK_INT == 27 && Build.SUPPORTED_ABIS.contains("armeabi-v7a"))
        check(Looper.myLooper() != Looper.getMainLooper())
        check(target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        CallWebRtcProbePolicy.requirePackages(target.packageName, test.packageName)
        check(android.os.Process.myUid() == target.applicationInfo.uid) { "Fixture target UID differs from executing UID" }
        fun denied(permission: String) = target.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED
        CallWebRtcProbePolicy.requireDeniedPermissions(denied(android.Manifest.permission.CAMERA), denied(android.Manifest.permission.RECORD_AUDIO))
        quiet(target)
        // Missing explicit dependency/asset is a failed phase, never a successful empty list.
        val bytes = test.assets.open("call-probe/webrtc.json").use { it.readBytesLimited(MAX_ASSET) }
        val receipt = JSONObject(String(bytes, Charsets.UTF_8))
        check(receipt.keys().asSequence().toSet() == setOf("schema", "variant", "version", "aar_sha256", "aar_size",
            "so_filename", "so_sha256", "so_size", "namespace", "filtered_aar_sha256"))
        check(receipt.getInt("schema") == 1 && receipt.getString("version") == "150.7871.04")
        val variant = receipt.getString("variant").also { check(it in selected) }
        if (standardFrame) CallH264ProbePolicy.requirePinnedStandard(variant, receipt.getString("version"),
            receipt.getString("aar_sha256"), receipt.getString("so_sha256"))
        val ns = if (variant == "android") "org.webrtc" else "livekit.org.webrtc"
        val name = if (variant == "android") "jingle_peerconnection_so" else "lkjingle_peerconnection_so"
        val filename = "lib$name.so"
        check(receipt.getString("namespace") == ns && receipt.getString("so_filename") == filename)
        for (key in listOf("aar_sha256", "so_sha256", "filtered_aar_sha256")) check(hash.matches(receipt.getString(key)))
        check(receipt.getLong("aar_size") in 1L..(64L*1024*1024) && receipt.getLong("so_size") in 52L..MAX_LIBRARY.toLong())
        val libraryRoot = File(requireNotNull(test.applicationInfo.nativeLibraryDir)).canonicalFile
        val library = File(libraryRoot, filename)
        check(!Files.isSymbolicLink(library.toPath()) && library.canonicalFile.parentFile == libraryRoot && library.isFile)
        val native = library.inputStream().use { it.readBytesLimited(MAX_LIBRARY) }
        check(native.size.toLong() == receipt.getLong("so_size") && sha(native) == receipt.getString("so_sha256"))
        check(native.size >= 52 && native.copyOfRange(0, 7).contentEquals(byteArrayOf(0x7f,69,76,70,1,1,1)) &&
            (native[18].toInt() and 255) == 40 && (native[19].toInt() and 255) == 0)
        val mapsBefore = File("/proc/self/maps").inputStream().use { it.readBytesLimited(2*1024*1024) }.toString(Charsets.UTF_8)
        CallWebRtcProbePolicy.requireFresh(mapsBefore)
        val report = JSONObject().put("schema", 1).put("scope", "explicit_test_native_load_and_factory_metadata")
            .put("input", receipt).put("target_package", target.packageName).put("test_package", test.packageName)
            .put("native_library_loaded", false).put("jni_onload_executed", false)
            .put("native_global_side_effects_not_excluded", true)
            .put("camera_permission_granted", false).put("microphone_permission_granted", false)
            .put("peer_connection_factory_initialized", false).put("peer_connection_created", false)
            .put("camera_opened", false).put("microphone_opened", false).put("actual_codec_created", false)
            .put("egl_created", false).put("network_api_requested", false).put("performance_measured", false)
        val loader = checkNotNull(CallWebRtcFactoryProbe::class.java.classLoader)
        // This is only the SDK's package-private load helper, not the public
        // PeerConnectionFactory.initialize that also initializes Android globals.
        val nativeLibrary = Class.forName("$ns.NativeLibrary", true, loader)
        val loaderInterface = Class.forName("$ns.NativeLibraryLoader", true, loader)
        check(nativeLibrary.classLoader === loader && loaderInterface.classLoader === loader) { "Unexpected SDK classloader" }
        val defaultLoader = Class.forName("$ns.NativeLibrary\$DefaultLoader", true, loader)
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        check(defaultLoader.javaClass.classLoader === loader) { "Unexpected SDK loader helper classloader" }
        CallWebRtcProbePolicy.requireDeniedPermissions(denied(android.Manifest.permission.CAMERA), denied(android.Manifest.permission.RECORD_AUDIO))
        nativeLibrary.getDeclaredMethod("initialize", loaderInterface, String::class.java).apply { isAccessible = true }
            .invoke(null, defaultLoader, name)
        val loaded = nativeLibrary.getDeclaredMethod("isLoaded").apply { isAccessible = true }.invoke(null) == true
        check(loaded)
        val mapsAfter = File("/proc/self/maps").inputStream().use { it.readBytesLimited(2*1024*1024) }.toString(Charsets.UTF_8)
        val mappedPath = CallWebRtcProbePolicy.requireNewMapping(mapsBefore, mapsAfter, library)
        // Kept only in the private local report: no hardware serial or public
        // machine identifier is collected. Basename alone is never load proof.
        report.put("private_load_evidence", JSONObject().put("verified_canonical_path", library.canonicalPath)
            .put("mapped_canonical_path", mappedPath).put("same_classloader", true)
            .put("probe_classloader_type", loader?.javaClass?.name)
            .put("sdk_classloader_type", nativeLibrary.classLoader?.javaClass?.name))
        report.put("native_library_loaded", true).put("jni_onload_executed", true)
        return Loaded(report, ns, loader)
    }

    fun run(test: Context, target: Context): JSONObject {
        fun denied(permission: String) = target.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED
        val loaded = load(test, target)
        val report = loaded.report
        val ns = loaded.namespace
        val loader = loaded.loader
        val contextClass = Class.forName("$ns.EglBase\$Context", false, loader)
        val rows = JSONArray(); report.put("factories", rows)
        var complete = true
        for (factoryName in listOf("SoftwareVideoEncoderFactory", "SoftwareVideoDecoderFactory", "DefaultVideoEncoderFactory", "DefaultVideoDecoderFactory")) {
            val row = JSONObject().put("factory", factoryName).put("actual_codec_created", false)
            rows.put(row)
            var stage = "class_loading"
            var codecIndex: Int? = null
            try {
                val type = Class.forName("$ns.$factoryName", true, loader)
                stage = "factory_construction"
                val factory = when (factoryName) {
                    "DefaultVideoEncoderFactory" -> type.getConstructor(contextClass, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
                        .newInstance(null, false, false)
                    "DefaultVideoDecoderFactory" -> type.getConstructor(contextClass).newInstance(*arrayOf<Any?>(null))
                    else -> type.getConstructor().newInstance()
                }
                // Software constructors allocate native factory objects; there
                // is no public dispose API. Each top-level factory is constructed
                // once; SDK-owned helper count is not asserted. Use a fresh test
                // process per phase, never a repeated allocation loop.
                stage = "get_supported_codecs"
                val codecs = type.getMethod("getSupportedCodecs").invoke(factory) as? Array<*> ?: error("codec array")
                check(codecs.size <= 64)
                val values = JSONArray()
                for ((index, codec) in codecs.withIndex()) {
                    codecIndex = index
                    stage = "codec_identity"
                    requireNotNull(codec)
                    val codecType = codec.javaClass
                    val codecName = text(codecType.getField("name").get(codec), 64)
                    stage = "codec_params"
                    val params = codecType.getField("params").get(codec) as? Map<*, *> ?: error("codec params")
                    check(params.size <= 16)
                    val fields = JSONObject()
                    for ((key,value) in params) fields.put(text(key,128),text(value,256))
                    stage = "codec_scalability_modes"
                    // The SDK native-facing constructor stores the List argument
                    // directly; null means metadata was not supplied, not an empty
                    // supported-mode list or a decoder/factory failure.
                    val modes = CallWebRtcProbePolicy.scalabilityModes(codecType.getField("scalabilityModes").get(codec))
                    stage = "codec_payload"
                    val payload = codecType.getField("payload").get(codec) as? Int ?: error("payload")
                    stage = "codec_report"
                    values.put(JSONObject().put("name",codecName).put("params",fields).put("payload",payload)
                        .put("scalability_modes",modes?.let { JSONArray(it) } ?: JSONObject.NULL))
                }
                row.put("supported_codecs",values).put("enumerated",true)
            } catch (error: Exception) {
                complete = false
                val cause = (error as? InvocationTargetException)?.targetException ?: error
                // All reasons/stages are fixed probe vocabulary. Never include
                // arbitrary SDK exception messages, stack traces or local paths.
                val reason = when {
                    error is InvocationTargetException -> "sdk_invocation_failed"
                    cause is ReflectiveOperationException -> "sdk_api_mismatch"
                    stage.startsWith("codec_") -> "invalid_codec_metadata"
                    else -> "factory_enumeration_failed"
                }
                row.put("enumerated",false).put("error_class",cause.javaClass.simpleName.take(80))
                    .put("error_stage",stage).put("error_reason",reason)
                codecIndex?.let { row.put("codec_index",it) }
            }
        }
        quiet(target)
        CallWebRtcProbePolicy.requireDeniedPermissions(denied(android.Manifest.permission.CAMERA), denied(android.Manifest.permission.RECORD_AUDIO))
        report.put("enumeration_complete",complete).put("webrtc_codec_selection_verified",false)
        check(report.toString().toByteArray(Charsets.UTF_8).size <= MAX_REPORT)
        return report
    }

    private fun text(value: Any?, max: Int): String {
        check(value is String && value.toByteArray(Charsets.UTF_8).size <= max && value.none { it.isISOControl() })
        return value
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun quiet(context: Context) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        check((1..5).all { audio.getStreamVolume(it) == 0 })
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer)
            if (n < 0) return out.toByteArray()
            check(out.size() <= limit - n) { "bounded input" }
            out.write(buffer,0,n)
        }
    }
}
