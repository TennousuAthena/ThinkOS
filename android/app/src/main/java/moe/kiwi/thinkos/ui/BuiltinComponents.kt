package moe.kiwi.thinkos.ui

import moe.kiwi.thinkos.core.WallClock
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import moe.kiwi.thinkos.core.Attention
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.min
import kotlin.math.sqrt

/** Fixed native components; none reads coordinates, network, files or a plugin's private channel. */
internal object BuiltinComponents {
    fun create(context: Context, definition: JSONObject, compact: Boolean,
               images: ((JSONObject) -> RenderComponent?)? = null, tap: (String, Int?) -> Unit): RenderComponent? = when (definition.optString("type")) {
        "progress" -> ProgressComponent(context, definition)
        "chart" -> ChartComponent(context, definition)
        "list" -> ListComponent(context, definition, compact, tap)
        "grid" -> GridComponent(context, definition, images, tap)
        "tabs" -> TabsComponent(context, definition, tap)
        "toggle" -> DeviceComponents.createToggle(context, definition)
        "daylight" -> DaylightComponent(context, definition)
        "moon" -> MoonComponent(context, definition)
        else -> null
    }
}

/** Compiled scalar bindings keep JSON/state parsing off draw calls. */
internal class ScalarBinding(private val value: Any?, private val fallback: Double? = null) {
    private val template = (value as? String)?.let(Binding::compile)
    fun number(snapshot: JSONObject, variant: String, strings: (String) -> String?, item: JSONObject? = null): Double? =
        (if (template != null) template.render(snapshot, variant, strings, item).toDoubleOrNull()
        else (value as? Number)?.toDouble() ?: fallback)?.takeIf(Double::isFinite)
}

internal object ComponentData {
    private val exactBinding = Regex("^\\{\\{\\s*([A-Za-z][A-Za-z0-9_.]*)\\s*\\}\\}$")
    fun array(value: Any?, snapshot: JSONObject): JSONArray? {
        if (value is JSONArray) return value
        val raw = value as? String ?: return null
        val path = exactBinding.matchEntire(raw)?.groupValues?.get(1) ?: raw
        return Binding.lookup(snapshot, path) as? JSONArray
    }
}

private abstract class DrawingComponent(context: Context) : View(context), RenderComponent {
    final override val view: View get() = this
    protected val dp = resources.displayMetrics.density
    protected var palette = Palette.DARK
    protected open val contentHeight: Int get() = (120 * dp).toInt()
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    override fun colors(value: Palette) { palette = value; invalidate() }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize((160 * dp).toInt(), widthMeasureSpec), resolveSize(contentHeight, heightMeasureSpec))
    }
}

private class ProgressComponent(context: Context, definition: JSONObject) : DrawingComponent(context) {
    private val ring = definition.optString("style", "bar") == "ring"
    private val value = ScalarBinding(definition.opt("value"))
    private val maximum = ScalarBinding(definition.opt("max"), 1.0)
    private var fraction: Double? = null
    private var percent = "—"
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    override val defaultWeight: Double get() = 0.0
    override val contentHeight: Int get() = ((if (ring) 112 else 24) * dp).toInt()
    override fun bind(snapshot: JSONObject, variant: String, strings: (String) -> String?) {
        fraction = RenderGeometry.progress(value.number(snapshot, variant, strings), maximum.number(snapshot, variant, strings))
        percent = fraction?.let { NumberFormat.getPercentInstance(Locale.getDefault()).format(it) } ?: "—"
        contentDescription = percent
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        line.strokeWidth = min(Stroke.TRACK * dp, min(width, height) / 3f)
        line.color = palette.divider
        if (ring) {
            val radius = ((min(width, height) - line.strokeWidth) / 2f - 4 * dp).coerceAtLeast(0f)
            val cx = width / 2f; val cy = height / 2f
            canvas.drawCircle(cx, cy, radius, line)
            fraction?.let {
                line.color = palette.accent
                canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, -90f, (it * 360).toFloat(), false, line)
            }
            label.color = palette.primary; label.textSize = TextScale.BODY * resources.displayMetrics.scaledDensity
            canvas.drawText(percent, cx, cy - (label.ascent() + label.descent()) / 2, label)
        } else {
            val start = line.strokeWidth / 2; val end = (width - start).coerceAtLeast(start)
            canvas.drawLine(start, height / 2f, end, height / 2f, line)
            fraction?.takeIf { it > 0 }?.let {
                line.color = palette.accent
                canvas.drawLine(start, height / 2f, start + ((end - start) * it).toFloat(), height / 2f, line)
            }
        }
    }
}

