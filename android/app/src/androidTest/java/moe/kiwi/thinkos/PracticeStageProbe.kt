package moe.kiwi.thinkos

import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.kiwi.thinkos.anim.Motion
import moe.kiwi.thinkos.core.Attention
import moe.kiwi.thinkos.core.DeckRouter
import moe.kiwi.thinkos.core.DeviceState
import moe.kiwi.thinkos.core.Store
import moe.kiwi.thinkos.plugin.PluginPage
import moe.kiwi.thinkos.settings.SettingsStore
import moe.kiwi.thinkos.ui.PageEvents
import moe.kiwi.thinkos.ui.Palette
import moe.kiwi.thinkos.ui.ThinkStage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Test APK only. Independent actual Stage/Router/Store in an owned Dialog.
 * No real RuntimeHost onboarding, Lua, user preference/store write or fake draw.
 * Root owns kernel input/foreground guards and any Surface recording.
 */
internal object PracticeStageProbe {
    suspend fun run(runner: UpdateInstrumentation, directory: File, token: String, result: JSONObject,
                    test: suspend (String, suspend () -> Unit) -> Unit) {
        check(token.matches(Regex("[a-f0-9]{32}")))
        val context = runner.targetContext
        val area = File(directory, "practice-stage")
        check(!area.exists() && area.mkdirs())
        val immutable = listOf("settings/local.json", "settings/local.json.bak", "settings/server.json",
            "settings/server.json.bak", "store/welcome.json", "store/welcome.json.bak", "cast/identity-seed",
            "cast/airplay-identity", "management-exit").map { File(context.filesDir, it) } +
            listOf("deck.xml", "deck.xml.bak").map { File(context.applicationInfo.dataDir, "shared_prefs/$it") }
        fun bytes(file: File): ByteArray? = if (!file.exists()) null else {
            check(file.isFile && file.length() <= 8 * 1024 * 1024); file.readBytes()
        }
        val originals = immutable.associateWith(::bytes)
        fun untouched() = originals.all { (file, before) -> bytes(file).let { after ->
            if (before == null) after == null else after != null && before.contentEquals(after)
        } }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun quiet() = check((1..5).all { audio.getStreamVolume(it) == 0 })
        val activity = runner.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) as MainActivity
        val host = main { (context.applicationContext as ThinkApplication).runtime }
        until { main { host.router.currentPage != null } }
        val originalDeck = main { host.store.current.deck }
        val originalSettings = main { host.settings.snapshot(SettingsStore.SYSTEM) }
        val originalMedia = main { host.media.snapshot() }
        val observations = JSONObject().put("scope", "Independent production Stage synchronous-clear feedback and local Home logic only")
            .put("surface_verified", false).put("real_guide_opened", false).put("timeline", JSONArray())
            .put("pending", JSONArray(listOf("Surface checkmark presentation", "global Home receiver and full guide")))
        result.put("practice_stage", observations)
        var fixture: Fixture? = null
        var marked = false
        suspend fun unchanged() {
            quiet(); check(untouched()) { "A real private file changed; preserve the newer data" }
            main {
                fixture?.assertHealthy()
                check(host.store.current.deck == originalDeck && !host.router.onboardingOpen)
                check(host.settings.snapshot(SettingsStore.SYSTEM) == originalSettings && host.media.snapshot() == originalMedia)
            }
        }
        suspend fun checked(name: String, block: suspend () -> Unit) {
            test(name) { unchanged(); block(); unchanged() }
            runner.checkedSnapshot()
        }
        try {
            checked("isolated Stage starts with completed real guide, idle media and five zero streams") {
                main {
                    check(!host.router.canGoBack && !host.router.settingsOpen && !host.store.current.safeMode)
                    check((host.settings.get(SettingsStore.SYSTEM, "onboarding.done") as Number).toInt() == 1)
                    check(originalMedia["status"] == "stopped" && originalMedia["owner"].toString().isEmpty())
                    check(!host.updates.screensSnapshot().settings && !host.updates.status.value.busy)
                    host.updates.nativeWindow(true); marked = true
                    fixture = Fixture(activity, area, observations.getJSONArray("timeline"))
                }
            }
            val owned = fixture!!
            until { main { owned.stage.isAttachedToWindow && owned.stage.hasWindowFocus() && owned.stage.width > 0 } }
            main { owned.reset("long-press", "swipe") }
            atomic(File(area, "ready.json"), JSONObject().put("state", "ready").put("generation", 0)
                .put("run_id", token).put("pid", Process.myPid())
                .put("control", "practice-stage/control.json").put("actions", JSONArray(listOf("run", "abort"))))
            val runGeneration = awaitControl(area, 0, "run") { unchanged() }
            checked("synchronous clear and next preserve actual first-drawn check until held long-press UP") {
                main { owned.reset("long-press", "swipe") }
                val down = SystemClock.uptimeMillis()
                main { owned.touch(MotionEvent.ACTION_DOWN, down, .5f, .5f) }
                until { main { owned.assertHealthy(); owned.drawAt > 0 } }
                // The real held pointer, not a narrow scheduler deadline, keeps
                // this case deterministic even on a low-priority test thread.
                delay(1000) // Test-only Surface observation window; production feedback stays120ms.
                main {
                    check(owned.stage.debugState().getBoolean("practiceDone"))
                    check(owned.stage.debugState().getString("practice") == "long-press")
                    check(owned.member(owned.stage, "practiceFeedbackReady") == true)
                    check(owned.names == listOf("long-press"))
                    owned.record("held_after_feedback", SystemClock.uptimeMillis())
                    check(SystemClock.uptimeMillis() - owned.drawAt >= Motion.FEEDBACK_EXIT)
                    owned.touch(MotionEvent.ACTION_UP, down, .5f, .5f)
                    check(owned.stage.debugState().getString("practice") == "swipe")
                    check(!owned.stage.debugState().getBoolean("practiceDone"))
                    check(owned.names == listOf("long-press"))
                    owned.record("up_handoff", SystemClock.uptimeMillis())
                }
                val swipe = SystemClock.uptimeMillis()
                main { owned.next = "variant"; owned.touch(MotionEvent.ACTION_DOWN, swipe, .25f, .5f) }
                delay(60)
                main { owned.touch(MotionEvent.ACTION_MOVE, swipe, .6f, .5f) }
                delay(60)
                main {
                    owned.touch(MotionEvent.ACTION_UP, swipe, .8f, .5f)
                    owned.touch(MotionEvent.ACTION_UP, swipe, .8f, .5f) // stale duplicate, same main turn
                    check(owned.names == listOf("long-press", "swipe"))
                }
                until { main { owned.assertHealthy(); owned.stage.debugState().optString("practice") == "variant" } }
                main { check(!owned.stage.debugState().getBoolean("practiceDone")); check(owned.names.size == 2) }
            }
            checked("real covering Dialog cancels held check feedback without reusing its old UP") {
                main { owned.reset("long-press", "swipe") }
                val down = SystemClock.uptimeMillis()
                main { owned.touch(MotionEvent.ACTION_DOWN, down, .5f, .5f) }
                until { main { owned.assertHealthy(); owned.drawAt > 0 } }
                try {
                    main {
                        check(owned.names == listOf("long-press"))
                        check((owned.member(owned.stage, "pageHost") as View).visibility == View.INVISIBLE)
                        owned.showCover()
                    }
                    until { main {
                        owned.assertHealthy()
                        !owned.stage.hasWindowFocus() && owned.member(owned.stage, "practiceFeedbackPage") == null &&
                            owned.stage.debugState().optString("practice") == "swipe"
                    } }
                    main { owned.hideCover() }
                    until { main { owned.stage.hasWindowFocus() } }
                    main {
                        owned.assertVisiblePractice("swipe")
                        owned.touch(MotionEvent.ACTION_UP, down, .5f, .5f)
                        check(owned.names == listOf("long-press"))
                        check(!owned.stage.debugState().getBoolean("practiceDone"))
                        owned.record("focus_old_up_ignored", SystemClock.uptimeMillis())
                    }
                    val fresh = SystemClock.uptimeMillis()
                    main { owned.next = null; owned.touch(MotionEvent.ACTION_DOWN, fresh, .25f, .5f) }
                    delay(60)
                    main { owned.touch(MotionEvent.ACTION_MOVE, fresh, .6f, .5f) }
                    delay(60)
                    main {
                        owned.touch(MotionEvent.ACTION_UP, fresh, .8f, .5f)
                        check(owned.names == listOf("long-press", "swipe"))
                    }
                    until { main { owned.assertHealthy(); owned.member(owned.stage, "practiceFeedbackPage") == null } }
                    main { check((owned.member(owned.stage, "pageHost") as View).visibility == View.VISIBLE) }
                } finally { main { owned.hideCover() } }
            }
            checked("real owned window resize drops a new candidate pointer while a swipe handoff is pending") {
                main { owned.reset("swipe", "variant") }
                val swipe = SystemClock.uptimeMillis()
                main { owned.touch(MotionEvent.ACTION_DOWN, swipe, .25f, .5f) }
                delay(60)
                main { owned.touch(MotionEvent.ACTION_MOVE, swipe, .6f, .5f) }
                delay(60)
                var candidate = 0L
                val oldHeight = main {
                    owned.touch(MotionEvent.ACTION_UP, swipe, .8f, .5f)
                    // Same main turn: the old check cannot draw/expire before this new DOWN.
                    candidate = SystemClock.uptimeMillis()
                    owned.touch(MotionEvent.ACTION_DOWN, candidate, .5f, .25f)
                    owned.touch(MotionEvent.ACTION_MOVE, candidate, .5f, .65f)
                    check(owned.names == listOf("swipe"))
                    check(owned.member(owned.stage, "practiceFeedbackPage") != null)
                    check(owned.member(owned.stage, "pendingPractice") == ("variant" to instruction("variant")))
                    check(owned.stage.debugState().getBoolean("touching") && owned.member(owned.stage, "axis") == 2)
                    owned.stage.height.also { owned.resizeOwnedWindow() }
                }
                try {
                    until { main {
                        owned.assertHealthy()
                        owned.stage.height != oldHeight && owned.member(owned.stage, "practiceFeedbackPage") == null &&
                            owned.stage.debugState().optString("practice") == "variant"
                    } }
                    main {
                        owned.assertVisiblePractice("variant")
                        owned.touch(MotionEvent.ACTION_UP, candidate, .5f, .85f)
                        check(owned.names == listOf("swipe"))
                        check(!owned.stage.debugState().getBoolean("practiceDone"))
                        owned.record("resize_old_up_ignored", SystemClock.uptimeMillis())
                    }
                    val fresh = SystemClock.uptimeMillis()
                    main { owned.next = null; owned.touch(MotionEvent.ACTION_DOWN, fresh, .5f, .25f) }
                    delay(60)
                    main { owned.touch(MotionEvent.ACTION_MOVE, fresh, .5f, .6f) }
                    delay(60)
                    main {
                        owned.touch(MotionEvent.ACTION_UP, fresh, .5f, .85f)
                        check(owned.names == listOf("swipe", "variant"))
                    }
                    until { main { owned.assertHealthy(); owned.member(owned.stage, "practiceFeedbackPage") == null } }
                    main { check((owned.member(owned.stage, "pageHost") as View).visibility == View.VISIBLE) }
                } finally { main { owned.restoreOwnedWindow() } }
                until { main { owned.stage.height == oldHeight } }
            }
            checked("actual local bottom-edge Home exits the isolated onboarding stack in its dispatch turn") {
                main { owned.reset("home", null) }
                val down = SystemClock.uptimeMillis()
                main { owned.touch(MotionEvent.ACTION_DOWN, down, .5f, 1f, bottom = true) }
                delay(60)
                main { owned.touch(MotionEvent.ACTION_MOVE, down, .5f, .55f) }
                delay(60)
                main {
                    owned.touch(MotionEvent.ACTION_UP, down, .5f, .2f)
                    check(!owned.router.onboardingOpen && owned.router.currentPage?.id == "home")
                    check(owned.member(owned.stage, "practiceFeedbackPage") == null)
                    check(owned.names == listOf("home"))
                    owned.record("home_dispatch_return", SystemClock.uptimeMillis())
                }
            }
            atomic(File(area, "ready.json"), JSONObject().put("state", "finished").put("generation", runGeneration)
                .put("run_id", token).put("pid", Process.myPid())
                .put("actions", JSONArray(listOf("finish", "abort"))).put("surface_verified", false))
            awaitControl(area, runGeneration, "finish") { unchanged() }
            checked("owned Stage detaches and deletes only its exact cache preferences") {
                main { owned.close(); fixture = null }
                observations.put("own_preferences_removed", !File(area, "prefs").exists())
                check(!File(area, "prefs").exists())
            }
        } finally {
            main { try { fixture?.close() } finally { if (marked) host.updates.nativeWindow(false) } }
        }
    }

    private class Fixture(private val activity: MainActivity, private val area: File, private val timeline: JSONArray) {
        private val prefsDir = File(area, "prefs").also { check(!it.exists() && it.mkdir()) }
        private val isolated = object : ContextWrapper(activity) {
            private var deck: SharedPreferences? = null
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(name == "deck" && mode == Context.MODE_PRIVATE)
                // API27 test-only implementation; never fall back to user prefs.
                return deck ?: (Class.forName("android.app.SharedPreferencesImpl")
                    .getDeclaredConstructor(File::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
                    .newInstance(File(prefsDir, "deck.xml"), mode) as SharedPreferences).also { deck = it }
            }
        }
        private val store = Store(DeviceState(attention = Attention.ENGAGED))
        val router = DeckRouter(isolated, store, onPageEvent = { _, event, _ ->
            if (event == "page.leave" && this::stage.isInitialized) stage.setPractice(null, "")
        })
        lateinit var stage: ThinkStage; private set
        val names = mutableListOf<String>()
        var next: String? = null
        var drawAt = 0L; private set
        private var callbackFailure: Throwable? = null
        private val pages = listOf(
            PluginPage("stage-probe", "home", "独立首页", emptyView(), roles = listOf("home")),
            PluginPage("stage-probe", "hello", "独立练习", emptyView(), roles = listOf("onboarding")))
        private val dialog: Dialog
        private var cover: Dialog? = null
        private var closed = false
        init {
            val events = object : PageEvents {
                override val tilesLive = false
                override val tilesIntervalMillis = Motion.BREATH
                override fun send(pluginId: String, name: String, args: List<Any?>) = Unit
                override fun closeSettings() = Unit
                override fun settingsInteraction() = Unit
                override fun text(pluginId: String, key: String): String? = null
            }
            stage = ThinkStage(isolated, store, router, router::noteInteraction,
                { callbackFailure = IllegalStateException("Unexpected maintenance") }, events,
                { callbackFailure = IllegalStateException("Unexpected real settings request") })
            stage.onPractice = { name ->
                try {
                names += name
                val hint = member(stage, "practiceHint")!!
                if (name != "home") {
                    @Suppress("UNCHECKED_CAST")
                    val realFirstDraw = member(hint, "onCompleteDraw") as? (() -> Unit) ?: error("No real first-draw feedback callback")
                    hint.javaClass.getDeclaredField("onCompleteDraw").apply { isAccessible = true }.set(hint, {
                        try {
                        drawAt = SystemClock.uptimeMillis()
                        check(member(hint, "complete") == true && (hint as View).visibility == View.VISIBLE)
                        check(stage.debugState().getBoolean("practiceDone") && stage.debugState().getString("practice") == name)
                        val pending = member(stage, "pendingPractice") as? Pair<*, *>
                        check(pending?.first == next)
                        record("first_check_onDraw", drawAt)
                        realFirstDraw() // Observe and delegate; never call draw manually.
                        } catch (error: Throwable) { callbackFailure = error }
                    } as () -> Unit)
                }
                stage.setPractice(null, "") // Actual RuntimeHost synchronous-clear ordering.
                next?.let { stage.setPractice(it, instruction(it)) }
                record("sync_clear_and_next_return", SystemClock.uptimeMillis())
                } catch (error: Throwable) { callbackFailure = error }
            }
            router.setPages(pages); stage.updateCatalog(pages, emptyList()); stage.start()
            dialog = Dialog(activity, android.R.style.Theme_Material_NoActionBar).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE); setCanceledOnTouchOutside(false)
                setOnKeyListener { _, key, _ -> key == KeyEvent.KEYCODE_BACK }
                setContentView(stage, ViewGroup.LayoutParams(-1, -1)); show()
                window!!.setBackgroundDrawable(ColorDrawable(Palette.DARK.bg)); window!!.setLayout(-1, -1)
                window!!.decorView.systemUiVisibility = activity.window.decorView.systemUiVisibility
            }
        }
        fun member(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
        fun assertHealthy() { callbackFailure?.let { throw IllegalStateException("Owned Stage callback failed", it) } }
        fun showCover() {
            check(!closed && cover == null && stage.hasWindowFocus())
            val owned = Dialog(activity, android.R.style.Theme_Material_NoActionBar).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE); setCanceledOnTouchOutside(false)
                setOnKeyListener { _, key, _ -> key == KeyEvent.KEYCODE_BACK }
                setContentView(View(activity), ViewGroup.LayoutParams(1, 1))
            }
            cover = owned // Close can always dismiss this owned window, even if show/setup fails.
            owned.show()
            owned.window!!.setLayout(1, 1)
            owned.window!!.decorView.systemUiVisibility = activity.window.decorView.systemUiVisibility
        }
        fun hideCover() { cover?.dismiss(); cover = null }
        fun resizeOwnedWindow() {
            check(!closed && stage.width > 256 && stage.height > 256)
            val inset = (48 * activity.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            check(stage.height - inset > 128)
            dialog.window!!.setLayout(stage.width, stage.height - inset)
        }
        fun restoreOwnedWindow() { dialog.window!!.setLayout(-1, -1) }
        fun assertVisiblePractice(name: String) {
            assertHealthy()
            check(stage.hasWindowFocus() && stage.debugState().optString("practice") == name)
            val host = member(stage, "pageHost") as View
            val current = member(stage, "current") as View
            val hint = member(stage, "practiceHint") as View
            check(host.visibility == View.VISIBLE && host.isShown && current.isShown && hint.isShown)
            check(current.width > 0 && current.height > 0 && hint.width > 0 && hint.height > 0)
            check(member(stage, "practiceFeedbackPage") == null)
        }
        fun reset(name: String, nextName: String?) {
            stage.stop(); router.homeKey(); names.clear(); drawAt = 0; next = nextName
            check(router.openOnboarding("restart")); stage.start(); stage.setPractice(name, instruction(name))
        }
        fun touch(action: Int, down: Long, x: Float, y: Float, bottom: Boolean = false) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, stage.width * x,
                if (bottom) stage.height - 1f else stage.height * y, 0)
            try { check(stage.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        fun record(phase: String, at: Long) {
            timeline.put(JSONObject().put("phase", phase).put("uptime_ms", at).put("first_draw_ms", drawAt)
                .put("state", stage.debugState()).put("callbacks", JSONArray(names)))
        }
        fun close() {
            if (closed) return
            hideCover()
            stage.stop(); router.homeKey(); router.setPages(emptyList()); dialog.dismiss()
            check(!stage.isAttachedToWindow)
            check(isolated.getSharedPreferences("deck", Context.MODE_PRIVATE).edit().clear().commit())
            val files = prefsDir.listFiles()?.toList() ?: error("Own preferences directory disappeared")
            check(files.all { it.name in setOf("deck.xml", "deck.xml.bak") && it.isFile &&
                it.canonicalFile == File(prefsDir.canonicalFile, it.name) })
            files.forEach { check(it.delete()) }; check(prefsDir.delete()); closed = true
        }
    }
    private fun emptyView() = JSONObject("""{"layout":"focus","slots":{"hero":{"type":"text","value":""}}}""")
    private fun instruction(name: String) = when (name) {
        "long-press" -> "长按进入设置"; "swipe" -> "左右滑动换页"; "variant" -> "上下滑动换样式"
        "home" -> "从底部上滑回到首页"; else -> error("Unknown fixture lesson")
    }
    private suspend fun <T> main(block: () -> T): T = withContext(Dispatchers.Main) { block() }
    private suspend fun until(predicate: suspend () -> Boolean) = withTimeout(10_000) { while (!predicate()) delay(20) }
    private suspend fun awaitControl(area: File, previous: Long, action: String, guard: suspend () -> Unit): Long = withTimeout(240_000) {
        while (true) {
            guard()
            val file = File(area, "control.json")
            if (file.isFile) {
                check(file.length() in 1..4096)
                val value = JSONObject(file.readText())
                val generation = value.getLong("generation")
                if (generation > previous) {
                    check(value.getString("action") != "abort") { "Owned Stage probe aborted" }
                    check(value.getString("action") == action) { "Unexpected fixed Stage probe action" }
                    return@withTimeout generation
                }
            }
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE") error("Unreachable")
    }
    private fun atomic(file: File, value: JSONObject) {
        val temporary = File(file.parentFile, file.name + ".new")
        FileOutputStream(temporary).use { it.write(value.toString(2).toByteArray()); it.fd.sync() }
        check(temporary.renameTo(file))
    }
}
