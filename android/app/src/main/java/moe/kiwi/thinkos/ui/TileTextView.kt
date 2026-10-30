package moe.kiwi.thinkos.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.TextView

/**
 * TextView retains shaping, autosize, ellipsis and accessibility. Repeated tile text shares its
 * alpha raster so a simultaneous black/white contrast switch doesn't reshape 16 badges. Only the
 * tile's plain single-line text uses this cache; forms and editable text do not.
 */
internal class TileTextView(context: Context) : TextView(context) {
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0 || text.isEmpty()) return
        val key =
            TileTextCache.Key(
                text.toString(),
                width,
                height,
                textSize,
                textScaleX,
                typeface,
                gravity,
                layoutDirection,
                resources.configuration.locales.toLanguageTags(),
                includeFontPadding,
                paddingLeft,
                paddingTop,
                paddingRight,
                paddingBottom,
            )
        val bitmap =
            TileTextCache.get(key) {
                Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8).also {
                    it.density = Bitmap.DENSITY_NONE
                    super.onDraw(Canvas(it))
                }
            }
        brush.color = currentTextColor
        canvas.drawBitmap(bitmap, 0f, 0f, brush)
    }
}

private object TileTextCache {
    data class Key(
        val text: String,
        val width: Int,
        val height: Int,
        val size: Float,
        val scale: Float,
        val typeface: android.graphics.Typeface?,
        val gravity: Int,
        val direction: Int,
        val locale: String,
        val fontPadding: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private val entries = LinkedHashMap<Key, Bitmap>(64, .75f, true)
    private var bytes = 0

    fun get(key: Key, create: () -> Bitmap): Bitmap {
        entries[key]?.let {
            return it
        }
        val value = create()
        entries[key] = value
        bytes += value.allocationByteCount
        while (entries.size > 128 || bytes > 2 * 1024 * 1024) {
            val oldest = entries.keys.first()
            bytes -= entries.remove(oldest)!!.allocationByteCount
        }
        return value
    }
}
