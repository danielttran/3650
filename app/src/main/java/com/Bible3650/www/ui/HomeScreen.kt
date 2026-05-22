package com.Bible3650.www.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.Bible3650.www.R
import com.Bible3650.www.data.BibleRegistry

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
                        stringResource(R.string.msg_no_audio_source),
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
                var showFullPlayer by rememberSaveable { mutableStateOf(false) }
                var jumpTarget by remember { mutableStateOf<TaskUiModel?>(null) }
                val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
                val playingTask = remember(uiState.tasks, currentMediaId) {
                    uiState.tasks.find { it.id == currentMediaId }
                }

                Column(Modifier.fillMaxSize()) {
                    // Pinned screen header — stays visible while the list scrolls
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.header_prof_grant_horner),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    // Resume-first CTA (#8): one tap to continue or start the rolling lists.
                    if (!isPlaying) {
                        val canResume = currentMediaId != null && playingTask != null
                        val resumeTask = playingTask ?: uiState.tasks.firstOrNull()
                        if (resumeTask != null) {
                            FilledTonalButton(
                                onClick = {
                                    if (canResume) {
                                        viewModel.dispatchAction(DashboardAction.PlayPause)
                                    } else {
                                        viewModel.dispatchAction(DashboardAction.PlayFrom(resumeTask.id))
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (canResume) "Resume — ${playingTask!!.subtitle}"
                                    else "Play all lists"
                                )
                            }
                        }
                    }

                    // Scrollable task list + floating mini-player
                    Box(Modifier.weight(1f)) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 8.dp)
                        ) {
                            items(uiState.tasks, key = { "task_${it.id}" }) { task ->
                                ListEntryItem(
                                    task = task,
                                    isPlaying = task.id == currentMediaId,
                                    onPlayClick = {
                                        viewModel.dispatchAction(DashboardAction.PlayFrom(task.id))
                                    },
                                    onJump = { jumpTarget = task },
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

                        // MiniPlayerBar is its own composable so that currentPosition /
                        // duration updates only recompose the bar, not the entire list.
                        MiniPlayerBar(
                            playingTask = playingTask,
                            viewModel = viewModel,
                            onExpand = { showFullPlayer = true },
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }

                // Full-screen Now Playing overlay (#5)
                if (showFullPlayer) {
                    NowPlayingScreen(
                        tasks = uiState.tasks,
                        currentMediaId = currentMediaId,
                        viewModel = viewModel,
                        onCollapse = { showFullPlayer = false }
                    )
                }

                // Jump-to-chapter dialog (#6) — opened by long-pressing a list row.
                jumpTarget?.let { target ->
                    JumpToChapterDialog(
                        task = target,
                        onDismiss = { jumpTarget = null },
                        onJump = { book, chapter ->
                            viewModel.jumpToChapter(target.listId, book, chapter)
                            jumpTarget = null
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerBar(
    playingTask: TaskUiModel?,
    viewModel: DashboardViewModel,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val duration by viewModel.duration.collectAsStateWithLifecycle()

    // Hide the bar entirely when there is nothing to show. Showing a placeholder
    // ("Audio Player (0)") while isPlaying is true but the playing task hasn't
    // resolved leads to a confusing flash, so require a real task.
    if (playingTask == null) return

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
                // Tapping the title area expands the full Now Playing screen.
                Column(modifier = Modifier.weight(1f).clickable { onExpand() }) {
                    Text(
                        playingTask.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                        maxLines = 1
                    )
                    Text(
                        playingTask.subtitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
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
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
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
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListEntryItem(
    task: TaskUiModel,
    isPlaying: Boolean,
    onPlayClick: () -> Unit,
    onJump: () -> Unit,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit
) {
    val listColor = if (task.listColor != 0) Color(task.listColor) else null
    val surface = MaterialTheme.colorScheme.surface

    val backgroundColor = when {
        isPlaying && listColor != null -> listColor.copy(alpha = 0.85f)
        isPlaying -> MaterialTheme.colorScheme.primaryContainer
        listColor != null -> listColor.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.surface
    }

    val titleColor: Color
    val subtitleColor: Color
    val iconColor: Color
    if (listColor != null) {
        // Composite the translucent list color over the real surface so the chosen
        // on-color stays readable in both light and dark themes.
        val effectiveBg = backgroundColor.compositeOver(surface)
        titleColor = contentColorOn(effectiveBg)
        subtitleColor = titleColor.copy(alpha = 0.7f)
        iconColor = titleColor.copy(alpha = 0.85f)
    } else if (isPlaying) {
        titleColor = MaterialTheme.colorScheme.onPrimaryContainer
        subtitleColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
        iconColor = MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        titleColor = MaterialTheme.colorScheme.onSurface
        subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant
        iconColor = MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onPlayClick, onLongClick = onJump),
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
                            text = task.title,
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
                    // Loop-progress indicator (#7): where this list sits in its cycle.
                    if (task.totalChapters > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(
                                progress = {
                                    (task.loopPosition.toFloat() / task.totalChapters).coerceIn(0f, 1f)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(4.dp),
                                color = iconColor,
                                trackColor = iconColor.copy(alpha = 0.2f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${task.loopPosition} / ${task.totalChapters}",
                                style = MaterialTheme.typography.labelSmall,
                                color = subtitleColor
                            )
                        }
                    }
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

@Composable
private fun JumpToChapterDialog(
    task: TaskUiModel,
    onDismiss: () -> Unit,
    onJump: (book: String, chapter: Int) -> Unit
) {
    val books = task.books.ifEmpty { listOf(task.targetBook) }
    var selectedBook by remember {
        mutableStateOf(if (task.targetBook in books) task.targetBook else books.first())
    }
    var chapterText by remember { mutableStateOf(task.targetChapter.toString()) }
    var bookMenuOpen by remember { mutableStateOf(false) }

    val maxChapter = BibleRegistry.getChapterCount(selectedBook).coerceAtLeast(1)
    val parsedChapter = chapterText.toIntOrNull()
    val valid = parsedChapter != null && parsedChapter in 1..maxChapter

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Jump to chapter") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (books.size > 1) {
                    Box {
                        OutlinedButton(onClick = { bookMenuOpen = true }) { Text(selectedBook) }
                        DropdownMenu(expanded = bookMenuOpen, onDismissRequest = { bookMenuOpen = false }) {
                            books.forEach { b ->
                                DropdownMenuItem(
                                    text = { Text(b) },
                                    onClick = {
                                        selectedBook = b
                                        chapterText = "1"
                                        bookMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                } else {
                    Text(selectedBook, style = MaterialTheme.typography.titleSmall)
                }
                OutlinedTextField(
                    value = chapterText,
                    onValueChange = { chapterText = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("Chapter (1–$maxChapter)") },
                    singleLine = true,
                    isError = chapterText.isNotEmpty() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onJump(selectedBook, parsedChapter ?: 1) }) {
                Text("Jump")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
