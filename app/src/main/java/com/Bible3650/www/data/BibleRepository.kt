package com.Bible3650.www.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.Bible3650.www.data.local.AudioSourceDao
import com.Bible3650.www.data.local.AudioSourceEntity
import com.Bible3650.www.data.local.BibleDao
import com.Bible3650.www.data.local.BookMappingEntity
import com.Bible3650.www.data.local.DailyTask
import com.Bible3650.www.data.local.ListWithBooks
import com.Bible3650.www.data.local.ReadingListEntity
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import androidx.room.withTransaction
import com.Bible3650.www.data.local.AppDatabase

private const val MAX_FOLDER_CACHE_ENTRIES = 100

@Singleton
class BibleRepository @Inject constructor(
    private val database: AppDatabase,
    val dao: BibleDao,
    val audioSourceDao: AudioSourceDao,
    private val contentResolver: ContentResolver
) {
    // Shared cache of folder→sorted-docIds so both dailyTasksFlow and
    // playTasks benefit from the same one-time directory scan.
    internal val folderCache = android.util.LruCache<String, List<String>>(MAX_FOLDER_CACHE_ENTRIES)
    private val cacheMutexes = ConcurrentHashMap<String, Mutex>()

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Freeze unresolved daily tasks outside the flow transform to avoid DB writes
        // inside a reactive pipeline (which would trigger immediate re-emission cycles).
        repoScope.launch {
            dao.observeActivePlaylists()
                .catch { e -> android.util.Log.e("BibleRepo", "Freeze observer failed", e) }
                .collect { lists ->
                    lists.forEach { listData ->
                        val list = listData.readingList
                        if (list.activeBook == null || list.activeChapter == null) {
                            try {
                                val result = calculateTargetTask(listData, 0)
                                dao.updateListProgress(list.listId, list.currentDayIndex, result.first, result.second)
                            } catch (e: Exception) {
                                android.util.Log.e("BibleRepo", "Failed to freeze task for list ${list.listId}", e)
                            }
                        }
                    }
                }
        }
    }

    fun clearCache() {
        folderCache.evictAll()
    }

    val dailyTasksFlow: Flow<List<DailyTask>> = combine(
        dao.observeActivePlaylists(),
        audioSourceDao.observeActiveMappings()
    ) { lists, activeMappings ->
        val mappingsByBook = activeMappings.associateBy { it.bookName }
        val activeSource   = audioSourceDao.getActiveSource()
        lists.map { listData ->
            resolveDailyTask(listData, 0, mappingsByBook, activeSource)
        }
    }
        .distinctUntilChanged()
        .catch { e ->
            android.util.Log.e("BibleRepo", "Error in dailyTasksFlow", e)
            emit(emptyList())
        }
        .flowOn(Dispatchers.IO)

    suspend fun initializeDatabaseIfNeeded(defaultColors: List<Int>) {
        val hasData = withTimeoutOrNull(3000) {
            dao.observeActivePlaylists().first { it.isNotEmpty() }
        } != null
        if (hasData) return

        insertDefaultLists(defaultColors)
    }

    private suspend fun insertDefaultLists(defaultColors: List<Int>) {
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
            val color = defaultColors.random()
            dao.createCustomList(ReadingListEntity(listName = name, listOrder = index, listColor = color), books)
        }
    }

    // ---------------------------------------------------------------------------
    // Chapter resolution
    // ---------------------------------------------------------------------------

    private suspend fun resolveDailyTask(
        listData: ListWithBooks,
        dayOffset: Int,
        mappingsByBook: Map<String, BookMappingEntity>,
        activeSource: AudioSourceEntity?
    ): DailyTask {
        val list = listData.readingList
        val targetBook: String
        val targetChapter: Int

        if (dayOffset == 0) {
            // Use frozen state if available; otherwise calculate dynamically.
            // The actual DB freeze is performed by the init observer in repoScope
            // to keep this flow transform free of side effects.
            if (list.activeBook != null && list.activeChapter != null) {
                targetBook = list.activeBook
                targetChapter = list.activeChapter
            } else {
                val result = calculateTargetTask(listData, 0)
                targetBook = result.first
                targetChapter = result.second
            }
        } else {
            // Future tasks: always calculate dynamically based on current list config
            val result = calculateTargetTask(listData, dayOffset)
            targetBook = result.first
            targetChapter = result.second
        }

        val mapping  = mappingsByBook[targetBook]
        val fileUri  = if (mapping != null && activeSource != null) {
            val treeUri = (mapping.overrideTreeUri ?: activeSource.rootTreeUri).toUri()
            resolveChapterFile(treeUri, mapping.folderDocId, targetChapter, folderCache)?.toString() ?: ""
        } else ""

        val totalChapters = listData.books.sumOf { BibleRegistry.getChapterCount(it.bookName) }

        return DailyTask(
            listId        = list.listId,
            dayOffset     = dayOffset,
            uniqueId      = "${list.listId}_$dayOffset",
            listName      = list.listName,
            targetBook    = targetBook,
            targetChapter = targetChapter,
            totalChapters = totalChapters,
            fileUri       = fileUri,
            listColor     = list.listColor
        )
    }

    private fun calculateTargetTask(listData: ListWithBooks, dayOffset: Int): Pair<String, Int> {
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

    suspend fun advanceListDay(listId: Long) {
        val list = dao.getListById(listId) ?: return
        val newIndex = list.currentDayIndex + 1
        // Setting book/chapter to null lets the init observer freeze the next task
        dao.updateListProgress(listId, newIndex, null, null)
    }

    suspend fun revertListDay(listId: Long) {
        val list = dao.getListById(listId) ?: return
        val newIndex = maxOf(1, list.currentDayIndex - 1)
        dao.updateListProgress(listId, newIndex, null, null)
    }

    suspend fun incrementManualOffset(listId: Long) {
        val list = dao.getListById(listId) ?: return
        dao.updateManualOffset(listId, list.manualOffset + 1)
    }

    suspend fun decrementManualOffset(listId: Long) {
        val list = dao.getListById(listId) ?: return
        dao.updateManualOffset(listId, list.manualOffset - 1)
    }

    suspend fun exportProgress(): String = withContext(Dispatchers.IO) {
        val lists = dao.getAllLists().map { ReadingListBackup(it.readingList, it.books) }
        val sources = audioSourceDao.getAllSources().map { AudioSourceBackup(it.source, it.mappings) }
        val backup = ProgressBackup(readingLists = lists, audioSources = sources)
        Gson().toJson(backup)
    }

    suspend fun importProgress(json: String): Boolean = withContext(Dispatchers.IO) {
        val backup = try {
            Gson().fromJson(json, ProgressBackup::class.java)
        } catch (e: Exception) {
            android.util.Log.e("BibleRepo", "Import failed", e)
            null
        } ?: return@withContext false

        try {
            database.withTransaction {
                dao.clearAllBooks()
                dao.clearAllLists()
                audioSourceDao.clearAllMappings()
                audioSourceDao.clearAllSources()

                backup.readingLists.forEach { rb ->
                    val list = rb.entity.copy(listId = 0)
                    val newId = dao.insertList(list)
                    dao.insertBooks(rb.books.map { it.copy(id = 0, listId = newId) })
                }

                backup.audioSources.forEach { sb ->
                    val source = sb.entity.copy(sourceId = 0)
                    val newId = audioSourceDao.insertSource(source)
                    audioSourceDao.upsertMappings(sb.mappings.map { it.copy(sourceId = newId) })
                }
            }

            folderCache.evictAll()
            true
        } catch (e: Exception) {
            android.util.Log.e("BibleRepo", "Database restoration failed", e)
            false
        }
    }

    suspend fun resetToDefaults(defaultColors: List<Int>) = withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.clearAllBooks()
            dao.clearAllLists()
            insertDefaultLists(defaultColors)
        }
        folderCache.evictAll()
    }

    // Returns the content URI for the Nth audio file (1-based) inside a SAF folder,
    // sorted with natural (numeric) ordering so "Chapter 10" follows "Chapter 9".
    suspend fun resolveChapterFile(
        treeUri: Uri,
        folderDocId: String,
        chapterIndex: Int,
        cache: android.util.LruCache<String, List<String>>? = null
    ): Uri? {
        val cacheKey = "${treeUri}::${folderDocId}"
        val mutex = cacheMutexes.getOrPut(cacheKey) { Mutex() }

        val sortedDocIds = mutex.withLock {
            val cachedFiles = cache?.get(cacheKey)
            if (cachedFiles != null) {
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

                        if (nameIdx == -1 || (idIdx == -1) || (mimeIdx == -1)) {
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
                    cache?.put(cacheKey, empty)
                    empty
                } else {
                    val sorted = files
                        .map { it.second to tokenize(it.first) }
                        .sortedWith { a, b -> compareTokens(a.second, b.second) }
                        .map { it.first }
                    // LruCache automatically evicts oldest entries when exceeding max size
                    cache?.put(cacheKey, sorted)
                    sorted
                }
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

    private fun compareTokens(aToks: List<Pair<Boolean, String>>, bToks: List<Pair<Boolean, String>>): Int {
        for (i in 0 until minOf(aToks.size, bToks.size)) {
            val (aNum, aStr) = aToks[i]; val (bNum, bStr) = bToks[i]
            val cmp = if (aNum && bNum) {
                val aLong = aStr.toLongOrNull() ?: Long.MAX_VALUE
                val bLong = bStr.toLongOrNull() ?: Long.MAX_VALUE
                aLong.compareTo(bLong)
            } else {
                aStr.compareTo(bStr, ignoreCase = true)
            }
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
