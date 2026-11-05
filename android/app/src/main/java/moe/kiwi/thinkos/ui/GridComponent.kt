package moe.kiwi.thinkos.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Fixed native grid vocabulary: recycled cells, OS-owned image grants and one-based tap indices. */
internal class GridComponent(
    context: Context,
    private val definition: JSONObject,
    private val images: ((JSONObject) -> RenderComponent?)?,
    private val tap: (String, Int?) -> Unit,
) : GridView(context), RenderComponent {
    override val view: View get() = this
    override val interactive: Boolean get() = true
    override val handlesTap: Boolean get() = true
    private val dp = resources.displayMetrics.density
    private var palette = Palette.DARK
    private var snapshot = JSONObject()
    private var items = JSONArray()
    private var variant = ""
    private var strings: (String) -> String? = { null }
    private var imagesActive = false
    private val drag = GridDragGate(ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    private var canceledDrag = false
    private val item = definition.getJSONObject("item")
    private val titleTemplate = Binding.compile(item.optString("title"))
    private val iconTemplate = Binding.compile(item.optString("icon"))
    private val imageTemplate = Binding.compile(item.optString("image"))
    private val cells = object : BaseAdapter() {
        override fun getCount() = items.length()
        override fun getItem(position: Int): Any = items.opt(position) ?: JSONObject.NULL
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            ((convertView as? Cell) ?: Cell()).also { cell ->
                cell.bind(items.optJSONObject(position) ?: JSONObject().put("value", items.opt(position)))
            }
    }

    init {
        numColumns = 3
        columnWidth = (96 * dp).toInt()
        stretchMode = STRETCH_COLUMN_WIDTH
        gravity = Gravity.CENTER
        horizontalSpacing = 0; verticalSpacing = 0
        overScrollMode = OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false
        selector = ColorDrawable(Color.TRANSPARENT)
        adapter = cells
        setRecyclerListener { (it as? Cell)?.active(false) }
        setOnItemClickListener { _, _, position, _ ->
            definition.optString("tap").takeIf { it.isNotBlank() }?.let { tap(it, position + 1) }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val columns = RenderGeometry.gridColumns(MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight, dp)
        if (numColumns != columns) numColumns = columns
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            drag.down(event.x, event.y); canceledDrag = false
        } else if (event.actionMasked == MotionEvent.ACTION_MOVE && drag.move(event.x, event.y)) {
            // AbsListView's vertical scroller can otherwise click the original cell after a horizontal drag.
            if (!canceledDrag) {
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                try { super.onTouchEvent(cancel) } finally { cancel.recycle() }
                canceledDrag = true
            }
            return true
        } else if (event.actionMasked in setOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL) && canceledDrag) {
            canceledDrag = false
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun bind(snapshot: JSONObject, variant: String, strings: (String) -> String?) {
        this.snapshot = snapshot; this.variant = variant; this.strings = strings
        items = ComponentData.array(definition.getString("items"), snapshot) ?: JSONArray()
        cells.notifyDataSetChanged()
    }

    override fun colors(value: Palette) { palette = value; cells.notifyDataSetChanged() }
    override fun active(value: Boolean) {
        imagesActive = value
        for (index in 0 until childCount) (getChildAt(index) as? Cell)?.active(value)
    }

    private inner class Cell : LinearLayout(context) {
        private val icon = IconView(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val imageBox = FrameLayout(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        private val title = TextView(context).apply {
            textSize = TextScale.CAPTION; includeFontPadding = false; maxLines = 1
            ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private var image: RenderComponent? = null
        private var source = ""

        init {
            orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            minimumHeight = (96 * dp).toInt()
            setPadding((4*dp).toInt(), (8*dp).toInt(), (4*dp).toInt(), (8*dp).toInt())
            val thumbnail = FrameLayout(context).apply {
                addView(icon, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                addView(imageBox, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
            addView(thumbnail, LayoutParams((56*dp).toInt(), (56*dp).toInt()))
            addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (8*dp).toInt() })
        }

        fun bind(item: JSONObject) {
            title.text = titleTemplate.render(snapshot, variant, strings, item)
            title.setTextColor(palette.primary); contentDescription = title.text
            icon.name = iconTemplate.render(snapshot, variant, strings, item); icon.palette = palette
            val next = imageTemplate.render(snapshot, variant, strings, item)
            // A recycled cell must not show another app's pixels while a new request is pending.
            if (next != source) {
                image?.active(false); imageBox.removeAllViews(); image = null; source = next
            }
            if (source.isNotBlank()) {
                if (image == null) image = images?.invoke(JSONObject().put("type", "image").put("src", "{{gridImage}}").put("fit", "contain"))?.also {
                    imageBox.addView(it.view, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                }
                image?.bind(JSONObject().put("gridImage", source), variant, strings)
                image?.colors(palette); image?.active(imagesActive && isAttachedToWindow)
            }
            imageBox.visibility = if (image == null) GONE else VISIBLE
            icon.visibility = if (image != null || icon.name.isBlank()) GONE else VISIBLE
            background = RippleDrawable(ColorStateList.valueOf((palette.accent and 0x00ffffff) or 0x26000000), null, ColorDrawable(Color.BLACK))
        }

        fun active(value: Boolean) { image?.active(value) }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); active(imagesActive) }
        override fun onDetachedFromWindow() { active(false); super.onDetachedFromWindow() }
    }
}
