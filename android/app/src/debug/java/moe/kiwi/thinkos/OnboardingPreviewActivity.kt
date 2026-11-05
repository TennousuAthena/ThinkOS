package moe.kiwi.thinkos

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import moe.kiwi.thinkos.anim.Motion
import moe.kiwi.thinkos.core.DeviceOrientation
import moe.kiwi.thinkos.core.Theme
import moe.kiwi.thinkos.shell.KioskState
import moe.kiwi.thinkos.ui.Palette
import moe.kiwi.thinkos.ui.TextScale
import java.util.Locale
import kotlin.math.min

/** WEL-0 visual prototype, shell/DUMP only. No onboarding role, completion flag, updater or device-setting actions. */
class OnboardingPreviewActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var headline: TextView? = null
    private var greeting = 0
    private var animate = false
    private val nextGreeting = object : Runnable {
        override fun run() {
            val title = headline ?: return
            title.animate().alpha(0f).translationY(-8f * resources.displayMetrics.density)
                .setDuration(160).setInterpolator(Motion.EXIT).withEndAction {
                    greeting = (greeting + 1) % greetings.size
                    title.text = greetings[greeting].second
                    title.translationY = 8f * resources.displayMetrics.density
                    title.animate().alpha(1f).translationY(0f).setDuration(Motion.NUMBER).setInterpolator(Motion.EASE).start()
                }.start()
            handler.postDelayed(this, 2_400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = DeviceOrientation.forced(intent.getIntExtra("orientation", ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        val locale = Locale.forLanguageTag(intent.getStringExtra("locale") ?: "zh-CN")
        val configuration = Configuration(resources.configuration).apply { setLocale(locale); setLayoutDirection(locale) }
        val ui = android.view.ContextThemeWrapper(createConfigurationContext(configuration), R.style.AppTheme)
        val palette = Palette.forTheme(if (intent.getStringExtra("theme") == "dark") Theme.DARK else Theme.LIGHT)
        val table = if (locale.language == "zh") {
            if (locale.country in setOf("TW", "HK") || locale.script == "Hant") "zh-Hant" else "zh-Hans"
        } else locale.language
        val words = copy[table] ?: copy.getValue("en")
        val hello = intent.getStringExtra("screen") != "update"
        greeting = greetings.indexOfFirst { it.first == table }.coerceAtLeast(0)
        animate = hello && intent.getBooleanExtra("animate", false)
        val layout = SetupLayout(ui, palette, hello).apply { layoutDirection = configuration.layoutDirection }
        headline = layout.heading.apply { text = if (hello) greetings[greeting].second else words.update }
        layout.body.text = words.body
        layout.primary.text = if (hello) words.skip else words.next
        layout.secondary.text = if (hello) words.accessibility else words.download
        layout.back.text = if (configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) "›" else "‹"
        layout.back.contentDescription = words.back
        // The prototype closes on any action; it cannot write an updater choice or an onboarding flag.
        layout.setOnClickListener { finish() }
        listOf(layout.primary, layout.secondary, layout.back).forEach { it.setOnClickListener { finish() } }
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume(); KioskState.noteResumed(taskId)
        if (animate) handler.postDelayed(nextGreeting, 2_400)
    }
    override fun onPause() {
        handler.removeCallbacks(nextGreeting); headline?.animate()?.cancel()
        KioskState.notePaused(taskId); super.onPause()
    }

    private class SetupLayout(context: Context, palette: Palette, private val hello: Boolean) : ViewGroup(context) {
        private val density = resources.displayMetrics.density
        private fun px(value: Int) = (value * density).toInt()
        private fun text(size: Float, color: Int) = TextView(context).apply {
            textSize = size; setTextColor(color); gravity = Gravity.CENTER
            includeFontPadding = false; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val heading = text(if (hello) TextScale.DISPLAY else TextScale.TITLE, palette.primary).apply {
            typeface = Typeface.create(if (hello) "sans-serif-light" else "sans-serif-medium", Typeface.NORMAL)
        }
        val body = text(TextScale.BODY, palette.secondary).apply { visibility = if (hello) GONE else VISIBLE }
        val primary = text(if (hello) TextScale.CAPTION else TextScale.BODY, if (hello) palette.accentText else Color.WHITE).apply {
            isClickable = true; isFocusable = true; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            if (!hello) background = GradientDrawable().apply { setColor(palette.accent); cornerRadius = px(12).toFloat() }
        }
        val secondary = text(TextScale.CAPTION, palette.accentText).apply {
            isClickable = true; isFocusable = true; maxLines = 2
        }
        val back = text(TextScale.TITLE, palette.secondary).apply { visibility = if (hello) GONE else VISIBLE }
        init { setBackgroundColor(palette.bg); listOf(heading, body, primary, secondary, back).forEach(::addView) }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val width = MeasureSpec.getSize(widthSpec); val height = MeasureSpec.getSize(heightSpec)
            val content = min(width - px(48), px(560)).coerceAtLeast(0)
            fun measureText(view: View) = view.measure(MeasureSpec.makeMeasureSpec(content, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            measureText(heading); measureText(body)
            fun measureButton(view: View, width: Int, height: Int) = view.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
            measureButton(primary, if (hello) content / 2 else content, px(if (hello) 48 else 56))
            measureButton(secondary, if (hello) content / 2 else content, px(48))
            measureButton(back, px(48), px(48))
            setMeasuredDimension(width, height)
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            fun center(view: View, y: Int) { val x = (width-view.measuredWidth)/2; view.layout(x, y, x+view.measuredWidth, y+view.measuredHeight) }
            if (hello) {
                center(heading, (height * .44f - heading.measuredHeight / 2).toInt())
                val start = (width - primary.measuredWidth - secondary.measuredWidth)/2
                val y = height-px(24)-primary.measuredHeight
                val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
                val first = if (rtl) secondary else primary; val last = if (rtl) primary else secondary
                first.layout(start, y, start+first.measuredWidth, y+first.measuredHeight)
                last.layout(start+first.measuredWidth, y, start+first.measuredWidth+last.measuredWidth, y+last.measuredHeight)
            } else {
                val total = heading.measuredHeight + px(16) + body.measuredHeight
                val y = (height * .44f - total / 2).toInt().coerceAtLeast(px(72))
                center(heading,y); center(body,y+heading.measuredHeight+px(16))
                center(secondary,height-px(24)-secondary.measuredHeight)
                center(primary,secondary.top-px(12)-primary.measuredHeight)
                val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width-px(16)-back.measuredWidth else px(16)
                back.layout(x,px(16),x+back.measuredWidth,px(16)+back.measuredHeight)
            }
        }
    }

    private data class Copy(val skip: String, val accessibility: String, val update: String, val body: String, val next: String, val download: String, val back: String)
    private companion object {
        val greetings = listOf("zh-Hans" to "你好", "en" to "Hello", "zh-Hant" to "你好", "es" to "Hola", "fr" to "Bonjour", "pt" to "Olá",
            "ru" to "Привет", "ar" to "مرحبًا", "ur" to "السلام علیکم", "hi" to "नमस्ते", "bn" to "হ্যালো")
        val copy = mapOf(
            "zh-Hans" to Copy("跳过设置","辅助功能","软件更新","夜间自动安装，可能重启一次","继续","只自动下载","返回"),
            "zh-Hant" to Copy("略過設定","輔助使用","軟件更新","夜間自動安裝，可能重新啟動一次","繼續","只自動下載","返回"),
            "en" to Copy("Skip setup","Accessibility","Software updates","Install automatically at night. A restart may be needed.","Continue","Only download automatically","Back"),
            "es" to Copy("Omitir configuración","Accesibilidad","Actualizaciones","Instalación automática por la noche. Puede requerir un reinicio.","Continuar","Solo descargar automáticamente","Atrás"),
            "fr" to Copy("Ignorer","Accessibilité","Mises à jour","Installation automatique la nuit. Un redémarrage peut être nécessaire.","Continuer","Télécharger automatiquement uniquement","Retour"),
            "pt" to Copy("Pular configuração","Acessibilidade","Atualizações","Instalação automática à noite. Pode ser necessário reiniciar.","Continuar","Apenas baixar automaticamente","Voltar"),
            "ru" to Copy("Пропустить","Спецвозможности","Обновление ПО","Автоматическая установка ночью. Может потребоваться перезапуск.","Продолжить","Только автоматическая загрузка","Назад"),
            "ar" to Copy("تخطي الإعداد","تسهيلات الاستخدام","تحديثات البرامج","تثبيت تلقائي ليلاً. قد يلزم إعادة التشغيل.","متابعة","التنزيل التلقائي فقط","رجوع"),
            "ur" to Copy("سیٹ اپ چھوڑیں","رسائی پذیری","سافٹ ویئر اپ ڈیٹس","رات کو خودکار تنصیب۔ دوبارہ شروع کرنا پڑ سکتا ہے۔","جاری رکھیں","صرف خودکار ڈاؤن لوڈ","واپس"),
            "hi" to Copy("सेटअप छोड़ें","सुलभता","सॉफ़्टवेयर अपडेट","रात में अपने आप इंस्टॉल करें। दोबारा शुरू करना पड़ सकता है।","जारी रखें","केवल अपने आप डाउनलोड करें","वापस"),
            "bn" to Copy("সেটআপ এড়িয়ে যান","অ্যাক্সেসিবিলিটি","সফটওয়্যার আপডেট","রাতে স্বয়ংক্রিয় ইনস্টল। পুনরায় চালু করা লাগতে পারে।","চালিয়ে যান","শুধু স্বয়ংক্রিয় ডাউনলোড","ফিরুন"),
        )
    }
}
