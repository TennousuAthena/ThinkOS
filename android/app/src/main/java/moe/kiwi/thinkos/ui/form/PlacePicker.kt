package moe.kiwi.thinkos.ui.form

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.kiwi.thinkos.R
import moe.kiwi.thinkos.location.PlaceResult
import moe.kiwi.thinkos.location.PlaceSearch
import moe.kiwi.thinkos.tz.ZoneNames
import moe.kiwi.thinkos.tz.Zones
import moe.kiwi.thinkos.ui.Palette
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/**
 * Picks where the device is: online city/district search (coordinates, elevation and time zone come with the
 * result, nobody types them), or the time zone city that needs no network. When the search is unreachable the
 * bundled time zone cities are searched instead.
 */
internal class PlacePicker(
    context: Context,
    val key: String,
    private val titleText: String,
    private val endpoint: String,
    private val onPick: (Map<String, Any>) -> Unit,
    private val onCancel: () -> Unit,
    private val onInteraction: () -> Unit,
) : LinearLayout(context) {
    private val dp = resources.displayMetrics.density
    private val locale = Locale.getDefault()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var palette = Palette.DARK
    private var current = JSONObject()
    private var results = emptyList<PlaceResult>()
    private val back = text(28f).apply { gravity = Gravity.CENTER; contentDescription = titleText }
    private val title = text(22f).apply { text = titleText; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val search = EditText(context).apply {
        textSize = 18f; setSingleLine(true)
        hint = context.getString(R.string.form_place_search)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        filters = arrayOf(InputFilter.LengthFilter(160))
        setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        trackInteraction()
        setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }
    }
    private val submit = text(24f).apply { text = "⌕"; gravity = Gravity.CENTER; contentDescription = context.getString(R.string.form_place_search) }
    private val message = text(15f).apply {
        text = context.getString(R.string.form_place_hint)
        setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
    }
    private val attribution = text(14f).apply {
        text = context.getString(R.string.form_place_attribution)
        gravity = Gravity.CENTER
        setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
    }
    private val list = ListView(context).apply { divider = null; isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER }
    /** Row 0 is the time zone city (no network, approximate); the search results follow. */
    private val adapter = object : BaseAdapter() {
        override fun getCount() = results.size + 1
        override fun getItem(position: Int): Any = if (position == 0) "device" else results[position - 1]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val label: String
            val selected: Boolean
            if (position == 0) {
                label = context.getString(R.string.setting_location_mode_auto) + "\n" + ZoneNames.city(Zones.home().id, locale)
                selected = current.optString("source") == "timezone"
            } else {
                val entry = results[position - 1]
                label = entry.location.name + "\n" + entry.detail
                selected = current.optString("source") != "timezone" && current.optString("name") == entry.location.name &&
                    abs(current.optDouble("latitude") - entry.location.latitude) < 1e-6 &&
                    abs(current.optDouble("longitude") - entry.location.longitude) < 1e-6
            }
            return ((convertView as? TextView) ?: text(18f).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
                minimumHeight = (66 * dp).toInt()
            }).apply {
                text = (if (selected) "✓  " else "") + label
                setTextColor(if (selected) palette.accentText else palette.primary)
            }
        }
    }

    init {
        orientation = VERTICAL; isClickable = true
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(back, LayoutParams((56 * dp).toInt(), (56 * dp).toInt()))
            addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (56 * dp).toInt()))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(search, LayoutParams(0, (48 * dp).toInt(), 1f))
            addView(submit, LayoutParams((56 * dp).toInt(), (48 * dp).toInt()))
        })
        addView(message, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(list, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(attribution, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            if (position == 0) { onPick(mapOf("mode" to "auto")); return@setOnItemClickListener }
            val entry = results[position - 1]
            val timezone = Zones.canonical(entry.location.timezone)
            if (timezone == null) message.setText(R.string.form_place_invalid)
            else onPick(entry.formValue() + ("timezone" to timezone))
        }
        back.setOnClickListener { onCancel() }
        submit.setOnClickListener { search() }
    }

    private fun text(size: Float) = TextView(context).apply { textSize = size; includeFontPadding = false }

    private fun EditText.trackInteraction() {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onInteraction()
        })
        setOnKeyListener { _, _, event ->
            if (event.action == KeyEvent.ACTION_DOWN) onInteraction()
            false
        }
        setOnTouchListener { _, _ -> onInteraction(); false }
    }

    fun refresh(field: JSONObject) { current = field.optJSONObject("place") ?: JSONObject() }

    private fun search() {
        onInteraction()
        if (job?.isActive == true) return
        val query = search.text.toString().trim()
        if (query.length < 2) { message.setText(R.string.form_place_hint); return }
        message.setText(R.string.form_place_loading)
        submit.isEnabled = false
        job = scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { PlaceSearch.search(endpoint, query, locale) }
                results = found
                adapter.notifyDataSetChanged()
                message.setText(if (found.isEmpty()) R.string.form_place_empty else R.string.form_place_hint)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                results = withContext(Dispatchers.IO) { PlaceSearch.offline(query, locale) }
                adapter.notifyDataSetChanged()
                message.setText(R.string.form_place_error)
            } finally { submit.isEnabled = true }
        }
    }

    fun colors(value: Palette) {
        palette = value; setBackgroundColor(value.bg)
        back.text = if (layoutDirection == LAYOUT_DIRECTION_RTL) "›" else "‹"
        back.setTextColor(value.accentText); title.setTextColor(value.primary)
        search.setTextColor(value.primary); search.setHintTextColor(value.secondary); search.setBackgroundColor(value.surface)
        submit.setTextColor(value.accentText);
        message.setTextColor(value.secondary); attribution.setTextColor(value.secondary)
        adapter.notifyDataSetChanged()
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }
}
