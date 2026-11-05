package moe.kiwi.thinkos.ui.form

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.ListView
import android.widget.TextView
import moe.kiwi.thinkos.tz.ZoneNames
import moe.kiwi.thinkos.R
import moe.kiwi.thinkos.settings.RegionOptions
import moe.kiwi.thinkos.ui.Palette
import moe.kiwi.thinkos.ui.TextScale
import org.json.JSONObject
import java.text.Collator
import java.util.Locale

/** Local region names and search. A selection is an ISO country code, never a guessed time zone. */
internal class RegionPicker(
    context: Context,
    val key: String,
    titleText: String,
    private val onPick: (String) -> Unit,
    private val onCancel: () -> Unit,
    private val onInteraction: () -> Unit,
) : PickerLayout(context) {
    private data class Entry(val code: String, val name: String, val search: String)
    private val dp = resources.displayMetrics.density
    private val locale = Locale.getDefault()
    private var palette = Palette.DARK
    private var selected = ""
    private var allowEmpty = false
    private val allRegions by lazy { Entry("", context.getString(R.string.form_region_all), "") }
    private var query = ""
    private var initialised = false
    private val entries: List<Entry> by lazy {
        val collator = Collator.getInstance(locale)
        RegionOptions.codes().map { code ->
            val name = ZoneNames.country(code, locale)
            Entry(code, name, "$code $name ${ZoneNames.country(code, Locale.ENGLISH)}".lowercase(locale))
        }.sortedWith { a, b -> collator.compare(a.name, b.name) }
    }
    private var visible = emptyList<Entry>()
    private val back = TextView(context).apply {
        textSize = TextScale.TITLE; gravity = Gravity.CENTER; includeFontPadding = false
        text = if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) "›" else "‹"
        contentDescription = context.getString(android.R.string.cancel)
        setOnClickListener { onInteraction(); onCancel() }
    }
    private val title = TextView(context).apply {
        text = titleText; textSize = TextScale.TITLE; includeFontPadding = false
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    private val search = EditText(context).apply {
        textSize = TextScale.BODY; setSingleLine(true)
        val label = resources.getIdentifier("form_region_search", "string", context.packageName)
        hint = context.getString(if (label != 0) label else android.R.string.search_go)
        setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
    }
    private val list = ListView(context).apply {
        divider = null; isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
    }
    private val adapter = object : BaseAdapter() {
        override fun getCount() = visible.size
        override fun getItem(position: Int) = visible[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            ((convertView as? Row) ?: Row()).apply { bind(visible[position]) }
    }

    init {
        orientation = VERTICAL; isClickable = true
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(back, LayoutParams((56 * dp).toInt(), (56 * dp).toInt()))
            addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (64 * dp).toInt()))
        addView(search, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()))
        addView(list, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        list.adapter = adapter
        list.setOnItemClickListener { _, _, index, _ -> onInteraction(); onPick(visible[index].code) }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                onInteraction()
                query = s?.toString().orEmpty().trim().lowercase(locale)
                filter()
            }
        })
    }

    fun refresh(field: JSONObject) {
        val value = field.optString("value").uppercase(Locale.ROOT)
        val nextAllowEmpty = field.optBoolean("allowEmpty", false)
        if (!initialised || value != selected || nextAllowEmpty != allowEmpty) {
            selected = value; allowEmpty = nextAllowEmpty; initialised = true; filter()
        } else adapter.notifyDataSetChanged()
    }

    private fun filter() {
        val suggested = listOf(selected, locale.country) + when (locale.language) {
            "zh" -> listOf("CN", "HK", "TW", "MO", "SG")
            "en" -> listOf("US", "GB", "AU", "CA", "NZ", "IN")
            "es" -> listOf("ES", "MX", "AR", "CO")
            "fr" -> listOf("FR", "CA", "BE", "CH")
            "pt" -> listOf("BR", "PT")
            "ar" -> listOf("EG", "SA", "AE")
            "ru" -> listOf("RU")
            "hi" -> listOf("IN")
            "bn" -> listOf("BD", "IN")
            "ur" -> listOf("PK", "IN")
            else -> emptyList()
        }
        visible = if (query.isNotEmpty()) entries.filter { query in it.search } else {
            val codes = suggested.filter(String::isNotEmpty).distinct()
            codes.mapNotNull { code -> entries.firstOrNull { it.code == code } } + entries.filter { it.code !in codes }
        }
        if (allowEmpty && query.isEmpty()) visible = listOf(allRegions) + visible
        adapter.notifyDataSetChanged(); list.setSelection(0)
    }

    fun colors(value: Palette) {
        palette = value; setBackgroundColor(value.bg)
        back.setTextColor(value.accentText); title.setTextColor(value.primary)
        search.setTextColor(value.primary); search.setHintTextColor(value.secondary)
        search.setBackgroundColor(value.surface)
        adapter.notifyDataSetChanged()
    }

    override fun onDetachedFromWindow() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(windowToken, 0)
        super.onDetachedFromWindow()
    }

    private inner class Row : LinearLayout(context) {
        private val name = TextView(context).apply {
            textSize = TextScale.BODY; includeFontPadding = false; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        private val check = TextView(context).apply { text = "✓"; textSize = TextScale.BODY; gravity = Gravity.CENTER }
        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = (56 * dp).toInt()
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            addView(name, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(check, LayoutParams((32 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        fun bind(entry: Entry) {
            name.text = entry.name
            val chosen = selected == entry.code
            name.setTextColor(if (chosen) palette.accentText else palette.primary)
            check.setTextColor(palette.accentText); check.visibility = if (chosen) VISIBLE else INVISIBLE
            foreground = RippleDrawable(ColorStateList.valueOf((palette.accent and 0x00ffffff) or 0x26000000), null, ColorDrawable(Color.BLACK))
        }
    }
}
