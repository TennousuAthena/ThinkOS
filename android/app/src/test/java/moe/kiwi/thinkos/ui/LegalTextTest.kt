package moe.kiwi.thinkos.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LegalTextTest {
    @Test fun nativeNoticeHeaderDoesNotRepeatTheMarkdownDocumentTitle() {
        assertEquals("正文\n\n小节\n说明", LegalText.display("THIRD_PARTY_NOTICES.md",
            "\r\n# 第三方说明\r\n\r\n正文\r\n\r\n## 小节\r\n说明\r\n"))
        assertEquals("文档标题\n\n正文", LegalText.display("notes.md", "# 文档标题\n\n正文"))
        assertEquals("小节\n\n正文", LegalText.display("THIRD_PARTY_NOTICES.md", "## 小节\n\n正文"))
    }

    @Test fun headingsQuotesListsAndEmphasisHaveAPlainTextPresentation() {
        val actual = LegalText.plain("# 第三方说明\n\n> **完整**声明\n\n- `armeabi-v7a`\n* _保留文字_\n\n---\n")
        assertEquals("第三方说明\n\n完整声明\n\n• armeabi-v7a\n• 保留文字", actual)
    }

    @Test fun tablesBecomeNamedFieldsWithoutDroppingCodePipesOrColumns() {
        val actual = LegalText.plain("| 内容 | 许可证 | 说明 |\n|---|:---:|---:|\n| `one` | MIT | `a | b` |\n| two | MPL-2.0 | 来源 |")
        assertEquals("one\n许可证：MIT\n说明：a | b\n\ntwo\n许可证：MPL-2.0\n说明：来源", actual)
    }

    @Test fun linkDestinationsAndLiteralCodeAreReadableAndIntact() {
        val actual = LegalText.plain("[**来源**](https://host.test/a_(b))\n[资料][ref]\n\n[ref]: https://host.test/ref\n\n`*.so | *.aar`\n```\n# literal **code**\n```\n<year> <name>")
        assertTrue(actual.contains("来源（https://host.test/a_(b)）"))
        assertTrue(actual.contains("资料（https://host.test/ref）"))
        assertTrue(actual.contains("*.so | *.aar")); assertTrue(actual.contains("# literal **code**"))
        assertTrue(actual.contains("<year> <name>")); assertFalse(actual.contains("```"))
    }

    @Test fun plainLicenseFilesRemainExactlyAsBundled() {
        for (file in listOf("LICENSE", "LICENSE-APACHE-2.0.txt", "LICENSE-MPL-2.0.txt")) {
            val source = File("../../$file").readText()
            assertEquals(source, LegalText.display(file, source))
        }
    }

    @Test fun theActualNoticesRetainLicensesPathsAndEveryInlineLink() {
        val source = File("../../THIRD_PARTY_NOTICES.md").readText()
        val plain = LegalText.display("THIRD_PARTY_NOTICES.md", source)
        assertFalse(plain.startsWith("第三方说明\n"))
        for (term in listOf("playfair", "MPL-2.0", "GPL-3.0", "Lua 5.4.9", "android/app/src/main/cpp/playfair/")) assertTrue(term, plain.contains(term))
        for (match in Regex("\\[[^]]+]\\((https?://[^)]+)\\)").findAll(source)) assertTrue(match.groupValues[1], plain.contains(match.groupValues[1]))
        assertFalse(Regex("(?m)^#{1,6} ").containsMatchIn(plain)); assertFalse(Regex("(?m)^\\|.*\\|$").containsMatchIn(plain))
        assertFalse(Regex("\\[[^]]+]\\(https?://").containsMatchIn(plain)); assertFalse(plain.contains('`'))
    }
}
