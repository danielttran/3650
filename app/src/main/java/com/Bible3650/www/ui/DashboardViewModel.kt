package com.Bible3650.www.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.Bible3650.www.audio.AudioControllerManager
import com.Bible3650.www.data.BibleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
    data class Active(
        val tasks: List<TaskUiModel>
    ) : DashboardUiState
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
        audioManager.currentMediaId
    ) { tasks, playingId ->
        val mappedTasks = tasks.map { task ->
            TaskUiModel(
                id = task.uniqueId,
                listId = task.listId,
                dayOffset = task.dayOffset,
                title = task.listName,
                subtitle = "${task.targetBook} ${task.targetChapter}",
                fileUri = task.fileUri,
                isCompleted = task.isCompleted,
                isPlaying = task.uniqueId == playingId
            )
        }
        DashboardUiState.Active(mappedTasks)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUiState.Loading)

    init {
        viewModelScope.launch {
            repository.initializeDatabaseIfNeeded()
        }

        viewModelScope.launch {
            audioManager.completedTracks.collect { listId ->
                repository.dao.updateTaskStatus(listId, true)
            }
        }
    }

    val isPlaying: StateFlow<Boolean> = audioManager.isPlaying
    val currentPosition: StateFlow<Long> = audioManager.currentPosition
    val duration: StateFlow<Long> = audioManager.duration

    fun dispatchAction(action: DashboardAction) {
        when (action) {
            is DashboardAction.ToggleTask -> viewModelScope.launch {
                repository.dao.updateTaskStatus(action.listId, action.isChecked)
            }
            is DashboardAction.PlayFrom -> {
                val state = uiState.value as? DashboardUiState.Active ?: return
                viewModelScope.launch {
                    val tasks = repository.dailyTasksFlow.first()
                    val index = tasks.indexOfFirst { it.uniqueId == action.taskId }
                    if (index != -1) {
                        audioManager.playTasks(tasks, index)
                    }
                }
            }
            is DashboardAction.PlayPause -> audioManager.togglePlayPause()
            is DashboardAction.SkipNext -> audioManager.skipToNext()
        }
    }
}
