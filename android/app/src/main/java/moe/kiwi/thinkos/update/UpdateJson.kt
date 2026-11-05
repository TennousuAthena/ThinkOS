package moe.kiwi.thinkos.update

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Strict UTF-8 JSON for authenticated update documents and APK-owned keys.
 * Android 8's JSONObject text reader is deliberately permissive; do not use it on wire bytes. */
internal object UpdateJson {
    fun read(bytes: ByteArray, maxBytes: Int): JSONObject {
        require(bytes.isNotEmpty() && bytes.size <= maxBytes) { "Update JSON size rejected" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        return Reader(text).read()
    }

    private class Reader(private val text: String) {
        private var position = 0
        private var nodes = 0
        fun read(): JSONObject {
            val result = value(0)
            whitespace()
            require(position == text.length && result is JSONObject) { "Expected exactly one update JSON object" }
            return result
        }
        private fun whitespace() { while (position < text.length && text[position] in " \t\r\n") position++ }
        private fun consume(char: Char): Boolean {
            whitespace()
            return if (position < text.length && text[position] == char) { position++; true } else false
        }
        private fun expect(char: Char) { require(consume(char)) { "Malformed update JSON" } }
        private fun value(depth: Int): Any {
            require(depth <= 16 && ++nodes <= 16_384) { "Update JSON nesting or node limit exceeded" }
            whitespace()
            require(position < text.length) { "Incomplete update JSON" }
            return when (text[position]) {
                '{' -> {
                    position++
                    JSONObject().apply {
                        if (!consume('}')) {
                            do {
                                whitespace()
                                require(position < text.length && text[position] == '"') { "Expected quoted JSON key" }
                                val key = string()
                                require(!has(key)) { "Duplicate update JSON key" }
                                expect(':'); put(key, value(depth + 1))
                                if (consume('}')) break
                                expect(',')
                            } while (true)
                        }
                    }
                }
                '[' -> {
                    position++
                    JSONArray().apply {
                        if (!consume(']')) {
                            do {
                                put(value(depth + 1))
                                if (consume(']')) break
                                expect(',')
                            } while (true)
                        }
                    }
                }
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", JSONObject.NULL)
                '-', in '0'..'9' -> number()
                else -> throw IllegalArgumentException("Invalid update JSON token")
            }
        }
        private fun literal(token: String, value: Any): Any {
            require(text.startsWith(token, position)) { "Invalid update JSON literal" }
            position += token.length
            return value
        }
        private fun number(): Long {
            val start = position
            if (text[position] == '-') position++
            require(position < text.length) { "Incomplete update integer" }
            if (text[position] == '0') position++ else {
                require(text[position] in '1'..'9') { "Invalid update integer" }
                while (position < text.length && text[position] in '0'..'9') position++
            }
            require(position == text.length || text[position] !in ".eE") { "Update fields require decimal integer tokens" }
            return text.substring(start, position).toLongOrNull() ?: throw IllegalArgumentException("Update integer overflow")
        }
        private fun string(): String {
            require(text[position++] == '"')
            val result = StringBuilder()
            while (position < text.length) {
                when (val char = text[position++]) {
                    '"' -> {
                        var i = 0
                        while (i < result.length) {
                            val next = result[i++]
                            if (next.isHighSurrogate()) require(i < result.length && result[i++].isLowSurrogate()) { "Unpaired JSON surrogate" }
                            else require(!next.isLowSurrogate()) { "Unpaired JSON surrogate" }
                        }
                        return result.toString()
                    }
                    '\\' -> {
                        require(position < text.length) { "Incomplete JSON escape" }
                        result.append(when (val escaped = text[position++]) {
                            '"', '\\', '/' -> escaped
                            'b' -> '\b'
                            'f' -> '\u000c'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> {
                                require(position + 4 <= text.length) { "Incomplete JSON unicode escape" }
                                val hex = text.substring(position, position + 4)
                                require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid JSON unicode escape" }
                                position += 4
                                hex.toInt(16).toChar()
                            }
                            else -> throw IllegalArgumentException("Invalid JSON escape")
                        })
                    }
                    else -> { require(char >= ' ') { "Unescaped JSON control character" }; result.append(char) }
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }
    }
}
