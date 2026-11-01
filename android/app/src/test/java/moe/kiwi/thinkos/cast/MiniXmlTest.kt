package moe.kiwi.thinkos.cast

import org.junit.Assert.*
import org.junit.Test

class MiniXmlTest {
    private fun rejected(xml: String) = assertThrows(IllegalArgumentException::class.java) { MiniXml.parse(xml) }

    @Test fun readsElementsAttributesAndText() {
        val root = MiniXml.parse("""<?xml version="1.0"?><a:Root xmlns:a="urn:x" id='7'><b>one</b><c><b>two</b></c><d/></a:Root>""")
        assertEquals("Root", root.local)
        assertEquals("7", root.attributes["id"])
        assertEquals("one", root.child("b")?.text)
        assertEquals(listOf("one", "two"), root.descendants("b").map { it.text })
        assertNotNull(root.child("d"))
    }

    @Test fun decodesTheFiveEntitiesAndNumericReferences() {
        assertEquals("<a href=\"x\">&'", MiniXml.parse("<t>&lt;a href=&quot;x&quot;&gt;&amp;&apos;</t>").text)
        assertEquals("Aé", MiniXml.parse("<t>&#65;&#xE9;</t>").text)
        assertEquals("a<b>&c", MiniXml.parse("<t><![CDATA[a<b>&c]]></t>").text)
    }

    @Test fun aBareAmpersandInAnAddressSurvives() {
        // Real controllers send this; it is not valid XML, and it is the address they mean.
        assertEquals("http://tv.test/a?x=1&y=2&z", MiniXml.parse("<u>http://tv.test/a?x=1&y=2&z</u>").text)
        assertEquals("a &unknown; b", MiniXml.parse("<u>a &unknown; b</u>").text)
    }

    @Test fun commentsAndProcessingInstructionsAreSkipped() {
        assertEquals("ab", MiniXml.parse("<?xml version=\"1.0\"?><!-- c --><t>a<!-- inner -->b<?pi x?></t>").text)
    }

    @Test fun documentTypesAndEntityDeclarationsAreRefused() {
        rejected("""<?xml version="1.0"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><t>&xxe;</t>""")
        rejected("<!DOCTYPE t><t/>")
        rejected("<t><!ENTITY a 'b'></t>")
    }

    @Test fun malformedDocumentsAreRefusedNotGuessed() {
        for (xml in listOf("", "text", "<a>", "<a></b>", "<a b=c/>", "<a b=\"c/>", "<a><b></a></b>", "<a", "<![CDATA[x")) rejected(xml)
    }

    @Test fun sizeDepthAndElementCountAreBounded() {
        rejected("<t>" + "x".repeat(MiniXml.MAX_CHARS) + "</t>")
        rejected("<a>".repeat(40) + "</a>".repeat(40))
        rejected("<r>" + "<i/>".repeat(5000) + "</r>")
        MiniXml.parse("<a>".repeat(20) + "</a>".repeat(20))
    }
}
