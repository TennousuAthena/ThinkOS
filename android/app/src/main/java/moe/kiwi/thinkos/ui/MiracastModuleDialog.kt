package moe.kiwi.thinkos.ui

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.os.SystemClock
import android.text.format.Formatter
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import moe.kiwi.thinkos.R
import moe.kiwi.thinkos.update.FeatureStage
import moe.kiwi.thinkos.update.FeatureUiState
import java.text.NumberFormat

/** A preparation release never grants the later installation release. */
internal class MiracastModuleDialog(
    activity: Activity, private val palette: Palette, private val token: String,
    private val state: StateFlow<FeatureUiState>, private val prepare: suspend (Long) -> Boolean,
    private val install: suspend (String, Long) -> Boolean, private val revoke: () -> Unit,
    private val ownsForeground: () -> Boolean, private val changed: () -> Unit,
    private val interaction: () -> Unit, private val closed: () -> Unit,
) : Dialog(activity, if (palette == Palette.LIGHT) android.R.style.Theme_Material_Light_NoActionBar else android.R.style.Theme_Material_NoActionBar) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val density = context.resources.displayMetrics.density
    private fun px(value: Int) = (value * density + .5f).toInt()
    private fun label(size: Float = TextScale.BODY, color: Int = palette.primary) = TextView(context).apply {
        textSize = size; setTextColor(color)
    }
    private val gate = NativeTouchGate()
    private var releasedAt: Long? = null
    private var pressed: String? = null
    private var shownIdentity: String? = null
    private var planShown: String? = null
    private var finished = false
    private var focused = false
    private var actionBusy = false
    private lateinit var phase: TextView
    private lateinit var progress: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var planRows: LinearLayout
    private lateinit var reboot: TextView
    private lateinit var approve: TextView
    private lateinit var close: TextView

    /** Read-only inventory may prove the module already exists: show no prompt then. */
    fun open() {
        scope.launch {
            state.collect { value ->
                if (value.token != token || value.stage in setOf(FeatureStage.CLOSED, FeatureStage.INSTALLED)) dismiss()
                else if (value.stage != FeatureStage.CHECKING || isShowing) {
                    if (!ownsForeground()) { dismiss(); return@collect }
                    if (!isShowing) { show(); changed() }
                    refresh(value)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        val root = object : LinearLayout(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (oldw > 0 && oldh > 0 && (w != oldw || h != oldh)) {
                    invalidateCredit(); scope.cancel(); revoke(); post { dismiss() }
                }
            }
        }.apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(palette.bg) }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(TextScale.TITLE).apply {
            text = "‹"; gravity = Gravity.CENTER
            contentDescription = context.getString(R.string.maintenance_back)
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(px(56), px(64)))
        header.addView(label(TextScale.TITLE).apply { setText(R.string.setting_cast_miracast); gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, px(64), 1f))
        root.addView(header)
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(px(24), px(24), px(24), px(24)) }
        phase = label(TextScale.TITLE)
        spinner = ProgressBar(context).apply { isIndeterminate = true }
        progress = label(TextScale.CAPTION, palette.secondary).apply { setPadding(0, px(12), 0, px(12)) }
        planRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(16), 0, 0) }
        reboot = label().apply { setText(R.string.feature_module_reboot); setPadding(0, px(24), 0, 0) }
        content.addView(phase, LinearLayout.LayoutParams(-1, -2))
        content.addView(spinner, LinearLayout.LayoutParams(px(32), px(32)).apply { topMargin = px(16) })
        for (view in listOf(progress, planRows, reboot)) content.addView(view, LinearLayout.LayoutParams(-1, -2))
        root.addView(ScrollView(context).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(24), 0, px(24), px(16)) }
        close = label(color = palette.accentText).apply {
            setText(R.string.plugins_cancel); gravity = Gravity.CENTER; minHeight = px(56)
            setOnClickListener { dismiss() }
        }
        actions.addView(close, LinearLayout.LayoutParams(0, -2, 1f))
        approve = label(color = palette.accentText).apply {
            gravity = Gravity.CENTER; minHeight = px(56); filterTouchesWhenObscured = true
            setOnTouchListener { view, event ->
                val current = state.value
                val eligible = view.isEnabled && view.isShown && view.hasWindowFocus() && ownsForeground() &&
                    current.token == token && identity(current) == shownIdentity && !actionBusy &&
                    current.stage in setOf(FeatureStage.IDLE, FeatureStage.FAILED, FeatureStage.READY) &&
                    event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) &&
                    event.pointerCount == 1 && event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED == 0
                val uptimeNow = SystemClock.uptimeMillis()
                val now = SystemClock.elapsedRealtime()
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { pressed = identity(current); releasedAt = null; gate.down(now, eligible) }
                    MotionEvent.ACTION_UP -> {
                        val same = pressed == identity(current)
                        val inside = event.x >= 0 && event.y >= 0 && event.x < view.width && event.y < view.height
                        gate.up(now, eligible && same, inside)
                        releasedAt = FeatureTouchTime.releaseElapsed(event.eventTime, uptimeNow, now)
                            .takeIf { eligible && same && inside }
                    }
                    MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> invalidateCredit()
                    else -> if (!eligible) invalidateCredit()
                }
                false
            }
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (oldRight > oldLeft && oldBottom > oldTop &&
                    (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom)) invalidateCredit()
            }
            setOnClickListener {
                val current = state.value
                val up = releasedAt
                val accepted = gate.consume(SystemClock.elapsedRealtime(), isEnabled && isShown && hasWindowFocus() &&
                    ownsForeground() && current.token == token && identity(current) == shownIdentity &&
                    pressed == identity(current) && !actionBusy)
                releasedAt = null; pressed = null
                if (!accepted || up == null) return@setOnClickListener
                val fingerprint = current.plan?.fingerprint
                if (current.stage == FeatureStage.READY && fingerprint == null) return@setOnClickListener
                actionBusy = true; interaction(); refresh(current)
                scope.launch {
                    try {
                        if (current.stage == FeatureStage.READY) install(requireNotNull(fingerprint), up) else prepare(up)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        android.util.Log.e("ThinkOS", "Casting component action unavailable", error)
                        dismiss()
                    } finally { actionBusy = false; if (isShowing) refresh(state.value) }
                }
            }
        }
        actions.addView(approve, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)
        setContentView(root); window?.setLayout(-1, -1)
    }

    private fun identity(value: FeatureUiState) = "${value.token}:${value.stage}:${value.plan?.fingerprint}"
    private fun invalidateCredit() { gate.cancel(); releasedAt = null; pressed = null }
    private fun refresh(value: FeatureUiState) {
        if (!::phase.isInitialized || finished) return
        val identity = identity(value)
        if (shownIdentity != identity) { invalidateCredit(); shownIdentity = identity }
        val waiting = value.stage in setOf(FeatureStage.CHECKING, FeatureStage.PREPARING, FeatureStage.INSTALLING, FeatureStage.SUBMITTED)
        phase.setText(when (value.stage) {
            FeatureStage.CHECKING -> R.string.feature_module_checking
            FeatureStage.IDLE -> R.string.feature_module_required
            FeatureStage.PREPARING -> R.string.feature_module_preparing
            FeatureStage.READY -> R.string.update_ready
            FeatureStage.INSTALLING, FeatureStage.SUBMITTED -> R.string.update_installing
            FeatureStage.UNAVAILABLE -> when (value.reason) {
                "updater" -> R.string.update_component_requires_updater
                "inventory" -> R.string.feature_module_inventory_unknown
                "keys", "sources" -> R.string.feature_module_not_configured
                "busy" -> R.string.feature_module_busy
                "media" -> R.string.feature_module_media
                else -> R.string.update_unavailable
            }
            FeatureStage.FAILED -> when (value.reason) {
                "changed" -> R.string.feature_module_changed
                "media" -> R.string.feature_module_media
                "package" -> R.string.update_package_mismatch
                else -> R.string.update_failed
            }
            else -> R.string.update_unavailable
        })
        // A submitted transaction is independently owned by the recovery chain;
        // this button leaves its window and does not promise cancellation.
        close.setText(if (value.stage in setOf(FeatureStage.INSTALLING, FeatureStage.SUBMITTED))
            R.string.maintenance_back else R.string.plugins_cancel)
        spinner.visibility = if (waiting) View.VISIBLE else View.GONE
        progress.visibility = if (value.stage == FeatureStage.PREPARING && value.size > 0) View.VISIBLE else View.GONE
        progress.text = if (value.size > 0) NumberFormat.getPercentInstance().format(value.downloaded.coerceIn(0, value.size).toDouble() / value.size) else ""
        val plan = value.plan
        planRows.visibility = if (value.stage == FeatureStage.READY && plan != null) View.VISIBLE else View.GONE
        reboot.visibility = if (value.stage == FeatureStage.READY && plan != null) View.VISIBLE else View.GONE
        if (plan != null && planShown != plan.fingerprint) {
            planShown = plan.fingerprint; planRows.removeAllViews()
            val numbers = NumberFormat.getIntegerInstance().apply { isGroupingUsed = false }
            fun line(value: String, caption: Boolean = false) {
                planRows.addView(label(if (caption) TextScale.CAPTION else TextScale.BODY,
                    if (caption) palette.secondary else palette.primary).apply { text = value; setPadding(0, 0, 0, px(12)) }, LinearLayout.LayoutParams(-1, -2))
            }
            line(context.getString(R.string.feature_module_apk, plan.apkVersion, numbers.format(plan.apkCode)))
            line(if (plan.apkChanges) Formatter.formatFileSize(context, plan.apkSize) else context.getString(R.string.feature_module_apk_unchanged), true)
            plan.modules.forEach { module ->
                line(context.getString(UpdateComponentUi.name(module.id)) + "  " + module.version)
                val installed = when {
                    module.installedCode == null || module.installedCode < 0 -> context.getString(R.string.update_component_unknown)
                    module.installedCode == 0 -> context.getString(R.string.update_component_missing)
                    else -> context.getString(R.string.update_component_build, numbers.format(module.installedCode))
                }
                line(context.getString(R.string.update_component_installed, installed), true)
                line(context.getString(R.string.update_component_target, context.getString(R.string.update_component_build, numbers.format(module.code))) +
                    "  " + Formatter.formatFileSize(context, module.size), true)
            }
        }
        val canPrepare = value.stage in setOf(FeatureStage.IDLE, FeatureStage.FAILED)
        val canInstall = value.stage == FeatureStage.READY && plan != null
        approve.setText(if (canInstall) R.string.feature_module_install_reboot else R.string.feature_module_prepare)
        approve.visibility = if (canPrepare || canInstall) View.VISIBLE else View.GONE
        approve.isEnabled = !actionBusy && (canPrepare || canInstall)
        approve.alpha = if (approve.isEnabled) 1f else .4f
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) { invalidateCredit(); if (focused) dismiss() } else focused = true
    }
    override fun dismiss() {
        if (finished) return
        finished = true; invalidateCredit()
        revoke(); scope.cancel()
        try { super.dismiss() } finally { closed() }
    }
}
