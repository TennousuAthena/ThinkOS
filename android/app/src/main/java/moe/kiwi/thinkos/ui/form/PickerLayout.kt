package moe.kiwi.thinkos.ui.form

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout

/** Keeps only full-screen form pickers clear of visible system bars and the keyboard. */
internal open class PickerLayout(context: Context) : LinearLayout(context),
    ViewTreeObserver.OnGlobalLayoutListener, ViewTreeObserver.OnPreDrawListener {
    private val visibleFrame = Rect()
    private val location = IntArray(2)
    private var observedTree: ViewTreeObserver? = null
    private val update = Runnable { refreshVisibleFrame() }
    private val fieldBounds = Rect()

    init {
        // Somewhere for the focus to go when the search box lets it go, so the keyboard does not come back by itself.
        isFocusable = true; isFocusableInTouchMode = true; defaultFocusHighlightEnabled = false
    }

    /** Puts the keyboard away and the focus on the picker, so the list can be used. Nobody has to reach for Back. */
    fun dismissKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(windowToken, 0)
        if (findFocus() is EditText) requestFocus()
    }

    /** A touch anywhere but the search box puts the keyboard away; the touch itself still goes where it was aimed. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) (findFocus() as? EditText)?.let { field ->
            if (!field.getGlobalVisibleRect(fieldBounds) || !fieldBounds.contains(event.rawX.toInt(), event.rawY.toInt())) {
                dismissKeyboard()
                onKeyboardPutAwayByTouch()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    /** The keyboard was put away by a touch elsewhere (not by its own search key); a picker with a search box can search now. */
    protected open fun onKeyboardPutAwayByTouch() = Unit

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        visibleFrame.setEmpty()
        observedTree = viewTreeObserver.also {
            it.addOnGlobalLayoutListener(this)
            it.addOnPreDrawListener(this)
        }
        requestApplyInsets()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val result = super.onApplyWindowInsets(insets)
        // Insets can arrive before the new size/origin has been laid out after rotation.
        removeCallbacks(update)
        post(update)
        return result
    }

    override fun onGlobalLayout() = refreshVisibleFrame()

    private fun refreshVisibleFrame() {
        if (!isAttachedToWindow || width <= 0 || height <= 0) return
        // This API27 query uses IPC; run it for layout/insets, never for each draw.
        getWindowVisibleDisplayFrame(visibleFrame)
        updateSafeArea()
    }

    override fun onPreDraw(): Boolean {
        // Burn-in/transition translations do not trigger layout. Recheck their screen origin
        // against the cached visible frame, and lay out changed padding before drawing it.
        return !updateSafeArea() && !isLayoutRequested
    }

    private fun updateSafeArea(): Boolean {
        if (!isAttachedToWindow || width <= 0 || height <= 0 || visibleFrame.isEmpty) return false
        // Compare screen coordinates so already-inset or resized parents are not inset twice.
        getLocationOnScreen(location)
        val next = PickerBounds.padding(
            PickerBounds.Frame(location[0], location[1], location[0] + width, location[1] + height),
            PickerBounds.Frame(visibleFrame.left, visibleFrame.top, visibleFrame.right, visibleFrame.bottom),
        ) ?: return false
        if (paddingLeft != next.left || paddingTop != next.top ||
            paddingRight != next.right || paddingBottom != next.bottom) {
            setPadding(next.left, next.top, next.right, next.bottom)
            return true
        }
        return false
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(update)
        observedTree?.takeIf { it.isAlive }?.let {
            it.removeOnGlobalLayoutListener(this)
            it.removeOnPreDrawListener(this)
        }
        observedTree = null
        visibleFrame.setEmpty()
        setPadding(0, 0, 0, 0)
        super.onDetachedFromWindow()
    }
}
