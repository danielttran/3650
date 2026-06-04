package com.Bible3650.www.audio

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.Bible3650.www.data.BibleRepository
import com.Bible3650.www.data.local.AudioSourceEntity
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.net.Uri
import androidx.core.net.toUri
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import com.Bible3650.www.di.MainDispatcher
import com.Bible3650.www.di.IoDispatcher

private const val PREFS_NAME = "audio_playback_state"
private const val KEY_MEDIA_ID = "last_media_id"
private const val KEY_POSITION = "last_position_ms"
private const val KEY_SPEED = "playback_speed"

const val MIN_PLAYBACK_SPEED = 0.5f
const val MAX_PLAYBACK_SPEED = 3.0f

// Separator in uniqueId format "listId_dayOffset_book_chapter"; listId is the first segment.
private const val TASK_ID_SEPARATOR = "_"

/**
 * Identity of the source a player playlist was built for. Task uniqueIds are source-agnostic
 * ("listId_day_book_chapter"), so the playlist fast-path keys off this to avoid replaying a
 * stale AUDIO playlist for a TEXT (TTS) source — or a different translation. Top-level + pure
 * so the discrimination is unit-testable without a MediaController.
 */
internal fun playlistSourceKey(source: AudioSourceEntity?): String =
    if (source == null) "none" else "${source.sourceId}:${source.sourceType}:${source.translationId ?: -1L}"

/** Thread-safe generation gate for asynchronous playlist builds. */
internal class PlaylistRequestTracker {
    private val generation = AtomicLong()

    fun next(): Long = generation.incrementAndGet()
    fun invalidate() { generation.incrementAndGet() }
    fun isCurrent(requestId: Long): Boolean = generation.get() == requestId
}

/** State of the optional sleep timer that auto-pauses playback. */
sealed interface SleepTimer {
    object Off : SleepTimer
    /** Pause when the currently playing chapter finishes. */
    object EndOfChapter : SleepTimer
    /** Pause at [endElapsedRealtimeMs] (SystemClock.elapsedRealtime base). */
    data class Timed(val endElapsedRealtimeMs: Long) : SleepTimer
}

