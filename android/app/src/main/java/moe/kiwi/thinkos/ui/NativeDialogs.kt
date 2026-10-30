package moe.kiwi.thinkos.ui

import android.app.AlertDialog

/** Match the native window theme even while the stage palette is still interpolating. */
internal fun AlertDialog.readableButtons(theme: Int) {
    val primary = if (theme == android.R.style.Theme_Material_Light_Dialog_Alert)
        Palette.LIGHT.primary else Palette.DARK.primary
    for (button in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
        getButton(button)?.setTextColor(primary)
    }
}
