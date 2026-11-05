package moe.kiwi.thinkos.ui

import android.app.AlertDialog
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

/** Shared gate for native plugin and maintenance approval. No callback API
 * provides touch credit. DebugReceiver's source-less synthetic events fail it. */
internal object NativeTouchConfirmation {
    fun bind(dialog: AlertDialog, accepted: () -> Unit): NativeTouchGate {
        val gate = NativeTouchGate()
        val approve = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        approve.filterTouchesWhenObscured = true
        approve.setOnTouchListener { view, event ->
            val physical = event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) && event.pointerCount == 1 &&
                event.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED == 0 && view.isShown && view.hasWindowFocus()
            val now = SystemClock.elapsedRealtime()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> gate.down(now, physical)
                MotionEvent.ACTION_UP -> gate.up(now, physical,
                    event.x >= 0 && event.y >= 0 && event.x < view.width && event.y < view.height)
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> gate.cancel()
                else -> if (!physical) gate.cancel()
            }
            false
        }
        approve.setOnClickListener {
            if (gate.consume(SystemClock.elapsedRealtime(), approve.isShown && approve.hasWindowFocus() && dialog.isShowing)) accepted()
        }
        return gate
    }
}
