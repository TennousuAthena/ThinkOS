package moe.kiwi.thinkos.ui.form

import moe.kiwi.thinkos.ui.TextScale
import android.app.AlertDialog
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputFilter
import android.text.InputType
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import moe.kiwi.thinkos.settings.SettingField
import moe.kiwi.thinkos.settings.SettingDependency
import moe.kiwi.thinkos.settings.SettingsDescriber
import moe.kiwi.thinkos.anim.Motion
import moe.kiwi.thinkos.ui.Palette
import moe.kiwi.thinkos.ui.Glyphs
import moe.kiwi.thinkos.ui.readableButtons
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.min

/** What a form needs from its surroundings: send an event to the owning plugin, and leave. */
internal interface FormHost {
    fun emit(name: String, args: List<Any?>)
    fun done()
    fun interacted()
    fun preview(scope: String, key: String, kind: String, value: Double?)
}

/**
 * The OS-drawn settings form (ADR-017). The plugin supplies field descriptors; this view supplies
 * the controls, so touch sizes, animation and right-to-left behaviour are the same for every plugin.
 */
internal class FormView(context: Context, private val host: FormHost) : FrameLayout(context) {
    private val dp = resources.displayMetrics.density
    private var palette = Palette.DARK
    private val title = TextView(context).apply {
        textSize = TextScale.TITLE; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val done = TextView(context).apply {
        textSize = TextScale.BODY; gravity = Gravity.CENTER; minWidth = (48 * dp).toInt(); minHeight = (48 * dp).toInt()
        setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
        setOnClickListener { host.done() }
    }
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (48 * dp).toInt()))
    }
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER
        addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val navigationList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val navigationScroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; visibility = GONE
        addView(navigationList, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val columns = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(navigationScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (64 * dp).toInt()))
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }
    /** Setup chrome is separate: forms without `primary` retain their existing hierarchy. */
    private var setup = false
    private var setupTap = ""
    private val setupTitle = TextView(context).apply {
        textSize = TextScale.TITLE; gravity = Gravity.CENTER; includeFontPadding = false
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val setupBack = TextView(context).apply {
        textSize = TextScale.TITLE; gravity = Gravity.CENTER; includeFontPadding = false
        setOnClickListener { if (!back()) setupAction("back") }
    }
    private val setupBar = FrameLayout(context).apply {
        addView(setupTitle, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            leftMargin = (48 * dp).toInt(); rightMargin = (48 * dp).toInt()
        })
        addView(setupBack, LayoutParams((48 * dp).toInt(), (48 * dp).toInt(), Gravity.TOP or Gravity.START))
    }
    private val setupSubtitle = TextView(context).apply {
        textSize = TextScale.BODY; gravity = Gravity.CENTER; includeFontPadding = false
        setPadding((8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt())
    }
    private val setupList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val setupScroll = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER
        addView(setupList, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private fun setupButton(action: String) = TextView(context).apply {
        textSize = TextScale.BODY; gravity = Gravity.CENTER; includeFontPadding = false
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        isClickable = true; isFocusable = true
        setOnClickListener { setupAction(action) }
    }
    private val setupPrimary = setupButton("primary")
    private val setupSecondary = setupButton("secondary")
    private val setupFooter = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, (12 * dp).toInt(), 0, (8 * dp).toInt())
        addView(setupPrimary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (56 * dp).toInt()))
        addView(setupSecondary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()).apply { topMargin = (8 * dp).toInt() })
    }
    private val setupBody = LinearLayout(context).apply {
        visibility = GONE; orientation = LinearLayout.VERTICAL
        addView(setupBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (80 * dp).toInt()))
        addView(setupSubtitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(setupScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(setupFooter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val rows = mutableListOf<FieldRow>()
    private var signature = ""
    private var expanded: String? = null
    private var picker: TimezonePicker? = null
    private var placePicker: PlacePicker? = null
    private var regionPicker: RegionPicker? = null
    private var timeEditor: TimeEditor? = null
    private var textDialog: AlertDialog? = null
    private var localeTag = Locale.getDefault().toLanguageTag()
    private var currentFields = JSONArray()
    private var navigation: JSONObject? = null
    private var navigationSignature = ""
    private val navigationRows = linkedMapOf<String, TextView>()
    private var wideLayout: Boolean? = null
    private var layoutDispatch: Runnable? = null

    init {
        clipChildren = false
        addView(body, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
        addView(setupBody, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
        colors(palette)
    }

    fun setHeader(titleText: String, doneText: String) {
        setupTitle.text = titleText
        if (title.text.toString() != titleText) {
            title.text = titleText
            expanded = null
            closePicker()
            textDialog?.dismiss(); textDialog = null
            scroll.scrollTo(0, 0)
            setupScroll.scrollTo(0, 0)
        }
        if (done.text.toString() != doneText) { done.text = doneText; done.contentDescription = doneText }
    }

    /** `enabled` follows declaration presence, so an empty primary on a selection screen is valid. */
    fun setSetup(enabled: Boolean, tap: String, subtitle: String, primary: String, secondary: String, back: String) {
        if (setup != enabled) {
            setup = enabled
            body.visibility = if (enabled) GONE else VISIBLE
            setupBody.visibility = if (enabled) VISIBLE else GONE
            signature = ""
            list.removeAllViews(); setupList.removeAllViews(); rows.clear()
            closePicker()
            requestLayout()
        }
        setupTap = tap
        setupSubtitle.text = subtitle
        setupSubtitle.visibility = if (enabled && subtitle.isNotEmpty()) VISIBLE else GONE
        fun label(view: TextView, value: String) {
            view.text = value; view.contentDescription = value
            view.visibility = if (enabled && value.isNotEmpty()) VISIBLE else GONE
        }
        label(setupPrimary, primary); label(setupSecondary, secondary)
        setupBack.contentDescription = back
        setupBack.visibility = if (enabled && back.isNotEmpty()) VISIBLE else GONE
        setupBack.text = if (layoutDirection == LAYOUT_DIRECTION_RTL) "›" else "‹"
    }

    private fun setupAction(action: String) {
        host.interacted()
        if (setupTap.isNotEmpty()) host.emit("tap", listOf(setupTap, action))
    }

    fun setNavigation(value: JSONObject?) {
        val hadNavigation = navigation != null
        navigation = value
        val items = value?.optJSONArray("items")
        val descriptors = (0 until minOf(items?.length() ?: 0, 32)).mapNotNull { items?.optJSONObject(it) }
            .filter { it.optString("id").matches(Regex("[a-z][a-z0-9_-]{0,63}")) && it.optString("label").isNotBlank() }
            .distinctBy { it.optString("id") }
        val signature = descriptors.joinToString { it.toString() }
        val icons = descriptors.isNotEmpty() && descriptors.all { Glyphs.has(it.optString("icon")) }
        if (signature != navigationSignature) {
            navigationSignature = signature
            navigationList.removeAllViews(); navigationRows.clear()
            for (item in descriptors) {
                val id = item.getString("id")
                val row = TextView(context).apply {
                    text = item.optString("label").take(80)
                    textSize = TextScale.BODY; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START; includeFontPadding = false
                    setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
                    setCategoryIcon(this, if (icons) item.optString("icon") else "", palette.secondary)
                    setOnClickListener { host.emit("form.navigate", listOf(id)) }
                }
                navigationRows[id] = row
                navigationList.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (56 * dp).toInt()))
            }
        }
        navigationColors()
        if (hadNavigation != (value != null)) { wideLayout = null; requestLayout() }
    }

    private fun navigationColors() {
        for ((id, row) in navigationRows) {
            row.isSelected = id == navigation?.optString("current")
            row.setTextColor(if (row.isSelected) palette.accentText else palette.primary)
            row.compoundDrawablesRelative[0]?.setTint(if (row.isSelected) palette.accentText else palette.secondary)
            row.foreground = rippleFor(palette)
        }
    }

    fun setFields(fields: JSONArray) {
        val currentLocale = Locale.getDefault().toLanguageTag()
        if (currentLocale != localeTag) {
            // Both pickers carry locale-specific search data. Reopening resolves the new locale;
            // closing also cancels a search whose result must not arrive in the old language.
            closePicker()
            textDialog?.dismiss(); textDialog = null
            expanded = null
            localeTag = currentLocale
        }
        val priorExpanded = expanded?.let(::findField)
        currentFields = fields
        expanded?.let { key ->
            val next = findField(key)
            if (next == null || priorExpanded?.optString("scope") != next.optString("scope")) expanded = null
        }
        if (picker?.let { findField(it.key) == null } == true || placePicker?.let { findField(it.key) == null } == true || regionPicker?.let { findField(it.key) == null } == true) closePicker()
        timeEditor?.let { editor ->
            val field = findField(editor.key)
            if (field == null || field.optString("type") != "time" || field.optString("scope") != editor.scope || field.optString("value") != editor.original) closePicker()
        }
        val next = (0 until fields.length()).joinToString("|") { i ->
            val f = fields.getJSONObject(i)
            f.optString("key") + ":" + f.optString("type") + ":" + f.optString("group") + ":" + f.optJSONArray("choices")?.length() + ":" + f.optString("source") + ":" + f.optJSONArray("presets")?.length()
        }
        if (next != signature) {
            signature = next
            val target = if (setup) setupList else list
            target.removeAllViews()
            rows.clear()
            for (i in 0 until fields.length()) {
                val row = FieldRow()
                rows += row
                target.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        for (i in 0 until fields.length()) rows[i].bind(fields.getJSONObject(i), previousGroup(fields, i))
        picker?.let { open -> fields.let { findField(open.key) }?.let(open::refresh) }
        placePicker?.let { open -> findField(open.key)?.let(open::refresh) }
        regionPicker?.let { open -> findField(open.key)?.let(open::refresh) }
    }

    private fun previousGroup(fields: JSONArray, index: Int) = if (index == 0) null else fields.getJSONObject(index - 1).optString("group").takeIf { it.isNotEmpty() }
    private fun findField(key: String): JSONObject? = (0 until currentFields.length()).map(currentFields::getJSONObject).firstOrNull { it.optString("key") == key }

    fun colors(value: Palette) {
        palette = value
        title.setTextColor(value.primary)
        done.setTextColor(value.accentText)
        setupTitle.setTextColor(value.primary)
        setupSubtitle.setTextColor(value.secondary)
        setupBack.setTextColor(value.accentText)
        setupBack.foreground = rippleFor(value)
        setupPrimary.setTextColor(Color.WHITE)
        setupPrimary.background = GradientDrawable().apply { setColor(value.accent); cornerRadius = 12 * dp }
        setupPrimary.foreground = rippleFor(value)
        setupSecondary.setTextColor(value.accentText)
        setupSecondary.foreground = rippleFor(value)
        rows.forEach { it.colors() }
        navigationColors()
        picker?.colors(value)
        placePicker?.colors(value)
        regionPicker?.colors(value)
        timeEditor?.colors(value)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        if (setup) {
            (setupBody.layoutParams as LayoutParams).width = minOf(availableWidth, (560 * dp).toInt())
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val plan = FormColumns.plan(availableWidth, dp, navigation != null)
        // Keep the original portrait render hierarchy, including text rasterization. Only a
        // breakpoint crossing reparents the same ScrollView; its position and fields survive.
        if (plan.wide && scroll.parent !== columns) {
            body.removeView(scroll)
            columns.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            body.addView(columns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else if (!plan.wide && scroll.parent !== body) {
            body.removeView(columns)
            columns.removeView(scroll)
            body.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        (body.layoutParams as LayoutParams).width = if (plan.wide || availableWidth > plan.body) plan.body else ViewGroup.LayoutParams.MATCH_PARENT
        navigationScroll.visibility = if (plan.wide) VISIBLE else GONE
        (navigationScroll.layoutParams as LinearLayout.LayoutParams).apply { width = plan.navigation; marginEnd = plan.gap }
        if (navigation != null && wideLayout != plan.wide) {
            wideLayout = plan.wide
            layoutDispatch?.let(::removeCallbacks)
            layoutDispatch = Runnable {
                if (isAttachedToWindow && navigation != null && wideLayout == plan.wide)
                    host.emit("form.layout", listOf(plan.wide))
            }.also { post(it) }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun rippleFor(p: Palette) = RippleDrawable(ColorStateList.valueOf((p.accent and 0x00ffffff) or 0x26000000), null, ColorDrawable(Color.BLACK))

    /** A fixed vector in the text's start slot: decorative, tintable and
     * mirrored by TextView's relative drawables, with no empty placeholder. */
    private fun setCategoryIcon(text: TextView, name: String, color: Int) {
        val icon = Glyphs.drawable(context, name)?.apply {
            val size = (24 * dp).toInt()
            setBounds(0, 0, size, size)
            setTint(color)
        }
        text.compoundDrawablePadding = if (icon == null) 0 else (16 * dp).toInt()
        text.setCompoundDrawablesRelative(icon, null, null, null)
    }

    fun active(value: Boolean) {
        if (!value) {
            rows.forEach { it.cancelInput() }
            if (timeEditor != null) closePicker()
        }
    }

    private fun dialogTheme() = if (Color.red(palette.bg) < 128) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert

    private fun editText(f: JSONObject) {
        textDialog?.dismiss()
        val key = f.getString("key")
        val theme = dialogTheme()
        val input = EditText(ContextThemeWrapper(context, theme)).apply {
            hint = f.optString("placeholder")
            setText(if (f.optBoolean("secret")) "" else f.optString("value"))
            setSelection(text.length)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = InputType.TYPE_CLASS_TEXT or if (f.optBoolean("secret")) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(f.optInt("maxLength", 1024)))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = host.interacted()
            })
            setOnKeyListener { _, _, event ->
                if (event.action == KeyEvent.ACTION_DOWN) host.interacted()
                false
            }
        }
        textDialog = AlertDialog.Builder(context, theme).setTitle(f.optString("label")).setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> host.emit("form.change", listOf(key, input.text.toString())) }
            .setNegativeButton(android.R.string.cancel, null).create().apply {
                window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
                show()
                readableButtons(theme)
            }
    }

    private fun openPicker(f: JSONObject) {
        closePicker()
        val open = TimezonePicker(context, f.getString("key"), f.optString("label"), f.optBoolean("allowEmpty"), { value ->
            closePicker()
            host.emit("form.change", listOf(f.getString("key"), value))
        }, ::closePicker, host::interacted)
        open.colors(palette)
        open.refresh(f)
        picker = open
        hideFormAccessibility()
        addView(open, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun closePicker() {
        picker?.let(::removeView)
        picker = null
        placePicker?.let(::removeView)
        placePicker = null
        regionPicker?.let(::removeView)
        regionPicker = null
        timeEditor?.let { it.cancel(); removeView(it) }
        timeEditor = null
        body.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
        setupBody.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    val hasBack: Boolean get() = picker != null || placePicker != null || regionPicker != null || timeEditor != null || expanded != null

    /** Close the current editor before the settings page itself. Android owns text dialogs. */
    fun back(): Boolean {
        if (picker != null || placePicker != null || regionPicker != null || timeEditor != null) { closePicker(); return true }
        if (expanded != null) { expanded = null; setFields(currentFields); return true }
        return false
    }

    private fun openPlacePicker(f: JSONObject) {
        closePicker()
        val open = PlacePicker(context, f.getString("key"), f.optString("label"), f.getString("searchUrl"), { value ->
            closePicker()
            host.emit("form.change", listOf(f.getString("key"), value))
        }, ::closePicker, host::interacted)
        open.colors(palette)
        open.refresh(f)
        placePicker = open
        hideFormAccessibility()
        addView(open, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun hideFormAccessibility() {
        body.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setupBody.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun openRegionPicker(f: JSONObject) {
        closePicker()
        val open = RegionPicker(context, f.getString("key"), f.optString("label"), { value ->
            closePicker()
            host.emit("form.change", listOf(f.getString("key"), value))
        }, ::closePicker, host::interacted)
        open.colors(palette)
        open.refresh(f)
        regionPicker = open
        hideFormAccessibility()
        addView(open, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun openTimeEditor(f: JSONObject) {
        val value = f.optString("value")
        if (moe.kiwi.thinkos.settings.SettingTime.minutes(value) == null) return
        closePicker()
        textDialog?.dismiss(); textDialog = null
        val open = TimeEditor(context, f.getString("key"), f.optString("scope"), value, f.optString("label"), { selected ->
            closePicker()
            host.emit("form.change", listOf(f.getString("key"), selected))
        }, ::closePicker, host::interacted)
        open.colors(palette)
        timeEditor = open
        hideFormAccessibility()
        addView(open, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onDetachedFromWindow() {
        layoutDispatch?.let(::removeCallbacks); layoutDispatch = null; wideLayout = null
        closePicker()
        textDialog?.dismiss(); textDialog = null
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------------------------------

    private inner class FieldRow : LinearLayout(context) {
        private var field = JSONObject()
        private val group = TextView(context).apply { textSize = TextScale.CAPTION; includeFontPadding = false }
        private val label = TextView(context).apply {
            textSize = TextScale.BODY; includeFontPadding = false
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        private val help = TextView(context).apply { textSize = TextScale.CAPTION; includeFontPadding = false; visibility = GONE }
        private val labels = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(label, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(help, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * dp).toInt() })
        }
        private val value = TextView(context).apply { textSize = TextScale.CAPTION; includeFontPadding = false; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        private val chevron = TextView(context).apply { textSize = TextScale.BODY; includeFontPadding = false }
        private val toggle = ToggleView(context)
        private val main = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = (56 * dp).toInt() }
        private val extra = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
        private val error = TextView(context).apply {
            textSize = TextScale.CAPTION; includeFontPadding = false; visibility = GONE
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }
        private var errorMessage = ""
        private var previewing = false
        private val slider = SliderView(context)
        private val minus = stepButton("−")
        private val plus = stepButton("+")
        private val sliderRow = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = GONE
            addView(minus, LayoutParams((48 * dp).toInt(), (48 * dp).toInt()))
            addView(slider, LayoutParams(0, (48 * dp).toInt(), 1f))
            addView(plus, LayoutParams((48 * dp).toInt(), (48 * dp).toInt()))
        }

        init {
            orientation = VERTICAL
            setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
            addView(group, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (20 * dp).toInt(); bottomMargin = (4 * dp).toInt() })
            main.addView(labels, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            main.addView(value, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (12 * dp).toInt() })
            main.addView(chevron, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * dp).toInt() })
            main.addView(toggle, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (12 * dp).toInt() })
            addView(main, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(sliderRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(extra, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(error, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8 * dp).toInt() })
            main.setOnClickListener { activate() }
            main.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(hostView: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(hostView, info)
                    if (field.optString("type") == "bool") {
                        info.className = android.widget.Switch::class.java.name
                        info.isCheckable = true
                        info.isChecked = field.optBoolean("value")
                    }
                }
            }
            slider.onPreview = { v ->
                value.text = numberDisplay(v)
                field.optString("preview").takeIf(String::isNotEmpty)?.let { kind ->
                    previewing = true
                    host.preview(field.optString("scope"), field.optString("key"), kind, v)
                }
            }
            slider.onCommit = { v -> host.emit("form.change", listOf(field.optString("key"), SettingField.canonical(v))) }
            slider.onCancel = { cancelPreview(); value.text = field.optString("display") }
            minus.setOnClickListener { nudge(-1) }
            plus.setOnClickListener { nudge(1) }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // A URL or other long value must not take the whole row away from its label.
            val inner = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
            val fixed = ((12 + if (chevron.visibility == VISIBLE) 30 else 0) * dp).toInt()
            value.maxWidth = ((inner - fixed) / 2).coerceAtLeast(0)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        private fun stepButton(glyph: String) = TextView(context).apply {
            text = glyph; textSize = TextScale.BODY; gravity = Gravity.CENTER; includeFontPadding = false
            contentDescription = glyph
        }

        private fun nudge(direction: Int) {
            val step = field.optDouble("step", 1.0).takeIf { it > 0 } ?: 1.0
            val next = slider.snap(slider.value + direction * step)
            if (next != slider.value) {
                slider.value = next
                value.text = numberDisplay(next)
                host.emit("form.change", listOf(field.optString("key"), SettingField.canonical(next)))
            }
        }

        private fun activate() {
            val key = field.optString("key")
            when (field.optString("type")) {
                "bool" -> host.emit("form.change", listOf(key, !toggle.checked))
                "text" -> editText(field)
                "time" -> openTimeEditor(field)
                "link" -> {
                    val confirmation = field.optString("confirm")
                    if (confirmation.isBlank()) host.emit("form.change", listOf(key, true))
                    else {
                        val theme = dialogTheme()
                        textDialog?.dismiss()
                        textDialog = AlertDialog.Builder(context, theme).setMessage(confirmation)
                            .setPositiveButton(android.R.string.ok) { _, _ -> host.emit("form.change", listOf(key, true)) }
                            .setNegativeButton(android.R.string.cancel, null).create().apply { show(); readableButtons(theme) }
                    }
                }
                "choice" -> when (field.optString("source")) {
                    "timezone" -> openPicker(field)
                    "place" -> openPlacePicker(field)
                    "region" -> openRegionPicker(field)
                    else -> {
                        expanded = if (expanded == key) null else key
                        setFields(currentFields)
                    }
                }
                "number" -> {
                    expanded = if (expanded == key) null else key
                    setFields(currentFields)
                }
            }
        }

        fun bind(next: JSONObject, previousGroup: String?) {
            if (field.optString("key") != next.optString("key") || field.optString("scope") != next.optString("scope") ||
                field.optString("preview") != next.optString("preview")) cancelInput()
            else if (!slider.isTracking) cancelPreview()
            field = next
            val type = next.optString("type")
            val groupText = next.optString("group").takeIf { it.isNotEmpty() }
            group.visibility = if (groupText != null && groupText != previousGroup) VISIBLE else GONE
            group.text = groupText.orEmpty()
            label.text = next.optString("label")
            setCategoryIcon(label, if (type == "link") next.optString("icon") else "", palette.secondary)
            val helpText = listOf(next.optString("help"), if (type == "bool" && next.optBoolean("value")) next.optString("warn") else "")
                .filter(String::isNotEmpty).joinToString("\n")
            val warningOnly = next.optString("help").isEmpty() && type == "bool"
            help.maxLines = if (warningOnly) 1 else Int.MAX_VALUE
            help.ellipsize = if (warningOnly) TextUtils.TruncateAt.END else null
            help.text = helpText
            help.visibility = if (helpText.isEmpty()) GONE else VISIBLE
            val verticalPadding = if (helpText.isEmpty()) 0 else (8 * dp).toInt()
            labels.setPadding(0, verticalPadding, 0, verticalPadding)
            val display = next.optString("display")
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            chevron.text = if (rtl) "‹" else "›"
            toggle.visibility = if (type == "bool") VISIBLE else GONE
            chevron.visibility = if (type == "link") VISIBLE else GONE
            value.visibility = if (type == "bool") GONE else VISIBLE
            value.text = if (type == "number" && slider.isTracking) numberDisplay(slider.value) else display
            if (type == "bool") {
                val checked = next.optBoolean("value")
                val changed = toggle.checked != checked
                toggle.set(checked, animate = isLaidOut)
                if (changed) main.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            }
            if (type == "number" && next.optJSONArray("presets") == null && expanded == next.optString("key")) {
                sliderRow.visibility = VISIBLE
                slider.minimum = next.optDouble("min"); slider.maximum = next.optDouble("max")
                slider.step = next.optDouble("step", 1.0)
                slider.value = (next.opt("value") as? Number)?.toDouble() ?: slider.minimum
            } else {
                cancelInput()
                sliderRow.visibility = GONE
            }
            showError(next.optString("error"))
            renderOptions()
            colors()
            main.contentDescription = listOf(label.text.toString(), helpText, display).filter(String::isNotEmpty).joinToString(", ")
        }

        private fun showError(message: String) {
            if (message == errorMessage) return
            errorMessage = message
            error.animate().cancel()
            error.alpha = 1f
            error.translationY = 0f
            if (message.isNotEmpty()) {
                error.text = message
                error.visibility = VISIBLE
                val observer = error.viewTreeObserver
                observer.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        if (observer.isAlive) observer.removeOnGlobalLayoutListener(this)
                        if (errorMessage == message) error.requestRectangleOnScreen(
                            android.graphics.Rect(0, 0, error.width, error.height), false)
                    }
                })
            } else if (error.visibility == VISIBLE) {
                if (!ValueAnimator.areAnimatorsEnabled()) clearError()
                else error.animate().alpha(0f).translationY(-2 * dp)
                    .setDuration(Motion.FEEDBACK_EXIT).setInterpolator(Motion.EXIT)
                    .withEndAction { if (errorMessage.isEmpty()) clearError() }.start()
            }
        }

        private fun clearError() {
            error.visibility = GONE
            error.text = ""
            error.alpha = 1f
            error.translationY = 0f
        }

        override fun onDetachedFromWindow() {
            cancelInput()
            error.animate().cancel()
            if (errorMessage.isEmpty()) clearError()
            super.onDetachedFromWindow()
        }

        private fun numberDisplay(value: Double) = SettingsDescriber.numberDisplay(value,
            field.optString("unit").takeIf(String::isNotEmpty), Locale.getDefault())

        private fun cancelPreview() {
            if (!previewing) return
            previewing = false
            host.preview(field.optString("scope"), field.optString("key"), field.optString("preview"), null)
        }

        fun cancelInput() { slider.cancelGesture(); cancelPreview() }

        private fun renderOptions() {
            extra.removeAllViews()
            val key = field.optString("key")
            val type = field.optString("type")
            val choices = if (type == "number") field.optJSONArray("presets") else field.optJSONArray("choices")
            val open = expanded == key && type in setOf("choice", "number") && choices != null
            extra.visibility = if (open) VISIBLE else GONE
            if (!open) return
            val selected = field.opt("value")
            for (i in 0 until choices.length()) {
                val choice = choices.optJSONObject(i) ?: continue
                val choiceValue = choice.opt("value")?.takeIf { if (type == "number") it is Number else it is String } ?: continue
                val checked = selected != null && SettingDependency.equal(choiceValue, selected)
                val option = TextView(context).apply {
                    textSize = TextScale.BODY; gravity = Gravity.CENTER_VERTICAL
                    text = choice.optString("label")
                    setPadding((28 * dp).toInt(), 0, (12 * dp).toInt(), 0)
                    setTextColor(if (checked) palette.accentText else palette.primary)
                    if (checked) {
                        setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
                        text = "✓  " + choice.optString("label")
                    }
                    foreground = rippleFor(palette)
                    setOnClickListener {
                        expanded = null
                        host.emit("form.change", listOf(key, choiceValue))
                    }
                }
                extra.addView(option, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()))
            }
        }

        fun colors() {
            group.setTextColor(palette.secondary)
            label.setTextColor(palette.primary)
            label.compoundDrawablesRelative[0]?.setTint(palette.secondary)
            help.setTextColor(palette.secondary)
            value.setTextColor(palette.secondary)
            chevron.setTextColor(palette.secondary)
            error.setTextColor(palette.warnText)
            toggle.palette = palette
            slider.palette = palette
            minus.setTextColor(palette.accentText)
            plus.setTextColor(palette.accentText)
            main.foreground = rippleFor(palette)
            renderOptions()
        }
    }
}

internal fun maxWidthDp(widthPx: Int, dp: Float, capDp: Int) = min(widthPx, (capDp * dp).toInt())
