package com.Bible3650.www.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.Bible3650.www.audio.AudioControllerManager
import com.Bible3650.www.data.BibleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@Immutable
data class TaskUiModel(
    val id: String,
    val listId: Long,
    val dayOffset: Int,
    val title: String,
    val subtitle: String,
    val totalChapters: Int,
    val fileUri: String
)

sealed interface DashboardUiState {
    object Loading : DashboardUiState
    object NoSource : DashboardUiState
    data class Active(val tasks: List<TaskUiModel>) : DashboardUiState
}

sealed interface DashboardAction {
    data class CompleteTask(val listId: Long) : DashboardAction
    data class UndoComplete(val listId: Long) : DashboardAction
    data class IncrementProgress(val listId: Long) : DashboardAction
    data class DecrementProgress(val listId: Long) : DashboardAction
    data class PlayFrom(val taskId: String) : DashboardAction
    object PlayPause : DashboardAction
    object SkipNext : DashboardAction
}

sealed interface DashboardUiEvent {
    data class ShowSnackbar(val message: String, val actionLabel: String, val listId: Long) : DashboardUiEvent
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: BibleRepository,
    private val audioManager: AudioControllerManager
) : ViewModel() {

    private val _uiEvents = MutableSharedFlow<DashboardUiEvent>()
    val uiEvents = _uiEvents.asSharedFlow()

    // currentMediaId is exposed separately so the UI can derive isPlaying per-item
    // without triggering a full re-map of all tasks on every track transition.
    val currentMediaId: StateFlow<String?> = audioManager.currentMediaId

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.dailyTasksFlow,
        repository.audioSourceDao.observeActiveMappings()
    ) { tasks, activeMappings ->
        if (activeMappings.isEmpty()) return@combine DashboardUiState.NoSource

        val mappedTasks = tasks.map { task ->
            TaskUiModel(
                id            = task.uniqueId,
                listId        = task.listId,
                dayOffset     = task.dayOffset,
                title         = task.listName,
                subtitle      = "${task.targetBook} ${task.targetChapter}",
                totalChapters = task.totalChapters,
                fileUri       = task.fileUri
            )
        }
        DashboardUiState.Active(mappedTasks)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUiState.Loading)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.initializeDatabaseIfNeeded()

                // Read from SharedPreferences on IO thread
                val savedId = audioManager.savedMediaId
                val savedPos = audioManager.savedPosition

                withContext(Dispatchers.Main) {
                    tryRestorePlayback(savedId, savedPos)
                }
            } catch (e: Exception) {
                android.util.Log.e("DashboardVM", "Initialization failed", e)
            }
        }
        // Track completion is now handled inside AudioControllerManager's own scope,
        // ensuring advanceListDay runs even when the UI is in the background.
    }

    val isPlaying: StateFlow<Boolean> = audioManager.isPlaying
    val currentPosition: StateFlow<Long> = audioManager.currentPosition
    val duration: StateFlow<Long> = audioManager.duration

    private suspend fun syncPlayerIfPlaying(listId: Long) {
        // taskId for day 0 is always "listId_0"
        val currentId = currentMediaId.value
        if (currentId == "${listId}_0") {
            // Guard against an indefinitely-suspended coroutine when the audio source
            // folder is missing or unlinked (fileUri would never become non-empty).
            val updatedTasks = withTimeoutOrNull(5_000) {
                repository.dailyTasksFlow
                    .filter { tasks -> tasks.any { it.listId == listId && it.fileUri.isNotEmpty() } }
                    .first()
            } ?: return  // Audio source unavailable; skip resync

            val startIndex = updatedTasks.indexOfFirst { it.uniqueId == currentId }
            if (startIndex != -1) {
                val playlist = updatedTasks.drop(startIndex) + updatedTasks.take(startIndex)
                audioManager.playTasks(playlist, 0)
            }
        }
    }

    private suspend fun tryRestorePlayback(savedId: String?, savedPos: Long) {
        if (savedId == null) return

        // Timeout to avoid hanging if controller never connects
        val player = withTimeoutOrNull(5000) {
            audioManager.player.first { it != null }
        } ?: return

        if (player.mediaItemCount > 0) return
        if (player.playbackState != Player.STATE_IDLE) return

        val tasks = repository.dailyTasksFlow.firstOrNull() ?: return
        val index = tasks.indexOfFirst { it.uniqueId == savedId }
        if (index == -1) return

        // Reorder so the saved track is at index 0; prevents prepending items at index 0
        // into an active ExoPlayer timeline which shifts currentMediaItemIndex.
        val playlist = tasks.drop(index) + tasks.take(index)
        audioManager.playTasks(playlist, 0, savedPos)
        player.pause()
    }

    fun dispatchAction(action: DashboardAction) {
        android.util.Log.d("DashboardVM", "Dispatching action: $action")
        when (action) {
            is DashboardAction.CompleteTask -> viewModelScope.launch {
                repository.advanceListDay(action.listId)
                _uiEvents.emit(DashboardUiEvent.ShowSnackbar(
                    message = "Task completed",
                    actionLabel = "Undo",
                    listId = action.listId
                ))
            }
            is DashboardAction.UndoComplete -> viewModelScope.launch {
                repository.revertListDay(action.listId)
            }
            is DashboardAction.IncrementProgress -> viewModelScope.launch {
                repository.incrementManualOffset(action.listId)
                syncPlayerIfPlaying(action.listId)
            }
            is DashboardAction.DecrementProgress -> viewModelScope.launch {
                repository.decrementManualOffset(action.listId)
                syncPlayerIfPlaying(action.listId)
            }
            is DashboardAction.PlayFrom -> {
                if (uiState.value !is DashboardUiState.Active) return
                viewModelScope.launch {
                    val tasks = repository.dailyTasksFlow.first()
                    val startIndex = tasks.indexOfFirst { it.uniqueId == action.taskId }
                    if (startIndex == -1) return@launch
                    // Wrap playlist so tapped item plays first, then remaining tasks,
                    // then loops back to the beginning of today's list.
                    val playlist = tasks.drop(startIndex) + tasks.take(startIndex)
                    audioManager.playTasks(playlist, 0)
                }
            }
            is DashboardAction.PlayPause -> audioManager.togglePlayPause()
            is DashboardAction.SkipNext  -> audioManager.skipToNext()
        }
    }
}
