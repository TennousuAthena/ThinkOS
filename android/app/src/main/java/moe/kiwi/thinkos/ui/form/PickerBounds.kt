package moe.kiwi.thinkos.ui.form

/** Only the part of an editor actually covered by the current visible-window boundary. */
internal object PickerBounds {
    data class Frame(val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class Padding(val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun padding(editor: Frame, visible: Frame): Padding? {
        // Ignore unattached/rotation intermediate frames, rather than collapsing the editor.
        if (maxOf(editor.left, visible.left) >= minOf(editor.right, visible.right) ||
            maxOf(editor.top, visible.top) >= minOf(editor.bottom, visible.bottom)) return null
        return Padding(
            (visible.left - editor.left).coerceAtLeast(0),
            (visible.top - editor.top).coerceAtLeast(0),
            (editor.right - visible.right).coerceAtLeast(0),
            (editor.bottom - visible.bottom).coerceAtLeast(0),
        )
    }
}
