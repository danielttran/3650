package com.Bible3650.www.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.Bible3650.www.data.local.AudioSourceDao
import com.Bible3650.www.data.local.AudioSourceEntity
import com.Bible3650.www.data.local.BibleDao
import com.Bible3650.www.data.local.BookMappingEntity
import com.Bible3650.www.data.local.DailyTask
import com.Bible3650.www.data.local.ListWithBooks
import com.Bible3650.www.data.local.ReadingListEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BibleRepository @Inject constructor(
    val dao: BibleDao,
    val audioSourceDao: AudioSourceDao,
    private val contentResolver: ContentResolver
) {
    // Reacts to both reading-list changes and active-source changes so the
    // player always uses the currently selected audio source.
    val dailyTasksFlow: Flow<List<DailyTask>> = combine(
        dao.observeActivePlaylists(),
        audioSourceDao.observeActiveMappings()
    ) { lists, _ ->
        val allTasks = mutableListOf<DailyTask>()
        for (offset in 0 until 30) {
            lists.forEach { listData ->
                allTasks.add(resolveDailyTask(listData, offset))
            }
        }
        allTasks
    }.flowOn(Dispatchers.IO)

    suspend fun initializeDatabaseIfNeeded() {
        val hasData = withTimeoutOrNull(3000) {
            dao.observeActivePlaylists().first { it.isNotEmpty() }
        } != null
        if (hasData) return

        val standardLists = listOf(
            "List 1: Gospels"                       to listOf("Matthew", "Mark", "Luke", "John"),
            "List 2: Pentateuch"                    to listOf("Genesis", "Exodus", "Leviticus", "Numbers", "Deuteronomy"),
            "List 3: Pauline Epistles & Hebrews"    to listOf("Romans", "1 Corinthians", "2 Corinthians", "Galatians", "Ephesians", "Philippians", "Colossians", "Hebrews"),
            "List 4: General Epistles & Revelation" to listOf("1 Thessalonians", "2 Thessalonians", "1 Timothy", "2 Timothy", "Titus", "Philemon", "James", "1 Peter", "2 Peter", "1 John", "2 John", "3 John", "Jude", "Revelation"),
            "List 5: Wisdom"                        to listOf("Job", "Ecclesiastes", "Song of Songs"),
            "List 6: Psalms"                        to listOf("Psalm"),
            "List 7: Proverbs"                      to listOf("Proverbs"),
            "List 8: History"                       to listOf("Joshua", "Judges", "Ruth", "1 Samuel", "2 Samuel", "1 Kings", "2 Kings", "1 Chronicles", "2 Chronicles", "Ezra", "Nehemiah", "Esther"),
            "List 9: Prophets"                      to listOf("Isaiah", "Jeremiah", "Lamentations", "Ezekiel", "Daniel", "Hosea", "Joel", "Amos", "Obadiah", "Jonah", "Micah", "Nahum", "Habakkuk", "Zephaniah", "Haggai", "Zechariah", "Malachi"),
            "List 10: Acts"                         to listOf("Acts")
        )
        standardLists.forEachIndexed { index, (name, books) ->
            dao.createCustomList(ReadingListEntity(listName = name, listOrder = index), books)
        }
    }

    // ---------------------------------------------------------------------------
    // Chapter resolution
    // ---------------------------------------------------------------------------

    private fun resolveDailyTask(
        listData: ListWithBooks,
        dayOffset: Int
    ): DailyTask {
        val books = listData.books.sortedBy { it.sortOrder }
        val totalChapters = books.sumOf { BibleRegistry.getChapterCount(it.bookName) }
        
        if (books.isEmpty() || totalChapters == 0) {
            return DailyTask(
                listId        = listData.readingList.listId,
                dayOffset     = dayOffset,
                uniqueId      = "${listData.readingList.listId}_$dayOffset",
                listName      = listData.readingList.listName,
                targetBook    = "Empty",
                targetChapter = 0,
                isCompleted   = listData.readingList.isCompletedToday
            )
        }

        var normalizedDay = ((listData.readingList.currentDayIndex + dayOffset - 1) % totalChapters) + 1
        var targetBook    = ""
        var targetChapter = 0
        for (book in books) {
            val count = BibleRegistry.getChapterCount(book.bookName)
            if (normalizedDay <= count) { targetBook = book.bookName; targetChapter = normalizedDay; break }
            normalizedDay -= count
        }

        return DailyTask(
            listId        = listData.readingList.listId,
            dayOffset     = dayOffset,
            uniqueId      = "${listData.readingList.listId}_$dayOffset",
            listName      = listData.readingList.listName,
            targetBook    = targetBook,
            targetChapter = targetChapter,
            isCompleted   = listData.readingList.isCompletedToday
        )
    }

    // Returns the content URI for the Nth audio file (1-based) inside a SAF folder,
    // sorted with natural (numeric) ordering so "Chapter 10" follows "Chapter 9".
    fun resolveChapterFile(
        treeUri: Uri, 
        folderDocId: String, 
        chapterIndex: Int,
        cache: MutableMap<String, List<String>>? = null
    ): Uri? {
        val cachedFiles = cache?.get(folderDocId)
        val sortedDocIds = if (cachedFiles != null) {
            cachedFiles
        } else {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderDocId)
            val files = mutableListOf<Pair<String, String>>() // (displayName, docId)

            try {
                contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null, null, null
                )?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val idIdx   = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    
                    if (nameIdx == -1 || idIdx == -1 || mimeIdx == -1) {
                        android.util.Log.e("BibleRepo", "Required columns missing in query")
                        return@use
                    }

                    while (cursor.moveToNext()) {
                        val name  = cursor.getString(nameIdx)  ?: continue
                        val docId = cursor.getString(idIdx)    ?: continue
                        val mime  = cursor.getString(mimeIdx)  ?: ""
                        if (mime.startsWith("audio/") || name.endsWithAudioExt()) files.add(name to docId)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("BibleRepo", "resolveChapterFile failed for $folderDocId ch$chapterIndex. Tree: $treeUri", e)
                return null
            }

            if (files.isEmpty()) {
                android.util.Log.w("BibleRepo", "No audio files found in $folderDocId")
                val empty = emptyList<String>()
                cache?.put(folderDocId, empty)
                empty
            } else {
                files.sortWith(Comparator { a, b -> naturalCompare(a.first, b.first) })
                val docIds = files.map { it.second }
                cache?.put(folderDocId, docIds)
                docIds
            }
        }

        val docId = sortedDocIds.getOrNull(chapterIndex - 1)
        if (docId == null) {
            android.util.Log.w("BibleRepo", "Chapter $chapterIndex out of bounds for $folderDocId (total ${sortedDocIds.size})")
            return null
        }
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun String.endsWithAudioExt(): Boolean =
        endsWith(".mp3",  ignoreCase = true) ||
        endsWith(".m4a",  ignoreCase = true) ||
        endsWith(".ogg",  ignoreCase = true) ||
        endsWith(".flac", ignoreCase = true)

    private fun naturalCompare(a: String, b: String): Int {
        val aToks = tokenize(a); val bToks = tokenize(b)
        for (i in 0 until minOf(aToks.size, bToks.size)) {
            val (aNum, aStr) = aToks[i]; val (bNum, bStr) = bToks[i]
            val cmp = if (aNum && bNum) aStr.toLong().compareTo(bStr.toLong())
                      else              aStr.compareTo(bStr, ignoreCase = true)
            if (cmp != 0) return cmp
        }
        return aToks.size.compareTo(bToks.size)
    }

    private fun tokenize(s: String): List<Pair<Boolean, String>> {
        val result = mutableListOf<Pair<Boolean, String>>()
        var i = 0
        while (i < s.length) {
            val start = i
            if (s[i].isDigit()) {
                while (i < s.length && s[i].isDigit()) i++
                result.add(true to s.substring(start, i))
            } else {
                while (i < s.length && !s[i].isDigit()) i++
                result.add(false to s.substring(start, i))
            }
        }
        return result
    }
}
