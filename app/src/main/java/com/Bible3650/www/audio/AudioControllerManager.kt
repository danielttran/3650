package com.Bible3650.www.audio

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.Bible3650.www.data.local.DailyTask
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.net.Uri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioControllerManager @Inject constructor(
    @ApplicationContext private val context: Context
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

    init {
        initializeController()
    }

    private fun initializeController() {
        val sessionToken = SessionToken(context, ComponentName(context, AudioPlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            val mediaController = controllerFuture?.get()
            _player.value = mediaController
            
            CoroutineScope(Dispatchers.Main).launch {
                while (true) {
                    _player.value?.let { p ->
                        if (p.isPlaying) {
                            _currentPosition.value = p.currentPosition
                            _duration.value = p.duration.coerceAtLeast(0L)
                        }
                    }
                    delay(1000)
                }
            }
            
            mediaController?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _isPlaying.value = isPlaying
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    _currentMediaId.value = mediaItem?.mediaId
                }

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
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.util.Log.e("AudioBible", "ExoPlayer Error: ${error.message}", error)
                }
            })
        }, MoreExecutors.directExecutor())
    }

    fun playTasks(tasks: List<DailyTask>, startIndex: Int = 0) {
        val player = _player.value ?: return
        
        val mediaItems = tasks.map { task ->
            MediaItem.Builder()
                .setMediaId(task.uniqueId)
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder()
                        .setMediaUri(Uri.parse(task.fileUri))
                        .build()
                )
                .build()
        }
        
        player.setMediaItems(mediaItems, startIndex, androidx.media3.common.C.TIME_UNSET)
        player.prepare()
        player.play()
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
}
