package com.Bible3650.www.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.Bible3650.www.R
import com.Bible3650.www.audio.SleepTimer
import kotlinx.coroutines.delay

private fun formatClock(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private val SPEED_OPTIONS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
private val SLEEP_MINUTE_OPTIONS = listOf(5, 10, 15, 30, 45, 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    tasks: List<TaskUiModel>,
    currentMediaId: String?,
    viewModel: DashboardViewModel,
    onCollapse: () -> Unit
) {
    BackHandler(enabled = true) { onCollapse() }

    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val duration by viewModel.duration.collectAsStateWithLifecycle()
    val position by viewModel.currentPosition.collectAsStateWithLifecycle()
    val speed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val sleepTimer by viewModel.sleepTimer.collectAsStateWithLifecycle()
    val isSynthesizing by viewModel.isSynthesizing.collectAsStateWithLifecycle()

    val playingTask = remember(tasks, currentMediaId) { tasks.find { it.id == currentMediaId } }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Top bar ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCollapse) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Collapse player")
                }
                Text(
                    stringResource(R.string.now_playing),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = playingTask?.title ?: stringResource(R.string.audio_player),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = playingTask?.subtitle ?: "—",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2
                )

                if (isSynthesizing) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.synthesizing),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.height(24.dp))

                // ── Seek bar (#2) ────────────────────────────────────────────
                var dragValue by remember { mutableStateOf<Float?>(null) }
                val maxValue = duration.coerceAtLeast(1L).toFloat()
                val sliderValue = (dragValue ?: position.toFloat()).coerceIn(0f, maxValue)
                Slider(
                    value = sliderValue,
                    onValueChange = { dragValue = it },
                    onValueChangeFinished = {
                        dragValue?.let { viewModel.seekTo(it.toLong()) }
                        dragValue = null
                    },
                    valueRange = 0f..maxValue,
                    enabled = duration > 0
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        formatClock(sliderValue.toLong()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatClock(duration),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(8.dp))

                // ── Transport controls (#4) ──────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { viewModel.dispatchAction(DashboardAction.SkipPrevious) }) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous chapter",
                             modifier = Modifier.size(36.dp))
                    }
                    IconButton(onClick = { viewModel.dispatchAction(DashboardAction.Rewind) }) {
                        Icon(Icons.Default.FastRewind, contentDescription = "Rewind 15 seconds",
                             modifier = Modifier.size(36.dp))
                    }
                    FilledIconButton(
                        onClick = { viewModel.dispatchAction(DashboardAction.PlayPause) },
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            modifier = Modifier.size(44.dp)
                        )
                    }
                    IconButton(onClick = { viewModel.dispatchAction(DashboardAction.FastForward) }) {
                        Icon(Icons.Default.FastForward, contentDescription = "Forward 15 seconds",
                             modifier = Modifier.size(36.dp))
                    }
                    IconButton(onClick = { viewModel.dispatchAction(DashboardAction.SkipNext) }) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next chapter",
                             modifier = Modifier.size(36.dp))
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Speed (#1) ───────────────────────────────────────────────
                Text(stringResource(R.string.speed), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SPEED_OPTIONS.forEach { option ->
                        val selected = kotlin.math.abs(speed - option) < 0.001f
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.setSpeed(option) },
                            label = { Text(if (option == 1.0f) "1×" else "${trimSpeed(option)}×") }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Sleep timer (#3) ─────────────────────────────────────────
                SleepTimerControl(sleepTimer = sleepTimer, viewModel = viewModel)
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // ── Queue (#5) ───────────────────────────────────────────────────
            Text(
                stringResource(R.string.up_next),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp)
            )
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(tasks, key = { "np_${it.id}" }) { task ->
                    val isCurrent = task.id == currentMediaId
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.dispatchAction(DashboardAction.PlayFrom(task.id)) },
                        color = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    task.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    task.subtitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (isCurrent && isPlaying) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Now playing",
                                     tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerControl(sleepTimer: SleepTimer, viewModel: DashboardViewModel) {
    var expanded by remember { mutableStateOf(false) }

    // Tick once per second so the countdown label stays current.
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(sleepTimer) {
        while (sleepTimer is SleepTimer.Timed) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }

    val label = when (val st = sleepTimer) {
        is SleepTimer.Off -> stringResource(R.string.sleep_timer_off)
        is SleepTimer.EndOfChapter -> stringResource(R.string.sleep_timer_end_of_chapter)
        is SleepTimer.Timed -> stringResource(
            R.string.sleep_timer_remaining,
            formatClock((st.endElapsedRealtimeMs - now).coerceAtLeast(0))
        )
    }

    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sleep_off)) },
                onClick = { viewModel.cancelSleepTimer(); expanded = false }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sleep_end_of_chapter)) },
                onClick = { viewModel.setSleepTimerEndOfChapter(); expanded = false }
            )
            SLEEP_MINUTE_OPTIONS.forEach { minutes ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sleep_minutes, minutes)) },
                    onClick = { viewModel.setSleepTimerMinutes(minutes); expanded = false }
                )
            }
        }
    }
}

private fun trimSpeed(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else value.toString().trimEnd('0').trimEnd('.')
