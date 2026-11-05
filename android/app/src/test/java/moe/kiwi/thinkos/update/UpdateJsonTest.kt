package moe.kiwi.thinkos.update

import org.junit.Assert.*
import org.junit.Test

class UpdateJsonTest {
    private fun read(value: String) = UpdateJson.read(value.toByteArray(Charsets.UTF_8), 1024 * 1024)

    @Test fun validUnicodeEscapesExactInt64AndWhitespaceHaveOneInterpretation() {
        val result = read(" \t{\"min\":-9223372036854775808,\"max\":9223372036854775807,\"str\":\"中文\\n\\uD83D\\uDE00\",\"array\":[true,false,null,{}]}\r\n")
        assertEquals(Long.MIN_VALUE, result.get("min")); assertEquals(Long.MAX_VALUE, result.get("max"))
        assertEquals("中文\n😀", result.getString("str")); assertEquals(4, result.getJSONArray("array").length())
    }

    @Test fun duplicateKeysIncludingEscapedNamesAndNestedNullValuesAreRejected() {
        for (value in listOf("{\"seq\":1,\"seq\":2}", "{\"seq\":1,\"\\u0073eq\":2}", "{\"a\":{\"k\":null,\"k\":1}}")) {
            assertThrows(IllegalArgumentException::class.java) { read(value) }
        }
    }

    @Test fun permissiveAndroidJsonExtensionsAndAmbiguousNumbersAreRejected() {
        for (value in listOf("{a:1}", "{'a':1}", "{\"a\":1,}", "{\"a\":[1,]}", "{/*x*/\"a\":1}", "{\"a\":01}",
            "{\"a\":0x10}", "{\"a\":1.0}", "{\"a\":1e0}", "{\"a\":+1}", "{\"a\":NaN}", "{\"a\":9223372036854775808}",
            "{\"a\":-9223372036854775809}", "{}{}", "[]", "true", "\uFEFF{}", "{\"a\":truex}", "{\"a\":\"raw\nline\"}")) {
            assertThrows(value, IllegalArgumentException::class.java) { read(value) }
        }
    }

    @Test fun malformedUtf8SurrogatesEscapesAndIncompleteDocumentsAreRejected() {
        for (value in listOf("{\"x\":\"\\uD800\"}", "{\"x\":\"\\uDC00\"}", "{\"x\":\"\\q\"}", "{\"x\":\"\\uZZZZ\"}", "{", "{\"x\":", "{\"x\":\"")) {
            assertThrows(IllegalArgumentException::class.java) { read(value) }
        }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { UpdateJson.read(byteArrayOf(0xc0.toByte(), 0xaf.toByte()), 10) }
    }

    @Test fun byteDepthAndNodeLimitsBoundParsingBeforeSchemaValidation() {
        assertThrows(IllegalArgumentException::class.java) { UpdateJson.read("{}".toByteArray(), 1) }
        assertThrows(IllegalArgumentException::class.java) { read("{\"a\":" + "[".repeat(17) + "0" + "]".repeat(17) + "}") }
        assertThrows(IllegalArgumentException::class.java) { read("{\"a\":[" + List(16_385) { "0" }.joinToString(",") + "]}") }
    }
}
