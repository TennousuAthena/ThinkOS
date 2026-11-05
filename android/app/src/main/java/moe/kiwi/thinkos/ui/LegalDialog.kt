package moe.kiwi.thinkos.ui

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.kiwi.thinkos.BuildConfig
import moe.kiwi.thinkos.R

/** Native legal notices remain available when no plugin can run. No network or WebView. */
internal class LegalDialog(activity: Activity, private val palette: Palette, private val interaction: () -> Unit) :
    Dialog(activity, if (palette == Palette.LIGHT) android.R.style.Theme_Material_Light_NoActionBar else android.R.style.Theme_Material_NoActionBar) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val documents = listOf(
        "LICENSE-GPL-3.0.md" to R.string.legal_gpl,
        "LICENSE" to R.string.legal_mit,
        "LICENSE-APACHE-2.0.txt" to R.string.legal_apache,
        "LICENSE-MPL-2.0.txt" to R.string.legal_mpl,
        "THIRD_PARTY_NOTICES.md" to R.string.legal_third_party,
    )
    private var texts = emptyMap<String, String>()
    private var document: String? = null
    private lateinit var root: LinearLayout
    private lateinit var title: TextView
    private lateinit var back: TextView
    private lateinit var scroll: ScrollView
    private val dp = context.resources.displayMetrics.density
    private fun px(value: Int) = (value * dp + .5f).toInt()
    private fun text(value: CharSequence, size: Float = TextScale.BODY, secondary: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(if (secondary) palette.secondary else palette.primary)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.bg)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        back = text("", TextScale.TITLE).apply {
            gravity = Gravity.CENTER
            setOnClickListener { goBack() }
        }
        title = text("", TextScale.TITLE).apply {
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        header.addView(back, LinearLayout.LayoutParams(px(56), px(64)))
        header.addView(title, LinearLayout.LayoutParams(0, px(64), 1f))
        header.setPadding(0, 0, px(24), 0)
        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        scroll = ScrollView(context).apply { isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        window?.setLayout(-1, -1)
        refresh()
        scope.launch {
            texts = withContext(Dispatchers.IO) {
                documents.associate { (file, _) -> file to runCatching {
                    context.assets.open("legal/$file").use { LegalText.display(file, it.readBytes().toString(Charsets.UTF_8)) }
                }.onFailure { error -> android.util.Log.w("ThinkOS", "Unable to prepare legal text: $file", error) }.getOrDefault("") }
            }
            refresh()
        }
    }

    /** Activity resources also follow process-local debug language changes. */
    fun refresh() {
        if (!::root.isInitialized) return
        val rtl = TextUtils.getLayoutDirectionFromLocale(java.util.Locale.getDefault()) == View.LAYOUT_DIRECTION_RTL
        root.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        back.text = if (rtl) "›" else "‹"
        back.contentDescription = context.getString(R.string.maintenance_back)
        val file = document
        title.text = context.getString(documents.firstOrNull { it.first == file }?.second ?: R.string.legal_title)
        scroll.removeAllViews()
        if (file != null) {
            scroll.addView(text(texts[file].orEmpty().ifEmpty { context.getString(R.string.legal_unavailable) }).apply {
                setPadding(px(24), px(12), px(24), px(32))
                setTextIsSelectable(true)
                // Plain legal files are verbatim; Markdown originals have a plain-text presentation.
                textDirection = View.TEXT_DIRECTION_LTR
                layoutDirection = View.LAYOUT_DIRECTION_LTR
            }, FrameLayout.LayoutParams(-1, -2))
        } else {
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(24), px(12), px(24), px(32))
            }
            content.addView(text(context.getString(R.string.legal_version, BuildConfig.VERSION_NAME)))
            content.addView(text(texts["LICENSE"].orEmpty().lineSequence().drop(2).firstOrNull().orEmpty(), TextScale.CAPTION, true).apply {
                setPadding(0, px(8), 0, px(24))
                setTextIsSelectable(true)
            })
            content.addView(text(context.getString(if (BuildConfig.DISTRIBUTION_GPL) R.string.legal_notice_gpl else R.string.legal_notice_mit)).apply {
                setPadding(0, 0, 0, px(24))
            })
            for ((name, label) in documents) content.addView(text(context.getString(label)).apply {
                minHeight = px(56)
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { document = name; refresh(); scroll.scrollTo(0, 0) }
            }, LinearLayout.LayoutParams(-1, -2))
            content.addView(text(context.getString(R.string.legal_source)).apply { setPadding(0, px(24), 0, px(8)) })
            content.addView(text(BuildConfig.SOURCE_CODE_URL, TextScale.CAPTION, true).apply {
                setTextIsSelectable(true)
                textDirection = View.TEXT_DIRECTION_LTR
            })
            scroll.addView(content, FrameLayout.LayoutParams(-1, -2))
        }
    }

    private fun goBack() {
        if (document == null) dismiss() else { document = null; refresh(); scroll.scrollTo(0, 0) }
    }
    /** Read-only observation for the shell-protected debug test; scrolling still uses touch. */
    fun debugState(): String = org.json.JSONObject().put("showing", isShowing)
        .put("document", document ?: org.json.JSONObject.NULL)
        .put("ready", texts.size == documents.size)
        .put("scrollY", if (::scroll.isInitialized) scroll.scrollY else 0)
        .put("canScrollDown", ::scroll.isInitialized && scroll.canScrollVertically(1)).toString()
    @Deprecated("Native Back first leaves the text, then the legal page")
    override fun onBackPressed() = goBack()
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) interaction()
        return super.dispatchTouchEvent(event)
    }
    override fun dismiss() { scope.cancel(); super.dismiss() }
}
