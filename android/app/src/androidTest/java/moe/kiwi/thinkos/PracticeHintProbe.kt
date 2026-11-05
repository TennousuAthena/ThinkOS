package moe.kiwi.thinkos

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.PointF
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.text.StaticLayout
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.kiwi.thinkos.settings.SettingsStore
import moe.kiwi.thinkos.ui.Palette
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

/** Test-APK only: actual private production Hint in an owned native window.
 * Does not open onboarding, invoke practice recognition, inject into ThinkStage,
 * change settings, or restore over user data. The root driver owns device guards.
 * Control protocol matches components_ui: monotonically increasing generation,
 * fixed actions, bounded JSON, ready/response files inside this run's cache area.
 */
internal object PracticeHintProbe {
    private val lessons = linkedMapOf("swipe" to "左右滑动换页", "variant" to "上下滑动换样式",
        "panel" to "从顶部下拉打开控制面板", "back" to "从左边缘右滑返回",
        "home" to "从底部上滑回到首页", "long-press" to "长按进入设置")
    private val immutable = listOf("settings/local.json", "settings/local.json.bak", "settings/server.json",
        "store/welcome.json", "store/welcome.json.bak", "cast/identity-seed", "cast/airplay-identity", "management-exit")

    suspend fun run(runner: UpdateInstrumentation, directory: File, token: String, result: JSONObject,
                    test: suspend (String, suspend () -> Unit) -> Unit) {
        check(token.matches(Regex("[a-f0-9]{32}")))
        val context = runner.targetContext
        val area = File(directory, "practice-hint")
        check(!area.exists() && area.mkdirs()) { "Use a fresh owned practice_hint_ui run_id" }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun quiet() = check((1..5).all { audio.getStreamVolume(it) == 0 })
        val originals = immutable.associateWith { path -> File(context.filesDir, path).let {
            if (!it.exists()) null else { check(it.isFile && it.length() <= 8 * 1024 * 1024); it.readBytes() }
        } }
        fun untouched() = originals.all { (path, old) -> File(context.filesDir, path).let {
            if (old == null) !it.exists() else it.isFile && it.readBytes().contentEquals(old)
        } }
        val activity = runner.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) as MainActivity
        val host = main { (context.applicationContext as ThinkApplication).runtime }
        until { main { host.router.currentPage != null } }
        val originalScale = scale(context)
        val settings = main { host.settings.snapshot(SettingsStore.SYSTEM) }
        val initialDeck = main { host.store.current.deck }
        val initialMedia = main { host.media.snapshot() }
        val apk = File(context.applicationInfo.sourceDir)
        val apkHash = sha(apk)
        var dialog: Dialog? = null
        var cover: Dialog? = null
        var markedWindow = false
        lateinit var hint: Hint
        lateinit var container: TouchContainer
        val observations = JSONObject().put("scope", "Actual production Hint in an isolated native dialog; no real onboarding/navigation/recognition qualification")
            .put("version", BuildConfig.VERSION_NAME).put("code", BuildConfig.VERSION_CODE).put("apk_sha256", apkHash)
            .put("motion_traces", JSONArray()).put("orientation_checkpoints", JSONArray())
            .put("surface_motion_verification_required", true).put("physical_direction_qualified", false)
        result.put("practice_hint", observations)
        val orientations = mutableSetOf<String>()
        var disabledChecked = false
        var enabledChecked = false
        suspend fun unchanged() {
            quiet(); check(untouched()) { "Unexpected private file change; preserve it" }
            main {
                check(host.settings.snapshot(SettingsStore.SYSTEM) == settings)
                check(host.store.current.deck == initialDeck && !host.router.onboardingOpen)
                check(host.media.snapshot() == initialMedia)
            }
        }
        suspend fun checked(name: String, body: suspend () -> Unit) {
            test(name) { unchanged(); body(); unchanged() }
            runner.checkedSnapshot()
        }
        try {
            checked("practice hint preflight preserves completed onboarding, idle navigation, stopped media and silence") {
                check(Locale.getDefault().language == "zh")
                main {
                    check(!host.router.canGoBack && !host.router.onboardingOpen && !host.router.settingsOpen)
                    check(!host.store.current.safeMode && !host.updates.screensSnapshot().settings)
                    check(initialMedia["status"] == "stopped" && initialMedia["owner"].toString().isEmpty())
                    check((host.settings.get(SettingsStore.SYSTEM, "onboarding.done") as Number).toDouble() == 1.0)
                    check(ValueAnimator.areAnimatorsEnabled() && scale(context) in 0.5f..4f)
                    val update = host.updates.status.value
                    check(!update.busy && !update.checking && update.state.phase !in setOf("CHECKING", "DOWNLOADING", "READY", "INSTALLING"))
                }
            }
            main {
                hint = Hint(activity)
                hint.bind("swipe")
                container = TouchContainer(activity, hint).apply { tag = token }
                host.updates.nativeWindow(true); markedWindow = true
                dialog = window(activity, container)
            }
            until { main { hint.view.isAttachedToWindow && hint.view.hasWindowFocus() && hint.view.width > 0 } }
            for (name in lessons.keys) checked("actual $name hint exposes a bounded marker in the owned native renderer") {
                main { hint.bind(name) }
                until { main { hint.demo.visibility == View.VISIBLE } }
                // Observe a complete existing motion + feedback + idle cycle. A cold low-priority
                // instrumentation thread can resume after the first short visible leg has ended.
                val observeMillis = ((moe.kiwi.thinkos.ui.ThinkStage.LONG_PRESS_MILLIS + moe.kiwi.thinkos.anim.Motion.FEEDBACK_EXIT) * originalScale + moe.kiwi.thinkos.anim.Motion.BREATH / 2 + moe.kiwi.thinkos.anim.Motion.PAGE).toLong()
                val trace = trace(hint, observeMillis)
                val observation = JSONObject().put("lesson", name).put("samples", trace)
                observations.getJSONArray("motion_traces").put(observation)
                val visible = (0 until trace.length()).map(trace::getJSONObject).filter { it.getBoolean("visible") }
                check(visible.size >= 3) { "No actual visible marker samples" }
                fun span(key: String) = visible.maxOf { it.getDouble(key) } - visible.minOf { it.getDouble(key) }
                val first = visible.first(); val last = visible.last()
                val minimum = context.resources.displayMetrics.density * 12
                // API27 RenderThread getters can expose staging/terminal properties. Keep
                // those real observations, but never replace actual Surface motion proof with them.
                val directionObserved = when (name) {
                    "swipe", "back" -> span("x") > minimum && last.getDouble("x") - first.getDouble("x") > minimum && span("y") < 2
                    "variant", "panel" -> span("y") > minimum && last.getDouble("y") - first.getDouble("y") > minimum && span("x") < 2
                    "home" -> span("y") > minimum && first.getDouble("y") - last.getDouble("y") > minimum && span("x") < 2
                    else -> span("scale") > .1 && span("x") < 2 && span("y") < 2
                }
                observation.put("property_direction_observed", directionObserved)
                check(visible.all { it.getDouble("x") >= 0 && it.getDouble("y") >= 0 &&
                    it.getDouble("x") + it.getInt("marker_width") <= it.getInt("width") &&
                    it.getDouble("y") + it.getInt("marker_height") <= it.getInt("height") })
            }
            checked("a stale real phase-finish callback cannot hide or requeue a newer lesson") {
                main { hint.bind("swipe") }; until { main { hint.demo.visibility == View.VISIBLE } }
                val late = main { hint.finishCallback() ?: error("No actual phase-finish callback") }
                main { hint.bind("home") }; until { main { hint.demo.visibility == View.VISIBLE } }
                main { late.run(); check(hint.demo.visibility == View.VISIBLE) }
            }
            checked("owned DOWN stops demonstration, held touch stays stopped, and UP resumes without touching the deck") {
                main { hint.bind("swipe") }; until { main { hint.demo.visibility == View.VISIBLE } }
                val late = main { hint.finishCallback() ?: error("No actual phase-finish callback") }
                val down = SystemClock.uptimeMillis()
                suspend fun touch(action: Int) = main {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, container.width / 2f, container.height / 2f, 0)
                    try { check(container.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
                touch(MotionEvent.ACTION_DOWN)
                delay(2500)
                main { late.run(); hint.assertStopped(); hint.replayPending(); hint.assertStopped() }
                touch(MotionEvent.ACTION_UP)
                until { main { hint.demo.visibility == View.VISIBLE } }
                val resumed = trace(hint, 700)
                observations.put("touch_resume_trace", resumed)
                val xs = (0 until resumed.length()).map(resumed::getJSONObject).filter { it.getBoolean("visible") }.map { it.getDouble("x") }
                check(xs.size >= 3)
                observations.put("touch_resume_property_span", xs.maxOrNull()!! - xs.minOrNull()!!)
                // Marker visibility/cycling proves resumed scheduling; root checks Surface movement.
                main { check(container.touches == 2) }
            }
            checked("focus loss, visibility and completion stop the real marker and reject a late owned runnable") {
                main { cover = window(activity, FrameLayout(activity).apply { setBackgroundColor(Palette.DARK.bg); isClickable = true }) }
                until { main { !hint.view.hasWindowFocus() } }
                main { hint.assertStopped(); hint.replayPending(); hint.assertStopped(); cover!!.dismiss(); cover = null }
                until { main { hint.view.hasWindowFocus() && hint.demo.visibility == View.VISIBLE } }
                main { hint.view.visibility = View.INVISIBLE; hint.replayPending(); hint.assertStopped() }
                delay(700)
                main { hint.assertStopped(); hint.view.visibility = View.VISIBLE }
                until { main { hint.demo.visibility == View.VISIBLE } }
                main { hint.bind("swipe", complete = true); hint.replayPending(); hint.assertStopped(); hint.bind("swipe") }
            }
            until { main { hint.demo.visibility == View.VISIBLE } }
            atomic(File(area, "ready.json"), main { hint.snapshot() }.put("run_id", token).put("pid", Process.myPid())
                .put("control", "cache/update-device-$token/practice-hint/control.json")
                .put("surface_motion_verification_required", true))
            var generation = 0L
            var finished = false
            withTimeout(4 * 60 * 1000L) {
                while (!finished) {
                    unchanged()
                    val file = File(area, "control.json")
                    val command = if (file.isFile && file.length() in 1..4096) runCatching { JSONObject(file.readText()) }.getOrNull() else null
                    val next = command?.optLong("generation") ?: 0
                    if (command != null && next > generation) {
                        generation = next
                        val response = JSONObject().put("generation", generation)
                        try {
                            when (command.getString("action")) {
                                "checkpoint" -> checked("Chinese ${command.getString("orientation")} production hint fits its actual native view") {
                                    val expected = command.getString("orientation"); check(expected in setOf("portrait", "landscape"))
                                    until { main { hint.snapshot().let {
                                        (it.getInt("width") > it.getInt("height")) == (expected == "landscape") && it.getBoolean("text_fits")
                                    } } }
                                    val snapshot = main { hint.snapshot() }
                                    check((snapshot.getInt("width") > snapshot.getInt("height")) == (expected == "landscape"))
                                    check(snapshot.getBoolean("text_fits") && snapshot.getBoolean("shown") && snapshot.getBoolean("focused"))
                                    response.put("checkpoint", snapshot); orientations += expected
                                    observations.getJSONArray("orientation_checkpoints").put(snapshot.put("orientation", expected))
                                }
                                "lesson" -> { val name = command.getString("name"); check(name in lessons); main { hint.bind(name) }; response.put("checkpoint", main { hint.snapshot() }) }
                                "motion_off" -> checked("real system animation-off keeps Chinese arrow and text static without a moving marker") {
                                    check(scale(context) == 0f)
                                    until { main { hint.refresh(); !ValueAnimator.areAnimatorsEnabled() } }
                                    delay(700); main { hint.assertStopped(); hint.replayPending(); hint.assertStopped(); check(hint.snapshot().getBoolean("text_fits")) }
                                    disabledChecked = true
                                }
                                "motion_on" -> checked("restoring the real animation setting allows the same owned hint to demonstrate again") {
                                    check(scale(context) > 0f)
                                    until { main { hint.refresh(); hint.demo.visibility == View.VISIBLE } }
                                    enabledChecked = true
                                }
                                "abort" -> error("Owned practice-hint run aborted by its driver")
                                "finish" -> { check(orientations == setOf("portrait", "landscape") && disabledChecked && enabledChecked); finished = true }
                                else -> error("Unknown fixed practice-hint fixture control")
                            }
                            response.put("handled", true)
                        } catch (error: Throwable) { response.put("error", error.toString()); throw error }
                        finally { atomic(File(area, "response.json"), response) }
                    }
                    delay(150)
                }
            }
            checked("dismissing the owned hint detaches it and late refresh/runnable cannot revive it") {
                main { dialog!!.dismiss(); dialog = null; check(!hint.view.isAttachedToWindow); hint.refresh(); hint.replayPending(); hint.assertStopped() }
                delay(700)
                main { hint.assertStopped(); check(!hint.view.isAttachedToWindow) }
            }
            checked("practice-hint probe leaves installed APK, private files, settings, navigation, media and silence intact") {
                check(sha(apk) == apkHash && scale(context) == originalScale)
                observations.put("private_files_unchanged", true).put("apk_unchanged", true).put("onboarding_never_opened", true)
                    .put("owned_touch_dispatches", main { container.touches })
            }
        } finally {
            main {
                cover?.dismiss(); dialog?.dismiss()
                if (markedWindow) host.updates.nativeWindow(false)
            }
        }
    }

    private class Hint(context: Context) {
        private val type = Class.forName("moe.kiwi.thinkos.ui.ThinkStage\$PracticeHint", true, context.classLoader)
        val view = type.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context) as ViewGroup
        private fun member(name: String) = type.getDeclaredField(name).apply { isAccessible = true }.get(view)
        private fun call(name: String, vararg arguments: Any?) {
            val method = type.declaredMethods.single { it.name == name && it.parameterTypes.size == arguments.size }
            method.isAccessible = true; method.invoke(view, *arguments)
        }
        val demo get() = member("demo") as View
        init { call("colors", Palette.DARK) }
        fun bind(name: String, complete: Boolean = false) {
            call("bind", null, "", false) // A fresh owned lesson has a deterministic first direction.
            call("bind", name, lessons.getValue(name), complete)
        }
        fun touch(point: PointF?) { call("touch", point) }
        fun refresh() { call("refreshMotion") }
        fun replayPending() { (member("runDemo") as Runnable).run() }
        fun finishCallback() = member("finishDemo") as? Runnable
        fun assertStopped() { check(demo.visibility != View.VISIBLE && member("queued") == false && member("cycling") == false) }
        fun snapshot(): JSONObject {
            val layout = member("textLayout") as? StaticLayout
            val fits = layout != null && layout.width <= view.width && view.height * .6 + layout.height <= view.height &&
                (0 until layout.lineCount).all { layout.getLineLeft(it) >= 0 && layout.getLineRight(it) <= layout.width && layout.getEllipsisCount(it) == 0 }
            val location = IntArray(2); view.getLocationOnScreen(location)
            return JSONObject().put("origin", JSONArray(location.toList())).put("marker_width", demo.width).put("marker_height", demo.height)
                .put("width", view.width).put("height", view.height).put("shown", view.isShown).put("focused", view.hasWindowFocus())
                .put("instruction", view.contentDescription.toString()).put("text_fits", fits).put("text_lines", layout?.lineCount ?: 0)
                .put("visible", demo.visibility == View.VISIBLE).put("x", demo.translationX.toDouble()).put("y", demo.translationY.toDouble()).put("scale", demo.scaleX.toDouble())
        }
    }
    private class TouchContainer(context: Context, private val hint: Hint) : FrameLayout(context) {
        var touches = 0; private set
        init { isClickable = true; setBackgroundColor(Palette.DARK.bg); addView(hint.view, LayoutParams(-1, -1)) }
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            touches++
            hint.touch(if (event.actionMasked in setOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) null else PointF(event.x, event.y))
            return true
        }
    }
    private fun window(activity: MainActivity, content: View): Dialog = Dialog(activity, android.R.style.Theme_Material_NoActionBar).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE); setCanceledOnTouchOutside(false)
        setOnKeyListener { _, key, _ -> key == KeyEvent.KEYCODE_BACK }
        setContentView(content, ViewGroup.LayoutParams(-1, -1)); show()
        window!!.setBackgroundDrawable(ColorDrawable(Palette.DARK.bg)); window!!.setLayout(-1, -1)
        window!!.decorView.systemUiVisibility = activity.window.decorView.systemUiVisibility
    }
    private suspend fun trace(hint: Hint, duration: Long): JSONArray {
        val values = JSONArray(); val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < duration && values.length() < 120) {
            values.put(main { hint.snapshot() }.put("elapsed_ms", SystemClock.elapsedRealtime() - start)); delay(50)
        }
        return values
    }
    private suspend fun <T> main(block: () -> T): T = withContext(Dispatchers.Main) { block() }
    private suspend fun until(predicate: suspend () -> Boolean) = withTimeout(10_000) { while (!predicate()) delay(20) }
    private fun scale(context: Context) = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    private fun atomic(file: File, value: JSONObject) {
        val temporary = File(file.parentFile, file.name + ".new")
        FileOutputStream(temporary).use { it.write(value.toString(2).toByteArray()); it.fd.sync() }; check(temporary.renameTo(file))
    }
    private fun sha(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(65536); while (true) { val n = input.read(bytes); if (n < 0) break; hash.update(bytes, 0, n) } }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
