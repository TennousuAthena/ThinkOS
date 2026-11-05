package moe.kiwi.thinkos.ui

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView

/** Match the native window theme even while the stage palette is still interpolating. */
internal fun AlertDialog.readableButtons(theme: Int) {
    // Modality comes from the native window; a plain page needs no translucent overlay.
    window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    val primary = if (theme == android.R.style.Theme_Material_Light_Dialog_Alert)
        Palette.LIGHT.primary else Palette.DARK.primary
    for (button in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
        getButton(button)?.apply { setTextColor(primary); transformationMethod = null }
    }
    fun size(view: View) {
        if (view is TextView) {
            val name = if (view.id == View.NO_ID) "" else runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
            view.textSize = if (name == "alertTitle") TextScale.TITLE else TextScale.BODY
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) size(view.getChildAt(i))
    }
    window?.decorView?.let { size(it) }
    listView?.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> size(view) }
}
