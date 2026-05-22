package com.Bible3650.www.data

import com.Bible3650.www.data.local.ListBookEntity
import com.Bible3650.www.data.local.ListWithBooks
import com.Bible3650.www.data.local.ReadingListEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListValidatorTest {

    private fun list(id: Long, vararg books: String): ListWithBooks {
        val entity = ReadingListEntity(listId = id, listName = "L$id")
        val bookEntities = books.mapIndexed { i, b -> ListBookEntity(listId = id, bookName = b, sortOrder = i) }
        return ListWithBooks(entity, bookEntities)
    }

    @Test
    fun `complete coverage with no duplicates is valid`() {
        val allBooks = BibleRegistry.getAllBooks()
        val single = list(1, *allBooks.toTypedArray())
        val result = ListValidator.validateCustomLists(listOf(single))
        assertTrue(result.isValid)
        assertTrue(result.missingBooks.isEmpty())
        assertTrue(result.duplicateBooks.isEmpty())
    }

    @Test
    fun `missing books are reported`() {
        val result = ListValidator.validateCustomLists(listOf(list(1, "Genesis", "Exodus")))
        assertFalse(result.isValid)
        assertTrue(result.missingBooks.contains("Revelation"))
        assertFalse(result.missingBooks.contains("Genesis"))
    }

    @Test
    fun `duplicate book across lists is reported`() {
        val a = list(1, "Genesis")
        val b = list(2, "Genesis")
        val result = ListValidator.validateCustomLists(listOf(a, b))
        assertFalse(result.isValid)
        assertEquals(listOf("Genesis"), result.duplicateBooks)
    }

    @Test
    fun `empty configuration reports all books missing and is invalid`() {
        val result = ListValidator.validateCustomLists(emptyList())
        assertFalse(result.isValid)
        assertEquals(BibleRegistry.getAllBooks().size, result.missingBooks.size)
    }

    @Test
    fun `apocrypha list does not affect canonical missing-book check`() {
        val allCanonical = list(1, *BibleRegistry.getAllBooks().toTypedArray())
        val apocrypha = list(2, "Tobit", "Judith", "Sirach")
        val result = ListValidator.validateCustomLists(listOf(allCanonical, apocrypha))
        assertTrue(result.isValid)
        assertTrue(result.missingBooks.isEmpty())
        assertTrue(result.duplicateBooks.isEmpty())
    }

    @Test
    fun `duplicate apocryphal book across lists is reported`() {
        val a = list(1, "Tobit")
        val b = list(2, "Tobit")
        val result = ListValidator.validateCustomLists(listOf(a, b))
        assertFalse(result.isValid)
        assertEquals(listOf("Tobit"), result.duplicateBooks)
    }
}
