package com.Bible3650.www.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.Bible3650.www.audio.BookDetectionEngine
import com.Bible3650.www.data.BibleRepository
import com.Bible3650.www.data.local.AudioSourceDao
import com.Bible3650.www.data.local.AudioSourceEntity
import com.Bible3650.www.data.local.BookMappingEntity
import com.Bible3650.www.data.local.SourceWithMappings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    private val repository: BibleRepository
) : ViewModel() {

    val sources: StateFlow<List<SourceWithMappings>> = dao.observeAllSources()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _detectionState = MutableStateFlow<DetectionState>(DetectionState.Idle)
    val detectionState: StateFlow<DetectionState> = _detectionState

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
                _detectionState.value = DetectionState.Done(sourceId)
            } catch (e: Exception) {
                android.util.Log.e("SourceManager", "Error adding source", e)
                _detectionState.value = DetectionState.Error(e.localizedMessage ?: "Unknown error")
            }
        }
    }

    fun resetDetectionState() {
        _detectionState.value = DetectionState.Idle
    }

    fun switchSource(source: AudioSourceEntity) {
        viewModelScope.launch {
            try {
                dao.switchTo(source.sourceId)
                repository.clearCache()
            } catch (e: Exception) {
                android.util.Log.e("SourceManager", "Error switching source", e)
            }
        }
    }

    fun deleteSource(source: AudioSourceEntity) {
        viewModelScope.launch {
            try {
                dao.deleteSource(source)
                repository.clearCache()
            } catch (e: Exception) {
                android.util.Log.e("SourceManager", "Error deleting source", e)
            }
        }
    }

}
