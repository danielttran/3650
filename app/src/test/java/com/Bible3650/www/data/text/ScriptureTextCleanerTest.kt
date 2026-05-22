package com.Bible3650.www.data.text

import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptureTextCleanerTest {

    @Test
    fun `leading verse numbers are stripped per line`() {
        val raw = "1 In the beginning God created.\n2 And the earth was formless."
        assertEquals(
            "In the beginning God created. And the earth was formless.",
            ScriptureTextCleaner.cleanPlainChapter(raw)
        )
    }

    @Test
    fun `leading chapter colon verse marker is stripped`() {
        assertEquals(
            "In the beginning God created.",
            ScriptureTextCleaner.cleanPlainChapter("1:1 In the beginning God created.")
        )
    }

    @Test
    fun `real numbers inside a verse are preserved`() {
        val raw = "9 He took the 5 loaves and the 2 fish."
        assertEquals("He took the 5 loaves and the 2 fish.", ScriptureTextCleaner.cleanPlainChapter(raw))
    }

    @Test
    fun `dates and inline numbers survive when not at line start`() {
        assertEquals(
            "It happened in A.D. 70.",
            ScriptureTextCleaner.cleanPlainChapter("It happened in A.D. 70.")
        )
    }

    @Test
    fun `footnote markers are removed`() {
        assertEquals("In the beginning God", ScriptureTextCleaner.cleanVerse("In the beginning[a] God"))
        assertEquals("the word", ScriptureTextCleaner.cleanVerse("the word*"))
    }

    @Test
    fun `joinVerses concatenates clean prose`() {
        assertEquals(
            "In the beginning And the earth",
            ScriptureTextCleaner.joinVerses(listOf("In the beginning", "  And the earth  "))
        )
    }

    @Test
    fun `aggressive mode removes inline references`() {
        assertEquals(
            "See and compare.",
            ScriptureTextCleaner.cleanPlainChapter("See 3:16 and compare.", aggressive = true)
        )
    }
}
