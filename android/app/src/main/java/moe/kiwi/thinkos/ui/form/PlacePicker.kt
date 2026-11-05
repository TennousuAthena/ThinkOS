package moe.kiwi.thinkos.ui.form

import moe.kiwi.thinkos.ui.TextScale
import android.content.Context
import android.app.AlertDialog
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
import android.widget.LinearLayout.LayoutParams
import android.widget.ListView
import android.widget.TextView
import android.widget.ScrollView
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
import moe.kiwi.thinkos.ui.readableButtons
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/**
 * Picks where the device is: online city/district search (coordinates, elevation and time zone come with the
 * result), manual coordinates, or the time zone city that needs no network. When
 * the search is unreachable the bundled time zone cities are searched instead.
 */
internal class PlacePicker(
    context: Context,
    val key: String,
    private val titleText: String,
    private val endpoint: String,
    private val onPick: (Map<String, Any>) -> Unit,
    private val onCancel: () -> Unit,
    private val onInteraction: () -> Unit,
) : PickerLayout(context) {
    private val dp = resources.displayMetrics.density
    private val locale = Locale.getDefault()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var palette = Palette.DARK
    private var current = JSONObject()
    private var results = emptyList<PlaceResult>()
    private var manualDialog: AlertDialog? = null
    private val back = text(TextScale.TITLE).apply { gravity = Gravity.CENTER; contentDescription = titleText }
    private val title = text(TextScale.TITLE).apply { text = titleText; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val search = EditText(context).apply {
        textSize = TextScale.BODY; setSingleLine(true)
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
    private val submit = text(TextScale.BODY).apply { text = "⌕"; gravity = Gravity.CENTER; contentDescription = context.getString(R.string.form_place_search) }
    private val manual = text(TextScale.BODY).apply {
        text = context.getString(R.string.setting_location_mode_manual)
        gravity = Gravity.CENTER
        setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        minimumWidth = (64 * dp).toInt()
        setOnClickListener { openManual() }
    }
    private val message = text(TextScale.CAPTION).apply {
        visibility = GONE
        setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
    }
    private val attribution = text(TextScale.CAPTION).apply {
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
            return ((convertView as? TextView) ?: text(TextScale.BODY).apply {
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
            addView(manual, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (56 * dp).toInt()))
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
            if (timezone == null) showMessage(R.string.form_place_invalid)
            else onPick(entry.formValue() + ("timezone" to timezone))
        }
        back.setOnClickListener { onCancel() }
        submit.setOnClickListener { search() }
    }

    private fun text(size: Float) = TextView(context).apply { textSize = size; includeFontPadding = false }

    /** An unsaved draft stays in this native dialog; the existing form callback commits all six values together. */
    private fun openManual() {
        if (manualDialog?.isShowing == true) return
        onInteraction()
        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
        }
        val inputs = linkedMapOf<String, EditText>()
        val declarations = listOf("name" to R.string.setting_location_name, "latitude" to R.string.setting_location_latitude,
            "longitude" to R.string.setting_location_longitude, "elevation" to R.string.setting_location_elevation,
            "timezone" to R.string.setting_location_timezone)
        for ((key, label) in declarations) {
            content.addView(text(TextScale.CAPTION).apply { text = context.getString(label); setTextColor(palette.primary) })
            val input = EditText(context).apply {
                textSize = TextScale.BODY
                setTextColor(palette.primary); setHintTextColor(palette.secondary)
                setSingleLine(true)
                inputType = if (key in setOf("latitude", "longitude", "elevation")) InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                filters = arrayOf(InputFilter.LengthFilter(160))
                setText(current.opt(key)?.toString().orEmpty())
                if (key == "timezone") hint = context.getString(R.string.form_place_timezone_empty)
                trackInteraction()
            }
            inputs[key] = input
            content.addView(input, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * dp).toInt()))
        }
        val scroll = ScrollView(context).apply { addView(content) }
        val error = text(TextScale.CAPTION).apply {
            visibility = GONE; setTextColor(palette.warnText)
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), (8 * dp).toInt())
        }
        val outer = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(error, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val theme = if (palette == Palette.LIGHT) android.R.style.Theme_Material_Light_Dialog_Alert else android.R.style.Theme_Material_Dialog_Alert
        val dialog = AlertDialog.Builder(context, theme).setTitle(titleText).setView(outer)
            .setPositiveButton(android.R.string.ok, null).setNegativeButton(android.R.string.cancel, null).create()
        manualDialog = dialog
        dialog.setOnDismissListener { manualDialog = null }
        dialog.setOnShowListener {
            dialog.readableButtons(theme)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                onInteraction()
                val latitude = inputs.getValue("latitude").text.toString().toDoubleOrNull()
                val longitude = inputs.getValue("longitude").text.toString().toDoubleOrNull()
                val elevationMeters = inputs.getValue("elevation").text.toString().toDoubleOrNull()
                val timezone = inputs.getValue("timezone").text.toString().trim()
                val place = runCatching {
                    moe.kiwi.thinkos.astro.Place(requireNotNull(latitude), requireNotNull(longitude), requireNotNull(elevationMeters))
                }.getOrNull()
                val zone = if (timezone.isEmpty()) "" else Zones.canonical(timezone)
                if (place == null || zone == null) {
                    error.setText(R.string.form_place_invalid_values)
                    error.visibility = VISIBLE
                } else {
                    onPick(mapOf("mode" to "manual", "name" to inputs.getValue("name").text.toString().trim(),
                        "latitude" to place.latitude, "longitude" to place.longitude, "elevation" to place.elevation, "timezone" to zone))
                }
            }
            // Leave room for the action bar in both orientations. The fields scroll independently.
            dialog.window?.setLayout((minOf(560f, resources.displayMetrics.widthPixels / dp - 32) * dp).toInt(),
                (resources.displayMetrics.heightPixels * .86f).toInt())
        }
        dialog.show()
    }

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
        if (query.length < 2) { showMessage(null); return }
        showMessage(R.string.form_place_loading)
        submit.isEnabled = false
        job = scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { PlaceSearch.search(endpoint, query, locale) }
                results = found
                adapter.notifyDataSetChanged()
                showMessage(if (found.isEmpty()) R.string.form_place_empty else null)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                results = withContext(Dispatchers.IO) { PlaceSearch.offline(query, locale) }
                adapter.notifyDataSetChanged()
                showMessage(R.string.form_place_error)
            } finally { submit.isEnabled = true }
        }
    }

    private fun showMessage(resource: Int?) {
        message.text = resource?.let(context::getString).orEmpty()
        message.visibility = if (resource == null) GONE else VISIBLE
    }

    fun colors(value: Palette) {
        palette = value; setBackgroundColor(value.bg)
        back.text = if (layoutDirection == LAYOUT_DIRECTION_RTL) "›" else "‹"
        back.setTextColor(value.accentText); title.setTextColor(value.primary)
        search.setTextColor(value.primary); search.setHintTextColor(value.secondary); search.setBackgroundColor(value.surface)
        submit.setTextColor(value.accentText);
        manual.setTextColor(value.accentText)
        message.setTextColor(value.secondary); attribution.setTextColor(value.secondary)
        adapter.notifyDataSetChanged()
    }

    override fun onDetachedFromWindow() {
        manualDialog?.dismiss(); manualDialog = null
        scope.cancel()
        super.onDetachedFromWindow()
    }
}
