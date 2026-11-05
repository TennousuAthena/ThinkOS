package moe.kiwi.thinkos.ui

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import moe.kiwi.thinkos.R
import moe.kiwi.thinkos.update.UpdateController
import moe.kiwi.thinkos.update.UpdateComponentStatus
import moe.kiwi.thinkos.update.UpdateModels
import moe.kiwi.thinkos.update.UpdateStatusDocument
import moe.kiwi.thinkos.update.UpdateSystemInventory
import moe.kiwi.thinkos.update.UpdateRepairUi
import java.text.NumberFormat

/** Recovery has no dependency on Lua, a plugin catalog, or a Server. The same
 * read model/actions can also back the hub. */
internal class UpdateDialog(activity: Activity, private val palette: Palette,
    private val read: () -> UpdateStatusDocument, private val changes: Flow<Unit>,
    private val perform: suspend (UpdateController.Action, Long) -> Boolean,
    private val readRepair: () -> UpdateRepairUi = { UpdateRepairUi(UpdateRepairUi.Stage.NONE) },
    private val inspectRepair: () -> Unit = {},
    private val repair: suspend (Long) -> Boolean = { false },
    private val interaction: () -> Unit,
) : Dialog(activity, if (palette == Palette.LIGHT) android.R.style.Theme_Material_Light_NoActionBar else android.R.style.Theme_Material_NoActionBar) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val density = context.resources.displayMetrics.density
    private fun px(value: Int) = (value * density + .5f).toInt()
    private fun text(size: Float = TextScale.BODY, color: Int = palette.primary) = TextView(context).apply {
        textSize = size; setTextColor(color)
    }
    private lateinit var root: LinearLayout
    private lateinit var title: TextView
    private lateinit var back: TextView
    private lateinit var current: TextView
    private lateinit var version: TextView
    private lateinit var phase: TextView
    private lateinit var result: TextView
    private lateinit var progress: TextView
    private lateinit var notes: TextView
    private lateinit var repairPanel: LinearLayout
    private lateinit var repairStatus: TextView
    private lateinit var inspectRecovery: TextView
    private lateinit var isolateRecovery: TextView
    private val inspectGate = NativeTouchGate()
    private val repairGate = NativeTouchGate()
    private var shownRepair: UpdateRepairUi? = null
    private var repairBusy = false
    private var repairFailed = false
    private var repairVisible = false
    private lateinit var components: LinearLayout
    private lateinit var inventoryNotice: TextView
    private lateinit var componentTitle: TextView
    private data class ComponentRow(val name: TextView, val state: TextView, val installed: TextView, val target: TextView,
                                    val progress: ProgressBar, val progressText: TextView, val result: TextView)
    private val componentRows = linkedMapOf<String, ComponentRow>()
    private lateinit var check: TextView
    private lateinit var download: TextView
    private lateinit var install: TextView
    private var notesSource: String? = null
    private var notesTask: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(palette.bg) }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        back = text(TextScale.TITLE).apply { gravity = Gravity.CENTER; setOnClickListener { dismiss() } }
        title = text(TextScale.TITLE).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(back, LinearLayout.LayoutParams(px(56), px(64)))
        header.addView(title, LinearLayout.LayoutParams(0, px(64), 1f))
        root.addView(header)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(24), px(24), px(24), px(24))
        }
        current = text(TextScale.CAPTION, palette.secondary)
        version = text()
        phase = text(TextScale.TITLE).apply { setPadding(0, px(16), 0, px(16)) }
        result = text(TextScale.TITLE)
        progress = text(TextScale.CAPTION, palette.secondary)
        repairPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, px(16), 0, px(8))
        }
        repairStatus = text()
        inspectRecovery = recoveryAction(inspectGate, UpdateRepairUi.Stage.NEEDED, UpdateRepairUi.Stage.BLOCKED, UpdateRepairUi.Stage.FAILED) {
            repairFailed = false
            inspectRepair()
            refresh()
        }
        isolateRecovery = recoveryAction(repairGate, UpdateRepairUi.Stage.READY) { tap ->
            repairBusy = true
            inspectGate.cancel(); repairGate.cancel()
            refresh()
            scope.launch {
                try { repair(tap) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    repairFailed = true
                    android.util.Log.e("ThinkOS", "Unable to isolate corrupt update record", error)
                } finally {
                    repairBusy = false
                    if (isShowing) refresh()
                }
            }
        }
        repairPanel.addView(repairStatus, LinearLayout.LayoutParams(-1, -2))
        repairPanel.addView(inspectRecovery, LinearLayout.LayoutParams(-1, -2))
        repairPanel.addView(isolateRecovery, LinearLayout.LayoutParams(-1, -2))
        components = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(24), 0, 0) }
        notes = text().apply { setTextIsSelectable(true); setPadding(0, px(24), 0, 0) }
        for (view in listOf(current, result, version, phase, progress, repairPanel, components, notes)) content.addView(view, LinearLayout.LayoutParams(-1, -2))
        root.addView(ScrollView(context).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(context).apply { setPadding(px(24), 0, px(24), px(16)) }
        fun action(selected: UpdateController.Action) = text(color = palette.accentText).apply {
            val gate = NativeTouchGate()
            gravity = Gravity.CENTER; minHeight = px(56)
            setOnTouchListener { view, event ->
                val now = SystemClock.elapsedRealtime()
                val eligible = view.isEnabled && view.isShown && view.hasWindowFocus() && event.pointerCount == 1 &&
                    event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED == 0
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> gate.down(now, eligible)
                    MotionEvent.ACTION_UP -> gate.up(now, eligible, event.x >= 0 && event.x < view.width && event.y >= 0 && event.y < view.height)
                    MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> gate.cancel()
                }
                false
            }
            setOnClickListener {
                val tap = SystemClock.elapsedRealtime()
                if (!gate.consume(tap, isEnabled && isShown && hasWindowFocus())) return@setOnClickListener
                scope.launch { perform(selected, tap); refresh() }
            }
        }
        check = action(UpdateController.Action.CHECK)
        download = action(UpdateController.Action.DOWNLOAD)
        install = action(UpdateController.Action.INSTALL)
        actions.addView(check, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(download, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(install, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)
        setContentView(root); window?.setLayout(-1, -1)
        refresh()
        scope.launch { changes.collect { refresh() } }
        inspectRepair()
    }

    fun refresh() {
        if (!::root.isInitialized) return
        val state = read()
        refreshRepair()
        root.layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        val rtl = android.text.TextUtils.getLayoutDirectionFromLocale(java.util.Locale.getDefault()) == View.LAYOUT_DIRECTION_RTL
        back.text = if (rtl) "›" else "‹"
        back.contentDescription = context.getString(R.string.maintenance_back)
        title.setText(R.string.update_title)
        current.text = context.getString(R.string.update_current_version, state.current_version)
        result.visibility = if (state.result == null) View.GONE else View.VISIBLE
        result.text = state.result?.let { listOfNotNull(it.message, it.reason?.takeIf { reason -> reason != it.message }).joinToString("\n") }.orEmpty()
        version.text = state.version?.let { context.getString(R.string.update_available_version, it) }.orEmpty()
        version.visibility = if (state.version == null) View.GONE else View.VISIBLE
        phase.setText(when (state.phase) {
            "checking" -> R.string.update_checking
            "available" -> R.string.update_available
            "paused" -> R.string.update_paused
            "downloading" -> R.string.update_downloading
            "ready" -> R.string.update_ready
            "installing" -> R.string.update_installing
            "up_to_date" -> R.string.update_up_to_date
            "failed" -> R.string.update_failed
            "unavailable" -> R.string.update_unavailable
            else -> R.string.update_not_checked
        })
        progress.visibility = if (state.phase == "downloading") View.VISIBLE else View.GONE
        progress.text = if (state.size > 0) context.getString(R.string.update_apk_progress,
            NumberFormat.getPercentInstance().format(state.downloaded.toDouble() / state.size)) else ""
        refreshComponents(state.components, state.inventory)
        val source = state.notes ?: state.result?.notes.orEmpty()
        if (source != notesSource) {
            notesSource = source; notesTask?.cancel(); notes.text = ""
            notesTask = scope.launch {
                val plain = if (state.notes != null) withContext(Dispatchers.Default) { LegalText.plain(source) } else source
                notes.text = plain
            }
        }
        check.setText(R.string.update_check); check.isEnabled = state.can_check; check.alpha = if (state.can_check) 1f else .4f
        download.setText(R.string.update_download); download.isEnabled = state.can_download; download.alpha = if (state.can_download) 1f else .4f
        download.visibility = if (state.version != null && state.phase !in setOf("ready", "installing")) View.VISIBLE else View.GONE
        install.setText(R.string.update_install); install.isEnabled = state.can_install; install.alpha = if (state.can_install) 1f else .4f
        install.visibility = if (state.version != null && state.phase == "ready") View.VISIBLE else View.GONE
    }

    /** Recovery credit belongs to its current read model. A read-only press can
     * never turn into a write merely because inspection finished before UP. */
    private fun recoveryAction(gate: NativeTouchGate, vararg allowed: UpdateRepairUi.Stage,
                               action: (Long) -> Unit): TextView = text(color = palette.accentText).apply {
        var pressed: UpdateRepairUi? = null
        gravity = Gravity.CENTER; minHeight = px(56)
        setOnTouchListener { view, event ->
            val now = SystemClock.elapsedRealtime()
            val current = readRepair()
            val eligible = view.isEnabled && view.isShown && view.hasWindowFocus() && event.pointerCount == 1 &&
                event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED == 0 && !repairBusy && repairStage(current) in allowed
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pressed = current; gate.down(now, eligible) }
                MotionEvent.ACTION_UP -> gate.up(now, eligible && pressed == current,
                    event.x >= 0 && event.x < view.width && event.y >= 0 && event.y < view.height)
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> { pressed = null; gate.cancel() }
            }
            false
        }
        setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            val current = readRepair()
            val accepted = gate.consume(now, isEnabled && isShown && hasWindowFocus() && !repairBusy &&
                repairStage(current) in allowed && pressed == current)
            pressed = null
            if (accepted) action(now)
        }
    }

    private fun repairStage(state: UpdateRepairUi): UpdateRepairUi.Stage =
        if (repairFailed && state.stage !in setOf(UpdateRepairUi.Stage.NONE, UpdateRepairUi.Stage.DONE)) UpdateRepairUi.Stage.FAILED else state.stage

    private fun refreshRepair() {
        val state = readRepair()
        if (shownRepair != state) { inspectGate.cancel(); repairGate.cancel(); shownRepair = state }
        val stage = repairStage(state)
        if (stage == UpdateRepairUi.Stage.NONE) repairVisible = false
        else if (stage != UpdateRepairUi.Stage.CHECKING) repairVisible = true
        // Opening a healthy update window may inspect asynchronously. Do not
        // flash a repair prompt until a damaged-record state is actually known.
        repairPanel.visibility = if (repairVisible) View.VISIBLE else View.GONE
        if (!repairVisible) return
        repairStatus.setText(when {
            repairBusy -> R.string.update_repair_working
            stage == UpdateRepairUi.Stage.CHECKING -> R.string.update_repair_checking
            stage == UpdateRepairUi.Stage.NEEDED -> R.string.update_repair_needed
            stage == UpdateRepairUi.Stage.READY -> R.string.update_repair_ready
            stage == UpdateRepairUi.Stage.BLOCKED -> R.string.update_repair_blocked
            stage == UpdateRepairUi.Stage.DONE -> R.string.update_repair_done
            else -> R.string.update_repair_failed
        })
        repairStatus.setTextColor(if (stage in setOf(UpdateRepairUi.Stage.NEEDED, UpdateRepairUi.Stage.BLOCKED, UpdateRepairUi.Stage.FAILED)) palette.warnText else palette.primary)
        inspectRecovery.setText(R.string.update_repair_inspect)
        inspectRecovery.visibility = if (stage in setOf(UpdateRepairUi.Stage.NEEDED, UpdateRepairUi.Stage.BLOCKED, UpdateRepairUi.Stage.FAILED)) View.VISIBLE else View.GONE
        inspectRecovery.isEnabled = !repairBusy
        isolateRecovery.setText(R.string.update_repair_isolate)
        isolateRecovery.visibility = if (stage == UpdateRepairUi.Stage.READY) View.VISIBLE else View.GONE
        isolateRecovery.isEnabled = !repairBusy
    }

    private fun refreshComponents(values: List<UpdateComponentStatus>, inventory: UpdateSystemInventory?) {
        val items = UpdateComponentUi.rows(values, inventory)
        components.visibility = if (inventory == null && items.isEmpty()) View.GONE else View.VISIBLE
        if (!::inventoryNotice.isInitialized || componentRows.keys.toList() != items.map { it.id }) {
            componentRows.clear(); components.removeAllViews()
            componentTitle = text()
            components.addView(componentTitle, LinearLayout.LayoutParams(-1, -2))
            inventoryNotice = text(TextScale.CAPTION, palette.secondary).apply { setPadding(0, px(8), 0, 0) }
            components.addView(inventoryNotice, LinearLayout.LayoutParams(-1, -2))
            for (item in items) {
                components.addView(View(context).apply { setBackgroundColor(palette.divider) },
                    LinearLayout.LayoutParams(-1, px(1)).apply { topMargin = px(16); bottomMargin = px(12) })
                val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
                val name = text()
                val state = text(TextScale.CAPTION, palette.secondary).apply { gravity = Gravity.END }
                heading.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
                heading.addView(state, LinearLayout.LayoutParams(0, -2, 1f))
                components.addView(heading, LinearLayout.LayoutParams(-1, -2))
                val installed = text(TextScale.CAPTION, palette.secondary).apply { setPadding(0, px(8), 0, 0) }
                val target = text(TextScale.CAPTION, palette.secondary)
                val progressText = text(TextScale.CAPTION, palette.secondary).apply { setPadding(0, px(8), 0, 0) }
                val result = text(TextScale.CAPTION, palette.secondary)
                for (line in listOf(installed, target, progressText, result)) components.addView(line, LinearLayout.LayoutParams(-1, -2))
                val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 10_000
                    progressTintList = ColorStateList.valueOf(palette.accent)
                    progressBackgroundTintList = ColorStateList.valueOf(palette.divider)
                    indeterminateTintList = ColorStateList.valueOf(palette.accent)
                }
                components.addView(bar, LinearLayout.LayoutParams(-1, px(4)).apply { topMargin = px(8) })
                componentRows[item.id] = ComponentRow(name, state, installed, target, bar, progressText, result)
            }
        }
        componentTitle.bind(context.getString(R.string.update_components))
        inventoryNotice.visibility = if (inventory != null && inventory.updater != "ready") View.VISIBLE else View.GONE
        inventoryNotice.bind(context.getString(R.string.update_component_requires_updater))
        // Reuse native rows: progress updates must not reset the scroll position.
        val numbers = NumberFormat.getIntegerInstance().apply { isGroupingUsed = false }
        val percent = NumberFormat.getPercentInstance()
        for (item in items) {
            val row = componentRows.getValue(item.id)
            val target = item.target
            row.name.bind(context.getString(UpdateComponentUi.name(item.id)))
            row.state.visibility = if (target == null) View.GONE else View.VISIBLE
            row.state.bind(target?.let { context.getString(UpdateComponentUi.state(it.state)) }.orEmpty())
            row.state.setTextColor(if (target?.state == "failed") palette.warnText else palette.secondary)
            val installed = when (UpdateComponentUi.installed(item.installedCode)) {
                UpdateComponentUi.Installed.UNKNOWN -> context.getString(R.string.update_component_unknown)
                UpdateComponentUi.Installed.ABSENT -> context.getString(R.string.update_component_missing)
                UpdateComponentUi.Installed.BUILD -> context.getString(R.string.update_component_build, numbers.format(item.installedCode))
            }
            row.installed.bind(context.getString(R.string.update_component_installed, installed))
            row.target.visibility = if (target == null) View.GONE else View.VISIBLE
            row.target.bind(target?.let {
                val build = context.getString(R.string.update_component_build, numbers.format(it.code))
                context.getString(R.string.update_component_target, "${it.version} ($build)")
            }.orEmpty())
            val progress = target?.let(UpdateComponentUi::progress) ?: UpdateComponentUi.Progress(false, null)
            row.progress.visibility = if (progress.visible) View.VISIBLE else View.GONE
            row.progressText.visibility = row.progress.visibility
            row.progress.isIndeterminate = progress.visible && progress.fraction == null
            if (progress.fraction != null) {
                row.progress.progress = (progress.fraction * 10_000).toInt().coerceIn(0, 10_000)
                row.progressText.bind(context.getString(R.string.update_component_progress, percent.format(progress.fraction)))
            } else row.progressText.bind(if (progress.visible) context.getString(R.string.update_component_downloading) else "")
            row.progress.contentDescription = row.progressText.text
            val outcome = UpdateComponentUi.result(target?.result)
            row.result.visibility = if (outcome == null) View.GONE else View.VISIBLE
            if (outcome != null) row.result.bind(context.getString(R.string.update_component_result, context.getString(outcome)))
            else row.result.bind("")
            row.result.setTextColor(if (target?.result in setOf("install_failed", "rollback_failed")) palette.warnText else palette.secondary)
        }
    }

    private fun TextView.bind(value: String) { if (text.toString() != value) text = value }

    /** Read-only DUMP-protected debug observation. */
    fun debugState(): String = UpdateModels.writeUpdateStatusDocument(read()).put("showing", isShowing).toString()
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) interaction()
        return super.dispatchTouchEvent(event)
    }
    override fun dismiss() { inspectGate.cancel(); repairGate.cancel(); scope.cancel(); super.dismiss() }
}
