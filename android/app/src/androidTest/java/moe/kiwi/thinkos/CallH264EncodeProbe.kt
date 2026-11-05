package moe.kiwi.thinkos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaCodec
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Opt-in test APK / exact fixture UID only. One actual SDK-selected QCOM AVC
 * encoder and one generated I420 frame. No Activity/RuntimeHost, camera, audio,
 * EGL, PeerConnection, network request, FPS, timing or throughput measurement.
 * Reflection preserves builds with no WebRTC dependency or opt-in asset.
 */
internal object CallH264EncodeProbe {
    private val invoked = AtomicBoolean(false)
    private const val CALLBACK_SECONDS = 10L
    private const val OPERATION_SECONDS = 35L
    private const val CLEANUP_GRACE_SECONDS = 8L

    fun run(test: Context, target: Context): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper())
        CallWebRtcProbePolicy.requirePackages(target.packageName, test.packageName)
        check(invoked.compareAndSet(false, true)) { "One codec experiment per fresh PID" }
        val result = CallH264ProbeObservation(JSONObject().put("schema", 1).put("scope", "explicit_test_one_gray_H264_frame")
            .put("functional_complete", false).put("actual_codec_created", false).put("actual_codec_identity_verified", false)
            .put("encoder_object_created", false).put("init_encode_attempted", false).put("encode_calls", 0)
            .put("release_attempted", false).put("release_completed", false).put("raw_i420_released", false)
            .put("input_release_scope", "probe_owned_reference_only").put("release_scope", "SDK_release_return_status_only")
            .put("factory_helper_lifetime", "fresh_test_process").put("factory_helper_dispose_api", false)
            .put("encoded_callbacks", 0).put("callback_count_capped_at", 2)
            .put("camera_opened", false).put("microphone_opened", false).put("egl_created", false)
            .put("peer_connection_factory_initialized", false).put("peer_connection_created", false)
            .put("context_utils_initialized", false).put("network_api_requested", false).put("performance_measured", false)
            .put("capture_720p30_qualified", false).put("call_qualified", false).put("codec_library_selected_for_product", false)
            .put("native_global_side_effects_not_excluded", true).put("native_call_preemption_guaranteed", false)
            .put("operation_observation_limit_s", OPERATION_SECONDS).put("cleanup_grace_limit_s", CLEANUP_GRACE_SECONDS)
            .put("callback_limit_s", CALLBACK_SECONDS).put("sdk_release_join_limit_ms", 5000))
        val stop = AtomicBoolean(false)
        val done = CountDownLatch(1)
        val worker = Thread({
            try { encode(test, target, result, stop) }
            catch (error: Throwable) { result.failure("worker_failure", error) }
            finally { done.countDown() }
        }, "CALL0-OneH264Frame").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        worker.start()
        try {
            if (!done.await(OPERATION_SECONDS, TimeUnit.SECONDS)) {
                stop.set(true)
                result.put("operation_observation_expired", true)
                // Never interrupt a JNI/Binder call or call release concurrently
                // on the wrong SDK thread. Give the sole worker bounded time to
                // reach its own finally; an unresponsive HAL remains FAILED.
                result.failure("observation_expired", java.util.concurrent.TimeoutException())
                done.await(CLEANUP_GRACE_SECONDS, TimeUnit.SECONDS)
            }
        } catch (interrupted: InterruptedException) {
            stop.set(true)
            Thread.currentThread().interrupt()
            result.failure("observation_interrupted", interrupted)
        }
        val observedDone = done.count == 0L // Read once; final success is decided atomically below.
        // This is a snapshot. A stuck native call is not declared stopped or
        // released, no second worker/retry/kill is issued, and Root retains it.
        return result.snapshot(observedDone).also { check(it.toString().toByteArray(Charsets.UTF_8).size <= 128 * 1024) }
    }

    private fun encode(test: Context, target: Context, out: CallH264ProbeObservation, stop: AtomicBoolean) {
        var encoder: Any? = null
        var api: Class<*>? = null
        var frame: Any? = null
        var buffer: Any? = null
        var bufferClass: Class<*>? = null
        val callbackDone = CountDownLatch(1)
        val callbackCount = AtomicInteger(0)
        val callbackError = AtomicReference<Throwable?>(null)
        val callbackProof = AtomicReference<JSONObject?>(null)
        var successful = false
        var releaseOk = false
        var frameReleased = false
        fun guard() {
            check(!stop.get()) { "Observation was cancelled" }
            CallWebRtcProbePolicy.requireDeniedPermissions(
                target.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED,
                target.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_DENIED)
            val audio = target.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            check((1..5).all { audio.getStreamVolume(it) == 0 })
        }
        try {
            out.stage("native_load"); guard()
            // All existing exact UID/permission, local AAR/ELF/hash/classloader,
            // fresh maps and full canonical mapped-path checks are reused.
            val loaded = CallWebRtcFactoryProbe.load(test, target, standardFrame = true)
            guard()
            out.put("native_load", loaded.report)
            val loader = loaded.loader
            check(loaded.namespace == "org.webrtc")
            val ns = loaded.namespace
            fun type(name: String) = Class.forName("$ns.$name", true, loader).also { check(it.classLoader === loader) }
            val contextType = type("EglBase\$Context")
            val factoryType = type("DefaultVideoEncoderFactory")
            val codecInfoType = type("VideoCodecInfo")
            val encoderApi = type("VideoEncoder")
            api = encoderApi
            out.stage("default_factory")
            val factory = factoryType.getConstructor(contextType, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
                .newInstance(null, false, false)
            guard()
            val codecs = factoryType.getMethod("getSupportedCodecs").invoke(factory) as Array<*>
            guard()
            check(codecs.size in 1..64)
            val descriptions = codecs.map { codec ->
                checkNotNull(codec)
                val name = codecInfoType.getField("name").get(codec) as String
                val raw = codecInfoType.getField("params").get(codec) as Map<*, *>
                check(raw.size <= 16)
                name to raw.entries.associate { (key, value) -> (key as String) to (value as String) }
            }
            val selected = CallH264ProbePolicy.baselineIndex(descriptions)
            out.put("selected_supported_codec", JSONObject().put("name", descriptions[selected].first)
                .put("params", JSONObject(descriptions[selected].second)))
            guard(); out.stage("create_encoder")
            val selectedEncoder = checkNotNull(factoryType.getMethod("createEncoder", codecInfoType).invoke(factory, codecs[selected]))
            encoder = selectedEncoder
            out.put("encoder_object_created", true).put("encoder_class", selectedEncoder.javaClass.name)
            guard()
            // Reject native fallback wrappers or software objects before init.
            check(selectedEncoder.javaClass.name == "$ns.HardwareVideoEncoder" && selectedEncoder.javaClass.classLoader === loader)
            val implementation = encoderApi.getMethod("getImplementationName").invoke(selectedEncoder) as String
            fun member(name: String): Any? = selectedEncoder.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(selectedEncoder)
            check(member("codecName") == implementation && member("sharedContext") == null)
            val color = member("yuvColorFormat") as? Int ?: error("No byte-buffer input format")
            check(color in setOf(19, 21, 2141391872, 2141391876))
            out.put("sdk_codec_name", implementation).put("sdk_yuv_color_format", color)
            bufferClass = type("JavaI420Buffer")
            out.stage("generate_one_gray_i420")
            buffer = bufferClass.getMethod("allocate", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(null, CallH264ProbePolicy.WIDTH, CallH264ProbePolicy.HEIGHT)
            guard()
            for ((plane, expected, rows) in listOf(Triple("Y", 1280, 720), Triple("U", 640, 360), Triple("V", 640, 360))) {
                val stride = bufferClass.getMethod("getStride$plane").invoke(buffer) as Int
                val data = (bufferClass.getMethod("getData$plane").invoke(buffer) as ByteBuffer).duplicate()
                check(stride == expected && data.remaining() == stride * rows)
                val fill = ByteArray(8192) { 128.toByte() }
                while (data.hasRemaining()) data.put(fill, 0, minOf(fill.size, data.remaining()))
            }
            val frameType = type("VideoFrame")
            frame = frameType.getConstructor(type("VideoFrame\$Buffer"), Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
                .newInstance(buffer, 0, CallH264ProbePolicy.FRAME_TIMESTAMP_NS)
            buffer = null // The caller's one buffer reference is now owned by frame.
            guard()
            val callbackType = type("VideoEncoder\$Callback")
            val imageType = type("EncodedImage")
            val callback = Proxy.newProxyInstance(loader, arrayOf(callbackType)) { proxy, method, args ->
                when (method.name) {
                    "onEncodedFrame" -> {
                        // Never throw through the SDK's output thread. Read/copy
                        // borrowed bytes only inside this callback; do not retain
                        // or release the SDK-owned EncodedImage reference.
                        if (!callbackCount.compareAndSet(0, 1)) {
                            callbackCount.set(2)
                            callbackError.compareAndSet(null, IllegalStateException("More than one encoded callback"))
                            callbackDone.countDown()
                        } else try {
                            val image = checkNotNull(args?.getOrNull(0))
                            val bytes = (imageType.getField("buffer").get(image) as ByteBuffer).duplicate()
                            val size = bytes.remaining()
                            val width = imageType.getField("encodedWidth").getInt(image)
                            val height = imageType.getField("encodedHeight").getInt(image)
                            val timestamp = imageType.getField("captureTimeNs").getLong(image)
                            val kind = (imageType.getField("frameType").get(image) as Enum<*>).name
                            CallH264ProbePolicy.requireOutput(width, height, size, timestamp, kind)
                            val copy = ByteArray(size); bytes.get(copy)
                            callbackProof.set(JSONObject().put("bytes", size).put("sha256", sha(copy))
                                .put("width", width).put("height", height).put("capture_time_ns", timestamp).put("frame_type", kind))
                        } catch (error: Throwable) { callbackError.compareAndSet(null, error) }
                        finally { callbackDone.countDown() }
                        null
                    }
                    "toString" -> "CALL0 bounded encoded callback"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    else -> null
                }
            }
            val settingsType = type("VideoEncoder\$Settings")
            val settings = settingsType.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, java.lang.Boolean.TYPE)
                .newInstance(1, 1280, 720, 1000, 30, 1, false)
            out.put("settings", JSONObject().put("width", 1280).put("height", 720).put("max_framerate", 30)
                .put("start_bitrate_kbps", 1000).put("simulcast_streams", 1).put("automatic_resize", false)
                .put("input_frames", 1).put("input_i420_bytes", 1_382_400))
            guard(); out.stage("init_encode")
            val initMethod = encoderApi.getMethod("initEncode", settingsType, callbackType)
            out.codecInitStarted()
            val init = initMethod.invoke(selectedEncoder, settings, callback) as Enum<*>
            out.codecInitReturned(init.name)
            guard()
            check(init.name == "OK")
            // This is the actual SDK-created MediaCodec, not its advertised
            // factory list or VideoEncoder's default hardware=true method.
            val wrapper = checkNotNull(member("codec"))
            val platform = wrapper.javaClass.getDeclaredField("mediaCodec").apply { isAccessible = true }.get(wrapper) as MediaCodec
            val info = platform.codecInfo
            CallH264ProbePolicy.requireHardware(selectedEncoder.javaClass.name, implementation, platform.name, info.isEncoder)
            check(info.supportedTypes.size in 1..16 && info.supportedTypes.all { it.length <= 128 } &&
                info.supportedTypes.any { it.equals("video/avc", true) })
            val capabilities = info.getCapabilitiesForType("video/avc")
            check(capabilities.colorFormats.size in 1..128 && color in capabilities.colorFormats)
            check(member("useSurfaceMode") == false && member("textureEglBase") == null && member("textureInputSurface") == null)
            out.codecIdentityVerified()
            out.put("platform_codec_name", platform.name)
                .put("platform_color_formats", JSONArray(capabilities.colorFormats.toList()))
                .put("byte_buffer_input_verified", true).put("surface_input", false)
                .put("hardware_evidence", "actual_HardwareVideoEncoder_and_QCOM_MediaCodec_instance_API27")
            val encodedFrameType = type("EncodedImage\$FrameType")
            val frameTypes = java.lang.reflect.Array.newInstance(encodedFrameType, 1)
            java.lang.reflect.Array.set(frameTypes, 0, encodedFrameType.getField("VideoFrameKey").get(null))
            val encodeInfoType = type("VideoEncoder\$EncodeInfo")
            val encodeInfo = encodeInfoType.getConstructor(frameTypes.javaClass).newInstance(frameTypes)
            guard(); out.stage("encode_one_frame")
            val encodeMethod = encoderApi.getMethod("encode", frameType, encodeInfoType)
            out.put("encode_calls", 1)
            val encode = encodeMethod.invoke(selectedEncoder, frame, encodeInfo) as Enum<*>
            out.put("encode_status", encode.name)
            guard()
            check(encode.name == "OK")
            out.stage("encoded_callback")
            check(callbackDone.await(CALLBACK_SECONDS, TimeUnit.SECONDS)) { "No bounded encoded callback" }
            callbackError.get()?.let { throw it }
            check(callbackCount.get() == 1)
            out.put("encoded", checkNotNull(callbackProof.get()))
            guard(); successful = true
        } catch (error: Throwable) { out.failure("encoding_failed", error) }
        finally {
            out.stage("release")
            try {
                if (frame != null) frame.javaClass.getMethod("release").invoke(frame)
                else if (buffer != null) bufferClass!!.getMethod("release").invoke(buffer)
                frameReleased = frame != null || buffer != null
                out.put("raw_i420_released", frameReleased)
            } catch (error: Throwable) { out.failure("input_release_failed", error) }
            if (encoder != null && api != null) {
                out.put("release_attempted", true)
                try {
                    val status = api.getMethod("release").invoke(encoder) as Enum<*>
                    releaseOk = status.name == "OK"
                    out.put("release_status", status.name).put("release_completed", releaseOk)
                } catch (error: Throwable) { out.failure("encoder_release_failed", error) }
            }
            out.put("encoded_callbacks", callbackCount.get())
            callbackError.get()?.let { out.failure("encoded_callback_failed", it) }
        }
        try {
            guard()
            out.put("camera_permission_granted", false).put("microphone_permission_granted", false)
                .put("audio_streams_1_to_5_zero", true)
            out.workerCompleted(successful && releaseOk && frameReleased && callbackCount.get() == 1 && callbackError.get() == null && !out.failed())
        } catch (error: Throwable) { out.failure("post_release_gate_failed", error) }
        out.stage("finished")
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
