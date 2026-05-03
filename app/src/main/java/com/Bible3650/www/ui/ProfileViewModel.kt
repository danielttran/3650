package com.Bible3650.www.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.Bible3650.www.data.BibleRegistry
import com.Bible3650.www.data.BibleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BookStat(
    val bookName: String,
    val readCount: Int,
    val listColor: Int = 0
)

data class ProfileUiState(
    val totalChaptersRead: Int = 0,
    val bookStats: List<BookStat> = emptyList()
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repository: BibleRepository
) : ViewModel() {

    // #15: Use repository.listsWithBooksFlow instead of repository.dao directly.
    val uiState: StateFlow<ProfileUiState> = repository.listsWithBooksFlow
        .map { lists ->
            try {
                var totalChapters = 0
                val bookCounts = mutableMapOf<String, Int>()
                val bookToListColor = mutableMapOf<String, Int>()

                lists.forEach { listData ->
                    val books = listData.books.sortedBy { it.sortOrder }
                    val totalChaptersInList = books.sumOf { BibleRegistry.getChapterCount(it.bookName) }

                    // Map every book in this list to its list color
                    val listColor = listData.readingList.listColor
                    books.forEach { bookToListColor[it.bookName] = listColor }

                    if (totalChaptersInList > 0) {
                        val chaptersReadInList = maxOf(0, (listData.readingList.currentDayIndex - 1) - listData.readingList.statResetOffset)
                        totalChapters += chaptersReadInList

                        val fullLoops = chaptersReadInList / totalChaptersInList
                        val remainingChapters = chaptersReadInList % totalChaptersInList

                        var chaptersPassed = 0
                        for (book in books) {
                            val bookChapterCount = BibleRegistry.getChapterCount(book.bookName)

                            // #8: Mark a book as read in the current partial loop when the
                            // chapter cursor has moved past the start of this book.
                            // Old condition (>= chaptersPassed + bookChapterCount) required the
                            // entire book to be covered — it should just require passing chapter 1.
                            val readThisLoop = if (remainingChapters > chaptersPassed) 1 else 0

                            val currentCount = bookCounts.getOrDefault(book.bookName, 0)
                            bookCounts[book.bookName] = currentCount + fullLoops + readThisLoop

                            chaptersPassed += bookChapterCount
                        }
                    }
                }

                val stats = BibleRegistry.getAllBooks().map { bookName ->
                    BookStat(
                        bookName = bookName,
                        readCount = bookCounts.getOrDefault(bookName, 0),
                        listColor = bookToListColor.getOrDefault(bookName, 0)
                    )
                }

                ProfileUiState(totalChaptersRead = totalChapters, bookStats = stats)
            } catch (e: Exception) {
                android.util.Log.e("ProfileVM", "Error calculating stats", e)
                ProfileUiState()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProfileUiState())

    // #15: Use repository.resetStats() wrapper instead of repository.dao directly.
    fun resetStats() {
        viewModelScope.launch {
            repository.resetStats()
        }
    }

    suspend fun exportData(): String = repository.exportProgress()

    suspend fun importData(json: String): Boolean = repository.importProgress(json)
}
