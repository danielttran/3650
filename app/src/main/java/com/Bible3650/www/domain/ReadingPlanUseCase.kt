package com.Bible3650.www.domain

import com.Bible3650.www.data.BibleRegistry
import com.Bible3650.www.data.local.ListWithBooks

object ReadingPlanUseCase {
    fun calculateTargetTask(listData: ListWithBooks, dayOffset: Int): Pair<String, Int> {
        val books = listData.books.sortedBy { it.sortOrder }
        val totalChapters = books.sumOf { BibleRegistry.getChapterCount(it.bookName) }

        if (books.isEmpty() || totalChapters == 0) return "Empty" to 0

        val absoluteDay = listData.readingList.currentDayIndex + dayOffset + listData.readingList.manualOffset
        var normalizedDay = ((absoluteDay - 1).mod(totalChapters)) + 1

        for (book in books) {
            val count = BibleRegistry.getChapterCount(book.bookName)
            if (normalizedDay <= count) return book.bookName to normalizedDay
            normalizedDay -= count
        }
        return "Empty" to 0
    }
}
