package moe.kiwi.thinkos

import android.app.Activity
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import moe.kiwi.thinkos.anim.Motion
import moe.kiwi.thinkos.core.Attention
import moe.kiwi.thinkos.core.Theme
import moe.kiwi.thinkos.shell.KioskState
import moe.kiwi.thinkos.ui.ClockView
import moe.kiwi.thinkos.ui.Palette
import org.json.JSONObject

/** Shell-only visual fixture. No listener, notification API, permission or player is started here. */
class NotificationPreviewActivity : Activity() {
    private var preview: View? = null
    private lateinit var ui: android.content.Context
    private val dp get() = resources.displayMetrics.density
    private fun px(value: Int) = (value * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = moe.kiwi.thinkos.core.DeviceOrientation.forced(intent.getIntExtra("orientation", android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR))
        val locale = intent.getStringExtra("locale")?.let(java.util.Locale::forLanguageTag) ?: java.util.Locale.getDefault()
        val config = android.content.res.Configuration(resources.configuration).apply {
            setLocale(locale); setLayoutDirection(locale)
        }
        // Activity resources are already initialized on API 27. A view-local configuration keeps
        // RTL preview isolated and avoids both global settings changes and Activity override errors.
        ui = android.view.ContextThemeWrapper(createConfigurationContext(config), R.style.AppTheme)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        val theme = when (intent.getStringExtra("theme")) { "light" -> Theme.LIGHT; "night" -> Theme.NIGHT; else -> Theme.DARK }
        val palette = Palette.forTheme(theme)
        val compact = intent.getStringExtra("mode") == "banner"
        val urgent = intent.getStringExtra("level") == "urgent"
        val frame = FrameLayout(ui).apply { setBackgroundColor(palette.bg); layoutDirection = config.layoutDirection }
        val clock = ClockView(ui, JSONObject().put("style", "digital"), false).apply {
            this.palette = palette
            tick(SystemClock.uptimeMillis(), Attention.AMBIENT)
        }
        // ClockView owns its own pulse alpha. Dim its parent so it cannot restore a bright
        // background underneath the notification text when it attaches or ticks.
        frame.addView(FrameLayout(ui).apply {
            alpha = if (compact) .28f else .025f
            addView(clock, FrameLayout.LayoutParams(-1, -1))
        }, FrameLayout.LayoutParams(-1, -1))
        val panel = if (compact) banner(palette, urgent) else takeover(palette, urgent)
        preview = panel
        frame.addView(panel, FrameLayout.LayoutParams(-1, -2,
            if (compact) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER).apply {
            setMargins(px(20), px(20), px(20), px(20))
        })
        if (theme == Theme.NIGHT) {
            val values = floatArrayOf(.2126f,.7152f,.0722f,0f,0f,
                .0492f,.1656f,.0167f,0f,0f, .0391f,.1316f,.0133f,0f,0f, 0f,0f,0f,1f,0f)
            val tint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(values)) }
            clock.setLayerType(View.LAYER_TYPE_HARDWARE, tint)
            if (!urgent) panel.setLayerType(View.LAYER_TYPE_HARDWARE, tint)
        }
        setContentView(frame)
        panel.alpha = 0f
        panel.translationY = px(if (compact) -16 else 12).toFloat()
        panel.scaleX = .98f; panel.scaleY = .98f
        panel.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(300).setInterpolator(Motion.EASE).start()
    }

    override fun onResume() { super.onResume(); KioskState.resumed = true }
    override fun onPause() { KioskState.resumed = false; super.onPause() }
    override fun onDestroy() { preview?.animate()?.cancel(); super.onDestroy() }

    private fun title(palette: Palette, size: Float) = TextView(ui).apply {
        text = intent.getStringExtra("title").orEmpty().take(160)
        setTextColor(palette.primary); textSize = size
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }
    private fun body(palette: Palette, size: Float, lines: Int) = TextView(ui).apply {
        text = intent.getStringExtra("text").orEmpty().take(800)
        setTextColor(palette.secondary); textSize = size
        maxLines = lines; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }
    private fun close(palette: Palette) = TextView(ui).apply {
        text = "×"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(palette.secondary)
        contentDescription = ui.getString(android.R.string.cancel)
        isFocusable = true; setOnClickListener { finish() }
    }

    private fun banner(palette: Palette, urgent: Boolean): View = LinearLayout(ui).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(px(20), px(18), px(12), px(18))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(palette.surface); cornerRadius = px(24).toFloat()
        }
        addView(Bell(if (urgent) palette.warn else palette.accent), LinearLayout.LayoutParams(px(48), px(48)))
        addView(LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            addView(title(palette, 24f), LinearLayout.LayoutParams(-1, -2))
            addView(body(palette, 18f, 2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(16); marginEnd = px(10) })
        addView(close(palette), LinearLayout.LayoutParams(px(48), px(48)))
    }

    private fun takeover(palette: Palette, urgent: Boolean): View = LinearLayout(ui).apply {
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        setPadding(px(24), px(16), px(24), px(16))
        val icon = px(if (landscape) 72 else 104)
        addView(Bell(if (urgent) palette.warn else palette.accent), LinearLayout.LayoutParams(icon, icon))
        addView(title(palette, if (landscape) 30f else 34f).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(if (landscape) 16 else 24) })
        addView(body(palette, if (landscape) 20f else 22f, if (landscape) 2 else 3).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) })
        addView(close(palette), LinearLayout.LayoutParams(px(56), px(56)).apply { topMargin = px(16) })
    }

    private inner class Bell(private val tint: Int) : View(ui) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()
        override fun onDraw(canvas: Canvas) {
            val scale = minOf(width, height) / 24f
            val dx = (width - 24 * scale) / 2
            val dy = (height - 24 * scale) / 2
            fun x(value: Float) = dx + value * scale
            fun y(value: Float) = dy + value * scale
            line.color = tint; line.strokeWidth = 1.5f * scale
            path.rewind(); path.moveTo(x(5f), y(17f)); path.cubicTo(x(7f), y(14f), x(6f), y(12f), x(7f), y(8f))
            path.cubicTo(x(8f), y(3f), x(16f), y(3f), x(17f), y(8f))
            path.cubicTo(x(18f), y(12f), x(17f), y(14f), x(19f), y(17f)); path.close()
            canvas.drawPath(path, line); canvas.drawArc(x(10f), y(18f), x(14f), y(22f), 0f, 180f, false, line)
            canvas.drawLine(x(12f), y(2f), x(12f), y(4f), line)
        }
    }
}
