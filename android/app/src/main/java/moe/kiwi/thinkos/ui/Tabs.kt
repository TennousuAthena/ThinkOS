package moe.kiwi.thinkos.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * `tabs`: up to six equal tabs in a pill. Each tab is a real labelled view, so accessibility services and UI tests see and
 * press it by its text; the selected one sits on the accent colour. The plugin hears `tap` with the 1-based tab.
 */
internal class TabsComponent(context: Context, private val definition: JSONObject, private val tap: (String, Int?) -> Unit) :
    LinearLayout(context), RenderComponent {
    override val view: View get() = this
    override val interactive: Boolean get() = true
    override val handlesTap: Boolean get() = true
    override val defaultWeight: Double get() = 0.0
    private val dp = resources.displayMetrics.density
    private val event = definition.optString("tap")
    private val current = ScalarBinding(definition.opt("current"))
    private val track = GradientDrawable().apply { cornerRadius = 24 * dp }
    private var palette = Palette.DARK
    private var labels = emptyList<String>()
    private var selected = 0

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = track
        setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
    }

    override fun bind(snapshot: JSONObject, variant: String, strings: (String) -> String?) {
        val items = ComponentData.array(definition.getString("items"), snapshot)
        val next = (0 until minOf(items?.length() ?: 0, MAX_TABS)).map { i ->
            val item = items?.opt(i)
            (item as? String) ?: (item as? JSONObject)?.optString("title").orEmpty()
        }
        selected = current.number(snapshot, variant, strings)?.toInt() ?: 0
        if (next != labels) {
            labels = next
            removeAllViews()
            next.forEachIndexed { index, label ->
                val tab = TextView(context).apply {
                    text = label
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    maxLines = 1
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setAutoSizeTextTypeUniformWithPresetSizes(intArrayOf(TextScale.CAPTION.toInt(), TextScale.BODY.toInt()), TypedValue.COMPLEX_UNIT_SP)
                    setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
                    isClickable = true
                    setOnClickListener { if (event.isNotBlank()) tap(event, index + 1) }
                }
                addView(tab, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            }
        }
        paint()
    }

    override fun colors(value: Palette) { palette = value; paint() }

    private fun paint() {
        track.setColor(palette.surface)
        val on = CoverTone.on(palette.accent)
        for (i in 0 until childCount) {
            val tab = getChildAt(i) as TextView
            val here = i + 1 == selected
            tab.setTextColor(if (here) on else palette.secondary)
            tab.background = if (here) GradientDrawable().apply { cornerRadius = 20 * dp; setColor(palette.accent) } else null
            tab.isSelected = here
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((48 * dp).toInt(), MeasureSpec.EXACTLY))
    }

    private companion object { const val MAX_TABS = 6 }
}
