package com.Bible3650.www.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.room.withTransaction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.Bible3650.www.audio.AudioControllerManager
import com.Bible3650.www.audio.BookDetectionEngine
import com.Bible3650.www.audio.DetectionResult
import com.Bible3650.www.data.BibleRegistry
import com.Bible3650.www.data.BibleRepository
import com.Bible3650.www.data.local.AppDatabase
import com.Bible3650.www.data.local.AudioSourceDao
import com.Bible3650.www.data.local.AudioSourceEntity
import com.Bible3650.www.data.local.BibleTextDao
import com.Bible3650.www.data.local.BibleTextEntity
import com.Bible3650.www.data.local.BibleTranslationEntity
import com.Bible3650.www.data.local.BookMappingEntity
import com.Bible3650.www.data.local.SourceWithMappings
import com.Bible3650.www.data.text.BibleTextDownloader
import com.Bible3650.www.data.text.BibleTextImporter
import com.Bible3650.www.data.text.CatalogEntry
import com.Bible3650.www.data.text.readUtf8UpTo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface DetectionState {
    object Idle : DetectionState
    object Running : DetectionState
    data class Done(val sourceId: Long) : DetectionState
    data class Error(val message: String) : DetectionState
}

internal fun downloadedCatalogEntryIds(
    translations: List<BibleTranslationEntity>,
    sources: List<SourceWithMappings>,
    catalog: List<CatalogEntry>
): Set<String> {
    val selectableTranslationIds = sources
        .filter { it.source.sourceType == "TEXT" }
        .mapNotNull { it.source.translationId }
        .toSet()
    val installedAbbrevs = translations
        .filter { it.origin == "DOWNLOAD" && it.translationId in selectableTranslationIds }
        .map { it.abbrev }
        .toSet()
    return catalog.filter { it.abbrev in installedAbbrevs }.map { it.id }.toSet()
}

