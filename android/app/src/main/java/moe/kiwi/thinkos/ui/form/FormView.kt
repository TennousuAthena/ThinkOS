package moe.kiwi.thinkos.ui.form

import android.app.AlertDialog
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
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
import moe.kiwi.thinkos.anim.Motion
import moe.kiwi.thinkos.ui.Palette
import moe.kiwi.thinkos.ui.readableButtons
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.min

/** What a form needs from its surroundings: send an event to the owning plugin, and leave. */
internal interface FormHost {
    fun emit(name: String, args: List<Any?>)
    fun done()
    fun interacted()
}

/**
 * The OS-drawn settings form (ADR-017). The plugin supplies field descriptors; this view supplies
 * the controls, so touch sizes, animation and right-to-left behaviour are the same for every plugin.
 */
internal class FormView(context: Context, private val host: FormHost) : FrameLayout(context) {
    private val dp = resources.displayMetrics.density
    private var palette = Palette.DARK
    private val title = TextView(context).apply {
        textSize = 32f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val done = TextView(context).apply {
        textSize = 18f; gravity = Gravity.CENTER; minWidth = (48 * dp).toInt(); minHeight = (48 * dp).toInt()
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
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (64 * dp).toInt()))
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }
    private val rows = mutableListOf<FieldRow>()
    private var signature = ""
    private var expanded: String? = null
    private var picker: TimezonePicker? = null
    private var placePicker: PlacePicker? = null
    private var textDialog: AlertDialog? = null
    private var localeTag = Locale.getDefault().toLanguageTag()
    private var currentFields = JSONArray()

    init {
        clipChildren = false
        addView(body, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
        colors(palette)
    }

    fun setHeader(titleText: String, doneText: String) {
        if (title.text.toString() != titleText) title.text = titleText
        if (done.text.toString() != doneText) { done.text = doneText; done.contentDescription = doneText }
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
        currentFields = fields
        val next = (0 until fields.length()).joinToString("|") { i ->
            val f = fields.getJSONObject(i)
            f.optString("key") + ":" + f.optString("type") + ":" + f.optString("group") + ":" + f.optJSONArray("choices")?.length() + ":" + f.optString("source")
        }
        if (next != signature) {
            signature = next
            list.removeAllViews()
            rows.clear()
            for (i in 0 until fields.length()) {
                val row = FieldRow()
                rows += row
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        for (i in 0 until fields.length()) rows[i].bind(fields.getJSONObject(i), previousGroup(fields, i))
        picker?.let { open -> fields.let { findField(open.key) }?.let(open::refresh) }
        placePicker?.let { open -> findField(open.key)?.let(open::refresh) }
    }

    private fun previousGroup(fields: JSONArray, index: Int) = if (index == 0) null else fields.getJSONObject(index - 1).optString("group").takeIf { it.isNotEmpty() }
    private fun findField(key: String): JSONObject? = (0 until currentFields.length()).map(currentFields::getJSONObject).firstOrNull { it.optString("key") == key }

    fun colors(value: Palette) {
        palette = value
        title.setTextColor(value.primary)
        done.setTextColor(value.accentText)
        rows.forEach { it.colors() }
        picker?.colors(value)
        placePicker?.colors(value)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // A comfortable reading width on the landscape canvas; full width in portrait.
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val cap = (560 * dp).toInt()
        (body.layoutParams as LayoutParams).width = if (w > cap) cap else ViewGroup.LayoutParams.MATCH_PARENT
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun rippleFor(p: Palette) = RippleDrawable(ColorStateList.valueOf((p.accent and 0x00ffffff) or 0x26000000), null, ColorDrawable(Color.BLACK))

    private fun formatted(value: Double, unit: String?): String =
        NumberFormat.getInstance(Locale.getDefault()).apply { maximumFractionDigits = 3 }.format(value) + (unit?.let { " $it" } ?: "")

    private fun dialogTheme() = if (Color.red(palette.bg) < 128) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert

    private fun editText(f: JSONObject) {
        textDialog?.dismiss()
        val key = f.getString("key")
        val theme = dialogTheme()
        val input = EditText(ContextThemeWrapper(context, theme)).apply {
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
        body.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(open, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun closePicker() {
        picker?.let(::removeView)
        picker = null
        placePicker?.let(::removeView)
        placePicker = null
        body.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
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
        body.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(open, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onDetachedFromWindow() {
        closePicker()
        textDialog?.dismiss(); textDialog = null
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------------------------------

    private inner class FieldRow : LinearLayout(context) {
        private var field = JSONObject()
        private val group = TextView(context).apply { textSize = 14f; includeFontPadding = false }
        private val dotMark = View(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val dot = object : FrameLayout(context) {
            override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name
        }.apply {
            isFocusable = true
            addView(dotMark, FrameLayout.LayoutParams((8 * dp).toInt(), (8 * dp).toInt(), Gravity.CENTER))
        }
        private val label = TextView(context).apply {
            textSize = 18f; includeFontPadding = false
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        private val help = TextView(context).apply { textSize = 14f; includeFontPadding = false; visibility = GONE }
        private val labels = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(label, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(help, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * dp).toInt() })
        }
        private val value = TextView(context).apply { textSize = 14f; includeFontPadding = false; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        private val chevron = TextView(context).apply { textSize = 22f; includeFontPadding = false }
        private val toggle = ToggleView(context)
        private val main = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = (56 * dp).toInt() }
        private val extra = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
        private val error = TextView(context).apply {
            textSize = 14f; includeFontPadding = false; visibility = GONE
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }
        private var errorMessage = ""
        private val divider = View(context)
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
            main.addView(dot, LayoutParams((48 * dp).toInt(), (48 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() })
            main.addView(labels, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            main.addView(value, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (12 * dp).toInt() })
            main.addView(chevron, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * dp).toInt() })
            main.addView(toggle, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (12 * dp).toInt() })
            addView(main, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(sliderRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(extra, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(error, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8 * dp).toInt() })
            addView(divider, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
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
            dot.setOnClickListener { host.emit("form.reset", listOf(field.optString("key"))) }
            slider.onPreview = { v -> value.text = formatted(v, field.optString("unit").takeIf { it.isNotEmpty() }) }
            slider.onCommit = { v -> host.emit("form.change", listOf(field.optString("key"), SettingField.canonical(v))) }
            minus.setOnClickListener { nudge(-1) }
            plus.setOnClickListener { nudge(1) }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // A URL or other long value must not take the whole row away from its label.
            val inner = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
            val fixed = ((48 + 8 + 12 + if (chevron.visibility == VISIBLE) 30 else 0) * dp).toInt()
            value.maxWidth = ((inner - fixed) / 2).coerceAtLeast(0)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        private fun stepButton(glyph: String) = TextView(context).apply {
            text = glyph; textSize = 24f; gravity = Gravity.CENTER; includeFontPadding = false
            contentDescription = glyph
        }

        private fun nudge(direction: Int) {
            val step = field.optDouble("step", 1.0).takeIf { it > 0 } ?: 1.0
            val next = slider.snap(slider.value + direction * step)
            if (next != slider.value) {
                slider.value = next
                value.text = formatted(next, field.optString("unit").takeIf { it.isNotEmpty() })
                host.emit("form.change", listOf(field.optString("key"), SettingField.canonical(next)))
            }
        }

        private fun activate() {
            val key = field.optString("key")
            when (field.optString("type")) {
                "bool" -> host.emit("form.change", listOf(key, !toggle.checked))
                "text" -> editText(field)
                "link" -> host.emit("form.change", listOf(key, true))
                "choice" -> when (field.optString("source")) {
                    "timezone" -> openPicker(field)
                    "place" -> openPlacePicker(field)
                    else -> {
                        expanded = if (expanded == key) null else key
                        renderOptions()
                    }
                }
            }
        }

        fun bind(next: JSONObject, previousGroup: String?) {
            field = next
            val type = next.optString("type")
            val groupText = next.optString("group").takeIf { it.isNotEmpty() }
            group.visibility = if (groupText != null && groupText != previousGroup) VISIBLE else GONE
            group.text = groupText.orEmpty()
            label.text = next.optString("label")
            val helpText = next.optString("help")
            help.text = helpText
            help.visibility = if (helpText.isEmpty()) GONE else VISIBLE
            val verticalPadding = if (helpText.isEmpty()) 0 else (8 * dp).toInt()
            labels.setPadding(0, verticalPadding, 0, verticalPadding)
            dot.visibility = if (next.optBoolean("overridden")) VISIBLE else INVISIBLE
            dot.contentDescription = label.text.toString() + " · " + context.getString(moe.kiwi.thinkos.R.string.form_reset_hint)
            val display = next.optString("display")
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
            chevron.text = if (rtl) "‹" else "›"
            toggle.visibility = if (type == "bool") VISIBLE else GONE
            chevron.visibility = if (type == "choice" || type == "text" || type == "link") VISIBLE else GONE
            value.visibility = if (type == "bool") GONE else VISIBLE
            value.text = display
            if (type == "bool") {
                val checked = next.optBoolean("value")
                val changed = toggle.checked != checked
                toggle.set(checked, animate = isLaidOut)
                if (changed) main.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            }
            if (type == "number") {
                sliderRow.visibility = VISIBLE
                slider.minimum = next.optDouble("min"); slider.maximum = next.optDouble("max")
                slider.step = next.optDouble("step", 1.0)
                slider.value = (next.opt("value") as? Number)?.toDouble() ?: slider.minimum
            } else sliderRow.visibility = GONE
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
            error.animate().cancel()
            if (errorMessage.isEmpty()) clearError()
            super.onDetachedFromWindow()
        }

        private fun renderOptions() {
            extra.removeAllViews()
            val key = field.optString("key")
            val choices = field.optJSONArray("choices")
            val open = expanded == key && field.optString("type") == "choice" && choices != null
            extra.visibility = if (open) VISIBLE else GONE
            chevron.rotation = if (open) 90f else 0f
            if (!open) return
            val selected = field.optString("value")
            for (i in 0 until choices.length()) {
                val choice = choices.getJSONObject(i)
                val option = TextView(context).apply {
                    textSize = 18f; gravity = Gravity.CENTER_VERTICAL
                    text = choice.optString("label")
                    setPadding((28 * dp).toInt(), 0, (12 * dp).toInt(), 0)
                    setTextColor(if (choice.optString("value") == selected) palette.accentText else palette.primary)
                    if (choice.optString("value") == selected) {
                        setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
                        text = "✓  " + choice.optString("label")
                    }
                    foreground = rippleFor(palette)
                    setOnClickListener {
                        expanded = null
                        host.emit("form.change", listOf(key, choice.optString("value")))
                    }
                }
                extra.addView(option, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()))
            }
        }

        fun colors() {
            group.setTextColor(palette.secondary)
            label.setTextColor(palette.primary)
            help.setTextColor(palette.secondary)
            value.setTextColor(palette.secondary)
            chevron.setTextColor(palette.secondary)
            error.setTextColor(palette.warnText)
            divider.setBackgroundColor(palette.divider)
            dotMark.background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(palette.accent) }
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