private class ChartComponent(context: Context, private val definition: JSONObject) : DrawingComponent(context) {
    private val style = definition.optString("style", "line")
    private val minimum = ScalarBinding(definition.opt("min"))
    private val maximum = ScalarBinding(definition.opt("max"))
    private val selected = ScalarBinding(definition.opt("highlight"))
    private var values = emptyList<Double?>()
    private var low: Double? = null
    private var high: Double? = null
    private var highlight: Int? = null
    private var projection = RenderGeometry.chart(emptyList(), 0.0, 0.0)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun bind(snapshot: JSONObject, variant: String, strings: (String) -> String?) {
        val series = ComponentData.array(definition.opt("values"), snapshot)
        values = (0 until minOf(series?.length() ?: 0, 168)).map { (series?.opt(it) as? Number)?.toDouble()?.takeIf(Double::isFinite) }
        low = minimum.number(snapshot, variant, strings); high = maximum.number(snapshot, variant, strings)
        highlight = selected.number(snapshot, variant, strings)?.takeIf { it >= 1 && it == Math.rint(it) }?.toInt()?.minus(1)
        project()
        contentDescription = values.filterNotNull().joinToString(", ") { NumberFormat.getInstance(Locale.getDefault()).format(it) }
        invalidate()
    }
    private fun project() {
        projection = RenderGeometry.chart(values, (width - 24 * dp).coerceAtLeast(0f).toDouble(),
            (height - 24 * dp).coerceAtLeast(0f).toDouble(), low, high)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { project() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save(); canvas.translate(12 * dp, 12 * dp)
        val plotWidth = (width - 24 * dp).coerceAtLeast(0f)
        stroke.color = palette.accent; stroke.strokeWidth = Stroke.ICON * dp
        fill.color = palette.accent
        if (style == "bar") {
            val slot = if (values.isEmpty()) 0f else plotWidth / values.size
            projection.points.forEachIndexed { index, point -> point?.let {
                fill.alpha = if (highlight == null || highlight == index) 255 else 100
                val center = (index + .5f) * slot
                val half = (slot * .35f).coerceAtLeast(0f)
                canvas.drawRect(center - half, minOf(it.y, projection.baselineY).toFloat(), center + half,
                    maxOf(it.y, projection.baselineY).toFloat(), fill)
            } }
            fill.alpha = 255
        } else {
            val segments = mutableListOf<List<RenderGeometry.Point>>()
            var current = mutableListOf<RenderGeometry.Point>()
            for (point in projection.points) {
                if (point == null) { if (current.isNotEmpty()) segments += current; current = mutableListOf() }
                else current += point
            }
            if (current.isNotEmpty()) segments += current
            for (segment in segments) {
                if (style == "area") {
                    path.reset(); path.moveTo(segment.first().x.toFloat(), projection.baselineY.toFloat())
                    segment.forEach { path.lineTo(it.x.toFloat(), it.y.toFloat()) }
                    path.lineTo(segment.last().x.toFloat(), projection.baselineY.toFloat()); path.close()
                    fill.alpha = 50; canvas.drawPath(path, fill); fill.alpha = 255
                }
                path.reset(); path.moveTo(segment.first().x.toFloat(), segment.first().y.toFloat())
                segment.drop(1).forEach { path.lineTo(it.x.toFloat(), it.y.toFloat()) }
                canvas.drawPath(path, stroke)
                if (segment.size == 1) canvas.drawCircle(segment[0].x.toFloat(), segment[0].y.toFloat(), 3 * dp, fill)
            }
            highlight?.let { projection.points.getOrNull(it) }?.let { canvas.drawCircle(it.x.toFloat(), it.y.toFloat(), 6 * dp, fill) }
        }
        canvas.restore()
    }
}

private class DaylightComponent(context: Context, private val definition: JSONObject) : DrawingComponent(context) {
    private val painter = DaylightPainter()
    private var sun: JSONObject? = null
    private var wall = WallClock.now()
    override fun bind(snapshot: JSONObject, variant: String, strings: (String) -> String?) {
        sun = Binding.lookup(snapshot, definition.getString("path")) as? JSONObject
        invalidate()
    }
    override fun tick(monotonic: Long, attention: Attention) {
        val next = WallClock.now()
        if (next / 60_000 != wall / 60_000) { wall = next; invalidate() }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (wall < 1_577_836_800_000L) return
        painter.draw(canvas, width / 2f, height / 2f, (min(width, height) / 2f - 12 * dp).coerceAtLeast(0f), sun, wall, palette, dp)
    }
}

private class MoonComponent(context: Context, definition: JSONObject) : DrawingComponent(context) {
    private val source = definition.getString("path")
    private val hemisphere = Binding.compile(definition.optString("hemisphere", "north"))
    private var fraction: Double? = null
    private var waxing = true
    private var south = false
    private var percent = "—"
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val lit = Path()
    override fun bind(snapshot: JSONObject, variant: String, strings: (String) -> String?) {
        val data = Binding.lookup(snapshot, source) as? JSONObject
        val phase = data?.optJSONObject("phase") ?: data
        fraction = (phase?.opt("illumination") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..1.0 }
        waxing = phase?.optBoolean("waxing", true) ?: true
        south = hemisphere.render(snapshot, variant, strings) == "south"
        percent = fraction?.let { NumberFormat.getPercentInstance(Locale.getDefault()).format(it) } ?: "—"
        contentDescription = percent
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = min(width * .45f, height * .36f)
        val cx = width / 2f; val cy = height * .41f
        fill.color = palette.divider; canvas.drawCircle(cx, cy, radius, fill)
        fraction?.let { illumination ->
            lit.reset()
            val right = waxing != south
            val terminator = 1 - 2 * illumination
            // Projected terminator ellipse; reflect horizontally in the southern hemisphere.
            for (step in 0..96) {
                val y = -1.0 + step / 48.0
                val extent = sqrt((1 - y * y).coerceAtLeast(0.0))
                val x = if (right) extent else -extent
                if (step == 0) lit.moveTo(cx + (x * radius).toFloat(), cy + (y * radius).toFloat())
                else lit.lineTo(cx + (x * radius).toFloat(), cy + (y * radius).toFloat())
            }
            for (step in 96 downTo 0) {
                val y = -1.0 + step / 48.0
                val extent = sqrt((1 - y * y).coerceAtLeast(0.0))
                val x = terminator * extent * if (right) 1 else -1
                lit.lineTo(cx + (x * radius).toFloat(), cy + (y * radius).toFloat())
            }
            lit.close(); fill.color = palette.primary; canvas.drawPath(lit, fill)
        }
        label.color = palette.secondary; label.textSize = TextScale.CAPTION * resources.displayMetrics.scaledDensity
        canvas.drawText(percent, cx, height * .93f - label.descent(), label)
    }
}

private class ListComponent(context: Context, private val definition: JSONObject, private val compact: Boolean,
    private val tap: (String, Int?) -> Unit) : ListView(context), RenderComponent {
    override val view: View get() = this
    override val interactive: Boolean get() = true
    override val handlesTap: Boolean get() = true
    private val dp = resources.displayMetrics.density
    private var palette = Palette.DARK
    private var snapshot = JSONObject()
    private var items = JSONArray()
    private var variant = ""
    private var strings: (String) -> String? = { null }
    private val rowDefinition = definition.getJSONObject("item")
    private fun template(key: String) = Binding.compile(rowDefinition.optString(key))
    private val titleTemplate = template("title")
    private val subtitleTemplate = template("subtitle")
    private val trailingTemplate = template("trailing")
    private val iconTemplate = template("icon")
    private val progress = ScalarBinding(rowDefinition.opt("progress"))
    private val maximum = ScalarBinding(rowDefinition.opt("max"), 1.0)
    /** The 1-based row marked as the one in use (the track playing); 0 marks none. */
    private val current = ScalarBinding(definition.opt("current"))
    private var currentRow = 0
    private val rows = object : BaseAdapter() {
        override fun getCount() = items.length()
        override fun getItem(position: Int): Any = items.opt(position) ?: JSONObject.NULL
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? Row) ?: Row()
            row.bind((items.opt(position) as? JSONObject) ?: JSONObject().put("value", items.opt(position)),
                position + 1 == currentRow)
            return row
        }
    }
    init {
        divider = null; overScrollMode = OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false
        adapter = rows
        setOnItemClickListener { _, _, position, _ -> definition.optString("tap").takeIf { it.isNotBlank() }?.let { tap(it, position + 1) } }
    }
    override fun bind(snapshot: JSONObject, variant: String, strings: (String) -> String?) {
        this.snapshot = snapshot; this.variant = variant; this.strings = strings
        items = ComponentData.array(definition.getString("items"), snapshot) ?: JSONArray()
        val marked = current.number(snapshot, variant, strings)?.toInt() ?: 0
        val moved = marked != currentRow
        currentRow = marked
        rows.notifyDataSetChanged()
        // The track in use scrolls into view when it changes, unless a finger is on the list.
        if (moved && marked in 1..items.length()) post { if (!isPressed) smoothScrollToPositionFromTop(marked - 1, height / 3, 250) }
    }
    override fun colors(value: Palette) { palette = value; rows.notifyDataSetChanged() }
    private inner class Row : LinearLayout(context) {
        private val icon = IconView(context)
        private val title = TextView(context).apply { textSize = TextScale.BODY; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false }
        private val subtitle = TextView(context).apply { textSize = TextScale.CAPTION; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false }
        private val trailing = TextView(context).apply { textSize = TextScale.CAPTION; maxLines = 1; includeFontPadding = false; gravity = Gravity.END }
        private val mini = object : View(context) {
            var fraction: Double? = null
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = Stroke.TRACK * dp }
            override fun onDraw(canvas: Canvas) {
                paint.color = palette.divider; canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, paint)
                fraction?.let { paint.color = palette.accent; canvas.drawLine(0f, height / 2f, (width * it).toFloat(), height / 2f, paint) }
            }
        }
        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ((if (compact) 64 else 80) * dp).toInt()
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            addView(icon, LayoutParams((36 * dp).toInt(), (36 * dp).toInt()).apply { marginEnd = (12 * dp).toInt() })
            addView(LinearLayout(context).apply {
                orientation = VERTICAL; addView(title); addView(subtitle); addView(mini, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (10 * dp).toInt()))
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(trailing, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (12 * dp).toInt() })
        }
        private val highlight = GradientDrawable().apply { cornerRadius = (if (compact) 8 else 14) * dp }
        private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
        private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        fun bind(item: JSONObject, marked: Boolean) {
            title.text = titleTemplate.render(snapshot, variant, strings, item)
            subtitle.text = subtitleTemplate.render(snapshot, variant, strings, item)
            subtitle.visibility = if (subtitle.text.isEmpty()) GONE else VISIBLE
            trailing.text = trailingTemplate.render(snapshot, variant, strings, item)
            trailing.visibility = if (trailing.text.isEmpty()) GONE else VISIBLE
            icon.name = iconTemplate.render(snapshot, variant, strings, item)
            icon.visibility = if (icon.name.isEmpty()) GONE else VISIBLE
            icon.palette = palette
            mini.fraction = RenderGeometry.progress(progress.number(snapshot, variant, strings, item), maximum.number(snapshot, variant, strings, item))
            mini.visibility = if (rowDefinition.has("progress")) VISIBLE else GONE
            mini.invalidate()
            title.setTextColor(if (marked) palette.accentText else palette.primary)
            title.typeface = if (marked) medium else regular
            subtitle.setTextColor(palette.secondary); trailing.setTextColor(palette.secondary)
            highlight.setColor(palette.surface)
            background = if (marked) highlight else null
        }
    }
}
