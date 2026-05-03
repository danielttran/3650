package com.Bible3650.www.domain

import com.Bible3650.www.data.local.ListBookEntity
import com.Bible3650.www.data.local.ListWithBooks
import com.Bible3650.www.data.local.ReadingListEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [ReadingPlanUseCase.calculateTargetTask].
 *
 * These tests use a minimal synthetic "list" with 3 books of 3 chapters each (9 total)
 * to keep assertions simple and readable. BibleRegistry is NOT mocked — the test books
 * used here correspond to real books with exactly 3 chapters (2 John, 3 John, Titus).
 */
class ReadingPlanUseCaseTest {

    // Build a minimal ListWithBooks fixture.
    // Books: 2 John (1 ch), 3 John (1 ch), Titus (3 ch) → 5 total chapters
    private fun makeListData(
        currentDayIndex: Int = 1,
        manualOffset: Int = 0
    ): ListWithBooks {
        val entity = ReadingListEntity(
            listId = 1L,
            listName = "Test",
            currentDayIndex = currentDayIndex,
            manualOffset = manualOffset
        )
        val books = listOf(
            ListBookEntity(listId = 1L, bookName = "2 John",  sortOrder = 0),
            ListBookEntity(listId = 1L, bookName = "3 John",  sortOrder = 1),
            ListBookEntity(listId = 1L, bookName = "Titus",   sortOrder = 2)
        )
        return ListWithBooks(entity, books)
    }

    @Test
    fun `day 1 starts at first book chapter 1`() {
        val result = ReadingPlanUseCase.calculateTargetTask(makeListData(currentDayIndex = 1), 0)
        assertEquals("2 John", result.first)
        assertEquals(1, result.second)
    }

    @Test
    fun `day 2 moves to next book when first book has 1 chapter`() {
        val result = ReadingPlanUseCase.calculateTargetTask(makeListData(currentDayIndex = 2), 0)
        assertEquals("3 John", result.first)
        assertEquals(1, result.second)
    }

    @Test
    fun `day 3 moves into third book first chapter`() {
        val result = ReadingPlanUseCase.calculateTargetTask(makeListData(currentDayIndex = 3), 0)
        assertEquals("Titus", result.first)
        assertEquals(1, result.second)
    }

    @Test
    fun `day 5 lands on last chapter of third book`() {
        val result = ReadingPlanUseCase.calculateTargetTask(makeListData(currentDayIndex = 5), 0)
        assertEquals("Titus", result.first)
        assertEquals(3, result.second)
    }

    @Test
    fun `day 6 wraps around to first book chapter 1`() {
        // 5 chapters total → day 6 == day 1 (wraps)
        val result = ReadingPlanUseCase.calculateTargetTask(makeListData(currentDayIndex = 6), 0)
        assertEquals("2 John", result.first)
        assertEquals(1, result.second)
    }

    @Test
    fun `day offset is added to the absolute day`() {
        // currentDayIndex = 1, dayOffset = 2 → behaves like day 3 = Titus 1
        val result = ReadingPlanUseCase.calculateTargetTask(makeListData(currentDayIndex = 1), 2)
        assertEquals("Titus", result.first)
        assertEquals(1, result.second)
    }

    @Test
    fun `manualOffset shifts reading forward`() {
        // currentDayIndex = 1, manualOffset = 2 → behaves like day 3 = Titus 1
        val result = ReadingPlanUseCase.calculateTargetTask(makeListData(currentDayIndex = 1, manualOffset = 2), 0)
        assertEquals("Titus", result.first)
        assertEquals(1, result.second)
    }

    @Test
    fun `empty book list returns sentinel`() {
        val entity = ReadingListEntity(listId = 1L, listName = "Empty")
        val listData = ListWithBooks(entity, emptyList())
        val result = ReadingPlanUseCase.calculateTargetTask(listData, 0)
        assertEquals("Empty", result.first)
        assertEquals(0, result.second)
    }

    @Test
    fun `very large day wraps correctly via modular arithmetic`() {
        // 5 chapters total; day 1001 → (1001-1) mod 5 + 1 = 1 → 2 John 1
        val result = ReadingPlanUseCase.calculateTargetTask(makeListData(currentDayIndex = 1001), 0)
        assertEquals("2 John", result.first)
        assertEquals(1, result.second)
    }
}
