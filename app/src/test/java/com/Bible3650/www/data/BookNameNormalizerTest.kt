package com.Bible3650.www.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookNameNormalizerTest {

    @Test
    fun `usfm codes map to canonical names`() {
        assertEquals("Genesis", BookNameNormalizer.normalize("GEN"))
        assertEquals("1 Samuel", BookNameNormalizer.normalize("1SA"))
        assertEquals("Revelation", BookNameNormalizer.normalize("REV"))
        assertEquals("Sirach", BookNameNormalizer.normalize("SIR"))
        assertEquals("Tobit", BookNameNormalizer.normalize("TOB"))
    }

    @Test
    fun `osis ids map to canonical names`() {
        assertEquals("Genesis", BookNameNormalizer.normalize("Gen"))
        assertEquals("1 Samuel", BookNameNormalizer.normalize("1Sam"))
        assertEquals("Psalm", BookNameNormalizer.normalize("Ps"))
        assertEquals("Song of Songs", BookNameNormalizer.normalize("Song"))
        assertEquals("1 Maccabees", BookNameNormalizer.normalize("1Macc"))
    }

    @Test
    fun `free-text names and variants map`() {
        assertEquals("Sirach", BookNameNormalizer.normalize("Ecclesiasticus"))
        assertEquals("Psalm", BookNameNormalizer.normalize("Psalms"))
        assertEquals("Song of Songs", BookNameNormalizer.normalize("Song of Solomon"))
        assertEquals("1 Samuel", BookNameNormalizer.normalize("1 sam"))
        assertEquals("1 Samuel", BookNameNormalizer.normalize("First Samuel"))
    }

    @Test
    fun `letter of jeremiah folds into baruch`() {
        assertEquals("Baruch", BookNameNormalizer.normalize("EpJer"))
        assertEquals("Baruch", BookNameNormalizer.normalize("Letter of Jeremiah"))
    }

    @Test
    fun `unknown returns null`() {
        assertNull(BookNameNormalizer.normalize("Definitely Not A Book"))
        assertNull(BookNameNormalizer.normalize(""))
    }
}
