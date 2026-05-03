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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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

    private val _completedTracks = MutableSharedFlow<Long>(extraBufferCapacity = 10)
    val completedTracks: SharedFlow<Long> = _completedTracks

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

    val savedMediaId: String? get() = prefs.getString(KEY_MEDIA_ID, null)
    val savedPosition: Long get() = prefs.getLong(KEY_POSITION, 0L)

    init {
        initializeController()
    }

    private fun initializeController() {
        val sessionToken = SessionToken(context, ComponentName(context, AudioPlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            val mediaController = try {
                controllerFuture?.get()
            } catch (e: Exception) {
                android.util.Log.e("AudioController", "MediaController build failed", e)
                null
            }
            if (mediaController == null) {
                android.util.Log.e("AudioController", "MediaController is null after build")
                return@addListener
            }
            _player.value = mediaController

            scope.launch {
                var lastSavedPosition = 0L
                while (true) {
                    _player.value?.let { p ->
                        if (p.isPlaying) {
                            val pos = p.currentPosition
                            _currentPosition.value = pos
                            _duration.value = p.duration.coerceAtLeast(0L)
                            if (pos - lastSavedPosition >= 5000L) {
                                prefs.edit().putLong(KEY_POSITION, pos).apply()
                                lastSavedPosition = pos
                            }
                        }
                    }
                    delay(1000)
                }
            }

            mediaController.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _isPlaying.value = isPlaying
                    if (!isPlaying) {
                        mediaController.currentPosition.let { pos ->
                            prefs.edit().putLong(KEY_POSITION, pos).apply()
                            _currentPosition.value = pos
                        }
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val id = mediaItem?.mediaId
                    _currentMediaId.value = id
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
                            _completedTracks.tryEmit(listId)
                        }
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        mediaController.currentMediaItem?.mediaId?.substringBefore("_")?.toLongOrNull()?.let { listId ->
                            _completedTracks.tryEmit(listId)
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

    fun playTasks(tasks: List<DailyTask>, startIndex: Int = 0, startPositionMs: Long = androidx.media3.common.C.TIME_UNSET) {
        android.util.Log.d("AudioController", "playTasks: tasks=${tasks.size}, startIndex=$startIndex")
        val player = _player.value ?: run {
            android.util.Log.w("AudioController", "playTasks failed: Player is null")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                // 1. Resolve ONLY the first item to start playing immediately
                val firstTask = tasks.getOrNull(startIndex) ?: return@launch
                val activeMappings = repository.audioSourceDao.observeActiveMappings().firstOrNull() ?: emptyList()
                val mappingsByBook = activeMappings.associateBy { it.bookName }
                val activeSource   = repository.audioSourceDao.getActiveSource()

                // Reuse the repository's shared folder cache so we don't re-scan
                // directories that were already scanned for dailyTasksFlow.
                fun resolveUri(task: DailyTask): Uri? {
                    val mapping = mappingsByBook[task.targetBook] ?: return null
                    if (activeSource == null) return null
                    val treeUri = (mapping.overrideTreeUri ?: activeSource.rootTreeUri).toUri()
                    return repository.resolveChapterFile(treeUri, mapping.folderDocId, task.targetChapter, repository.folderCache)
                }

                val firstUri = resolveUri(firstTask)

                withContext(Dispatchers.Main) {
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
                    player.play()
                }

                // 2. Resolve the rest in background and update playlist
                val allMediaItems = tasks.map { task ->
                    val uri = if (task.uniqueId == firstTask.uniqueId) firstUri else resolveUri(task)
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

                withContext(Dispatchers.Main) {
                    // Only update the playlist if we are still on the same track
                    if (player.currentMediaItem?.mediaId == firstTask.uniqueId) {
                        val before = allMediaItems.take(startIndex)
                        val after = allMediaItems.drop(startIndex + 1)
                        
                        // Append items after current
                        if (after.isNotEmpty()) player.addMediaItems(after)
                        // Prepend items before current (index 0 in player right now)
                        if (before.isNotEmpty()) player.addMediaItems(0, before)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AudioController", "Failed to play tasks", e)
            }
        }
    }

    fun togglePlayPause() {
        val player = _player.value ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun skipToNext() {
        _player.value?.seekToNext()
    }

    fun release() {
        scope.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
