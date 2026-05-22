package com.Bible3650.www.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.Bible3650.www.data.local.AudioSourceDao
import com.Bible3650.www.data.local.AudioSourceEntity
import com.Bible3650.www.data.local.BibleDao
import com.Bible3650.www.data.local.BibleTextDao
import com.Bible3650.www.data.local.BookMappingEntity
import com.Bible3650.www.data.local.DailyTask
import com.Bible3650.www.data.local.ListWithBooks
import com.Bible3650.www.data.local.ReadingListEntity
import com.Bible3650.www.domain.DefaultsProvider
import com.Bible3650.www.domain.PresetPlan
import com.Bible3650.www.domain.ReadingPlanUseCase
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import androidx.room.withTransaction
import com.Bible3650.www.data.local.AppDatabase

private const val MAX_FOLDER_CACHE_ENTRIES = 100

// Current backup schema version. Checked on import to guard against stale backups.
private const val BACKUP_VERSION_CURRENT = 1

// Tolerant JSON config: ignore unknown keys (forward-compat) and coerce nulls on
// non-null-with-default fields so partial/older backups still import cleanly.
private val BackupJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
}

@Singleton
class BibleRepository @Inject constructor(
    private val database: AppDatabase,
    // #15: Both DAOs are internal so AudioControllerManager (same module) can access
    // audioSourceDao directly for hot-path URI resolution, but external callers must
    // go through repository methods which also handle cache invalidation.
    internal val dao: BibleDao,
    internal val audioSourceDao: AudioSourceDao,
    internal val bibleTextDao: BibleTextDao,
    private val contentResolver: ContentResolver
) {
    // Shared cache of folder→sorted-docIds so both dailyTasksFlow and
    // playTasks benefit from the same one-time directory scan.
    internal val folderCache = android.util.LruCache<String, List<String>>(MAX_FOLDER_CACHE_ENTRIES)
    private val cacheMutexes = ConcurrentHashMap<String, Mutex>()

    private val resolvedUris = MutableStateFlow<Map<String, String>>(emptyMap())
    private val resolvingTasks = ConcurrentHashMap.newKeySet<String>()
    // Lifecycle-bound scope for background URI resolution — never leaks like ad-hoc scopes
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // #7: AtomicBoolean + compareAndSet prevents two coroutines racing past the check
    // simultaneously (a @Volatile var is visible across threads but doesn't give
    // compare-and-set atomicity, so TOCTOU is possible under concurrent callers).
    private val frozenOnce = AtomicBoolean(false)

    /** Emits true when at least one audio source mapping is active. */
    val hasActiveSourceFlow: Flow<Boolean> =
        audioSourceDao.observeActiveMappings().map { it.isNotEmpty() }

    // #15: Expose raw list-with-books data for screens that need full entity access
    // (ManageListsScreen, ProfileScreen) without leaking the DAO itself.
    val listsWithBooksFlow: Flow<List<ListWithBooks>> = dao.observeActivePlaylists()

    // #7: idempotency guard — freezeActiveTasks is called on every cold start and must
    // not issue N database writes if the user re-opens the app rapidly.
    suspend fun freezeActiveTasks() {
        if (!frozenOnce.compareAndSet(false, true)) return

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
        resolvedUris.value = emptyMap()
        resolvingTasks.clear()
    }

    val dailyTasksFlow: Flow<List<DailyTask>> = combine(
        dao.observeActivePlaylists(),
        resolvedUris
    ) { lists, uris ->
        val tasks = lists.map { listData ->
            resolveDailyTask(listData, 0)
        }

        // 1A: Guard by key presence, NOT by empty string. getTaskFileUri returns "" when a
        // file can't be found, and the old isEmpty() check would re-queue that task on every
        // emission, creating an infinite background-fetch loop that drains the battery.
        val missing = tasks.filter { !uris.containsKey(it.uniqueId) && resolvingTasks.add(it.uniqueId) }
        if (missing.isNotEmpty()) {
            repositoryScope.launch {
                try {
                    val newUris = missing.associate { task ->
                        task.uniqueId to getTaskFileUri(task.targetBook, task.targetChapter)
                    }
                    resolvedUris.update { it + newUris }
                } finally {
                    // Always remove from in-flight set so a failure doesn't permanently
                    // block re-resolution for these tasks on the next emission.
                    missing.forEach { resolvingTasks.remove(it.uniqueId) }
                }
            }
        }

        tasks.map { task ->
            // Use the cached value if the key exists (even if the value is empty —
            // that means we already tried and the file wasn't found, so don't retry).
            val cachedUri = uris[task.uniqueId]
            if (cachedUri != null) task.copy(fileUri = cachedUri) else task
        }
    }
        .distinctUntilChanged()
        .catch { e ->
            android.util.Log.e("BibleRepo", "Error in dailyTasksFlow", e)
            emit(emptyList())
        }
        .flowOn(Dispatchers.IO)

    suspend fun initializeDatabaseIfNeeded() {
        // Use a direct count query — eliminates the race condition of observing
        // an empty-first-emission from Room before the DB is fully ready.
        if (dao.countLists() > 0) return
        insertDefaultLists()
    }

    private suspend fun insertDefaultLists() {
        DefaultsProvider.standardLists.forEachIndexed { index, (name, books) ->
            dao.createCustomList(ReadingListEntity(listName = name, listOrder = index, listColor = 0), books)
        }
    }

    // ---------------------------------------------------------------------------
    // #15: Repository-level wrappers for list mutations (ManageListsViewModel,
    // ProfileViewModel, etc. should call these instead of accessing dao directly)
    // ---------------------------------------------------------------------------

    suspend fun deleteList(list: ReadingListEntity) = dao.deleteList(list)

    suspend fun createList(name: String, books: List<String>, colorArgb: Int) {
        val maxOrder = dao.getMaxListOrder() ?: -1
        dao.createCustomList(
            ReadingListEntity(listName = name, listColor = colorArgb, listOrder = maxOrder + 1),
            books
        )
    }

    suspend fun updateList(list: ReadingListEntity, newBooks: List<String>) =
        dao.updateCustomList(list, newBooks)

    suspend fun reorderLists(listIds: List<Long>) = dao.reorderLists(listIds)

    suspend fun resetStats() = dao.resetAllStats()

    // ---------------------------------------------------------------------------
    // Chapter resolution
    // ---------------------------------------------------------------------------

    private suspend fun resolveDailyTask(
        listData: ListWithBooks,
        dayOffset: Int
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

        val sortedBooks = listData.books.sortedBy { it.sortOrder }
        val totalChapters = sortedBooks.sumOf { BibleRegistry.getChapterCount(it.bookName) }

        // Cumulative position (1..totalChapters) of the current chapter within the cycle.
        var loopPosition = 0
        for (b in sortedBooks) {
            val count = BibleRegistry.getChapterCount(b.bookName)
            if (b.bookName == targetBook) {
                loopPosition += targetChapter.coerceIn(0, count)
                break
            }
            loopPosition += count
        }

        return DailyTask(
            listId        = list.listId,
            // 1C: Include book+chapter so ExoPlayer's playlist match check correctly
            // detects when the user edits a list (e.g., swaps Genesis for Exodus).
            // Old format "listId_dayOffset" was stable across edits — ExoPlayer would
            // assume the playlist hadn't changed and replay the old audio file.
            uniqueId      = "${list.listId}_${dayOffset}_${targetBook}_${targetChapter}",
            listName      = list.listName,
            targetBook    = targetBook,
            targetChapter = targetChapter,
            totalChapters = totalChapters,
            loopPosition  = loopPosition,
            books         = sortedBooks.map { it.bookName },
            fileUri       = fileUri,
            listColor     = list.listColor
        )
    }

    /**
     * Repositions a list's cursor to [targetBook] [targetChapter] without changing the
     * lifetime progress counter, so manual jumps don't inflate or erase read statistics.
     * Implemented as an adjustment to the manual offset (which also clears the frozen task).
     */
    suspend fun jumpToChapter(listId: Long, targetBook: String, targetChapter: Int) {
        val listData = dao.getListWithBooksById(listId) ?: return
        val sortedBooks = listData.books.sortedBy { it.sortOrder }
        val total = sortedBooks.sumOf { BibleRegistry.getChapterCount(it.bookName) }
        if (total == 0) return

        var position = 0
        var matched = false
        for (b in sortedBooks) {
            val count = BibleRegistry.getChapterCount(b.bookName)
            if (b.bookName == targetBook) {
                position += targetChapter.coerceIn(1, count)
                matched = true
                break
            }
            position += count
        }
        if (!matched || position <= 0) return

        // Choose an offset so the computed position lands on `position` while leaving
        // currentDayIndex (and therefore stats) untouched.
        val newOffset = position - listData.readingList.currentDayIndex
        dao.updateManualOffset(listId, newOffset)
    }

    suspend fun getTaskFileUri(targetBook: String, targetChapter: Int): String {
        val activeSource = audioSourceDao.getActiveSource() ?: return ""

        // For TEXT (TTS) sources, report availability cheaply WITHOUT synthesizing — the
        // actual audio is rendered lazily at play time. This sentinel is never played; it
        // only signals to the UI/resync that this chapter has text.
        if (activeSource.sourceType == "TEXT") {
            val tid = activeSource.translationId ?: return ""
            return if (bibleTextDao.getChapter(tid, targetBook, targetChapter) != null)
                "tts://$tid/$targetBook/$targetChapter" else ""
        }

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
        BackupJson.encodeToString(backup)
    }

    // #3: Guard prevents wiping audio sources when the backup contains none.
    // #14: Reject backups with an unsupported schema version to avoid silent corruption.
    suspend fun importProgress(json: String): Boolean = withContext(Dispatchers.IO) {
        val backup = try {
            BackupJson.decodeFromString<ProgressBackup>(json)
        } catch (e: Exception) {
            android.util.Log.e("BibleRepo", "Import JSON parse failed", e)
            null
        } ?: return@withContext false

        // #14: Version guard — reject unknown future schemas
        if (backup.version > BACKUP_VERSION_CURRENT) {
            android.util.Log.e("BibleRepo", "Unsupported backup version ${backup.version} (current: $BACKUP_VERSION_CURRENT)")
            return@withContext false
        }

        // Nullable list fields are guarded below so partial backups still import cleanly.
        val readingLists = backup.readingLists ?: emptyList()
        val audioSources = backup.audioSources ?: emptyList()

        try {
            database.withTransaction {
                dao.clearAllBooks()
                dao.clearAllLists()

                readingLists.forEach { rb ->
                    if (rb?.entity == null) return@forEach
                    val list = rb.entity.copy(listId = 0)
                    val newId = dao.insertList(list)
                    val books = rb.books ?: emptyList()
                    dao.insertBooks(books.map { it.copy(id = 0, listId = newId) })
                }

                // #3: Only replace audio sources if the backup actually includes them.
                // An old/stripped backup with no sources must NOT wipe existing mappings.
                if (audioSources.isNotEmpty()) {
                    audioSourceDao.clearAllMappings()
                    audioSourceDao.clearAllSources()
                    audioSources.forEach { sb ->
                        if (sb?.entity == null) return@forEach
                        val source = sb.entity.copy(sourceId = 0)
                        val newId = audioSourceDao.insertSource(source)
                        val mappings = sb.mappings ?: emptyList()
                        audioSourceDao.upsertMappings(mappings.map { it.copy(sourceId = newId) })
                    }
                }
            }

            // Clear all caches (including cacheMutexes) so stale URI paths don't
            // bleed into the newly restored data.
            clearCache()
            true
        } catch (e: Exception) {
            android.util.Log.e("BibleRepo", "Database restoration failed", e)
            false
        }
    }

    // #4: StateFlow resets now happen AFTER the transaction completes so there is no
    // window where dailyTasksFlow sees stale IDs and starts background URI resolution
    // for lists that are mid-deletion.
    suspend fun resetToDefaults(plan: PresetPlan = PresetPlan.GrantHorner) = withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.clearAllBooks()
            dao.clearAllLists()
            val lists = DefaultsProvider.listsFor(plan)
            lists.forEachIndexed { index, (name, books) ->
                dao.createCustomList(ReadingListEntity(listName = name, listOrder = index, listColor = 0), books)
            }
        }
        // Reset caches only after the transaction is committed
        resolvedUris.value = emptyMap()
        resolvingTasks.clear()
        folderCache.evictAll()
        // Allow freezeActiveTasks to re-freeze the new lists on next launch
        frozenOnce.set(false)
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
        // computeIfAbsent is atomic — getOrPut on ConcurrentHashMap is NOT, so two
        // concurrent callers could create separate Mutex objects for the same key,
        // defeating the purpose of the lock.
        val mutex = cacheMutexes.computeIfAbsent(cacheKey) { Mutex() }

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
                    // #2: Cache the failure as an empty list to prevent re-querying a broken
                    // SAF provider on every dailyTasksFlow emission.
                    cache?.put(cacheKey, emptyList())
                    return null
                }

                if (files.isEmpty()) {
                    android.util.Log.w("BibleRepo", "No audio files found in $folderDocId")
                    val empty = emptyList<String>()
                    cache?.put(cacheKey, empty)
                    empty
                } else {
                    // Natural numeric ordering so "Chapter 10" follows "Chapter 9".
                    val sorted = NaturalFileSort.sortedDocIds(files)
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
}
