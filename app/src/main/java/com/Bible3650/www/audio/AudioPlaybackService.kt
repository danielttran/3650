package com.Bible3650.www.audio

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.common.Player
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class AudioPlaybackService : MediaLibraryService() {
    @Inject lateinit var audioControllerManager: AudioControllerManager

    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekForwardIncrementMs(15000)
            .setSeekBackIncrementMs(15000)
            .build()

        val forwardingPlayer = object : ForwardingPlayer(player) {
            override fun seekToNext() { super.seekToNextMediaItem() }
            override fun seekToPrevious() { super.seekToPreviousMediaItem() }
        }

        val callback = object : MediaLibrarySession.Callback {
            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<MutableList<MediaItem>> {
                val resolvedItems = mediaItems.map { item ->
                    if (item.localConfiguration != null) return@map item
                    
                    val uri = item.requestMetadata.mediaUri
                    if (uri != null) {
                        item.buildUpon()
                            .setUri(uri)
                            .build()
                    } else {
                        item
                    }
                }.toMutableList()
                return Futures.immediateFuture(resolvedItems)
            }

            override fun onPlaybackResumption(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                // Return an empty result if we can't resume
                return Futures.immediateFailedFuture(UnsupportedOperationException())
            }
        }

        mediaLibrarySession = MediaLibrarySession.Builder(this, forwardingPlayer, callback)
            .setId("GrantHornerAudioSession")
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaLibrarySession

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val activePlayer = mediaLibrarySession?.player
        if (activePlayer == null || !activePlayer.playWhenReady || activePlayer.playbackState == Player.STATE_ENDED) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        audioControllerManager.release()
        if (mediaLibrarySession != null) {
            mediaLibrarySession?.run {
                player.stop()
                player.release()
                release()
            }
        } else if (::player.isInitialized) {
            // Session was never assigned (onCreate threw after player creation) — release directly.
            player.release()
        }
        mediaLibrarySession = null
        super.onDestroy()
    }
}
