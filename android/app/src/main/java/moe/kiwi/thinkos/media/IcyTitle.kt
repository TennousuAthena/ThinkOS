package moe.kiwi.thinkos.media

/** Bound sender-provided ICY display text; the extractor owns protocol parsing. */
internal fun icyTitle(value: String): String? {
    val text = value.replace(Regex("[\\p{Cc}\\p{Zl}\\p{Zp}]+"), " ").trim()
    if (text.isEmpty()) return null
    val end = minOf(text.length, 256).let { if (it < text.length && text[it - 1].isHighSurrogate()) it - 1 else it }
    return text.substring(0, end)
}
