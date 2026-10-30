package moe.kiwi.thinkos.ui.form

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import moe.kiwi.thinkos.R
import moe.kiwi.thinkos.tz.ZoneFormat
import moe.kiwi.thinkos.tz.ZoneNames
import moe.kiwi.thinkos.tz.Zones
import moe.kiwi.thinkos.ui.Palette
import org.json.JSONObject
import java.text.Collator
import java.util.Locale

/**
 * Picks one of the ~420 IANA zones by continent chip or by typing. City and country names come from
 * the platform's localised CLDR data; the offset shown is today's, so daylight saving is visible.
 */
internal class TimezonePicker(
    context: Context,
    val key: String,
    private val titleText: String,
    private val allowEmpty: Boolean,
    private val onPick: (String) -> Unit,
    private val onCancel: () -> Unit,
    private val onInteraction: () -> Unit,
) : LinearLayout(context) {
    private class Entry(val id: String, val city: String, val country: String, val area: String) {
        val haystack = (city + " " + country + " " + id.replace('_', ' ')).lowercase()
    }

    private val dp = resources.displayMetrics.density
    private val locale: Locale = Locale.getDefault()
    private var palette = Palette.DARK
    private var selected = ""
    private var area = ZoneNames.area(Zones.home().id)
    private var query = ""
    private var visible = emptyList<Entry>()
    private var initialised = false

    private val entries: List<Entry> by lazy {
        val database = Zones.database ?: return@lazy emptyList()
        val collator = Collator.getInstance(locale)
        database.zones.map { Entry(it.id, ZoneNames.city(it.id, locale), ZoneNames.country(it.country, locale), ZoneNames.area(it.id)) }
            .sortedWith { a, b -> collator.compare(a.city, b.city) }
    }

    private val back = TextView(context).apply { textSize = 28f; gravity = Gravity.CENTER; includeFontPadding = false; contentDescription = titleText }
    private val title = TextView(context).apply { textSize = 22f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false }
    private val bar = LinearLayout(context).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(back, LayoutParams((56 * dp).toInt(), (56 * dp).toInt()))
        addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    private val search = EditText(context).apply {
        setSingleLine(true); textSize = 18f
        hint = context.getString(R.string.form_tz_search)
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
        setOnKeyListener { _, _, event ->
            if (event.action == KeyEvent.ACTION_DOWN) onInteraction()
            false
        }
    }
    private val chips = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val chipScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER
        addView(chips)
    }
    private val listView = ListView(context).apply { divider = null; isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER }
    private val adapter = object : BaseAdapter() {
        override fun getCount() = visible.size + if (showNone()) 1 else 0
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? Row) ?: Row()
            if (showNone() && position == 0) row.bindNone() else row.bind(visible[position - if (showNone()) 1 else 0])
            return row
        }
    }

    init {
        orientation = VERTICAL
        isClickable = true                     // swallow touches meant for the form underneath
        addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (56 * dp).toInt()))
        addView(search, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()))
        addView(chipScroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()))
        addView(listView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        listView.adapter = adapter
        title.text = titleText
        back.setOnClickListener { onCancel() }
        listView.setOnItemClickListener { _, _, position, _ ->
            if (showNone() && position == 0) onPick("") else onPick(visible[position - if (showNone()) 1 else 0].id)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                onInteraction() // IME input does not pass through the stage's touch window.
                query = s?.toString().orEmpty().trim().lowercase()
                applyFilter(false)
            }
        })
        for (id in ZoneNames.AREA_ORDER) chips.addView(chip(id), LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (48 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() })
    }

    private fun showNone() = allowEmpty && query.isEmpty()

    private fun areaLabel(id: String): String {
        val resource = resources.getIdentifier("tz_area_" + id.lowercase(), "string", context.packageName)
        return if (resource == 0) id else context.getString(resource)
    }

    private fun chip(id: String) = TextView(context).apply {
        tag = id; text = areaLabel(id); textSize = 14f; gravity = Gravity.CENTER; includeFontPadding = false
        minWidth = (48 * dp).toInt()
        setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
        setOnClickListener { area = id; search.setText(""); applyFilter(true) }
    }

    /** Called whenever the owning form refreshes; the first call chooses where to start. */
    fun refresh(field: JSONObject) {
        selected = field.optString("value")
        if (!initialised) {
            initialised = true
            if (selected.isNotEmpty()) area = ZoneNames.area(Zones.canonical(selected) ?: selected)
            applyFilter(true)
        } else adapter.notifyDataSetChanged()
    }

    private fun applyFilter(scrollToSelection: Boolean) {
        visible = if (query.isNotEmpty()) entries.filter { query in it.haystack } else entries.filter { it.area == area }
        adapter.notifyDataSetChanged()
        paintChips()
        if (scrollToSelection) {
            val index = visible.indexOfFirst { it.id == selected }
            listView.setSelection(if (index >= 0) index + if (showNone()) 1 else 0 else 0)
        }
    }

    private fun paintChips() {
        for (i in 0 until chips.childCount) {
            val chip = chips.getChildAt(i) as TextView
            val active = query.isEmpty() && chip.tag == area
            chip.isSelected = active
            chip.background = GradientDrawable().apply {
                cornerRadius = 24 * dp
                setColor(palette.surface)
                if (active) setStroke((2 * dp).toInt(), palette.accent)
            }
            chip.setTextColor(if (active) palette.accentText else palette.secondary)
        }
    }

    fun colors(value: Palette) {
        palette = value
        setBackgroundColor(value.bg)
        back.setTextColor(value.accentText)
        back.text = if (layoutDirection == LAYOUT_DIRECTION_RTL) "›" else "‹"
        title.setTextColor(value.primary)
        search.setTextColor(value.primary)
        search.setHintTextColor(value.secondary)
        search.setBackgroundColor(value.surface)
        paintChips()
        adapter.notifyDataSetChanged()
    }

    private inner class Row : LinearLayout(context) {
        private val city = TextView(context).apply { textSize = 18f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false }
        private val detail = TextView(context).apply { textSize = 14f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false }
        private val offset = TextView(context).apply { textSize = 14f; includeFontPadding = false; gravity = Gravity.END }
        private val check = TextView(context).apply { textSize = 18f; includeFontPadding = false; text = "✓" }
        private val names = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(city); addView(detail, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (2 * dp).toInt() })
        }

        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (60 * dp).toInt()
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            addView(names, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(offset, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (12 * dp).toInt() })
            addView(check, LayoutParams((28 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * dp).toInt() })
            foreground = RippleDrawable(ColorStateList.valueOf((palette.accent and 0x00ffffff) or 0x26000000), null, ColorDrawable(Color.BLACK))
        }

        fun bindNone() {
            city.text = context.getString(R.string.form_tz_none)
            detail.visibility = GONE
            offset.text = ""
            check.visibility = if (selected.isEmpty()) VISIBLE else INVISIBLE
            paint(selected.isEmpty())
        }

        fun bind(entry: Entry) {
            val type = Zones.clock(entry.id).typeAt(System.currentTimeMillis())
            city.text = entry.city
            detail.visibility = VISIBLE
            detail.text = listOf(entry.country, if (entry.id.startsWith("Etc/")) "" else entry.id.replace('_', ' ')).filter { it.isNotEmpty() }.joinToString(" · ")
            offset.text = ZoneFormat.offsetLabel(type.offset) + if (type.dst) "  " + context.getString(R.string.form_tz_dst) else ""
            val isSelected = entry.id == selected
            check.visibility = if (isSelected) VISIBLE else INVISIBLE
            paint(isSelected)
        }

        private fun paint(isSelected: Boolean) {
            this.isSelected = isSelected
            city.setTextColor(if (isSelected) palette.accentText else palette.primary)
            detail.setTextColor(palette.secondary)
            offset.setTextColor(palette.secondary)
            check.setTextColor(palette.accentText)
        }
    }
}
