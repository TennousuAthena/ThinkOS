package moe.kiwi.thinkos

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Looper
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject

/** Test APK only. Reads Camera2/codec metadata under the real application UID.
 * No camera device/session, MediaCodec instance, microphone, frame, network,
 * setting mutation, permission grant or WebRTC library is created here. */
internal object CallCapabilityProbe {
    private const val MAX_CAMERAS = 8
    private const val MAX_CODEC_SCAN = 256
    private const val MAX_AVC_CODECS = 32
    private const val MAX_TYPES = 32
    private const val MAX_COLORS = 32
    private const val MAX_SIZES = 256
    private const val MAX_AE_RANGES = 32
    private const val MAX_REPORT_BYTES = 96 * 1024
    private val target = Size(1280, 720)

    fun run(context: Context): JSONObject {
        check(Build.VERSION.SDK_INT == 27)
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(Looper.myLooper() != Looper.getMainLooper())
        quiet(context)
        val report = JSONObject().put("schema", 1).put("sdk", Build.VERSION.SDK_INT)
            .put("scope", "platform_metadata_only")
            .put("camera_permission_granted", context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            .put("camera_opened", false).put("codec_created", false).put("microphone_opened", false)
            .put("webrtc_loaded", false).put("webrtc_selection_verified", false)
            .put("target", JSONObject().put("width", target.width).put("height", target.height).put("fps", 30))
        val cameras = JSONArray()
        val avc = JSONArray()
        report.put("cameras", cameras).put("avc_codecs", avc)
        var complete = true
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val ids = manager.cameraIdList
            report.put("camera_count", ids.size)
            if (ids.size > MAX_CAMERAS) complete = false
            report.put("cameras_truncated", ids.size > MAX_CAMERAS)
            for ((index, id) in ids.take(MAX_CAMERAS).withIndex()) {
                val row = JSONObject().put("index", index)
                cameras.put(row)
                try {
                    row.put("id", name(id))
                    camera(manager.getCameraCharacteristics(id), row)
                } catch (error: Exception) {
                    complete = false
                    row.put("error", error.javaClass.simpleName.take(80))
                }
            }
        } catch (error: Exception) {
            complete = false
            report.put("camera_enumeration_error", error.javaClass.simpleName.take(80))
        }
        try {
            val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            report.put("platform_codec_count", codecs.size)
                .put("codec_scan_truncated", codecs.size > MAX_CODEC_SCAN)
            if (codecs.size > MAX_CODEC_SCAN) complete = false
            var matches = 0
            for (info in codecs.take(MAX_CODEC_SCAN)) {
                try {
                    val types = info.supportedTypes
                    check(types.size <= MAX_TYPES)
                    val type = types.firstOrNull { it.equals("video/avc", ignoreCase = true) } ?: continue
                    matches++
                    if (matches > MAX_AVC_CODECS) { complete = false; continue }
                    val row = JSONObject().put("name", name(info.name)).put("encoder", info.isEncoder)
                        .put("mime", "video/avc")
                        .put("origin", CallCapabilityFacts.codecOrigin(info.name))
                        .put("origin_basis", "API27_component_name_heuristic_not_platform_hardware_flag")
                    avc.put(row)
                    try { codec(info.getCapabilitiesForType(type), row) }
                    catch (error: Exception) {
                        complete = false
                        row.put("error", error.javaClass.simpleName.take(80))
                    }
                } catch (error: Exception) {
                    complete = false
                    // Do not repeat an unbounded or malformed platform name.
                    report.put("codec_entry_error", error.javaClass.simpleName.take(80))
                }
            }
            report.put("avc_matches_in_scanned_codecs", matches).put("avc_rows_truncated", matches > MAX_AVC_CODECS)
        } catch (error: Exception) {
            complete = false
            report.put("codec_enumeration_error", error.javaClass.simpleName.take(80))
        }
        report.put("enumeration_complete", complete)
        quiet(context)
        check(report.toString().toByteArray(Charsets.UTF_8).size <= MAX_REPORT_BYTES)
        return report
    }

    private fun camera(info: CameraCharacteristics, row: JSONObject) {
        val level = info.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
        val facing = info.get(CameraCharacteristics.LENS_FACING)
        row.put("hardware_level", level ?: JSONObject.NULL).put("hardware_level_name", when (level) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "legacy"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "limited"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "full"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "level_3"
            else -> "unknown"
        }).put("lens_facing", facing ?: JSONObject.NULL).put("lens_facing_name", when (facing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> "unknown"
        }).put("sensor_orientation", info.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: JSONObject.NULL)
        val ranges = info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        check(ranges == null || ranges.size <= MAX_AE_RANGES)
        row.put("ae_target_fps_ranges", ranges?.let { values ->
            JSONArray(values.map { JSONArray(listOf(it.lower, it.upper)) })
        } ?: JSONObject.NULL)
        val fps = ranges?.map { it.lower..it.upper }
        val streams = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (streams == null) {
            row.put("streams", JSONObject.NULL)
            return
        }
        row.put("streams", JSONArray().put(stream(streams, false, fps)).put(stream(streams, true, fps)))
    }

    private fun stream(map: StreamConfigurationMap, surface: Boolean, fps: List<IntRange>?): JSONObject {
        val row = JSONObject().put("output", if (surface) "SurfaceTexture_PRIVATE" else "YUV_420_888")
        val sizes = if (surface) map.getOutputSizes(SurfaceTexture::class.java) else map.getOutputSizes(ImageFormat.YUV_420_888)
        check(sizes == null || sizes.size <= MAX_SIZES)
        val supported = sizes?.contains(target) ?: false
        row.put("output_size_count", sizes?.size ?: 0).put("supports_1280x720", supported)
        val minimum = if (!supported) null else if (surface) map.getOutputMinFrameDuration(SurfaceTexture::class.java, target)
            else map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, target)
        row.put("minimum_frame_duration_ns", minimum ?: JSONObject.NULL)
            .put("supports_720p30_metadata", CallCapabilityFacts.camera720p30(supported, minimum, fps) ?: JSONObject.NULL)
        return row
    }

    private fun codec(caps: MediaCodecInfo.CodecCapabilities, row: JSONObject) {
        val formats = caps.colorFormats
        check(formats.size <= MAX_COLORS)
        row.put("color_formats", JSONArray(formats.distinct().sorted().map { value ->
            JSONObject().put("value", value).put("hex", "0x" + Integer.toHexString(value).padStart(8, '0'))
        }))
        val video = caps.videoCapabilities
        if (video == null) {
            row.put("video_capabilities", JSONObject.NULL)
            return
        }
        val size = video.isSizeSupported(target.width, target.height)
        val rates = if (size) video.getSupportedFrameRatesFor(target.width, target.height) else null
        row.put("video_capabilities", JSONObject().put("supports_1280x720", size)
            .put("supports_720p30_metadata", video.areSizeAndRateSupported(target.width, target.height, 30.0))
            .put("fps_range_at_1280x720", rates?.let { JSONArray(listOf(it.lower, it.upper)) } ?: JSONObject.NULL))
    }

    private fun name(value: String): String {
        check(value.length in 1..128 && value.toByteArray(Charsets.UTF_8).size <= 256 && value.none { it.isISOControl() })
        return value
    }

    private fun quiet(context: Context) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for (stream in 1..5) check(audio.getStreamVolume(stream) == 0)
    }
}
