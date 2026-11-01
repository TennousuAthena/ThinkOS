package moe.kiwi.thinkos.cast.airplay

import moe.kiwi.thinkos.cast.MiniXml
import moe.kiwi.thinkos.cast.XmlElement
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Property lists, the way AirPlay speaks them: Apple's binary format (`bplist00`) and its XML form.
 *
 * Values are plain Kotlin: `Map<String, Any?>`, `List<Any?>`, `String`, `Long`, `Double`, `Boolean`, `ByteArray`. A date reads as
 * seconds since 2001-01-01 (a `Double`), a null as null. Everything that arrives is from a phone on the network and is not
 * trusted: the reader bounds size, object count, nesting and total work, and refuses what it does not understand.
 */
internal object Plist {
    const val MAX_BYTES = 1 shl 20
    private const val MAX_OBJECTS = 20_000
    private const val MAX_DEPTH = 24
    private const val MAX_VISITS = 100_000

    class PlistError(message: String) : IllegalArgumentException(message)

    /** Binary or XML, by looking at the first bytes. */
    fun read(bytes: ByteArray): Map<String, Any?> {
        if (bytes.size > MAX_BYTES) throw PlistError("Property list too large")
        val value = if (bytes.size >= 8 && String(bytes, 0, 6, Charsets.ISO_8859_1) == "bplist") Binary(bytes).root() else readXml(String(bytes, Charsets.UTF_8))
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?> ?: throw PlistError("A dictionary was expected")
    }

    // ---- binary -----------------------------------------------------------------------------------------------------------------

    private class Binary(private val d: ByteArray) {
        private var offsetSize = 0
        private var refSize = 0
        private var count = 0
        private var tableAt = 0
        private var visits = 0

        fun root(): Any? {
            if (String(d, 0, 8, Charsets.ISO_8859_1) != "bplist00") throw PlistError("Not a version 0 binary plist")
            if (d.size < 8 + 32) throw PlistError("Binary plist too short")
            val t = d.size - 32
            offsetSize = d[t + 6].toInt() and 0xff
            refSize = d[t + 7].toInt() and 0xff
            val n = number(t + 8, 8)
            val top = number(t + 16, 8)
            val table = number(t + 24, 8)
            if (offsetSize !in 1..8 || refSize !in 1..8 || n !in 1..MAX_OBJECTS || top !in 0 until n) throw PlistError("Bad binary plist trailer")
            if (table < 8 || table + n * offsetSize > t) throw PlistError("Bad offset table")
            count = n.toInt(); tableAt = table.toInt()
            return value(top.toInt(), 0)
        }

        private fun number(at: Int, size: Int): Long {
            if (at < 0 || at + size > d.size) throw PlistError("Binary plist read out of range")
            var v = 0L
            for (i in 0 until size) v = (v shl 8) or (d[at + i].toLong() and 0xff)
            return v
        }

        private fun offsetOf(index: Int): Int {
            if (index !in 0 until count) throw PlistError("Bad object reference")
            val o = number(tableAt + index * offsetSize, offsetSize)
            if (o < 8 || o >= d.size - 32) throw PlistError("Bad object offset")
            return o.toInt()
        }

        private fun length(at: Int, nibble: Int): Pair<Int, Int> {
            if (nibble != 0xf) return nibble to at
            if (at >= d.size) throw PlistError("Truncated length")
            val marker = d[at].toInt() and 0xff
            if (marker shr 4 != 1) throw PlistError("Bad length marker")
            val size = 1 shl (marker and 0xf)
            if (size > 8) throw PlistError("Bad length size")
            val v = number(at + 1, size)
            if (v !in 0..MAX_BYTES) throw PlistError("Length out of range")
            return v.toInt() to at + 1 + size
        }

