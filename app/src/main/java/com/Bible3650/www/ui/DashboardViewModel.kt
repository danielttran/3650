package com.Bible3650.www.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.Bible3650.www.audio.AudioControllerManager
import com.Bible3650.www.audio.SleepTimer
import com.Bible3650.www.data.BibleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@Immutable
data class TaskUiModel(
    val id: String,
    val listId: Long,
    val title: String,
    val subtitle: String,
    val targetBook: String,
    val targetChapter: Int,
    val totalChapters: Int,
    val loopPosition: Int = 0,
    val books: List<String> = emptyList(),
    val fileUri: String,
    val listColor: Int = 0
)

sealed interface DashboardUiState {
    object Loading : DashboardUiState
    object NoSource : DashboardUiState
    data class Active(val tasks: List<TaskUiModel>) : DashboardUiState
}

sealed interface DashboardAction {
    data class IncrementProgress(val listId: Long) : DashboardAction
    data class DecrementProgress(val listId: Long) : DashboardAction
    data class PlayFrom(val taskId: String) : DashboardAction
    object PlayPause : DashboardAction
    object SkipNext : DashboardAction
    object SkipPrevious : DashboardAction
    object Rewind : DashboardAction
    object FastForward : DashboardAction
}

sealed interface DashboardUiEvent {
    data class ShowSnackbar(val message: String) : DashboardUiEvent
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: BibleRepository,
    private val audioManager: AudioControllerManager
) : ViewModel() {

    // Buffer one event with DROP_OLDEST so a playback error emitted before the UI collector
    // attaches (e.g. during startup) is delivered when it subscribes, instead of suspending the
    // forwarder. Mirrors AudioControllerManager._playerError.
    private val _uiEvents = MutableSharedFlow<DashboardUiEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val uiEvents = _uiEvents.asSharedFlow()

    // currentMediaId is exposed separately so the UI can derive isPlaying per-item
    // without triggering a full re-map of all tasks on every track transition.
    val currentMediaId: StateFlow<String?> = audioManager.currentMediaId

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.dailyTasksFlow,
        repository.hasActiveSourceFlow
    ) { tasks, hasSource ->
        // hasActiveSourceFlow is true for an active TEXT source or an AUDIO source with
        // mappings; otherwise the home screen shows the "add a source" empty state.
        if (!hasSource) return@combine DashboardUiState.NoSource

        val mappedTasks = tasks.map { task ->
            TaskUiModel(
                id            = task.uniqueId,
                listId        = task.listId,
                title         = task.listName,
                subtitle      = "${task.targetBook} ${task.targetChapter}",
                targetBook    = task.targetBook,
                targetChapter = task.targetChapter,
                totalChapters = task.totalChapters,
                loopPosition  = task.loopPosition,
                books         = task.books,
                fileUri       = task.fileUri,
                listColor     = task.listColor
            )
        }
        DashboardUiState.Active(mappedTasks)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUiState.Loading)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.initializeDatabaseIfNeeded()
                repository.freezeActiveTasks()

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
        // #13: Forward audio playback errors (e.g. unresolvable URI) as snackbar events.
        viewModelScope.launch {
            audioManager.playerError.collect { errorMsg ->
                _uiEvents.emit(DashboardUiEvent.ShowSnackbar(errorMsg))
            }
        }
        // Track completion is handled inside AudioControllerManager's own scope,
        // ensuring advanceListDay runs even when the UI is in the background.
    }

    val isPlaying: StateFlow<Boolean> = audioManager.isPlaying
    val currentPosition: StateFlow<Long> = audioManager.currentPosition
    val duration: StateFlow<Long> = audioManager.duration
    val playbackSpeed: StateFlow<Float> = audioManager.playbackSpeed
    val sleepTimer: StateFlow<SleepTimer> = audioManager.sleepTimer
    val isSynthesizing: StateFlow<Boolean> = audioManager.isSynthesizing

    fun setSpeed(speed: Float) = audioManager.setPlaybackSpeed(speed)
    fun seekTo(positionMs: Long) = audioManager.seekTo(positionMs)
    fun setSleepTimerMinutes(minutes: Int) = audioManager.setSleepTimerMinutes(minutes)
    fun setSleepTimerEndOfChapter() = audioManager.setSleepTimerEndOfChapter()
    fun cancelSleepTimer() = audioManager.cancelSleepTimer()

    private suspend fun syncPlayerIfPlaying(listId: Long) {
        val currentId = currentMediaId.value ?: return
        // 1C: uniqueId format is now "listId_dayOffset_book_chapter".
        // Parse the listId from the first segment instead of string-comparing the full ID,
        // which would fail now that book+chapter are appended.
        val playingListId = currentId.substringBefore("_").toLongOrNull() ?: return
        if (playingListId != listId) return

        // Guard against an indefinitely-suspended coroutine when the audio source
        // folder is missing or unlinked (fileUri would never become non-empty).
        val updatedTasks = withTimeoutOrNull(5_000) {
            repository.dailyTasksFlow
                .filter { tasks -> tasks.any { it.listId == listId && it.fileUri.isNotEmpty() } }
                .first()
        } ?: return  // Audio source unavailable; skip resync

        val startIndex = updatedTasks.indexOfFirst { it.uniqueId == currentId }
        if (startIndex != -1) {
            audioManager.playTasks(updatedTasks, startIndex)
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

        // Wait for a non-empty task list — firstOrNull() can return an empty list
        // on the very first Room emission before data is ready, causing index = -1.
        val tasks = withTimeoutOrNull(3000) {
            repository.dailyTasksFlow.first { it.isNotEmpty() }
        } ?: return
        val index = tasks.indexOfFirst { it.uniqueId == savedId }
        if (index == -1) return

        audioManager.playTasks(tasks, index, savedPos, playWhenReady = false)
    }

    fun jumpToChapter(listId: Long, book: String, chapter: Int) {
        viewModelScope.launch {
            repository.jumpToChapter(listId, book, chapter)
            syncPlayerIfPlaying(listId)
        }
    }

    fun dispatchAction(action: DashboardAction) {
        android.util.Log.d("DashboardVM", "Dispatching action: $action")
        when (action) {
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
                    val tasks = withTimeoutOrNull(3_000) {
                        repository.dailyTasksFlow.first()
                    } ?: return@launch
                    val startIndex = tasks.indexOfFirst { it.uniqueId == action.taskId }
                    if (startIndex == -1) return@launch
                    audioManager.playTasks(tasks, startIndex)
                }
            }
            is DashboardAction.PlayPause -> audioManager.togglePlayPause()
            is DashboardAction.SkipNext  -> audioManager.skipToNext()
            is DashboardAction.SkipPrevious -> audioManager.skipToPrevious()
            is DashboardAction.Rewind -> audioManager.rewind()
            is DashboardAction.FastForward -> audioManager.fastForward()
        }
    }
}
