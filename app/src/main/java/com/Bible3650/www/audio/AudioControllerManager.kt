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
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri
import androidx.core.net.toUri
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import com.Bible3650.www.di.MainDispatcher
import com.Bible3650.www.di.IoDispatcher

private const val PREFS_NAME = "audio_playback_state"
private const val KEY_MEDIA_ID = "last_media_id"
private const val KEY_POSITION = "last_position_ms"

// Separator in uniqueId format "listId_dayOffset_book_chapter"; listId is the first segment.
private const val TASK_ID_SEPARATOR = "_"

@Singleton
class AudioControllerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: BibleRepository,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
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

    // #13: Expose playback errors (e.g. unresolvable audio URI) so the UI can show a snackbar.
    private val _playerError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val playerError: SharedFlow<String> = _playerError.asSharedFlow()

    // #10: Singleton scope — never cancelled. release() only tears down the MediaController,
    // not the scope itself, so coroutines launched after reconnect work correctly.
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
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

                // AUTO_TRANSITION fires for items 0..N-1 when they finish and the player
                // moves to the next item. STATE_ENDED (below) fires for the last item only.
                // The two handlers are mutually exclusive, so there is no double-fire risk.
                @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                        oldPosition.mediaItem?.mediaId
                            ?.substringBefore(TASK_ID_SEPARATOR)
                            ?.toLongOrNull()
                            ?.let { listId ->
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

                // Handles the last item in the playlist — AUTO_TRANSITION does NOT fire here
                // because there is no next media item, so this is the only place the last
                // list day gets advanced. Also clears saved position so the mini-player resets.
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        mediaController.currentMediaItem?.mediaId
                            ?.substringBefore(TASK_ID_SEPARATOR)
                            ?.toLongOrNull()
                            ?.let { listId ->
                                scope.launch {
                                    try {
                                        repository.advanceListDay(listId)
                                    } catch (e: Exception) {
                                        android.util.Log.e("AudioController", "Error advancing list day on STATE_ENDED", e)
                                    }
                                }
                            }
                        prefs.edit().remove(KEY_MEDIA_ID).remove(KEY_POSITION).apply()
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.util.Log.e("AudioBible", "ExoPlayer Error: ${error.message}", error)
                    _playerError.tryEmit("Playback error: ${error.message ?: "unknown"}")
                }
            })
        }, ContextCompat.getMainExecutor(context))
    }

    private var currentPlaylistRequestId: Long = 0

    // #5: Resolve ALL URIs before touching the player so there is no seek race from
    // the old two-phase build. Latency is acceptable for ≤10 items.
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
                // Playlist matches — just seek to the required index and play/pause
                val startPos = if (startPositionMs != androidx.media3.common.C.TIME_UNSET) startPositionMs else 0L
                player.seekTo(startIndex, startPos)
                if (playWhenReady) player.play() else player.pause()
                return
            }
        }

        val requestId = ++currentPlaylistRequestId

        scope.launch(ioDispatcher) {
            try {
                val activeMappings = repository.audioSourceDao.observeActiveMappings().firstOrNull() ?: emptyList()
                val mappingsByBook = activeMappings.associateBy { it.bookName }
                val activeSource   = repository.audioSourceDao.getActiveSource()

                suspend fun resolveUri(task: DailyTask): Uri? {
                    val mapping = mappingsByBook[task.targetBook] ?: return null
                    if (activeSource == null) return null
                    val treeUri = (mapping.overrideTreeUri ?: activeSource.rootTreeUri).toUri()
                    return repository.resolveChapterFile(treeUri, mapping.folderDocId, task.targetChapter, repository.folderCache)
                }

                // Resolve all URIs up front (single atomic playlist set, no seek race)
                val allMediaItems = tasks.map { task ->
                    val uri = resolveUri(task)
                    MediaItem.Builder()
                        .setMediaId(task.uniqueId)
                        .setUri(uri)
                        .setRequestMetadata(
                            MediaItem.RequestMetadata.Builder()
                                .setMediaUri(uri)
                                .build()
                        )
                        .build()
                }

                // #13: Warn if the start item has no resolvable URI
                val startUri = allMediaItems.getOrNull(startIndex)?.localConfiguration?.uri
                if (startUri == null) {
                    android.util.Log.w("AudioController", "No audio URI for task at index $startIndex")
                    _playerError.tryEmit("Audio file not found. Check your audio source mapping.")
                }

                withContext(mainDispatcher) {
                    if (requestId != currentPlaylistRequestId) return@withContext

                    val startPos = if (startPositionMs != androidx.media3.common.C.TIME_UNSET) startPositionMs
                                   else androidx.media3.common.C.TIME_UNSET
                    player.setMediaItems(allMediaItems, startIndex, startPos)
                    player.prepare()
                    if (playWhenReady) player.play() else player.pause()
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

    // #10: Do NOT cancel the singleton scope here. The scope must survive across
    // release/reconnect cycles. Only tear down the controller and position job.
    fun release() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
        _isPlaying.value = false
        // Clear playback state so the UI doesn't show stale "Now Playing" after
        // the service is destroyed and before a new controller connects.
        _currentMediaId.value = null
        _currentPosition.value = 0L
        _duration.value = 0L
        _player.value?.release()
        _player.value = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        // scope.cancel() intentionally removed — @Singleton scope lives forever
    }
}
