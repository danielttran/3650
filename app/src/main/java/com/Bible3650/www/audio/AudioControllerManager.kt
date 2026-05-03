package com.Bible3650.www.audio

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.Bible3650.www.data.BibleRepository
import com.Bible3650.www.data.local.DailyTask
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "audio_playback_state"
private const val KEY_MEDIA_ID = "last_media_id"
private const val KEY_POSITION = "last_position_ms"

@Singleton
class AudioControllerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: BibleRepository
) {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val _player = MutableStateFlow<Player?>(null)
    val player: StateFlow<Player?> = _player

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentMediaId = MutableStateFlow<String?>(null)
    val currentMediaId: StateFlow<String?> = _currentMediaId

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var positionUpdateJob: Job? = null

    val savedMediaId: String? get() = prefs.getString(KEY_MEDIA_ID, null)
    val savedPosition: Long get() = prefs.getLong(KEY_POSITION, 0L)

    init {
        initializeController()
    }

    private fun savePosition(p: Player) {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
        val pos = p.currentPosition
        val id = p.currentMediaItem?.mediaId
        val editor = prefs.edit()
        editor.putLong(KEY_POSITION, pos)
        if (id != null) {
            editor.putString(KEY_MEDIA_ID, id)
        }
        editor.apply()
        _currentPosition.value = pos
    }

    private fun startPositionUpdates(p: Player) {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            while (isActive) {
                _currentPosition.value = p.currentPosition
                val dur = p.duration
                if (dur > 0) _duration.value = dur
                delay(500)
            }
        }
    }

    private fun initializeController() {
        val sessionToken = SessionToken(context, ComponentName(context, AudioPlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            val mediaController = try {
                controllerFuture?.get()
            } catch (e: Exception) {
                android.util.Log.e("AudioController", "MediaController build failed", e)
                controllerFuture = null
                null
            }
            if (mediaController == null) {
                android.util.Log.e("AudioController", "MediaController is null after build")
                controllerFuture = null
                return@addListener
            }
            _player.value = mediaController

            mediaController.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _isPlaying.value = isPlaying
                    if (isPlaying) {
                        startPositionUpdates(mediaController)
                    } else {
                        savePosition(mediaController)
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val id = mediaItem?.mediaId
                    _currentMediaId.value = id
                    val dur = mediaController.duration
                    if (dur > 0) _duration.value = dur
                    if (id != null) {
                        prefs.edit().putString(KEY_MEDIA_ID, id).putLong(KEY_POSITION, 0L).apply()
                    }
                }

                @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                        oldPosition.mediaItem?.mediaId?.substringBefore("_")?.toLongOrNull()?.let { listId ->
                            // Run in manager's own scope so this survives ViewModel destruction
                            scope.launch {
                                try {
                                    repository.advanceListDay(listId)
                                } catch (e: Exception) {
                                    android.util.Log.e("AudioController", "Error advancing list day", e)
                                }
                            }
                        }
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        mediaController.currentMediaItem?.mediaId?.substringBefore("_")?.toLongOrNull()?.let { listId ->
                            scope.launch {
                                try {
                                    repository.advanceListDay(listId)
                                } catch (e: Exception) {
                                    android.util.Log.e("AudioController", "Error advancing list day", e)
                                }
                            }
                        }
                        prefs.edit().remove(KEY_MEDIA_ID).remove(KEY_POSITION).apply()
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.util.Log.e("AudioBible", "ExoPlayer Error: ${error.message}", error)
                }
            })
        }, MoreExecutors.directExecutor())
    }

    private var currentPlaylistRequestId: Long = 0

    fun playTasks(
        tasks: List<DailyTask>,
        startIndex: Int = 0,
        startPositionMs: Long = androidx.media3.common.C.TIME_UNSET,
        playWhenReady: Boolean = true
    ) {
        android.util.Log.d("AudioController", "playTasks: tasks=${tasks.size}, startIndex=$startIndex")
        val player = _player.value ?: run {
            // Attempt to reconnect if a previous release cleared the controller
            if (controllerFuture == null) initializeController()
            android.util.Log.w("AudioController", "playTasks failed: Player is null, initiating reconnect")
            return
        }

        // Check if the current playlist already matches `tasks`
        if (tasks.isNotEmpty() && player.mediaItemCount == tasks.size) {
            var isMatch = true
            for (i in tasks.indices) {
                if (player.getMediaItemAt(i).mediaId != tasks[i].uniqueId) {
                    isMatch = false
                    break
                }
            }
            if (isMatch) {
                // Playlist matches! Just seek to the required index
                val startPos = if (startPositionMs != androidx.media3.common.C.TIME_UNSET) startPositionMs else 0L
                player.seekTo(startIndex, startPos)
                if (playWhenReady) player.play() else player.pause()
                return
            }
        }

        val requestId = ++currentPlaylistRequestId

        scope.launch(Dispatchers.IO) {
            try {
                // 1. Resolve ONLY the first item to start playing immediately
                val firstTask = tasks.getOrNull(startIndex) ?: return@launch
                val activeMappings = repository.audioSourceDao.observeActiveMappings().firstOrNull() ?: emptyList()
                val mappingsByBook = activeMappings.associateBy { it.bookName }
                val activeSource   = repository.audioSourceDao.getActiveSource()

                suspend fun resolveUri(task: DailyTask): Uri? {
                    val mapping = mappingsByBook[task.targetBook] ?: return null
                    if (activeSource == null) return null
                    val treeUri = (mapping.overrideTreeUri ?: activeSource.rootTreeUri).toUri()
                    return repository.resolveChapterFile(treeUri, mapping.folderDocId, task.targetChapter, repository.folderCache)
                }

                val firstUri = resolveUri(firstTask)

                withContext(Dispatchers.Main) {
                    if (requestId != currentPlaylistRequestId) return@withContext

                    val firstItem = MediaItem.Builder()
                        .setMediaId(firstTask.uniqueId)
                        .setUri(firstUri)
                        .setRequestMetadata(
                            MediaItem.RequestMetadata.Builder()
                                .setMediaUri(firstUri)
                                .build()
                        )
                        .build()
                    player.setMediaItem(firstItem)
                    if (startPositionMs != androidx.media3.common.C.TIME_UNSET) player.seekTo(startPositionMs)
                    player.prepare()
                    if (playWhenReady) player.play() else player.pause()
                }

                // 2. Resolve the rest in background
                val allMediaItems = mutableListOf<MediaItem>()
                for (task in tasks) {
                    val uri = if (task.uniqueId == firstTask.uniqueId) firstUri else resolveUri(task)
                    allMediaItems.add(
                        MediaItem.Builder()
                            .setMediaId(task.uniqueId)
                            .setUri(uri)
                            .setRequestMetadata(
                                MediaItem.RequestMetadata.Builder()
                                    .setMediaUri(uri)
                                    .build()
                            )
                            .build()
                    )
                }

                withContext(Dispatchers.Main) {
                    if (requestId != currentPlaylistRequestId) return@withContext

                    val before = allMediaItems.take(startIndex)
                    val after = allMediaItems.drop(startIndex + 1)

                    // Append items after current
                    if (after.isNotEmpty()) player.addMediaItems(after)
                    // Prepend items before current; seek to maintain correct window index
                    if (before.isNotEmpty()) {
                        val currentPos = player.currentPosition
                        player.addMediaItems(0, before)
                        player.seekTo(before.size, currentPos)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AudioController", "Failed to play tasks", e)
            }
        }
    }

    fun togglePlayPause() {
        val player = _player.value ?: return
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0, 0L)
            player.play()
        } else if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun skipToNext() {
        _player.value?.seekToNext()
    }

    fun release() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
        _isPlaying.value = false
        _player.value?.release()
        _player.value = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        // Reinitialize so the controller is ready when the service next starts
        initializeController()
    }
}
