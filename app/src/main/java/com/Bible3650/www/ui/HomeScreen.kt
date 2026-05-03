package com.Bible3650.www.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentMediaId by viewModel.currentMediaId.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        when (val uiState = state) {
            is DashboardUiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            is DashboardUiState.NoSource -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "No audio source linked",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Go to Lists → Audio Sources and browse to a folder containing your audio Bible files.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
            is DashboardUiState.Active -> {
                val playingTask = remember(uiState.tasks, currentMediaId) {
                    uiState.tasks.find { it.id == currentMediaId }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "PROFESSOR GRANT HORNER BIBLE",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    items(uiState.tasks, key = { "task_${it.id}" }) { task ->
                        ListEntryItem(
                            task = task,
                            isPlaying = task.id == currentMediaId,
                            onPlayClick = {
                                viewModel.dispatchAction(DashboardAction.PlayFrom(task.id))
                            },
                            onDecrement = {
                                viewModel.dispatchAction(DashboardAction.DecrementProgress(task.listId))
                            },
                            onIncrement = {
                                viewModel.dispatchAction(DashboardAction.IncrementProgress(task.listId))
                            }
                        )
                    }

                    item { Spacer(modifier = Modifier.height(140.dp)) }
                }

                // MiniPlayerBar is its own composable so that currentPosition / duration
                // updates only recompose the bar, not the entire screen.
                MiniPlayerBar(
                    playingTask = playingTask,
                    viewModel = viewModel,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
private fun MiniPlayerBar(
    playingTask: TaskUiModel?,
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier
) {
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val duration by viewModel.duration.collectAsStateWithLifecycle()

    if (playingTask == null && !isPlaying) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 8.dp,
        shadowElevation = 16.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column {
            PlayerProgressBar(duration = duration, viewModel = viewModel)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${playingTask?.title ?: "Audio Player"} (${playingTask?.totalChapters ?: 0})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                    Text(
                        playingTask?.subtitle ?: "Playing…",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                    TimeRemainingText(duration = duration, viewModel = viewModel)
                }

                IconButton(
                    onClick = { viewModel.dispatchAction(DashboardAction.PlayPause) },
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        modifier = Modifier.size(52.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(
                    onClick = { viewModel.dispatchAction(DashboardAction.SkipNext) },
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Skip",
                        modifier = Modifier.size(52.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private fun formatTimeRemaining(remainingMs: Long): String {
    if (remainingMs <= 0) return "-0:00"
    val totalSeconds = remainingMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "-${hours}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "-${minutes}:${seconds.toString().padStart(2, '0')}"
    }
}

@Composable
private fun PlayerProgressBar(duration: Long, viewModel: DashboardViewModel) {
    val currentPosition by viewModel.currentPosition.collectAsStateWithLifecycle()
    val progress = if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth().height(6.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
    )
}

@Composable
private fun TimeRemainingText(duration: Long, viewModel: DashboardViewModel) {
    val currentPosition by viewModel.currentPosition.collectAsStateWithLifecycle()
    val timeRemaining = remember(duration, currentPosition) { formatTimeRemaining(duration - currentPosition) }
    if (duration > 0) {
        Text(
            text = timeRemaining,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            maxLines = 1
        )
    }
}

@Composable
fun ListEntryItem(
    task: TaskUiModel,
    isPlaying: Boolean,
    onPlayClick: () -> Unit,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit
) {
    val listColor = if (task.listColor != 0) Color(task.listColor) else null
    
    val backgroundColor = when {
        isPlaying && listColor != null -> listColor.copy(alpha = 0.85f)
        isPlaying -> MaterialTheme.colorScheme.primaryContainer
        listColor != null -> listColor.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.surface
    }

    val titleColor = when {
        isPlaying && listColor != null -> Color.Black
        isPlaying -> MaterialTheme.colorScheme.primary
        listColor != null -> Color.Black
        else -> MaterialTheme.colorScheme.onSurface
    }

    val subtitleColor = when {
        listColor != null -> Color.Black.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val iconColor = when {
        listColor != null -> Color.Black.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlayClick() },
        color = backgroundColor,
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${task.title} (${task.totalChapters})",
                            style = MaterialTheme.typography.titleMedium,
                            color = titleColor
                        )
                        if (isPlaying) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Now Playing",
                                style = MaterialTheme.typography.labelSmall,
                                color = titleColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        text = task.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = subtitleColor
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDecrement) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous",
                             tint = iconColor)
                    }
                    IconButton(onClick = onIncrement) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Next",
                             tint = iconColor)
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.align(Alignment.BottomCenter),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        }
    }
}
