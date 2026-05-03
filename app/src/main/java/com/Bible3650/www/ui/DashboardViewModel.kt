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
    val fileUri: String,
    val isCompleted: Boolean,
    val isPlaying: Boolean
)

sealed interface DashboardUiState {
    object Loading : DashboardUiState
    object NoSource : DashboardUiState
    data class Active(val tasks: List<TaskUiModel>) : DashboardUiState
}

sealed interface DashboardAction {
    data class ToggleTask(val listId: Long, val isChecked: Boolean) : DashboardAction
    data class PlayFrom(val taskId: String) : DashboardAction
    object PlayPause : DashboardAction
    object SkipNext : DashboardAction
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: BibleRepository,
    private val audioManager: AudioControllerManager
) : ViewModel() {

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.dailyTasksFlow,
        audioManager.currentMediaId,
        repository.audioSourceDao.observeActiveMappings()
    ) { tasks, playingId, activeMappings ->
        // If there are no mappings at all, no source has been linked yet
        if (activeMappings.isEmpty()) return@combine DashboardUiState.NoSource

        val mappedTasks = tasks.map { task ->
            TaskUiModel(
                id          = task.uniqueId,
                listId      = task.listId,
                dayOffset   = task.dayOffset,
                title       = task.listName,
                subtitle    = "${task.targetBook} ${task.targetChapter}",
                fileUri     = task.fileUri,
                isCompleted = task.isCompleted,
                isPlaying   = task.uniqueId == playingId
            )
        }
        DashboardUiState.Active(mappedTasks)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUiState.Loading)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.initializeDatabaseIfNeeded()
                withContext(Dispatchers.Main) {
                    tryRestorePlayback()
                }
            } catch (e: Exception) {
                android.util.Log.e("DashboardVM", "Initialization failed", e)
            }
        }

        viewModelScope.launch {
            audioManager.completedTracks.collect { listId ->
                try {
                    repository.dao.advanceListDay(listId)
                } catch (e: Exception) {
                    android.util.Log.e("DashboardVM", "Error advancing day", e)
                }
            }
        }
    }

    val isPlaying: StateFlow<Boolean> = audioManager.isPlaying
    val currentPosition: StateFlow<Long> = audioManager.currentPosition
    val duration: StateFlow<Long> = audioManager.duration

    private suspend fun tryRestorePlayback() {
        val savedId  = audioManager.savedMediaId ?: return
        val savedPos = audioManager.savedPosition
        
        // Timeout to avoid hanging if controller never connects
        val player = withTimeoutOrNull(5000) {
            audioManager.player.first { it != null }
        } ?: return

        if (player.mediaItemCount > 0) return
        if (player.playbackState != Player.STATE_IDLE) return
        
        val tasks = repository.dailyTasksFlow.firstOrNull() ?: return
        val index = tasks.indexOfFirst { it.uniqueId == savedId }
        if (index == -1) return

        audioManager.playTasks(tasks, index, savedPos)
        player.pause()
    }

    fun dispatchAction(action: DashboardAction) {
        android.util.Log.d("DashboardVM", "Dispatching action: $action")
        when (action) {
            is DashboardAction.ToggleTask -> viewModelScope.launch {
                repository.dao.updateTaskStatus(action.listId, action.isChecked)
            }
            is DashboardAction.PlayFrom -> {
                if (uiState.value !is DashboardUiState.Active) return
                viewModelScope.launch {
                    val tasks = repository.dailyTasksFlow.first()
                    val index = tasks.indexOfFirst { it.uniqueId == action.taskId }
                    if (index != -1) audioManager.playTasks(tasks, index)
                }
            }
            is DashboardAction.PlayPause -> audioManager.togglePlayPause()
            is DashboardAction.SkipNext  -> audioManager.skipToNext()
        }
    }
}