        fun value(index: Int, depth: Int): Any? {
            if (depth > MAX_DEPTH) throw PlistError("Property list nested too deep")
            if (++visits > MAX_VISITS) throw PlistError("Property list too complex")
            val at = offsetOf(index)
            val marker = d[at].toInt() and 0xff
            val kind = marker shr 4
            val low = marker and 0xf
            return when (kind) {
                0x0 -> when (low) { 0x0 -> null; 0x8 -> false; 0x9 -> true; else -> throw PlistError("Unsupported simple value") }
                0x1 -> { val size = 1 shl low; if (size > 8) throw PlistError("Integer too large"); number(at + 1, size) }
                0x2 -> when (low) {
                    2 -> java.lang.Float.intBitsToFloat(number(at + 1, 4).toInt()).toDouble()
                    3 -> java.lang.Double.longBitsToDouble(number(at + 1, 8))
                    else -> throw PlistError("Unsupported real size")
                }
                0x3 -> java.lang.Double.longBitsToDouble(number(at + 1, 8))
                0x4 -> { val (n, start) = length(at + 1, low); slice(start, n) }
                0x5 -> { val (n, start) = length(at + 1, low); String(slice(start, n), Charsets.US_ASCII) }
                0x6 -> { val (n, start) = length(at + 1, low); if (n > MAX_BYTES / 2) throw PlistError("String too long"); String(slice(start, n * 2), Charsets.UTF_16BE) }
                0xA -> {
                    val (n, start) = length(at + 1, low)
                    if (n > count) throw PlistError("Array longer than the document")
                    List(n) { value(number(start + it * refSize, refSize).toInt(), depth + 1) }
                }
                0xD -> {
                    val (n, start) = length(at + 1, low)
                    if (n > count) throw PlistError("Dictionary larger than the document")
                    val map = LinkedHashMap<String, Any?>(n * 2)
                    for (i in 0 until n) {
                        val key = value(number(start + i * refSize, refSize).toInt(), depth + 1) as? String ?: throw PlistError("A dictionary key is not text")
                        map[key] = value(number(start + (n + i) * refSize, refSize).toInt(), depth + 1)
                    }
                    map
                }
                else -> throw PlistError("Unsupported object type $kind")
            }
        }

        private fun slice(start: Int, n: Int): ByteArray {
            if (start < 0 || n < 0 || start + n > d.size - 32) throw PlistError("Binary plist slice out of range")
            return d.copyOfRange(start, start + n)
        }
    }

    // ---- XML ---------------------------------------------------------------------------------------------------------------------

    private val DOCTYPE = Regex("<!DOCTYPE\\s+plist\\s+PUBLIC\\s+\"[^\"]*\"\\s+\"[^\"]*\"\\s*>")

    private fun readXml(text: String): Any? {
        // The one document type every plist carries; anything else is still refused by the XML reader.
        val root = MiniXml.parse(DOCTYPE.replaceFirst(text, ""))
        if (root.local != "plist") throw PlistError("Not a property list")
        val first = root.children.firstOrNull() ?: throw PlistError("Empty property list")
        return xmlValue(first, 0)
    }

    private fun xmlValue(e: XmlElement, depth: Int): Any? {
        if (depth > MAX_DEPTH) throw PlistError("Property list nested too deep")
        return when (e.local) {
            "dict" -> {
                val out = LinkedHashMap<String, Any?>()
                var key: String? = null
                for (c in e.children) {
                    if (c.local == "key") key = MiniXml.decode(c.text)
                    else { out[key ?: throw PlistError("A value has no key")] = xmlValue(c, depth + 1); key = null }
                }
                out
            }
            "array" -> e.children.map { xmlValue(it, depth + 1) }
            "string" -> MiniXml.decode(e.text)
            "integer" -> e.text.trim().toLongOrNull() ?: throw PlistError("Bad integer")
            "real" -> e.text.trim().toDoubleOrNull() ?: throw PlistError("Bad real")
            "true" -> true
            "false" -> false
            "data" -> try { Base64.getMimeDecoder().decode(e.text.trim()) } catch (error: IllegalArgumentException) { throw PlistError("Bad data") }
            "date" -> 0.0
            else -> throw PlistError("Unsupported element ${e.local}")
        }
    }

    // ---- writing -----------------------------------------------------------------------------------------------------------------

