package moe.kiwi.thinkos.ui

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

internal class ImmediateClick(private val view: View) {
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
    private var singlePointer = false
    private var cancelled = false
    private var longPressed = false

    fun longPressed() { longPressed = true }

    inline fun touch(event: MotionEvent, stageHasSinglePointer: Boolean, nativeTouch: (MotionEvent) -> Boolean): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return nativeTouch(event)
        val action = event.actionMasked
        val onePointer = event.pointerCount == 1 && stageHasSinglePointer
        if (action == MotionEvent.ACTION_DOWN) {
            singlePointer = onePointer
            cancelled = false
            longPressed = false
            val handled = nativeTouch(event)
            if (handled && singlePointer && view.isEnabled && view.isClickable) {
                view.isPressed = true
            } else if (!singlePointer) clearPress()
            return handled
        }
        singlePointer = singlePointer && onePointer
        if (action == MotionEvent.ACTION_CANCEL) cancelled = true
        if (!singlePointer) clearPress()
        if (action != MotionEvent.ACTION_UP) return nativeTouch(event)

        val inside = inside(event.x, event.y, view.width, view.height, slop)
        if (!canClick(view.isPressed, inside, singlePointer, cancelled, longPressed, view.isEnabled, view.isClickable)) {
            if (!inside || cancelled) clearPress()
            return nativeTouch(event)
        }

        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        try {
            view.performClick()
        } finally {
            try { nativeTouch(cancel) } finally { cancel.recycle() }
        }
        return true
    }

    private fun clearPress() {
        view.cancelLongPress()
        view.isPressed = false
    }

    companion object {
        fun inside(x: Float, y: Float, width: Int, height: Int, slop: Float): Boolean =
            x >= -slop && x < width + slop && y >= -slop && y < height + slop

        fun canClick(pressed: Boolean, inside: Boolean, singlePointer: Boolean, cancelled: Boolean,
                     longPressed: Boolean, enabled: Boolean, clickable: Boolean): Boolean =
            pressed && inside && singlePointer && !cancelled && !longPressed && enabled && clickable
    }
}
