package moe.kiwi.thinkos.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import moe.kiwi.thinkos.R
import moe.kiwi.thinkos.core.WallClock
import org.json.JSONObject
import java.util.Date

/**
 * What the `tv` component lays over the cinema picture in place of the transport bar (ADR-038): the channel banner at the top,
 * the programme on air with its progress and the one after it, and pills for the channel list, the next line of the channel and
 * the favourite mark. Every press goes to the plugin as an action name; the plugin decides what a channel change means.
 */
internal class TvOverlay(private val context: Context, private val path: String, private val press: (String) -> Unit) {
    private val dp = context.resources.displayMetrics.density
    private var channel = TvChannel.EMPTY
    private var accent = Palette.DARK.accent
    private var timeFormat = DateFormat.getTimeFormat(context)
    private var lastTick = 0L

    val notice: String get() = channel.notice

    /** A button of the overlay, or a double tap on the picture, asks the plugin for [action]. */
    fun send(action: String) = press(action)

    private val number = TextView(context).apply {
        setTextColor(Color.WHITE); textSize = TextScale.BODY; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); includeFontPadding = false
        setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
    }
    private val name = TextView(context).apply {
        setTextColor(Color.WHITE); textSize = TextScale.BODY; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    val banner = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding((24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt(), (28 * dp).toInt())
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xb3000000.toInt(), 0x00000000))
        addView(number, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (12 * dp).toInt() })
        addView(name, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private val now = TextView(context).apply {
        setTextColor(Color.WHITE); textSize = TextScale.BODY; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    private val bar = ProgressStrip(context)
    private val next = TextView(context).apply {
        setTextColor(0xb3ffffff.toInt()); textSize = TextScale.CAPTION; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    val info = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), (6 * dp).toInt())
        addView(now, LinearLayout.LayoutParams(-1, -2))
        addView(bar, LinearLayout.LayoutParams(-1, (3 * dp).toInt()).apply { topMargin = (6 * dp).toInt(); bottomMargin = (4 * dp).toInt() })
        addView(next, LinearLayout.LayoutParams(-1, -2))
    }

    private val list = pill("list")
    private val line = pill("line")
    private val favorite = pill("favorite")
    val pills: List<View> = listOf(list, line, favorite)

    private fun pill(action: String) = TextView(context).apply {
        setTextColor(Color.WHITE); textSize = TextScale.CAPTION; maxLines = 1; gravity = Gravity.CENTER; includeFontPadding = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
        minimumHeight = (40 * dp).toInt()
        background = GradientDrawable().apply { setColor(0x33ffffff); cornerRadius = 20 * dp }
        isClickable = true
        setOnClickListener { press(action) }
    }

    init { localeChanged() }

    fun bind(snapshot: JSONObject) {
        val value = Binding.lookup(snapshot, path.trim().removePrefix("{{").removeSuffix("}}").trim())
        channel = TvChannel.parse(value as? JSONObject)
        render()
    }

    fun colors(value: Palette) {
        accent = value.accent
        bar.accent = value.accent
        render()
    }

    fun localeChanged() {
        timeFormat = DateFormat.getTimeFormat(context)
        list.text = context.getString(R.string.media_tv_list)
        favorite.text = context.getString(R.string.media_tv_favorite)
        favorite.contentDescription = context.getString(R.string.media_tv_favorite)
        render()
    }

    /** The progress of the programme on air creeps forward; once a second is enough for a bar this thin. */
    fun tick() {
        val time = WallClock.now()
        if (time - lastTick < 1_000) return
        lastTick = time
        bar.fraction = channel.now?.let { TvChannel.progress(it, time) }
    }

    private fun clock(millis: Long) = timeFormat.format(Date(millis))

    private fun render() {
        number.text = channel.number.takeIf { it > 0 }?.toString().orEmpty()
        number.visibility = if (channel.number > 0) View.VISIBLE else View.GONE
        name.text = channel.name
        name.visibility = if (channel.name.isNotEmpty()) View.VISIBLE else View.GONE
        banner.contentDescription = context.getString(R.string.media_tv_channel, channel.number.toString(), channel.name)

        val current = channel.now
        val following = channel.next
        now.visibility = if (current != null) View.VISIBLE else View.GONE
        bar.visibility = if (current != null && current.end > current.start) View.VISIBLE else View.GONE
        next.visibility = if (following != null) View.VISIBLE else View.GONE
        info.visibility = if (current != null || following != null) View.VISIBLE else View.GONE
        if (current != null) {
            now.text = (if (current.end > 0) clock(current.start) + " – " + clock(current.end) else clock(current.start)) + "   " + current.title
            lastTick = 0
            tick()
        }
        if (following != null) next.text = context.getString(R.string.media_tv_next_programme) + "   " + clock(following.start) + "   " + following.title

        line.visibility = if (channel.hasLines) View.VISIBLE else View.GONE
        line.text = context.getString(R.string.media_tv_line, channel.line.toString(), channel.lines.toString())
        line.contentDescription = line.text
        val on = CoverTone.on(accent)
        (favorite.background as GradientDrawable).setColor(if (channel.favorite) accent else 0x33ffffff)
        favorite.setTextColor(if (channel.favorite) on else Color.WHITE)
        favorite.isSelected = channel.favorite
    }
}

/** A thin bar: the fraction of the programme that has gone by. */
private class ProgressStrip(context: Context) : View(context) {
    var accent = Palette.DARK.accent
        set(value) { field = value; invalidate() }
    var fraction: Float? = null
        set(value) { if (field != value) { field = value; invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        paint.color = 0x40ffffff
        box.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(box, radius, radius, paint)
        val done = fraction ?: return
        paint.color = accent
        box.set(0f, 0f, width * done, height.toFloat())
        canvas.drawRoundRect(box, radius, radius, paint)
    }
}
