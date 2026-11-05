package moe.kiwi.thinkos.ui

import moe.kiwi.thinkos.ui.RenderGeometry.Box
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The music player view's own maths, kept free of Android so every hue, size and edge case runs on the JVM:
 * the colours of a cover and the controls around it, the line that describes the audio format, and where each
 * part of the view goes for a given size, style and variant.
 */

/** Colours derived from what is playing. The same track always gets the same cover; every colour clears its contrast target. */
internal object CoverTone {
    const val GRAPHIC = 3.2          // WCAG asks 3:1 for graphics; a little headroom for rounding
    const val TEXT = 4.6             // 4.5:1 for text
    private const val WHITE = 0xffffffff.toInt()
    private const val INK = 0xff0e0f12.toInt()

    /** A stable hue in degrees (FNV-1a over [key]). */
    fun hue(key: String): Float {
        var hash = -2128831035
        for (ch in key) hash = (hash xor ch.code) * 16777619
        return ((hash.toLong() and 0xffffffffL) % 360L).toFloat()
    }

    fun rgb(hue: Float, saturation: Float, lightness: Float): Int {
        val s = saturation.coerceIn(0f, 1f)
        val l = lightness.coerceIn(0f, 1f)
        val chroma = (1 - abs(2 * l - 1)) * s
        val sector = (((hue % 360f) + 360f) % 360f) / 60f
        val x = chroma * (1 - abs(sector % 2 - 1))
        val (r, g, b) = when (sector.toInt()) {
            0 -> Triple(chroma, x, 0f); 1 -> Triple(x, chroma, 0f); 2 -> Triple(0f, chroma, x)
            3 -> Triple(0f, x, chroma); 4 -> Triple(x, 0f, chroma); else -> Triple(chroma, 0f, x)
        }
        val m = l - chroma / 2
        fun channel(v: Float) = ((v + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xff shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    /** Hue and saturation of a colour, for a cover that has a picture: its average colour decides the controls' colour. */
    fun hueSaturation(color: Int): Pair<Float, Float> {
        val r = (color shr 16 and 255) / 255f
        val g = (color shr 8 and 255) / 255f
        val b = (color and 255) / 255f
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val delta = hi - lo
        val lightness = (hi + lo) / 2
        if (delta < 1e-4f) return 0f to 0f
        val saturation = delta / (1 - abs(2 * lightness - 1)).coerceAtLeast(1e-4f)
        val hue = 60f * when (hi) {
            r -> ((g - b) / delta).mod(6f)
            g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        }
        return hue to saturation.coerceIn(0f, 1f)
    }

    fun luminance(color: Int): Double {
        fun linear(v: Int): Double { val s = v / 255.0; return if (s <= .03928) s / 12.92 else ((s + .055) / 1.055).pow(2.4) }
        return .2126 * linear(color shr 16 and 255) + .7152 * linear(color shr 8 and 255) + .0722 * linear(color and 255)
    }

    fun contrast(a: Int, b: Int): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (max(x, y) + .05) / (min(x, y) + .05)
    }

    /** Black or white, whichever reads better on [fill]. */
    fun on(fill: Int): Int = if (contrast(WHITE, fill) >= contrast(INK, fill)) WHITE else INK

    /** The fill of a drawn cover: dark enough that white text on it keeps 4.5:1. */
    fun cover(hue: Float, saturation: Float = .5f): Int {
        var lightness = .46f
        while (lightness > .06f && contrast(WHITE, rgb(hue, saturation, lightness)) < TEXT) lightness -= .01f
        return rgb(hue, saturation, lightness)
    }

    /** [hue] at the lightest (dark pages) or darkest (light pages) tone that still keeps [target] contrast on every one of [backdrops]. */
    fun solve(hue: Float, saturation: Float, backdrops: IntArray, target: Double): Int {
        val s = saturation.coerceIn(.45f, .8f)
        val onLight = backdrops.all { luminance(it) > .4 }
        var lightness = if (onLight) .5f else .66f
        val step = if (onLight) -.01f else .01f
        var color = rgb(hue, s, lightness)
        while (backdrops.any { contrast(color, it) < target } && lightness in .03f..<.98f) {
            lightness += step
            color = rgb(hue, s, lightness)
        }
        return color
    }

    fun accent(hue: Float, saturation: Float, backdrops: IntArray) = solve(hue, saturation, backdrops, GRAPHIC)
    fun accentText(hue: Float, saturation: Float, backdrops: IntArray) = solve(hue, saturation, backdrops, TEXT)
}

/** "FLAC  24-bit  48 kHz" or "MP3  320 kbps  44.1 kHz": only what the decoder actually reported. */
internal object AudioFormatLine {
    private val NAMES = mapOf(
        "flac" to "FLAC", "mpeg" to "MP3", "mp3" to "MP3", "mp4a-latm" to "AAC", "aac" to "AAC", "vorbis" to "Vorbis", "opus" to "Opus",
        "alac" to "ALAC", "ac3" to "AC-3", "eac3" to "E-AC-3", "raw" to "PCM", "wav" to "WAV", "dts" to "DTS", "amr-wb" to "AMR-WB", "3gpp" to "AMR",
    )
    private val LOSSLESS = setOf("flac", "alac", "raw", "wav")

    fun codec(codec: String): String = NAMES[codec.lowercase(Locale.ROOT)] ?: codec.uppercase(Locale.ROOT)

    fun sampleRate(hertz: Int): String {
        if (hertz % 1000 == 0) return "${hertz / 1000} kHz"
        val tenths = (hertz / 100.0).roundToInt()
        return if (tenths % 10 == 0) "${tenths / 10} kHz" else String.format(Locale.ROOT, "%d.%d kHz", tenths / 10, tenths % 10)
    }

    fun of(codec: String, sampleRate: Int, bitDepth: Int, bitrate: Int): String {
        val parts = ArrayList<String>(3)
        if (codec.isNotBlank()) parts += codec(codec.trim())
        val lossless = codec.lowercase(Locale.ROOT) in LOSSLESS
        if (lossless && bitDepth > 0) parts += "$bitDepth-bit"
        else if (!lossless && bitrate > 0) parts += "${(bitrate + 500) / 1000} kbps"
        if (sampleRate > 0) parts += sampleRate(sampleRate)
        return parts.joinToString("  ")
    }
}

/** Where the equal-width tabs of a `tabs` row sit. */
internal object TabsGeometry {
    /** The tab under [x] (0-based), or -1 outside the row; in a right-to-left row the first tab is on the right. */
    fun indexAt(x: Float, width: Int, count: Int, rtl: Boolean): Int {
        if (count <= 0 || width <= 0 || x < 0f || x >= width) return -1
        val slot = (x * count / width).toInt().coerceIn(0, count - 1)
        return if (rtl) count - 1 - slot else slot
    }
}

internal enum class ButtonSet { NONE, PLAY, TRANSPORT, FULL }

/** One rectangle per part of the view; a null part is not shown at this size, style or state. */
internal class NowPlayingLayout(
    val cover: Box? = null, val title: Box? = null, val artist: Box? = null, val format: Box? = null, val notice: Box? = null,
    val idle: Box? = null, val seek: Box? = null, val line: Box? = null, val controls: Box? = null, val volume: Box? = null,
    val queue: Box? = null, val buttons: ButtonSet = ButtonSet.NONE, val titleLines: Int = 1,
    val titleMaxSp: Float = TextScale.TITLE, val titleMinSp: Float = TextScale.BODY, val artistSp: Float = TextScale.BODY,
) {
    fun boxes(): List<Box> = listOfNotNull(cover, title, artist, format, notice, idle, seek, line, controls, volume, queue)
    fun mirrored(width: Int): NowPlayingLayout {
        fun flip(box: Box?) = box?.let { Box(width - it.right, it.top, width - it.left, it.bottom) }
        return NowPlayingLayout(flip(cover), flip(title), flip(artist), flip(format), flip(notice), flip(idle), flip(seek), flip(line),
            flip(controls), flip(volume), flip(queue), buttons, titleLines, titleMaxSp, titleMinSp, artistSp)
    }
}

internal object NowPlayingGeometry {
    // Sizes in dp; text boxes are as tall as their lines, so the auto-sizing text always has room.
    private const val TITLE_LINE = 36f
    private const val ARTIST = 28f
    private const val FORMAT = 20f
    private const val NOTICE = 22f
    private const val SEEK = 48f
    private const val BIG = 64f
    private const val SMALL = 48f
    private const val VOLUME = 44f

    private class Tier(val titleLines: Int, val format: Boolean, val volume: Boolean)
    private val TIERS = listOf(Tier(2, true, true), Tier(1, true, true), Tier(1, false, true), Tier(1, false, false))

    fun layout(style: String, variant: String, width: Int, height: Int, density: Float, rtl: Boolean, hasNotice: Boolean, active: Boolean): NowPlayingLayout {
        val d = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        val w = width.coerceAtLeast(0)
        val h = height.coerceAtLeast(0)
        val dp = { v: Float -> (v * d).roundToInt() }
        val plan = when (style) {
            "bar" -> bar(w, h, dp, active)
            "card" -> card(w, h, dp, active, hasNotice)
            else -> (if (variant == "queue" && active) queue(w, h, dp) else cover(w, h, dp, active, hasNotice)) ?: bar(w, h, dp, active)
        }
        return if (rtl) plan.mirrored(w) else plan
    }

    /** The buttons of a set inside [box], left to right: `shuffle, previous, play, next, repeat` for FULL. */
    fun buttons(set: ButtonSet, box: Box, density: Float): List<Box> {
        val d = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        val dp = { v: Float -> (v * d).roundToInt() }
        val count = when (set) { ButtonSet.NONE -> 0; ButtonSet.PLAY -> 1; ButtonSet.TRANSPORT -> 3; ButtonSet.FULL -> 5 }
        if (count == 0 || box.width <= 0 || box.height <= 0) return emptyList()
        val big = min(box.height, dp(BIG))
        var small = min(box.height, dp(SMALL))
        val playAt = count / 2
        // Squeeze the small buttons before they would touch.
        val room = box.width - big
        if (count > 1 && small * (count - 1) > room) small = (room / (count - 1)).coerceAtLeast(0)
        val sizes = List(count) { if (it == playAt) big else small }
        val free = (box.width - sizes.sum()).coerceAtLeast(0)
        // FULL runs edge to edge with the seek bar above it; the shorter sets sit evenly in the middle.
        val gap = if (set == ButtonSet.FULL) free / (count - 1) else free / (count + 1)
        var x = box.left + if (set == ButtonSet.FULL) 0 else gap
        return sizes.map { size ->
            val top = box.top + (box.height - size) / 2
            Box(x, top, x + size, top + size).also { x += size + gap }
        }
    }

    private fun need(tier: Tier, notice: Boolean, dp: (Float) -> Int): Int =
        dp(tier.titleLines * TITLE_LINE + 2f + ARTIST + (if (tier.format) 2f + FORMAT else 0f) + (if (notice) 6f + NOTICE else 0f) + 12f +
            SEEK + 4f + BIG + (if (tier.volume) 4f + VOLUME else 0f))

    /** The text, then (from the bottom up) volume, buttons and position bar, inside the column ([left], [width]) between [top] and [bottom]. */
    private fun column(left: Int, width: Int, top: Int, bottom: Int, tier: Tier, notice: Boolean, dp: (Float) -> Int, cover: Box?): NowPlayingLayout {
        val right = left + width
        var y = top
        val title = Box(left, y, right, y + dp(tier.titleLines * TITLE_LINE)); y = title.bottom + dp(2f)
        val artist = Box(left, y, right, y + dp(ARTIST)); y = artist.bottom
        var format: Box? = null
        if (tier.format) { y += dp(2f); format = Box(left, y, right, y + dp(FORMAT)); y = format.bottom }
        var noticeBox: Box? = null
        if (notice) { y += dp(6f); noticeBox = Box(left, y, right, y + dp(NOTICE)) }
        var floor = bottom
        var volume: Box? = null
        if (tier.volume) { volume = Box(left, floor - dp(VOLUME), right, floor); floor = volume.top - dp(4f) }
        val controls = Box(left, floor - dp(BIG), right, floor)
        val seek = Box(left, controls.top - dp(4f) - dp(SEEK), right, controls.top - dp(4f))
        return NowPlayingLayout(cover, title, artist, format, noticeBox, null, seek, null, controls, volume, null,
            if (width >= dp(250f)) ButtonSet.FULL else ButtonSet.TRANSPORT, tier.titleLines, 30f, 18f, 20f)
    }

    private fun idleColumn(left: Int, width: Int, top: Int, bottom: Int, notice: Boolean, dp: (Float) -> Int, cover: Box?): NowPlayingLayout {
        val lines = dp(2 * TITLE_LINE)
        val total = lines + if (notice) dp(6f + NOTICE) else 0
        val y = top + ((bottom - top - total) / 2).coerceAtLeast(0)
        val idle = Box(left, y, left + width, y + lines)
        val noticeBox = if (notice) Box(left, idle.bottom + dp(6f), left + width, idle.bottom + dp(6f + NOTICE)) else null
        return NowPlayingLayout(cover = cover, idle = idle, notice = noticeBox, titleLines = 2, titleMaxSp = TextScale.TITLE, titleMinSp = TextScale.BODY)
    }

    /** The full page, `cover` variant: the cover beside (landscape) or above (portrait) the column of text and controls. */
    private fun cover(w: Int, h: Int, dp: (Float) -> Int, active: Boolean, notice: Boolean): NowPlayingLayout? {
        if (w >= h) {
            val gap = dp(28f)
            val side = min(h, w - gap - dp(236f))
            if (side < dp(140f)) return null
            val top = (h - side) / 2
            val cover = Box(0, top, side, top + side)
            val left = side + gap
            if (!active) return idleColumn(left, w - left, 0, h, notice, dp, cover)
            val tier = TIERS.firstOrNull { need(it, notice, dp) <= h } ?: return null
            return column(left, w - left, 0, h, tier, notice, dp, cover)
        }
        val gap = dp(16f)
        if (!active) {
            val side = min(w, h * 6 / 10)
            if (side < dp(140f)) return null
            val cover = Box((w - side) / 2, 0, (w - side) / 2 + side, side)
            return idleColumn(0, w, side + gap, h, notice, dp, cover)
        }
        // Prefer the richest tier as long as the cover stays close to the full width.
        for ((i, tier) in TIERS.withIndex()) {
            val side = min(w, h - gap - need(tier, notice, dp))
            if (side >= w * 7 / 10 || i == TIERS.lastIndex) {
                if (side < dp(140f)) return null
                val cover = Box((w - side) / 2, 0, (w - side) / 2 + side, side)
                return column(0, w, side + gap, h, tier, notice, dp, cover)
            }
        }
        return null
    }

    /** The queue variant: a narrow column with the cover, the title and the controls beside (landscape) or over (portrait) the list. */
    private fun queue(w: Int, h: Int, dp: (Float) -> Int): NowPlayingLayout? {
        val seekH = dp(SEEK)
        val controlsH = dp(56f)
        val gap = dp(4f)
        if (w >= h) {
            val colW = (w * 3 / 10).coerceIn(dp(196f), dp(232f))
            val bottomStack = seekH + gap + controlsH
            val text = dp(2 * 27f + 2 + 22f)
            val side = min(colW, h - bottomStack - text - dp(24f))
            if (side < dp(96f)) return null
            val cover = Box(0, 0, side, side)
            val title = Box(0, side + dp(12f), colW, side + dp(12f) + dp(2 * 27f))
            val artist = Box(0, title.bottom + dp(2f), colW, title.bottom + dp(2f) + dp(22f))
            val controls = Box(0, h - controlsH, colW, h)
            val seek = Box(0, controls.top - gap - seekH, colW, controls.top - gap)
            val list = Box(colW + dp(24f), 0, w, h)
            if (list.width < dp(180f)) return null
            return NowPlayingLayout(cover, title, artist, null, null, null, seek, null, controls, null, list, ButtonSet.TRANSPORT, 2, 22f, 16f, 16f)
        }
        val head = dp(88f)
        val bottomStack = seekH + gap + controlsH
        val cover = Box(0, 0, head, head)
        val textLeft = head + dp(16f)
        val textH = dp(2 * 27f + 2 + 22f)
        val textTop = (head - textH) / 2
        val title = Box(textLeft, textTop, w, textTop + dp(2 * 27f))
        val artist = Box(textLeft, title.bottom + dp(2f), w, title.bottom + dp(2f) + dp(22f))
        val controls = Box(0, h - controlsH, w, h)
        val seek = Box(0, controls.top - gap - seekH, w, controls.top - gap)
        val list = Box(0, head + dp(16f), w, seek.top - dp(12f))
        if (list.height < dp(120f) || textLeft >= w) return null
        return NowPlayingLayout(cover, title, artist, null, null, null, seek, null, controls, null, list, ButtonSet.TRANSPORT, 2, 22f, 16f, 16f)
    }

    /** A home card: cover over (tall card) or beside (wide card) the title, a thin progress line and the three transport buttons. */
    private fun card(w: Int, h: Int, dp: (Float) -> Int, active: Boolean, notice: Boolean): NowPlayingLayout {
        val controlsH = dp(56f)
        val lineH = dp(12f)
        val titleH = dp(48f)
        val artistH = dp(22f)
        val gap = dp(8f)
        if (h >= w * 9 / 10) {
            val rest = titleH + dp(2f) + artistH + gap + lineH + gap + controlsH
            val side = min(w, h - dp(12f) - rest)
            val cover = if (side >= dp(72f)) Box((w - side) / 2, 0, (w - side) / 2 + side, side) else null
            val used = (cover?.let { side + dp(12f) } ?: 0) + (if (active) rest else titleH * 2)
            if (used > h) return bar(w, h, dp, active)
            val offset = ((h - used) / 2).coerceAtLeast(0)
            val shifted = cover?.let { Box(it.left, offset, it.right, offset + it.height) }
            var y = offset + (cover?.let { side + dp(12f) } ?: 0)
            if (!active) {
                val idle = Box(0, y, w, y + titleH * 2)
                return NowPlayingLayout(cover = shifted, idle = idle, titleLines = 2, titleMaxSp = TextScale.BODY, titleMinSp = TextScale.BODY)
            }
            val title = Box(0, y, w, y + titleH); y = title.bottom + dp(2f)
            val artist = Box(0, y, w, y + artistH); y = artist.bottom + gap
            val line = Box(0, y, w, y + lineH); y = line.bottom + gap
            val controls = Box(0, y, w, y + controlsH)
            return NowPlayingLayout(cover = shifted, title = title, artist = artist, line = line, controls = controls,
                buttons = ButtonSet.TRANSPORT, titleLines = 2, titleMaxSp = TextScale.BODY, titleMinSp = TextScale.BODY, artistSp = TextScale.CAPTION)
        }
        val side = min(h, w * 42 / 100)
        val cover = if (side >= dp(72f)) Box(0, (h - side) / 2, side, (h - side) / 2 + side) else null
        val left = (cover?.let { side + dp(16f) } ?: 0)
        val width = w - left
        val rest = titleH + dp(2f) + artistH + gap + lineH + gap + controlsH
        if (rest > h || titleH * 2 > h) return bar(w, h, dp, active)
        if (!active) {
            val top = ((h - titleH * 2) / 2).coerceAtLeast(0)
            return NowPlayingLayout(cover = cover, idle = Box(left, top, w, top + titleH * 2), titleLines = 2, titleMaxSp = TextScale.BODY, titleMinSp = TextScale.BODY)
        }
        var y = ((h - rest) / 2).coerceAtLeast(0)
        val title = Box(left, y, w, y + titleH); y = title.bottom + dp(2f)
        val artist = Box(left, y, w, y + artistH); y = artist.bottom + gap
        val line = Box(left, y, w, y + lineH); y = line.bottom + gap
        val controls = Box(left, y, w, y + controlsH)
        return NowPlayingLayout(cover = cover, title = title, artist = artist, line = line, controls = controls,
            buttons = if (width >= dp(200f)) ButtonSet.TRANSPORT else ButtonSet.PLAY, titleLines = 2, titleMaxSp = TextScale.BODY, titleMinSp = TextScale.BODY, artistSp = TextScale.CAPTION)
    }

    /** The one-line mini player of the library page: thumbnail, two lines of text, the transport buttons and a progress line along the bottom. */
    private fun bar(w: Int, h: Int, dp: (Float) -> Int, active: Boolean): NowPlayingLayout {
        val thumb = min(h - dp(8f), dp(44f)).coerceAtLeast(0)
        val top = (h - thumb) / 2
        val cover = if (thumb >= dp(24f)) Box(0, top, thumb, top + thumb) else null
        val left = (cover?.let { thumb + dp(12f) } ?: 0)
        if (!active) {
            return NowPlayingLayout(idle = Box(0, 0, w, h), titleLines = 1, titleMaxSp = TextScale.BODY, titleMinSp = TextScale.BODY)
        }
        val big = min(h - dp(4f), dp(BIG)).coerceAtLeast(0)
        val room = w - left - dp(120f)            // the text keeps at least this much
        val set = when {
            big < dp(40f) -> ButtonSet.NONE            // a bar too thin to hit a button in
            room >= big + 2 * dp(40f) -> ButtonSet.TRANSPORT
            room >= big -> ButtonSet.PLAY
            else -> ButtonSet.NONE
        }
        val span = when (set) { ButtonSet.TRANSPORT -> min(room, dp(3 * 48f)); ButtonSet.PLAY -> big; else -> 0 }
        val controls = if (set == ButtonSet.NONE) null else Box(w - span, 0, w, h - dp(4f))
        val textRight = (controls?.left ?: w) - dp(8f)
        val half = h / 2
        val title = Box(left, 0, max(left, textRight), half)
        val artist = Box(left, half, max(left, textRight), h - dp(4f))
        val line = Box(0, h - dp(3f), w, h)
        return NowPlayingLayout(cover = cover, title = title, artist = artist, line = line, controls = controls,
            buttons = set, titleLines = 1, titleMaxSp = TextScale.BODY, titleMinSp = TextScale.BODY, artistSp = TextScale.CAPTION)
    }
}
