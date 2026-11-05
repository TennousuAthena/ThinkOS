package moe.kiwi.thinkos.ui

/** Presentation only. Bundled legal originals stay byte-for-byte unchanged. */
internal object LegalText {
    fun display(file: String, source: String): String {
        if (!file.endsWith(".md")) return source
        // The native header already names this document. Only its top-level
        // notice title is redundant; canonical license headings stay intact.
        val body = if (file == "THIRD_PARTY_NOTICES.md") source.replaceFirst(noticeTitle, "") else source
        return plain(body)
    }

    private val noticeTitle = Regex("\\A(?:[\\t ]*\\r?\\n)* {0,3}#\\s+[^\\r\\n]+(?:\\r?\\n|\\z)")
    private val reference = Regex("^\\s*\\[([^]]+)]:\\s*(\\S+)(?:\\s+.*)?$")
    private val rule = Regex("^\\s*(?:[-*_]\\s*){3,}$")
    private val heading = Regex("^ {0,3}#{1,6}\\s+(.+?)(?:\\s+#+)?$")
    private val separator = Regex(":?-{3,}:?")
    private val setext = Regex("={3,}")
    private val blankLines = Regex("\n{3,}")
    private val quote = Regex("^\\s*(?:>\\s*)+")
    private val bullet = Regex("^(\\s*)[-+*]\\s+")
    private val codeSpan = Regex("(`+)(.+?)\\1")
    private val referenceLink = Regex("\\[([^]]+)]\\[([^]]*)]")
    private val autoLink = Regex("<(https?://[^>]+)>")
    private val lineBreak = Regex("(?i)<br\\s*/?>")
    private val emphasis = listOf("\\*\\*([^*\\n]+)\\*\\*", "__([^_\\n]+)__",
        "(?<![\\p{L}\\p{N}\\\\])\\*([^*\\n]+)\\*(?![\\p{L}\\p{N}])", "(?<![\\p{L}\\p{N}\\\\])_([^_\\n]+)_(?![\\p{L}\\p{N}])").map(::Regex)
    private val escaped = Regex("\\\\([\\\\`*_{}\\[\\]()#+.!>|-])")
    private val placeholder = Regex("\uE000([0-9]+)\uE001")

    internal fun plain(source: String): String {
        val definitions = mutableMapOf<String, String>()
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').lines().filter { line ->
            val match = reference.matchEntire(line)
            if (match != null) definitions[match.groupValues[1].lowercase(java.util.Locale.ROOT)] = match.groupValues[2]
            match == null
        }
        val output = mutableListOf<String>()
        var index = 0
        var fence: String? = null
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val marker = trimmed.takeWhile { it == trimmed[0] }
                if (fence == null) fence = marker else if (marker[0] == fence[0] && marker.length >= fence.length) fence = null
                output.add(""); index++; continue
            }
            if (fence != null) { output.add(line); index++; continue }
            if (index + 1 < lines.size && line.contains('|') && cells(lines[index + 1]).let { it.isNotEmpty() && it.all(separator::matches) }) {
                val headers = cells(line).map { inline(it, definitions) }
                index += 2
                while (index < lines.size && lines[index].contains('|')) {
                    val values = cells(lines[index])
                    if (values.size != headers.size) break
                    output.add(inline(values[0], definitions))
                    for (column in 1 until values.size) {
                        val value = inline(values[column], definitions)
                        if (value.isNotEmpty()) output.add("${headers[column]}：$value")
                    }
                    output.add(""); index++
                }
                continue
            }
            val title = heading.matchEntire(line)
            output.add(when {
                title != null -> inline(title.groupValues[1], definitions)
                rule.matches(line) || setext.matches(line.trim()) -> ""
                else -> inline(line.replace(quote, "").replace(bullet, "$1• "), definitions)
            })
            index++
        }
        return output.joinToString("\n").replace(blankLines, "\n\n").trim()
    }

    private fun cells(line: String): List<String> {
        val result = mutableListOf<String>()
        val cell = StringBuilder()
        var code = false
        var escaped = false
        for (char in line.trim().removePrefix("|").removeSuffix("|")) {
            when {
                escaped -> { cell.append(char); escaped = false }
                char == '\\' -> { cell.append(char); escaped = true }
                char == '`' -> { code = !code; cell.append(char) }
                char == '|' && !code -> { result.add(cell.toString().trim()); cell.setLength(0) }
                else -> cell.append(char)
            }
        }
        result.add(cell.toString().trim())
        return result
    }

    private fun inline(source: String, references: Map<String, String>, depth: Int = 0): String {
        if (depth > 4) return source
        val literals = mutableListOf<String>()
        fun keep(value: String): String { literals.add(value); return "\uE000${literals.lastIndex}\uE001" }
        var text = codeSpan.replace(source) { keep(it.groupValues[2]) }
        // Balanced destinations preserve parentheses in URLs rather than cutting them at the first ')'.
        val linked = StringBuilder()
        var at = 0
        while (at < text.length) {
            if (text[at] == '[') {
                val end = text.indexOf("](", at + 1)
                if (end >= 0) {
                    var cursor = end + 2
                    var nesting = 1
                    while (cursor < text.length && nesting > 0) {
                        if (text[cursor] == '(') nesting++ else if (text[cursor] == ')') nesting--
                        cursor++
                    }
                    if (nesting == 0) {
                        val label = inline(text.substring(at + 1, end), references, depth + 1)
                        val url = text.substring(end + 2, cursor - 1).trim().removeSurrounding("<", ">")
                        linked.append(keep(if (label == url) url else "$label（$url）")); at = cursor; continue
                    }
                }
            }
            linked.append(text[at++])
        }
        text = linked.toString()
        text = referenceLink.replace(text) { match ->
            val label = match.groupValues[1]
            val key = match.groupValues[2].ifEmpty { label }.lowercase(java.util.Locale.ROOT)
            references[key]?.let { keep("${inline(label, references, depth + 1)}（$it）") } ?: match.value
        }
        text = autoLink.replace(text) { keep(it.groupValues[1]) }
        text = text.replace(lineBreak, "\n")
        for (pattern in emphasis) text = pattern.replace(text, "$1")
        text = escaped.replace(text, "$1")
        // Nested placeholders can occur when a code span is inside a link label.
        repeat(4) {
            text = placeholder.replace(text) { literals.getOrNull(it.groupValues[1].toInt()) ?: it.value }
        }
        return text
    }
}
