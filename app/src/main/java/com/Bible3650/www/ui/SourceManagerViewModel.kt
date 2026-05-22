package com.Bible3650.www.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.Bible3650.www.audio.BookDetectionEngine
import com.Bible3650.www.audio.DetectionResult
import com.Bible3650.www.data.BibleRegistry
import com.Bible3650.www.data.BibleRepository
import com.Bible3650.www.data.local.AudioSourceDao
import com.Bible3650.www.data.local.AudioSourceEntity
import com.Bible3650.www.data.local.BookMappingEntity
import com.Bible3650.www.data.local.SourceWithMappings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

@HiltViewModel
class SourceManagerViewModel @Inject constructor(
    private val dao: AudioSourceDao,
    private val engine: BookDetectionEngine,
    private val repository: BibleRepository,
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
                    val active = dao.getActiveSource()
                    dao.insertSource(
                        AudioSourceEntity(
                            displayName  = suggestedName,
                            rootTreeUri  = treeUri.toString(),
                            isActive     = active == null // auto-activate first source
                        )
                    )
                }

                withContext(Dispatchers.IO) {
                    val mappings = results.mapNotNull { r ->
                        r.folderDocId?.let { docId ->
                            BookMappingEntity(
                                sourceId   = sourceId,
                                bookName   = r.bookName,
                                folderDocId = docId,
                                confidence = r.confidence,
                                fileCount  = r.fileCount
                            )
                        }
                    }
                    dao.upsertMappings(mappings)
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
                repository.clearCache()
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

    private val _uiEvents = MutableSharedFlow<String>()
    val uiEvents: SharedFlow<String> = _uiEvents.asSharedFlow()

    fun switchSource(source: AudioSourceEntity) {
        viewModelScope.launch {
            try {
                dao.switchTo(source.sourceId)
                repository.clearCache()
                refreshSourceHealth()
            } catch (e: Exception) {
                android.util.Log.e("SourceManager", "Error switching source", e)
                _uiEvents.emit("Failed to switch audio source.")
            }
        }
    }

    fun deleteSource(source: AudioSourceEntity) {
        viewModelScope.launch {
            try {
                dao.deleteSource(source)
                repository.clearCache()
                refreshSourceHealth()
            } catch (e: Exception) {
                android.util.Log.e("SourceManager", "Error deleting source", e)
                _uiEvents.emit("Failed to delete audio source.")
            }
        }
    }

    private companion object {
        const val APOCRYPHA_LIST_NAME = "Apocrypha"
    }
}