@HiltViewModel
class SourceManagerViewModel @Inject constructor(
    private val dao: AudioSourceDao,
    private val engine: BookDetectionEngine,
    private val repository: BibleRepository,
    private val audioManager: AudioControllerManager,
    private val downloader: BibleTextDownloader,
    private val textDao: BibleTextDao,
    private val database: AppDatabase,
    private val contentResolver: ContentResolver
) : ViewModel() {

    val sources: StateFlow<List<SourceWithMappings>> = dao.observeAllSources()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _detectionState = MutableStateFlow<DetectionState>(DetectionState.Idle)
    val detectionState: StateFlow<DetectionState> = _detectionState

    // #12: Source IDs whose folder is no longer reachable (permission revoked, folder
    // moved/deleted). Surfaced in the UI with a re-link prompt.
    private val _unavailableSourceIds = MutableStateFlow<Set<Long>>(emptySet())
    val unavailableSourceIds: StateFlow<Set<Long>> = _unavailableSourceIds

    // Apocryphal books detected in the most recent scan; non-empty drives a "create an
    // Apocrypha list?" prompt. Cleared on dismiss or after the list is created.
    private val _apocryphaSuggestion = MutableStateFlow<List<String>>(emptyList())
    val apocryphaSuggestion: StateFlow<List<String>> = _apocryphaSuggestion

    // Text-Bible (TTS) catalog.
    private val _catalog = MutableStateFlow<List<CatalogEntry>>(emptyList())
    val catalog: StateFlow<List<CatalogEntry>> = _catalog

    // The catalog entry id currently downloading (or IMPORT_ID for a file import), else null.
    // Only one text operation runs at a time; the dialog stays on the catalog list throughout.
    private val _downloadingId = MutableStateFlow<String?>(null)
    val downloadingId: StateFlow<String?> = _downloadingId

    // Last download/import failure, shown inline in the dialog (a snackbar would sit behind it).
    private val _textOpError = MutableStateFlow<String?>(null)
    val textOpError: StateFlow<String?> = _textOpError

    // Catalog entry ids that are already installed and selectable (a DOWNLOAD-origin
    // translation plus its TEXT source exist). Drives the per-row Download → Delete toggle.
    val downloadedEntryIds: StateFlow<Set<String>> =
        combine(textDao.observeTranslations(), sources, _catalog, ::downloadedCatalogEntryIds)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // Catalog entry id whose translation is the active source, so the row can show Active / Use.
    val activeTextEntryId: StateFlow<String?> =
        combine(sources, textDao.observeTranslations(), _catalog) { srcs, translations, catalog ->
            val active = srcs.firstOrNull { it.source.isActive && it.source.sourceType == "TEXT" }
                ?: return@combine null
            val abbrev = translations.firstOrNull { it.translationId == active.source.translationId }?.abbrev
                ?: return@combine null
            catalog.firstOrNull { it.abbrev == abbrev }?.id
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Re-checks whether each source's root folder is still accessible. */
    fun refreshSourceHealth() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val all = dao.getAllSources()
                val held = contentResolver.persistedUriPermissions
                    .filter { it.isReadPermission }
                    .map { it.uri.toString() }
                    .toSet()
                val bad = all.mapNotNull { swm ->
                    // TEXT sources are backed by a stored translation (rootTreeUri = "text://<id>"),
                    // not a SAF folder, so the folder reachability check never applies to them.
                    if (swm.source.sourceType == "TEXT") return@mapNotNull null
                    val uriStr = swm.source.rootTreeUri
                    val ok = held.contains(uriStr) && canQueryTree(uriStr)
                    if (ok) null else swm.source.sourceId
                }.toSet()
                _unavailableSourceIds.value = bad
            } catch (e: Exception) {
                android.util.Log.e("SourceManager", "Health check failed", e)
            }
        }
    }

    private fun canQueryTree(treeUriStr: String): Boolean = try {
        val treeUri = treeUriStr.toUri()
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    // Called by the composable after the user picks a root folder via OpenDocumentTree.
    fun onRootFolderPicked(treeUri: Uri, suggestedName: String) {
        viewModelScope.launch {
            _detectionState.value = DetectionState.Running
            try {
                val results = withContext(Dispatchers.IO) {
                    engine.detect(treeUri)
                }

                val sourceId = withContext(Dispatchers.IO) {
                    database.withTransaction {
                        val active = dao.getActiveSource()
                        val id = dao.insertSource(
                            AudioSourceEntity(
                                displayName  = suggestedName,
                                rootTreeUri  = treeUri.toString(),
                                isActive     = active == null // auto-activate first source
                            )
                        )
                        val mappings = results.mapNotNull { r ->
                            r.folderDocId?.let { docId ->
                                BookMappingEntity(
                                    sourceId   = id,
                                    bookName   = r.bookName,
                                    folderDocId = docId,
                                    confidence = r.confidence,
                                    fileCount  = r.fileCount
                                )
                            }
                        }
                        dao.upsertMappings(mappings)
                        id
                    }
                }

                repository.clearCache()
                refreshSourceHealth()
                updateApocryphaSuggestion(results)
                _detectionState.value = DetectionState.Done(sourceId)
            } catch (e: Exception) {
                android.util.Log.e("SourceManager", "Error adding source", e)
                _detectionState.value = DetectionState.Error(e.localizedMessage ?: "Unknown error")
            }
        }
    }

    /**
     * #12: Re-points an existing source at a freshly picked folder and re-detects its
     * book mappings, used to recover when the original folder's access was lost.
     */
    fun relinkSource(sourceId: Long, treeUri: Uri, suggestedName: String) {
        viewModelScope.launch {
            _detectionState.value = DetectionState.Running
            try {
                val existing = withContext(Dispatchers.IO) {
                    dao.getAllSources().firstOrNull { it.source.sourceId == sourceId }
                }
                if (existing == null) {
                    _detectionState.value = DetectionState.Error("Source not found")
                    return@launch
                }
                val results = withContext(Dispatchers.IO) { engine.detect(treeUri) }
                withContext(Dispatchers.IO) {
                    database.withTransaction {
                        dao.updateSource(
                            existing.source.copy(
                                rootTreeUri = treeUri.toString(),
                                displayName = suggestedName
                            )
                        )
                        dao.clearMappingsForSource(sourceId)
                        val mappings = results.mapNotNull { r ->
                            r.folderDocId?.let { docId ->
                                BookMappingEntity(
                                    sourceId    = sourceId,
                                    bookName    = r.bookName,
                                    folderDocId = docId,
                                    confidence  = r.confidence,
                                    fileCount   = r.fileCount
                                )
                            }
                        }
                        dao.upsertMappings(mappings)
                    }
                }
                repository.clearCache()
                if (existing.source.isActive) audioManager.reloadCurrentPlaylist()
                refreshSourceHealth()
                updateApocryphaSuggestion(results)
                _detectionState.value = DetectionState.Done(sourceId)
            } catch (e: Exception) {
                android.util.Log.e("SourceManager", "Error re-linking source", e)
                _detectionState.value = DetectionState.Error(e.localizedMessage ?: "Unknown error")
            }
        }
    }

    fun resetDetectionState() {
        _detectionState.value = DetectionState.Idle
    }

    private fun updateApocryphaSuggestion(results: List<DetectionResult>) {
        val order = BibleRegistry.getApocryphalBooks()
        _apocryphaSuggestion.value = results
            .filter {
                it.folderDocId != null &&
                    it.confidence >= BookDetectionEngine.MIN_CONFIDENCE_THRESHOLD &&
                    BibleRegistry.isApocryphal(it.bookName)
            }
            .map { it.bookName }
            .sortedBy { order.indexOf(it) }
    }

    /** Creates a new "Apocrypha" list (or merges into an existing one) from the detected books. */
    fun createOrUpdateApocryphaList() {
        val detected = _apocryphaSuggestion.value
        if (detected.isEmpty()) return
        viewModelScope.launch {
            try {
                val existing = withContext(Dispatchers.IO) {
                    repository.dao.getAllLists()
                        .firstOrNull { it.readingList.listName.equals(APOCRYPHA_LIST_NAME, ignoreCase = true) }
                }
                if (existing == null) {
                    repository.createList(APOCRYPHA_LIST_NAME, detected, 0)
                } else {
                    val existingBooks = existing.books.sortedBy { it.sortOrder }.map { it.bookName }
                    val merged = existingBooks + detected.filter { it !in existingBooks }
                    repository.updateList(existing.readingList, merged)
                }
                _apocryphaSuggestion.value = emptyList()
            } catch (e: Exception) {
                android.util.Log.e("SourceManager", "Failed to create Apocrypha list", e)
                _uiEvents.emit("Failed to create Apocrypha list.")
            }
        }
    }

    fun dismissApocryphaSuggestion() {
        _apocryphaSuggestion.value = emptyList()
    }

    // ---------------------------------------------------------------------------
    // Text Bibles (TTS)
    // ---------------------------------------------------------------------------

    fun loadCatalog() {
        viewModelScope.launch {
            _catalog.value = runCatching { withContext(Dispatchers.IO) { downloader.loadCatalog() } }
                .getOrDefault(emptyList())
        }
    }

    /** Clears a previous download/import error so a reopened dialog starts clean. */
    fun clearTextOpError() {
        _textOpError.value = null
    }

    /** Downloads a catalog translation into the text store and adds it as a TEXT source. */
    fun downloadTranslation(entry: CatalogEntry) {
        if (_downloadingId.value != null) return            // one text op at a time
        _textOpError.value = null
        _downloadingId.value = entry.id                     // set on the caller thread to block re-entry
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Idempotent: never download a translation that is already installed (#2).
                val existing = textDao.getTranslations()
                    .firstOrNull { it.origin == "DOWNLOAD" && it.abbrev == entry.abbrev }
                val existingSource = existing?.let { translation ->
                    dao.getAllSources().firstOrNull { it.source.translationId == translation.translationId }
                }
                if (existingSource == null) {
                    // A translation without a source is debris from an interrupted write in an
                    // older app version. Remove it and perform a clean transactional download.
                    existing?.let { textDao.deleteTranslation(it) }
                    downloader.download(entry) { translationId ->
                        insertTextSource(translationId, entry.name)
                    }
                    repository.clearCache()
                }
            } catch (e: Exception) {
                android.util.Log.e("SourceManager", "Text download failed", e)
                _textOpError.value = "Couldn't download ${entry.name}: ${e.localizedMessage ?: "download failed"}"
            } finally {
                _downloadingId.value = null
            }
        }
    }

    /** Deletes an installed catalog translation (its TEXT source + stored text). */
    fun deleteDownloadedTranslation(entry: CatalogEntry) {
        viewModelScope.launch {
            val source = withContext(Dispatchers.IO) {
                val t = textDao.getTranslations().firstOrNull { it.origin == "DOWNLOAD" && it.abbrev == entry.abbrev }
                t?.let { tr -> dao.getAllSources().firstOrNull { it.source.translationId == tr.translationId }?.source }
            } ?: return@launch
            deleteSource(source)
        }
    }

    /** Activates the installed translation for [entry] so playback reads it aloud. */
    fun useDownloadedTranslation(entry: CatalogEntry) {
        viewModelScope.launch {
            val source = withContext(Dispatchers.IO) {
                val t = textDao.getTranslations().firstOrNull { it.origin == "DOWNLOAD" && it.abbrev == entry.abbrev }
                t?.let { tr -> dao.getAllSources().firstOrNull { it.source.translationId == tr.translationId }?.source }
            } ?: return@launch
            switchSource(source)
        }
    }

    /** Imports a user-supplied text file (any supported format) as a TEXT source. */
    fun importText(uri: Uri, displayName: String) {
        if (_downloadingId.value != null) return
        _textOpError.value = null
        _downloadingId.value = IMPORT_ID
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val content = contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readUtf8UpTo(MAX_TEXT_IMPORT_BYTES)
                    } ?: throw IllegalStateException("Could not read file or file exceeds 32 MB")
                    val chapters = BibleTextImporter.parse(content)
                    if (chapters.isEmpty()) throw IllegalStateException("No scripture found in file")
                    val hasApoc = chapters.any { BibleRegistry.isApocryphal(it.book) }
                    database.withTransaction {
                        val tid = textDao.insertTranslation(
                            BibleTranslationEntity(
                                name = displayName,
                                abbrev = displayName.take(8),
                                origin = "IMPORT",
                                hasApocrypha = hasApoc,
                                license = "Imported by user"
                            )
                        )
                        chapters.chunked(200).forEach { batch ->
                            textDao.upsertChapters(batch.map { BibleTextEntity(tid, it.book, it.chapter, it.text) })
                        }
                        insertTextSource(tid, displayName)
                    }
                }
                repository.clearCache()
            } catch (e: Exception) {
                android.util.Log.e("SourceManager", "Text import failed", e)
                _textOpError.value = "Import failed: ${e.localizedMessage ?: "could not read file"}"
            } finally {
                _downloadingId.value = null
            }
        }
    }

    private suspend fun insertTextSource(translationId: Long, name: String) {
        val active = dao.getActiveSource()
        dao.insertSource(
            AudioSourceEntity(
                displayName = name,
                rootTreeUri = "text://$translationId",
                isActive = active == null,
                sourceType = "TEXT",
                translationId = translationId
            )
        )
    }

    private val _uiEvents = MutableSharedFlow<String>()
    val uiEvents: SharedFlow<String> = _uiEvents.asSharedFlow()

    fun switchSource(source: AudioSourceEntity) {
        viewModelScope.launch {
            try {
                // Capture playback before switching so we can resume the same book+chapter
                // on the new source (position is reset only when the source type changes).
                val snapshot = audioManager.currentPlaybackSnapshot()
                audioManager.invalidatePendingPlaylistLoads()
                val previous = dao.getActiveSource()
                val typeChanged = previous != null && previous.sourceType != source.sourceType
                dao.switchTo(source.sourceId)
                repository.clearCache()
                refreshSourceHealth()
                if (snapshot != null) audioManager.resumeFromSnapshot(snapshot, resetPosition = typeChanged)
            } catch (e: Exception) {
                android.util.Log.e("SourceManager", "Error switching source", e)
                _uiEvents.emit("Failed to switch audio source.")
            }
        }
    }

    fun deleteSource(source: AudioSourceEntity) {
        viewModelScope.launch {
            try {
                val snapshot = audioManager.currentPlaybackSnapshot()
                val result = database.withTransaction {
                    val deleteResult = dao.deleteAndActivateFallback(source)
                    // For a text source, also remove the stored translation (cascades its text).
                    if (source.sourceType == "TEXT") {
                        source.translationId?.let { tid ->
                            textDao.getTranslation(tid)?.let { textDao.deleteTranslation(it) }
                        }
                    }
                    deleteResult
                }
                repository.clearCache()
                refreshSourceHealth()
                // Purge the deleted translation's synthesized TTS audio so its WAVs don't linger
                // in the cache until LRU eviction at the 250 MB cap.
                if (source.sourceType == "TEXT") {
                    source.translationId?.let { tid ->
                        withContext(Dispatchers.IO) { audioManager.clearTtsCacheForTranslation(tid) }
                    }
                }
                // Only disturb live playback when the ACTIVE source was deleted. Invalidating
                // pending playlist loads unconditionally would cancel the active source's
                // in-flight TTS prefetch when an unrelated, inactive source is removed, leaving
                // the loaded playlist's later chapters URI-less (ExoPlayer would then error on
                // auto-transition). stopPlayback()/resumeFromSnapshot() each invalidate on their
                // own, so the explicit invalidate belongs inside this branch.
                if (result.deletedActiveSource) {
                    audioManager.invalidatePendingPlaylistLoads()
                    val fallback = result.fallbackSource
                    if (fallback == null) {
                        audioManager.stopPlayback()
                    } else if (snapshot != null) {
                        audioManager.resumeFromSnapshot(
                            snapshot,
                            resetPosition = source.sourceType != fallback.sourceType
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SourceManager", "Error deleting source", e)
                _uiEvents.emit("Failed to delete audio source.")
            }
        }
    }

    companion object {
        private const val APOCRYPHA_LIST_NAME = "Apocrypha"
        private const val MAX_TEXT_IMPORT_BYTES = 32 * 1024 * 1024
        /** Sentinel [downloadingId] value used while a user file import is in progress. */
        const val IMPORT_ID = "::import::"
    }
}
