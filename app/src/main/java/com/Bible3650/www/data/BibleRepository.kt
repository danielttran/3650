package com.Bible3650.www.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.Bible3650.www.BuildConfig
import com.Bible3650.www.data.local.BibleDao
import com.Bible3650.www.data.local.DailyTask
import com.Bible3650.www.data.local.ListWithBooks
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BibleRepository @Inject constructor(
    val dao: BibleDao,
    @ApplicationContext private val context: Context
) {
    val dailyTasksFlow: Flow<List<DailyTask>> = dao.observeActivePlaylists()
        .map { lists -> 
            val allTasks = mutableListOf<DailyTask>()
            for (offset in 0 until 30) {
                lists.forEach { listData ->
                    allTasks.add(resolveDailyTask(listData, offset))
                }
            }
            allTasks
        }
        .flowOn(Dispatchers.IO)

    suspend fun initializeDatabaseIfNeeded() {
        val currentLists = dao.observeActivePlaylists().first()
        if (currentLists.isEmpty()) {
            val standardLists = listOf(
                "List 1: Gospels" to listOf("Matthew", "Mark", "Luke", "John"),
                "List 2: Pentateuch" to listOf("Genesis", "Exodus", "Leviticus", "Numbers", "Deuteronomy"),
                "List 3: Pauline Epistles & Hebrews" to listOf("Romans", "1 Corinthians", "2 Corinthians", "Galatians", "Ephesians", "Philippians", "Colossians", "Hebrews"),
                "List 4: General Epistles & Revelation" to listOf("1 Thessalonians", "2 Thessalonians", "1 Timothy", "2 Timothy", "Titus", "Philemon", "James", "1 Peter", "2 Peter", "1 John", "2 John", "3 John", "Jude", "Revelation"),
                "List 5: Wisdom" to listOf("Job", "Ecclesiastes", "Song of Songs"),
                "List 6: Psalms" to listOf("Psalm"),
                "List 7: Proverbs" to listOf("Proverbs"),
                "List 8: History" to listOf("Joshua", "Judges", "Ruth", "1 Samuel", "2 Samuel", "1 Kings", "2 Kings", "1 Chronicles", "2 Chronicles", "Ezra", "Nehemiah", "Esther"),
                "List 9: Prophets" to listOf("Isaiah", "Jeremiah", "Lamentations", "Ezekiel", "Daniel", "Hosea", "Joel", "Amos", "Obadiah", "Jonah", "Micah", "Nahum", "Habakkuk", "Zephaniah", "Haggai", "Zechariah", "Malachi"),
                "List 10: Acts" to listOf("Acts")
            )
            standardLists.forEachIndexed { index, (listName, books) ->
                dao.createCustomList(com.Bible3650.www.data.local.ReadingListEntity(listName = listName, listOrder = index), books)
            }
        }
    }

    private fun resolveDailyTask(listData: ListWithBooks, dayOffset: Int = 0): DailyTask {
        val books = listData.books.sortedBy { it.sortOrder }
        val totalChaptersInList = books.sumOf { BibleRegistry.getChapterCount(it.bookName) }
        
        if (totalChaptersInList == 0) throw IllegalStateException("List ${listData.readingList.listId} has 0 chapters.")

        var normalizedDay = ((listData.readingList.currentDayIndex + dayOffset - 1) % totalChaptersInList) + 1
        var targetBook = ""
        var targetChapter = 0
        var testament = ""

        for (book in books) {
            val chapterCount = BibleRegistry.getChapterCount(book.bookName)
            if (normalizedDay <= chapterCount) {
                targetBook = book.bookName
                targetChapter = normalizedDay
                testament = BibleRegistry.getTestament(book.bookName)
                break
            }
            normalizedDay -= chapterCount
        }

        val fileName = "Chapter $targetChapter.mp3"
        
        val folderName = BibleRegistry.getBook(targetBook)?.folderName ?: targetBook
        val filePrefix = BibleRegistry.getBook(targetBook)?.filePrefix ?: targetBook
        
        // Pad chapter with 0 to length 2 or 3 depending on padding required (e.g. Psalm needs 3, others 2)
        val padding = BibleRegistry.getBook(targetBook)?.padding ?: 2
        val chapterString = targetChapter.toString().padStart(padding, '0')
        
        val relativePath = "NIV/$testament/$folderName/${filePrefix} $chapterString.mp3"
        
        if (BuildConfig.DEBUG) {
            validateAssetExists(relativePath)
        }

        // Format is asset:///NIV/Old%20Testament/...
        val encodedPath = Uri.encode(relativePath, "/")
        val absoluteUri = "asset:///$encodedPath"

        return DailyTask(
            listId = listData.readingList.listId,
            dayOffset = dayOffset,
            uniqueId = "${listData.readingList.listId}_$dayOffset",
            listName = listData.readingList.listName,
            targetBook = targetBook,
            targetChapter = targetChapter,
            fileUri = absoluteUri,
            isCompleted = listData.readingList.isCompletedToday
        )
    }

    private fun validateAssetExists(path: String) {
        // Simple fast path logic: not exhaustive but catches most
        val parts = path.split("/")
        if (parts.size < 3) return
        try {
            val directory = parts.dropLast(1).joinToString("/")
            val fileName = parts.last()
            val assetsInDir = context.assets.list(directory)
            if (assetsInDir?.contains(fileName) != true) {
                Log.e("AudioBible", "MISSING ASSET: $path")
            }
        } catch (e: Exception) {
             Log.e("AudioBible", "Error checking asset: $path", e)
        }
    }
}
