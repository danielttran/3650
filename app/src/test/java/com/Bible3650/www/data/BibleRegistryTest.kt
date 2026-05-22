package com.Bible3650.www.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BibleRegistryTest {

    @Test
    fun `getAllBooks stays at the 66 canonical books`() {
        assertEquals(66, BibleRegistry.getAllBooks().size)
    }

    @Test
    fun `apocrypha is non-empty and disjoint from the canon`() {
        val canonical = BibleRegistry.getAllBooks().toSet()
        val apocrypha = BibleRegistry.getApocryphalBooks()
        assertTrue(apocrypha.isNotEmpty())
        assertTrue(apocrypha.none { it in canonical })
    }

    @Test
    fun `getAllKnownBooks is canonical plus apocrypha`() {
        assertEquals(
            BibleRegistry.getAllBooks() + BibleRegistry.getApocryphalBooks(),
            BibleRegistry.getAllKnownBooks()
        )
    }

    @Test
    fun `chapter counts cover both canons`() {
        assertEquals(50, BibleRegistry.getChapterCount("Genesis"))
        assertTrue(BibleRegistry.getChapterCount("Sirach") > 0)
        assertTrue(BibleRegistry.getChapterCount("1 Maccabees") > 0)
        assertEquals(0, BibleRegistry.getChapterCount("Definitely Not A Book"))
    }

    @Test
    fun `isApocryphal classifies correctly`() {
        assertTrue(BibleRegistry.isApocryphal("Tobit"))
        assertTrue(BibleRegistry.isApocryphal("Sirach"))
        assertFalse(BibleRegistry.isApocryphal("Genesis"))
        assertFalse(BibleRegistry.isApocryphal("Revelation"))
        assertFalse(BibleRegistry.isApocryphal("Unknown"))
    }

    @Test
    fun `canon lookup matches the accessors`() {
        assertEquals(BibleRegistry.Canon.CANONICAL, BibleRegistry.getCanon("John"))
        assertEquals(BibleRegistry.Canon.APOCRYPHA, BibleRegistry.getCanon("Judith"))
        assertEquals(null, BibleRegistry.getCanon("Unknown"))
    }
}