@Singleton
class AudioControllerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: BibleRepository,
    private val ttsSynthesizer: TtsSynthesizer,
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

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed

    private val _sleepTimer = MutableStateFlow<SleepTimer>(SleepTimer.Off)
    val sleepTimer: StateFlow<SleepTimer> = _sleepTimer
    private var sleepTimerJob: Job? = null
    // Bumped on every sleep-timer change so a previously-scheduled countdown that has
    // already passed its delay cannot clobber a newer timer's state.
    private var sleepTimerGeneration = 0

    // #13: Expose playback errors (e.g. unresolvable audio URI) so the UI can show a snackbar.
    private val _playerError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val playerError: SharedFlow<String> = _playerError.asSharedFlow()

    /** True while a text chapter is being rendered to audio (TTS sources). */
    val isSynthesizing: StateFlow<Boolean> = ttsSynthesizer.isSynthesizing

    // #10: Singleton scope — never cancelled. release() only tears down the MediaController,
    // not the scope itself, so coroutines launched after reconnect work correctly.
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var positionUpdateJob: Job? = null

    // mediaIds whose playback completion has already advanced their list for the CURRENTLY loaded
    // playlist instance. Replaying an already-finished queue (togglePlayPause after STATE_ENDED, or
    // the notification Play button — both do seekTo(0,0)+play on the same loaded items, never a
    // rebuild) would otherwise fire AUTO_TRANSITION/STATE_ENDED again and advance the plan a second
    // time, over-counting progress and skipping the next chapter. Cleared whenever a fresh playlist
    // is installed. Accessed only on the main thread (Media3 listener callbacks + the main-dispatched
    // setMediaItems/clearLoadedPlaylist paths), so a plain set is safe.
    private val advancedMediaIds = mutableSetOf<String>()

    val savedMediaId: String? get() = prefs.getString(KEY_MEDIA_ID, null)
    val savedPosition: Long get() = prefs.getLong(KEY_POSITION, 0L)

    init {
        _playbackSpeed.value = prefs.getFloat(KEY_SPEED, 1.0f)
            .coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
        initializeController()
        observeActiveSourceForStalePlaylist()
    }

    // When the active source changes away from whatever playlist is currently loaded, drop the
    // stale media. Task uniqueIds are source-agnostic, so without this the loaded recordings
    // would keep playing (via the resume button / togglePlayPause) after switching to a TEXT
    // (TTS) source — or after deleting the audio source and linking a text Bible.
    private fun observeActiveSourceForStalePlaylist() {
        scope.launch(ioDispatcher) {
            repository.audioSourceDao.observeAllSources()
                .map { sources -> playlistSourceKey(sources.firstOrNull { it.source.isActive }?.source) }
                .distinctUntilChanged()
                .collect { newKey ->
                    withContext(mainDispatcher) {
                        val loaded = currentPlaylistSourceKey
                        if (loaded != null && loaded != newKey) {
                            // Invariant: do NOT invalidate playlistRequests here. A concurrent
                            // switchSource → resumeFromSnapshot rebuild may already be in flight;
                            // invalidating it would cancel that rebuild and leave
                            // the player empty after a source switch.
                            clearLoadedPlaylist()
                        }
                    }
                }
        }
    }

    private fun savePosition(p: Player) {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
        // When the playlist has ended, onPlaybackStateChanged(STATE_ENDED) already cleared the
        // saved media id/position. onIsPlayingChanged(false) also fires on end, so don't resurrect
        // them here: persisting the finished chapter's end position would restore a completed
        // chapter (paused at its end) on the next cold start instead of a cleared mini-player.
        if (p.playbackState == Player.STATE_ENDED) return
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
            // Re-apply the persisted playback speed across reconnect cycles.
            mediaController.setPlaybackSpeed(_playbackSpeed.value)

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
                    // Reflect the new item's position immediately. The 500ms updater only runs
                    // while playing, so without this the seek bar would keep showing the previous
                    // chapter's position when the user skips chapters while paused. Reading the
                    // player's own position is correct for both a skip (0) and a restore (saved pos).
                    val pos = mediaController.currentPosition.coerceAtLeast(0L)
                    _currentPosition.value = pos
                    if (id != null) {
                        // Persist the player's ACTUAL position, not a hardcoded 0. On a chapter
                        // skip/auto-transition this is ~0 (new chapter starts at the beginning),
                        // but on a paused cold-start restore that seeks to the saved position this
                        // is that position. Writing 0 here clobbered the saved resume point whenever
                        // the app was reopened and closed again without pressing play.
                        prefs.edit().putString(KEY_MEDIA_ID, id).putLong(KEY_POSITION, pos).apply()
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
                        val finishedId = oldPosition.mediaItem?.mediaId
                        // Guard: only advance once per item per loaded playlist instance, so a
                        // replay of an already-finished queue can't double-count.
                        if (finishedId != null && advancedMediaIds.add(finishedId)) {
                            finishedId.substringBefore(TASK_ID_SEPARATOR)
                                .toLongOrNull()
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
                        // Sleep timer: stop once the chapter that was playing has finished.
                        if (_sleepTimer.value is SleepTimer.EndOfChapter) {
                            mediaController.pause()
                            _sleepTimer.value = SleepTimer.Off
                        }
                    }
                }

                // Handles the last item in the playlist — AUTO_TRANSITION does NOT fire here
                // because there is no next media item, so this is the only place the last
                // list day gets advanced. Also clears saved position so the mini-player resets.
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        val endedId = mediaController.currentMediaItem?.mediaId
                        // Guard: only advance once per item per loaded playlist instance, so
                        // pressing play on an already-ended queue can't double-count.
                        if (endedId != null && advancedMediaIds.add(endedId)) {
                            endedId.substringBefore(TASK_ID_SEPARATOR)
                                .toLongOrNull()
                                ?.let { listId ->
                                    scope.launch {
                                        try {
                                            repository.advanceListDay(listId)
                                        } catch (e: Exception) {
                                            android.util.Log.e("AudioController", "Error advancing list day on STATE_ENDED", e)
                                        }
                                    }
                                }
                        }
                        prefs.edit().remove(KEY_MEDIA_ID).remove(KEY_POSITION).apply()
                        // Playback finished; disarm an end-of-chapter sleep timer.
                        if (_sleepTimer.value is SleepTimer.EndOfChapter) {
                            _sleepTimer.value = SleepTimer.Off
                        }
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    android.util.Log.e("AudioBible", "ExoPlayer Error: ${error.message}", error)
                    _playerError.tryEmit("Playback error: ${error.message ?: "unknown"}")
                }
            })
        }, ContextCompat.getMainExecutor(context))
    }

    private val playlistRequests = PlaylistRequestTracker()

    // Identifies which source the currently-loaded player playlist was built for. The
    // playlist fast-path must not reuse media items across a source switch, because task
    // uniqueIds ("listId_day_book_chapter") are source-agnostic — without this an AUDIO
    // playlist would be replayed for a TEXT (TTS) source and vice versa.
    private var currentPlaylistSourceKey: String? = null

    private fun computeSafeStartIndex(taskCount: Int, requestedStartIndex: Int): Int {
        return when {
            taskCount <= 0 -> androidx.media3.common.C.INDEX_UNSET
            requestedStartIndex < 0 -> 0
            requestedStartIndex >= taskCount -> taskCount - 1
            else -> requestedStartIndex
        }
    }

    // #5: Resolve ALL URIs before touching the player so there is no seek race from
    // the old two-phase build. Latency is acceptable for ≤10 items.
    fun playTasks(
        tasks: List<DailyTask>,
        startIndex: Int = 0,
        startPositionMs: Long = androidx.media3.common.C.TIME_UNSET,
        playWhenReady: Boolean = true,
        forceReload: Boolean = false
    ) {
        android.util.Log.d("AudioController", "playTasks: tasks=${tasks.size}, startIndex=$startIndex")
        val safeStartIndex = computeSafeStartIndex(tasks.size, startIndex)
        val player = _player.value ?: run {
            // Attempt to reconnect if a previous release cleared the controller
            if (controllerFuture == null) initializeController()
            android.util.Log.w("AudioController", "playTasks failed: Player is null, initiating reconnect")
            return
        }

        val requestId = playlistRequests.next()

        scope.launch(ioDispatcher) {
            try {
                val activeSource = repository.audioSourceDao.getActiveSource()
                val sourceKey = playlistSourceKey(activeSource)

                // Fast path: the same playlist is already loaded FOR THE SAME SOURCE — just
                // seek and play. The source-key guard is essential: uniqueIds do not encode
                // the source, so without it a stale AUDIO playlist would be replayed after a
                // switch to a TEXT (TTS) source (and vice versa). Skipped on forceReload.
                if (!forceReload && tasks.isNotEmpty()) {
                    val reused = withContext(mainDispatcher) {
                        if (!playlistRequests.isCurrent(requestId)) return@withContext false
                        if (sourceKey != currentPlaylistSourceKey) return@withContext false
                        if (player.mediaItemCount != tasks.size) return@withContext false
                        for (i in tasks.indices) {
                            if (player.getMediaItemAt(i).mediaId != tasks[i].uniqueId) return@withContext false
                        }
                        // The TEXT (TTS) build installs the playlist with not-yet-synthesized items
                        // URI-less and fills them in asynchronously via prefetch. Reusing the playlist
                        // to seek+play an item whose URI hasn't been resolved yet would play a
                        // null-URI MediaItem and raise a playback error — and the requestId bump above
                        // cancels the in-flight prefetch, so it would never recover. Fall through to a
                        // full rebuild when the target item has no resolved URI so playTextTasks can
                        // synthesize the requested chapter (AUDIO items are all resolved up front, so
                        // this only triggers for a still-prefetching TTS item or a genuinely missing
                        // file, both of which are handled correctly by the rebuild path).
                        if (safeStartIndex != androidx.media3.common.C.INDEX_UNSET &&
                            player.getMediaItemAt(safeStartIndex).localConfiguration == null) {
                            return@withContext false
                        }
                        // A playTasks call is a deliberate (re)start request, so reset the advance
                        // de-dup tracking here too. Without this, a list whose total is a single
                        // chapter (stable uniqueId across advance) would reuse the playlist and the
                        // sticky guard would suppress its advance on an explicit second play. Safe
                        // because the reuse only seeks FORWARD from safeStartIndex, so no
                        // already-advanced item replays (no double-advance). The accidental
                        // ended-queue replay path is togglePlayPause, which does not call playTasks
                        // and stays correctly de-duplicated.
                        advancedMediaIds.clear()
                        val startPos = if (startPositionMs != androidx.media3.common.C.TIME_UNSET) startPositionMs else 0L
                        if (safeStartIndex != androidx.media3.common.C.INDEX_UNSET) player.seekTo(safeStartIndex, startPos)
                        if (playWhenReady) player.play() else player.pause()
                        true
                    }
                    if (reused) return@launch
                }

                if (activeSource?.sourceType == "TEXT") {
                    playTextTasks(tasks, safeStartIndex, startPositionMs, playWhenReady, requestId, activeSource.translationId, sourceKey)
                    return@launch
                }

                val activeMappings = repository.audioSourceDao.observeActiveMappings().firstOrNull() ?: emptyList()
                val mappingsByBook = activeMappings.associateBy { it.bookName }

                suspend fun resolveUri(task: DailyTask): Uri? {
                    val mapping = mappingsByBook[task.targetBook] ?: return null
                    if (activeSource == null) return null
                    val treeUri = (mapping.overrideTreeUri ?: activeSource.rootTreeUri).toUri()
                    return repository.resolveChapterFile(treeUri, mapping.folderDocId, task.targetChapter, repository.folderCache)
                }

                // Resolve all URIs up front (single atomic playlist set, no seek race)
                val allMediaItems = tasks.map { task ->
                    val uri = resolveUri(task)
                    buildMediaItem(task, uri)
                }
                // The source may have changed while SAF queries were running. Never install
                // a playlist built for a deleted or inactive source.
                if (!sourceStillActive(sourceKey)) return@launch

                // #13: Warn if the start item has no resolvable URI, and also report
                // when other items in the playlist are missing — those would otherwise
                // fail silently mid-playback when ExoPlayer auto-transitions to them.
                val missingCount = allMediaItems.count { it.localConfiguration?.uri == null }
                val startUri = allMediaItems.getOrNull(safeStartIndex)?.localConfiguration?.uri
                if (startUri == null) {
                    android.util.Log.w("AudioController", "No audio URI for task at index $startIndex")
                    _playerError.tryEmit("Audio file not found. Check your audio source mapping.")
                } else if (missingCount > 0) {
                    android.util.Log.w("AudioController", "$missingCount task(s) missing audio URIs — those tracks will be skipped")
                    _playerError.tryEmit("$missingCount reading(s) have no audio file mapped.")
                }

                withContext(mainDispatcher) {
                    if (!playlistRequests.isCurrent(requestId)) return@withContext

                    val startPos = if (startPositionMs != androidx.media3.common.C.TIME_UNSET) startPositionMs
                                   else androidx.media3.common.C.TIME_UNSET
                    // Fresh playlist instance: reset per-item advance tracking.
                    advancedMediaIds.clear()
                    player.setMediaItems(allMediaItems, safeStartIndex, startPos)
                    player.prepare()
                    if (playWhenReady) player.play() else player.pause()
                    currentPlaylistSourceKey = sourceKey
                }
            } catch (e: Exception) {
                android.util.Log.e("AudioController", "Failed to play tasks", e)
            }
        }
    }

    private suspend fun sourceStillActive(expectedSourceKey: String): Boolean =
        playlistSourceKey(repository.audioSourceDao.getActiveSource()) == expectedSourceKey

    private fun buildMediaItem(task: DailyTask, uri: Uri?): MediaItem =
        MediaItem.Builder()
            .setMediaId(task.uniqueId)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .build()

    // TEXT (TTS) playback: synthesize the start chapter synchronously so playback can begin,
    // set the playlist (other items start URI-less), then prefetch the remaining chapters in
    // play order and swap them in via replaceMediaItem before they're reached.
    private suspend fun playTextTasks(
        tasks: List<DailyTask>,
        safeStartIndex: Int,
        startPositionMs: Long,
        playWhenReady: Boolean,
        requestId: Long,
        translationId: Long?,
        sourceKey: String
    ) {
        val player = _player.value ?: return
        val startTask = tasks.getOrNull(safeStartIndex)
        val startUri = startTask?.let { ttsSynthesizer.synthesizeChapter(translationId, it.targetBook, it.targetChapter) }
        if (startUri == null) {
            // Start chapter couldn't be synthesized. Surface the specific reason (missing voice
            // data / engine reject / no callback / missing text) and stop — don't set a broken
            // playlist or run the prefetch, which would otherwise fail on every remaining chapter.
            _playerError.tryEmit(
                ttsSynthesizer.lastFailureReason
                    ?: "Couldn't read this chapter aloud."
            )
            return
        }
        // TTS synthesis may take long enough for the user to switch or delete sources.
        if (!playlistRequests.isCurrent(requestId) || !sourceStillActive(sourceKey)) return

        val mediaItems = tasks.mapIndexed { i, task ->
            buildMediaItem(task, if (i == safeStartIndex) startUri else null)
        }
        withContext(mainDispatcher) {
            if (!playlistRequests.isCurrent(requestId)) return@withContext
            val startPos = if (startPositionMs != androidx.media3.common.C.TIME_UNSET) startPositionMs
                           else androidx.media3.common.C.TIME_UNSET
            // Fresh playlist instance: reset per-item advance tracking.
            advancedMediaIds.clear()
            player.setMediaItems(mediaItems, safeStartIndex, startPos)
            player.prepare()
            if (playWhenReady) player.play() else player.pause()
            currentPlaylistSourceKey = sourceKey
        }

        // Prefetch the rest in play order (after the start, then wrap to the beginning).
        val order = (safeStartIndex + 1 until tasks.size) + (0 until safeStartIndex)
        for (i in order) {
            if (!playlistRequests.isCurrent(requestId) || !sourceStillActive(sourceKey)) return
            val task = tasks[i]
            val uri = ttsSynthesizer.synthesizeChapter(translationId, task.targetBook, task.targetChapter) ?: continue
            withContext(mainDispatcher) {
                if (!playlistRequests.isCurrent(requestId)) return@withContext
                if (i < player.mediaItemCount && player.getMediaItemAt(i).mediaId == task.uniqueId) {
                    player.replaceMediaItem(i, buildMediaItem(task, uri))
                }
            }
        }
    }

    /** Snapshot of the live player, used to resume the same chapter after a source switch. */
    data class PlaybackSnapshot(val mediaId: String, val positionMs: Long, val wasPlaying: Boolean)

    fun currentPlaybackSnapshot(): PlaybackSnapshot? {
        val p = _player.value ?: return null
        val id = p.currentMediaItem?.mediaId ?: return null
        return PlaybackSnapshot(id, p.currentPosition.coerceAtLeast(0L), p.isPlaying)
    }

    /**
     * Re-points the player at the same book+chapter on the now-active source so a
     * source/translation switch never loses the user's place. [resetPosition] starts the
     * chapter from 0 (used when switching across types, e.g. audio↔text, where durations
     * differ); otherwise the prior position is reused (clamped by the player).
     */
    fun resumeFromSnapshot(snapshot: PlaybackSnapshot, resetPosition: Boolean) {
        scope.launch {
            val tasks = withTimeoutOrNull(5_000) {
                repository.dailyTasksFlow.first { list -> list.any { it.uniqueId == snapshot.mediaId } }
            } ?: return@launch
            val index = tasks.indexOfFirst { it.uniqueId == snapshot.mediaId }
            if (index == -1) return@launch
            val pos = if (resetPosition) 0L else snapshot.positionMs
            playTasks(tasks, index, pos, playWhenReady = snapshot.wasPlaying, forceReload = true)
        }
    }

    /** Cancels URI/TTS work started for a source that is about to become inactive. */
    fun invalidatePendingPlaylistLoads() {
        playlistRequests.invalidate()
    }

    /** Rebuilds the loaded playlist after lists or active-source folder mappings change. */
    fun reloadCurrentPlaylist() {
        val snapshot = currentPlaybackSnapshot()
        playlistRequests.invalidate()
        // Clear immediately: if the current task was deleted, resumeFromSnapshot will time out
        // and must not leave the removed chapter playing from an obsolete playlist.
        clearLoadedPlaylist()
        if (snapshot != null) resumeFromSnapshot(snapshot, resetPosition = false)
    }

    private fun clearLoadedPlaylist() {
        _player.value?.let { player ->
            player.stop()
            player.clearMediaItems()
        }
        currentPlaylistSourceKey = null
        advancedMediaIds.clear()
        _isPlaying.value = false
        _currentMediaId.value = null
        _currentPosition.value = 0L
        _duration.value = 0L
    }

    /** Stops stale playback when the last installed source is removed or progress is restored. */
    fun stopPlayback() {
        playlistRequests.invalidate()
        clearLoadedPlaylist()
        cancelSleepTimer()
        prefs.edit().remove(KEY_MEDIA_ID).remove(KEY_POSITION).apply()
    }

    /** Purges cached TTS audio for a deleted translation so its WAVs don't linger in the cache. */
    fun clearTtsCacheForTranslation(translationId: Long) =
        ttsSynthesizer.clearCacheForTranslation(translationId)

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

    fun skipToPrevious() {
        _player.value?.seekToPrevious()
    }

    /** Rewind within the current chapter by the configured increment (15s). */
    fun rewind() {
        _player.value?.seekBack()
    }

    /** Fast-forward within the current chapter by the configured increment (15s). */
    fun fastForward() {
        _player.value?.seekForward()
    }

    /** Seek to an absolute position (ms) within the current chapter. */
    fun seekTo(positionMs: Long) {
        val player = _player.value ?: return
        player.seekTo(positionMs.coerceAtLeast(0L))
        _currentPosition.value = positionMs.coerceAtLeast(0L)
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
        _playbackSpeed.value = clamped
        _player.value?.setPlaybackSpeed(clamped)
        prefs.edit().putFloat(KEY_SPEED, clamped).apply()
    }

    // Cancels any running countdown and advances the generation; returns the new generation.
    private fun beginSleepTimerChange(): Int {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        return ++sleepTimerGeneration
    }

    /** Arm a countdown sleep timer; [minutes] <= 0 cancels it. */
    fun setSleepTimerMinutes(minutes: Int) {
        beginSleepTimerChange()
        if (minutes <= 0) {
            _sleepTimer.value = SleepTimer.Off
            return
        }
        val gen = sleepTimerGeneration
        val end = android.os.SystemClock.elapsedRealtime() + minutes * 60_000L
        _sleepTimer.value = SleepTimer.Timed(end)
        sleepTimerJob = scope.launch {
            val remaining = end - android.os.SystemClock.elapsedRealtime()
            if (remaining > 0) delay(remaining)
            if (gen != sleepTimerGeneration) return@launch  // superseded by a newer change
            _player.value?.pause()
            _sleepTimer.value = SleepTimer.Off
        }
    }

    /** Pause automatically once the current chapter finishes. */
    fun setSleepTimerEndOfChapter() {
        beginSleepTimerChange()
        _sleepTimer.value = SleepTimer.EndOfChapter
    }

    fun cancelSleepTimer() {
        beginSleepTimerChange()
        _sleepTimer.value = SleepTimer.Off
    }

    // #10: Do NOT cancel the singleton scope here. The scope must survive across
    // release/reconnect cycles. Only tear down the controller and position job.
    fun release() {
        playlistRequests.invalidate()
        positionUpdateJob?.cancel()
        positionUpdateJob = null
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerGeneration++
        _sleepTimer.value = SleepTimer.Off
        _isPlaying.value = false
        // Clear playback state so the UI doesn't show stale "Now Playing" after
        // the service is destroyed and before a new controller connects.
        _currentMediaId.value = null
        _currentPosition.value = 0L
        _duration.value = 0L
        _player.value = null
        currentPlaylistSourceKey = null
        // Use releaseFuture only; it handles the case where the future is still
        // pending (cancels) and where it has resolved (releases the controller).
        // Do NOT also call mediaController.release() — that double-disposes.
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        // scope.cancel() intentionally removed — @Singleton scope lives forever
    }
}
