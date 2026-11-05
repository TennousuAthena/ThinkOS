package moe.kiwi.thinkos.ui.form

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import moe.kiwi.thinkos.R
import moe.kiwi.thinkos.settings.SettingTime
import moe.kiwi.thinkos.ui.Palette
import moe.kiwi.thinkos.ui.TextScale

/** A local draft editor. No keyboard, seconds, AM/PM conversion or implicit submission. */
internal class TimeEditor(
    context: Context,
    val key: String,
    val scope: String,
    val original: String,
    label: String,
    private val onPick: (String) -> Unit,
    private val onCancel: () -> Unit,
    private val onInteraction: () -> Unit,
) : LinearLayout(context) {
    private val dp = resources.displayMetrics.density
    private val edit = SettingTime.Edit(original)
    private val texts = mutableListOf<TextView>()
    private val buttons = mutableListOf<TextView>()
    private fun text(size: Float, value: String) = TextView(context).apply {
        textSize = size; text = value; gravity = Gravity.CENTER; includeFontPadding = false
        texts += this
    }
    private fun button(value: String, description: String = value, action: () -> Unit) = text(TextScale.BODY, value).apply {
        contentDescription = description; isClickable = true; isFocusable = true
        minWidth = (64 * dp).toInt(); minHeight = (48 * dp).toInt()
        setOnClickListener { onInteraction(); action() }
        buttons += this
    }
    private val hour = text(TextScale.TITLE, original.substring(0, 2))
    private val minute = text(TextScale.TITLE, original.substring(3, 5))

    init {
        orientation = VERTICAL; isClickable = true
        setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
        addView(text(TextScale.TITLE, label), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (64 * dp).toInt()))
        val centre = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER
            // A time value is always HH:mm, including in an RTL user interface.
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(column(hour, context.getString(R.string.form_time_hour), 60), LayoutParams((104 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            // Match the numeric row's position inside its 40/48/64/48dp
            // column rather than centring the colon against the whole column.
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(View(context), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (88 * dp).toInt()))
                addView(text(TextScale.TITLE, ":"), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (64 * dp).toInt()))
                addView(View(context), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()))
            }, LayoutParams((32 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(column(minute, context.getString(R.string.form_time_minute), 5), LayoutParams((104 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(centre, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val footer = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER
            addView(button(context.getString(android.R.string.cancel)) { cancel(); onCancel() }, LayoutParams(0, (56 * dp).toInt(), 1f))
            addView(button(context.getString(android.R.string.ok)) { edit.commit()?.let(onPick) }, LayoutParams(0, (56 * dp).toInt(), 1f))
        }
        addView(footer, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun column(value: TextView, label: String, step: Int) = LinearLayout(context).apply {
        orientation = VERTICAL; gravity = Gravity.CENTER
        addView(text(TextScale.BODY, label), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (40 * dp).toInt()))
        addView(button("+", context.getString(R.string.form_time_increase, label)) { move(step) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()))
        addView(value, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (64 * dp).toInt()))
        addView(button("−", context.getString(R.string.form_time_decrease, label)) { move(-step) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()))
    }

    private fun move(step: Int) {
        edit.move(step)
        hour.text = edit.value.substring(0, 2)
        minute.text = edit.value.substring(3, 5)
    }

    fun cancel() = edit.cancel()

    fun colors(palette: Palette) {
        setBackgroundColor(palette.bg)
        texts.forEach { it.setTextColor(palette.primary) }
        buttons.forEach {
            it.setTextColor(palette.accentText)
            it.background = RippleDrawable(ColorStateList.valueOf((palette.accent and 0x00ffffff) or 0x26000000), null, ColorDrawable(Color.BLACK))
        }
    }
}