    fun xml(value: Map<String, Any?>): ByteArray {
        val out = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n<plist version=\"1.0\">\n")
        writeXml(out, value)
        return out.append("</plist>\n").toString().toByteArray(Charsets.UTF_8)
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun writeXml(out: StringBuilder, v: Any?) {
        when (v) {
            is Map<*, *> -> { out.append("<dict>\n"); v.forEach { (k, x) -> out.append("<key>").append(escape(k.toString())).append("</key>\n"); writeXml(out, x) }; out.append("</dict>\n") }
            is List<*> -> { out.append("<array>\n"); v.forEach { writeXml(out, it) }; out.append("</array>\n") }
            is String -> out.append("<string>").append(escape(v)).append("</string>\n")
            is Boolean -> out.append(if (v) "<true/>\n" else "<false/>\n")
            is Int -> out.append("<integer>").append(v).append("</integer>\n")
            is Long -> out.append("<integer>").append(v).append("</integer>\n")
            is Float -> out.append("<real>").append(v.toDouble()).append("</real>\n")
            is Double -> out.append("<real>").append(v).append("</real>\n")
            is ByteArray -> out.append("<data>").append(Base64.getEncoder().encodeToString(v)).append("</data>\n")
            null -> out.append("<string></string>\n")
            else -> throw PlistError("Cannot write ${v.javaClass.simpleName}")
        }
    }

    /** `bplist00`, with each distinct object written once per use (no sharing): the documents here are small. */
    fun binary(value: Map<String, Any?>): ByteArray {
        val objects = ArrayList<Any?>()
        val refs = HashMap<Int, IntArray>()          // index of a container -> indices of its children (keys then values for a dictionary)
        fun add(v: Any?): Int {
            val index = objects.size
            objects += v
            when (v) {
                is Map<*, *> -> {
                    val keys = v.keys.map { add(it.toString()) }
                    val values = v.values.map { add(it) }
                    refs[index] = (keys + values).toIntArray()
                }
                is List<*> -> refs[index] = v.map { add(it) }.toIntArray()
            }
            return index
        }
        add(value)
        val refSize = if (objects.size < 256) 1 else 2
        val body = ByteArrayOutputStream()
        body.write("bplist00".toByteArray(Charsets.US_ASCII))
        val offsets = IntArray(objects.size)
        fun writeInt(out: ByteArrayOutputStream, v: Long, size: Int) { for (i in size - 1 downTo 0) out.write(((v shr (8 * i)) and 0xff).toInt()) }
        fun header(out: ByteArrayOutputStream, kind: Int, n: Int) {
            if (n < 15) out.write((kind shl 4) or n)
            else { out.write((kind shl 4) or 0xf); out.write(0x11); writeInt(out, n.toLong(), 2) }
        }
        for ((i, v) in objects.withIndex()) {
            offsets[i] = body.size()
            when (v) {
                null -> body.write(0x00)
                is Boolean -> body.write(if (v) 0x09 else 0x08)
                is Int, is Long -> {
                    val n = (v as Number).toLong()
                    when {
                        n in 0..0xff -> { body.write(0x10); writeInt(body, n, 1) }
                        n in 0..0xffff -> { body.write(0x11); writeInt(body, n, 2) }
                        n in 0..0xffffffffL -> { body.write(0x12); writeInt(body, n, 4) }
                        else -> { body.write(0x13); writeInt(body, n, 8) }
                    }
                }
                is Float, is Double -> { body.write(0x23); writeInt(body, java.lang.Double.doubleToRawLongBits((v as Number).toDouble()), 8) }
                is String -> if (v.all { it.code < 0x80 }) { header(body, 5, v.length); body.write(v.toByteArray(Charsets.US_ASCII)) }
                             else { header(body, 6, v.length); body.write(v.toByteArray(Charsets.UTF_16BE)) }
                is ByteArray -> { header(body, 4, v.size); body.write(v) }
                is List<*> -> { header(body, 0xA, v.size); refs.getValue(i).forEach { writeInt(body, it.toLong(), refSize) } }
                is Map<*, *> -> { header(body, 0xD, v.size); refs.getValue(i).forEach { writeInt(body, it.toLong(), refSize) } }
                else -> throw PlistError("Cannot write ${v.javaClass.simpleName}")
            }
        }
        val tableAt = body.size()
        for (o in offsets) writeInt(body, o.toLong(), 4)
        repeat(6) { body.write(0) }
        body.write(4); body.write(refSize)
        writeInt(body, objects.size.toLong(), 8); writeInt(body, 0, 8); writeInt(body, tableAt.toLong(), 8)
        return body.toByteArray()
    }
}
