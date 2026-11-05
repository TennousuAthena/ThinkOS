package moe.kiwi.thinkos.ui

import android.app.AlertDialog
import android.content.Context
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import moe.kiwi.thinkos.plugin.RegistryConsent
import java.util.concurrent.atomic.AtomicBoolean

/** No plugin callback can synthesize acceptance: only a fresh, unobscured touchscreen release. */
class PluginConsentDialog(private val context: Context, request: RegistryConsent, private val palette: Palette = Palette.LIGHT, result: (Boolean) -> Unit) {
    private val delivered = AtomicBoolean()
    private var accepted = false
    private var touchGate: NativeTouchGate? = null
    private val dp = context.resources.displayMetrics.density
    private val theme = if (palette == Palette.LIGHT) android.R.style.Theme_Material_Light_Dialog_Alert else android.R.style.Theme_Material_Dialog_Alert
    private fun text(key: String, fallback: Int) = context.resources.getIdentifier(key, "string", context.packageName).let { context.getString(if (it != 0) it else fallback) }
    private fun label(key: String, fallback: String): String = context.resources.getIdentifier(key, "string", context.packageName).let { if (it == 0) fallback else context.getString(it) }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (12 * dp).toInt())
    }
    val dialog: AlertDialog
    init {
        fun line(value: String, size: Float = TextScale.BODY) {
            content.addView(TextView(context).apply { text = value; textSize = size; includeFontPadding = false; setTextColor(if (size == TextScale.CAPTION) palette.secondary else palette.primary) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (12 * dp).toInt() })
        }
        line(request.name, TextScale.TITLE)
        line(label("plugins_source", "Source") + "\n" + request.repo + "@" + request.commit, TextScale.CAPTION)
        line(label("plugins_tier_" + request.tier, request.tier), TextScale.CAPTION)
        line(label("plugins_size", "Size") + "  " + android.text.format.Formatter.formatFileSize(context, request.size), TextScale.CAPTION)
        if (request.permissions.isNotEmpty()) line(label("plugins_permissions", "Permissions"))
        request.permissions.forEach { (permission, why) ->
            line(label("plugins_permission_" + permission, permission))
            line(why, TextScale.CAPTION)
        }
        val scroll = ScrollView(context).apply { addView(content) }
        dialog = AlertDialog.Builder(context, theme)
            .setView(scroll)
            .setPositiveButton(text("plugins_install", android.R.string.ok), null)
            .setNegativeButton(text("plugins_cancel", android.R.string.cancel), null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(palette.bg))
            for (which in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE)) dialog.getButton(which)?.apply {
                textSize = TextScale.BODY; transformationMethod = null; setTextColor(palette.primary)
            }
            touchGate = NativeTouchConfirmation.bind(dialog) { accepted = true; dialog.dismiss() }
        }
        dialog.setOnDismissListener { touchGate?.cancel(); if (delivered.compareAndSet(false, true)) result(accepted) }
    }
    fun show() = dialog.show()
    fun dismiss() = dialog.dismiss()
}
