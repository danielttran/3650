package com.Bible3650.www.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BibleTextImporterTest {

    private fun chapter(list: List<ParsedChapter>, book: String, ch: Int): ParsedChapter? =
        list.firstOrNull { it.book == book && it.chapter == ch }

    @Test
    fun `parses USFM into cleaned chapters`() {
        val usfm = """
            \id GEN Genesis
            \c 1
            \v 1 In the beginning God created the heavens and the earth.
            \v 2 The earth was formless and empty.
            \c 2
            \v 1 Thus the heavens were finished.
        """.trimIndent()
        val result = BibleTextImporter.parse(usfm)
        assertEquals(TextFormat.USFM, BibleTextImporter.sniff(usfm))
        assertEquals(
            "In the beginning God created the heavens and the earth. The earth was formless and empty.",
            chapter(result, "Genesis", 1)?.text
        )
        assertEquals("Thus the heavens were finished.", chapter(result, "Genesis", 2)?.text)
    }

    @Test
    fun `USFM drops footnotes and headings but keeps poetry lines`() {
        val usfm = """
            \id PSA
            \c 23
            \s A psalm of David
            \q1 The Lord is my shepherd;\f + \fr 23.1 \ft a footnote\f*
            \q2 I shall not want.
        """.trimIndent()
        val result = BibleTextImporter.parse(usfm)
        assertEquals("The Lord is my shepherd; I shall not want.", chapter(result, "Psalm", 23)?.text)
    }

    @Test
    fun `parses OSIS verses`() {
        val osis = """
            <osis><osisText><div type="book" osisID="John">
            <chapter osisID="John.3">
            <verse osisID="John.3.16">For God so loved the world.<note>n</note></verse>
            <verse osisID="John.3.17">For God did not send his Son.</verse>
            </chapter></div></osisText></osis>
        """.trimIndent()
        val result = BibleTextImporter.parse(osis)
        assertEquals(TextFormat.OSIS, BibleTextImporter.sniff(osis))
        assertEquals("For God so loved the world. For God did not send his Son.", chapter(result, "John", 3)?.text)
    }

    @Test
    fun `rejects XML documents with doctypes`() {
        val osis = """
            <!DOCTYPE osis [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <osis><osisText><verse osisID="John.3.16">&xxe;</verse></osisText></osis>
        """.trimIndent()
        assertEquals(emptyList<ParsedChapter>(), BibleTextImporter.parse(osis, TextFormat.OSIS))
    }

    @Test
    fun `parses Zefania verses by name`() {
        val zef = """
            <XMLBIBLE><BIBLEBOOK bnumber="1" bname="Genesis">
            <CHAPTER cnumber="1">
            <VERS vnumber="1">In the beginning.</VERS>
            <VERS vnumber="2">And the earth was void.</VERS>
            </CHAPTER></BIBLEBOOK></XMLBIBLE>
        """.trimIndent()
        val result = BibleTextImporter.parse(zef)
        assertEquals(TextFormat.ZEFANIA, BibleTextImporter.sniff(zef))
        assertEquals("In the beginning. And the earth was void.", chapter(result, "Genesis", 1)?.text)
    }

    @Test
    fun `parses nested JSON form`() {
        val js = """{"Genesis":{"1":{"1":"In the beginning.","2":"And the earth."}}}"""
        val result = BibleTextImporter.parse(js)
        assertEquals(TextFormat.JSON, BibleTextImporter.sniff(js))
        assertEquals("In the beginning. And the earth.", chapter(result, "Genesis", 1)?.text)
    }

    @Test
    fun `parses flat verse-list JSON form`() {
        val js = """
            {"verses":[
              {"book_name":"Genesis","chapter":1,"verse":1,"text":"In the beginning."},
              {"book_name":"Genesis","chapter":1,"verse":2,"text":"And the earth."}
            ]}
        """.trimIndent()
        val result = BibleTextImporter.parse(js)
        assertNotNull(chapter(result, "Genesis", 1))
        assertEquals("In the beginning. And the earth.", chapter(result, "Genesis", 1)?.text)
    }

    @Test
    fun `parses helloao complete json and skips footnote and linebreak objects`() {
        val js = """
            {"translation":{"id":"BSB","completeTranslationApiLink":"/api/BSB/complete.json"},
             "books":[
               {"id":"GEN","name":"Genesis","commonName":"Genesis","chapters":[
                 {"chapter":{"number":1,"content":[
                   {"type":"heading","content":["The Creation"]},
                   {"type":"verse","number":1,"content":["In the beginning God created the heavens and the earth."]},
                   {"type":"line_break"},
                   {"type":"verse","number":2,"content":["And God said, “Let there be light,”",{"noteId":0},"and there was light.",{"lineBreak":true}]}
                 ]}}
               ]}
             ]}
        """.trimIndent()
        assertEquals(TextFormat.HELLOAO, BibleTextImporter.sniff(js))
        val result = BibleTextImporter.parse(js)
        assertEquals(
            "In the beginning God created the heavens and the earth. And God said, “Let there be light,” and there was light.",
            chapter(result, "Genesis", 1)?.text
        )
    }

    @Test
    fun `helloao normalizes osis book ids`() {
        val js = """
            {"translation":{"id":"eng_kjv","completeTranslationApiLink":"/api/eng_kjv/complete.json"},
             "books":[
               {"id":"JHN","name":"John","commonName":"John","chapters":[
                 {"chapter":{"number":3,"content":[
                   {"type":"verse","number":16,"content":["For God so loved the world."]}
                 ]}}
               ]}
             ]}
        """.trimIndent()
        val result = BibleTextImporter.parse(js)
        assertEquals("For God so loved the world.", chapter(result, "John", 3)?.text)
    }

    @Test
    fun `parses apocrypha book codes`() {
        val usfm = "\\id SIR\n\\c 1\n\\v 1 All wisdom comes from the Lord."
        val result = BibleTextImporter.parse(usfm)
        assertEquals("All wisdom comes from the Lord.", chapter(result, "Sirach", 1)?.text)
    }
}
