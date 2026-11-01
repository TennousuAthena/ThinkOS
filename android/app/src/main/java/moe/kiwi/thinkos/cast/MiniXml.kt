package moe.kiwi.thinkos.cast

/** One element of a parsed document. [name] keeps its prefix, [local] drops it. */
internal class XmlElement(val name: String, val attributes: Map<String, String>) {
    val local: String get() = name.substringAfter(':')
    val children = ArrayList<XmlElement>()
    private val buffer = StringBuilder()
    val text: String get() = buffer.toString()
    fun append(value: CharSequence) { buffer.append(value) }
    fun child(local: String): XmlElement? = children.firstOrNull { it.local == local }
    fun descendants(local: String): List<XmlElement> =
        children.flatMap { (if (it.local == local) listOf(it) else emptyList()) + it.descendants(local) }
}

/**
 * A deliberately small XML reader for the two shapes casting needs: SOAP envelopes and DIDL-Lite. Senders on the LAN are
 * not trusted, so it refuses what a general parser would have to defend against (document types, entity declarations)
 * and bounds size, depth and element count. It is lenient about one thing real controllers get wrong: a bare `&` in a URL.
 */
internal object MiniXml {
    const val MAX_CHARS = 256 * 1024
    private const val MAX_DEPTH = 24
    private const val MAX_ELEMENTS = 4000

    fun parse(source: String): XmlElement {
        require(source.length <= MAX_CHARS) { "XML too large" }
        return Reader(source).document()
    }

    fun decode(raw: String): String {
        if ('&' !in raw) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '&') { out.append(c); i++; continue }
            val end = raw.indexOf(';', i + 1)
            val entity = if (end in i + 2..i + 10) raw.substring(i + 1, end) else null
            val value = when {
                entity == null -> null
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "amp" -> "&"
                entity == "quot" -> "\""
                entity == "apos" -> "'"
                entity.startsWith("#x") || entity.startsWith("#X") -> entity.substring(2).toIntOrNull(16)?.let(::codePoint)
                entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.let(::codePoint)
                else -> null
            }
            // A "&" that starts no entity stays a "&": "…?a=1&b=2" is not valid XML, and it is what some controllers send.
            if (value == null) { out.append('&'); i++ } else { out.append(value); i = end + 1 }
        }
        return out.toString()
    }

    private fun codePoint(value: Int): String? =
        if (value in 0x20..0x10FFFF && value !in 0xD800..0xDFFF || value == 9 || value == 10 || value == 13) String(Character.toChars(value)) else null

    private class Reader(private val s: String) {
        private var i = 0
        private var elements = 0

        private fun fail(message: String): Nothing = throw IllegalArgumentException("Bad XML: $message at $i")

        fun document(): XmlElement {
            if (s.startsWith("\uFEFF")) i = 1
            skipMisc()
            if (i >= s.length || s[i] != '<') fail("no root element")
            val root = element(0)
            skipMisc()
            return root
        }

        /** Whitespace, processing instructions and comments; document types and entity declarations are refused. */
        private fun skipMisc() {
            while (i < s.length) {
                when {
                    s[i].isWhitespace() -> i++
                    s.startsWith("<?", i) -> i = after("?>")
                    s.startsWith("<!--", i) -> i = after("-->")
                    s.startsWith("<!", i) && !s.startsWith("<![CDATA[", i) -> fail("DOCTYPE and ENTITY are not accepted")
                    else -> return
                }
            }
        }

        private fun after(token: String): Int {
            val at = s.indexOf(token, i)
            if (at < 0) fail("unterminated $token")
            return at + token.length
        }

        private fun name(): String {
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] in ":_-.")) i++
            if (i == start) fail("expected a name")
            return s.substring(start, i)
        }

        private fun element(depth: Int): XmlElement {
            if (depth > MAX_DEPTH) fail("nested too deeply")
            if (++elements > MAX_ELEMENTS) fail("too many elements")
            i++ // <
            val tag = name()
            val attributes = linkedMapOf<String, String>()
            while (true) {
                while (i < s.length && s[i].isWhitespace()) i++
                if (i >= s.length) fail("unterminated tag")
                if (s[i] == '>') { i++; break }
                if (s.startsWith("/>", i)) { i += 2; return XmlElement(tag, attributes) }
                val key = name()
                while (i < s.length && s[i].isWhitespace()) i++
                if (i >= s.length || s[i] != '=') fail("attribute without a value")
                i++
                while (i < s.length && s[i].isWhitespace()) i++
                val quote = s.getOrNull(i)
                if (quote != '"' && quote != '\'') fail("unquoted attribute")
                val end = s.indexOf(quote, i + 1)
                if (end < 0) fail("unterminated attribute")
                if (attributes.size >= 32) fail("too many attributes")
                attributes[key] = decode(s.substring(i + 1, end))
                i = end + 1
            }
            val node = XmlElement(tag, attributes)
            while (true) {
                if (i >= s.length) fail("unterminated element <$tag>")
                val next = s.indexOf('<', i)
                if (next < 0) fail("unterminated element <$tag>")
                if (next > i) node.append(decode(s.substring(i, next)))
                i = next
                when {
                    s.startsWith("</", i) -> {
                        i += 2
                        if (name() != tag) fail("closing tag does not match <$tag>")
                        while (i < s.length && s[i].isWhitespace()) i++
                        if (i >= s.length || s[i] != '>') fail("malformed closing tag")
                        i++
                        return node
                    }
                    s.startsWith("<!--", i) -> i = after("-->")
                    s.startsWith("<![CDATA[", i) -> {
                        val end = s.indexOf("]]>", i)
                        if (end < 0) fail("unterminated CDATA")
                        node.append(s.substring(i + 9, end))
                        i = end + 3
                    }
                    s.startsWith("<?", i) -> i = after("?>")
                    s.startsWith("<!", i) -> fail("DOCTYPE and ENTITY are not accepted")
                    else -> node.children += element(depth + 1)
                }
            }
        }
    }
}
