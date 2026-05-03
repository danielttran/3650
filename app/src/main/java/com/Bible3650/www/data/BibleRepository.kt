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
import com.Bible3650.www.domain.DefaultsProvider
import com.Bible3650.www.domain.ReadingPlanUseCase
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
    
    private val resolvedUris = MutableStateFlow<Map<String, String>>(emptyMap())
    private val resolvingTasks = ConcurrentHashMap.newKeySet<String>()

    suspend fun freezeActiveTasks() {
        val lists = dao.getAllLists()
        val updates = mutableListOf<suspend () -> Unit>()
        lists.forEach { listData ->
            val list = listData.readingList
            if (list.activeBook == null || list.activeChapter == null) {
                try {
                    val result = ReadingPlanUseCase.calculateTargetTask(listData, 0)
                    updates.add {
                        dao.updateListProgress(list.listId, list.currentDayIndex, result.first, result.second)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("BibleRepo", "Failed to freeze task for list ${list.listId}", e)
                }
            }
        }
        if (updates.isNotEmpty()) {
            database.withTransaction {
                updates.forEach { it.invoke() }
            }
        }
    }

    fun clearCache() {
        folderCache.evictAll()
        cacheMutexes.clear()
    }

    val dailyTasksFlow: Flow<List<DailyTask>> = combine(
        dao.observeActivePlaylists(),
        audioSourceDao.observeActiveMappings(),
        resolvedUris
    ) { lists, activeMappings, uris ->
        val mappingsByBook = activeMappings.associateBy { it.bookName }
        val activeSource   = audioSourceDao.getActiveSource()
        
        val tasks = lists.map { listData ->
            resolveDailyTask(listData, 0, mappingsByBook, activeSource)
        }
        
        val missing = tasks.filter { it.fileUri.isEmpty() && resolvingTasks.add(it.uniqueId) }
        if (missing.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                val newUris = missing.associate { task ->
                    task.uniqueId to getTaskFileUri(task.targetBook, task.targetChapter)
                }
                resolvedUris.update { it + newUris }
                missing.forEach { resolvingTasks.remove(it.uniqueId) }
            }
        }
        
        tasks.map { task ->
            if (task.fileUri.isEmpty()) {
                task.copy(fileUri = uris[task.uniqueId] ?: "")
            } else task
        }
    }
        .distinctUntilChanged()
        .catch { e ->
            android.util.Log.e("BibleRepo", "Error in dailyTasksFlow", e)
            emit(emptyList())
        }
        .flowOn(Dispatchers.IO)

    suspend fun initializeDatabaseIfNeeded() {
        val hasData = withTimeoutOrNull(3000) {
            dao.observeActivePlaylists().first { it.isNotEmpty() }
        } != null
        if (hasData) return

        insertDefaultLists()
    }

    private suspend fun insertDefaultLists() {
        DefaultsProvider.standardLists.forEachIndexed { index, (name, books) ->
            dao.createCustomList(ReadingListEntity(listName = name, listOrder = index, listColor = 0), books)
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
            if (list.activeBook != null && list.activeChapter != null) {
                targetBook = list.activeBook
                targetChapter = list.activeChapter
            } else {
                val result = ReadingPlanUseCase.calculateTargetTask(listData, 0)
                targetBook = result.first
                targetChapter = result.second
            }
        } else {
            val result = ReadingPlanUseCase.calculateTargetTask(listData, dayOffset)
            targetBook = result.first
            targetChapter = result.second
        }

        // fileUri is resolved asynchronously later by the ViewModel to prevent IPC blocking
        val fileUri = ""

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

    suspend fun getTaskFileUri(targetBook: String, targetChapter: Int): String {
        val activeSource = audioSourceDao.getActiveSource() ?: return ""
        val mapping = audioSourceDao.getMappingForBook(activeSource.sourceId, targetBook) ?: return ""
        
        return try {
            val treeUri = (mapping.overrideTreeUri ?: activeSource.rootTreeUri).toUri()
            resolveChapterFile(treeUri, mapping.folderDocId, targetChapter, folderCache)?.toString() ?: ""
        } catch (e: Exception) {
            android.util.Log.e("BibleRepo", "Failed to resolve audio for $targetBook $targetChapter", e)
            ""
        }
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

    suspend fun resetToDefaults() = withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.clearAllBooks()
            dao.clearAllLists()
            insertDefaultLists()
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
