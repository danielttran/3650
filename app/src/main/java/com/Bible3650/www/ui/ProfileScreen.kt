package com.Bible3650.www.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import com.Bible3650.www.R
import com.Bible3650.www.data.text.readUtf8UpTo
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private const val MAX_BACKUP_BYTES = 64 * 1024 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showImportConfirm by remember { mutableStateOf(false) }
    var pendingImportJson by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    val json = viewModel.exportData()
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openOutputStream(it)
                            ?: throw IOException("Could not open backup destination")
                        stream.use { output -> output.write(json.toByteArray()) }
                    }
                    snackbarHostState.showSnackbar(context.getString(R.string.progress_exported))
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar(context.getString(R.string.export_failed))
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    val json = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(it)?.use { stream ->
                            // Text-Bible backups can legitimately exceed the old 10 MB cap. Read
                            // incrementally so a corrupted provider cannot allocate without bound.
                            stream.readUtf8UpTo(MAX_BACKUP_BYTES)
                        }
                    }
                    if (json != null) {
                        pendingImportJson = json
                        showImportConfirm = true
                    } else {
                        snackbarHostState.showSnackbar(context.getString(R.string.backup_read_failed_large))
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar(context.getString(R.string.backup_read_failed))
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.title_my_progress)) })
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.total_chapters),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "${uiState.totalChaptersRead}",
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Button(
                        onClick = { viewModel.resetStats() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = MaterialTheme.colorScheme.onSecondary
                        ),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(stringResource(R.string.reset_total_stats))
                    }

                    Row(
                        modifier = Modifier.padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = { exportLauncher.launch("bible3650_backup.json") }) {
                            Text(stringResource(R.string.export_progress))
                        }
                        OutlinedButton(onClick = {
                            importLauncher.launch(
                                arrayOf(
                                    "application/json",
                                    "application/octet-stream"
                                )
                            )
                        }) {
                            Text(stringResource(R.string.import_progress))
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.books_completed_stats),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 32.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(uiState.bookStats, key = { it.bookName }) { stat ->
                    val hasColor = stat.listColor != 0
                    val cardColor = if (hasColor) Color(stat.listColor) else MaterialTheme.colorScheme.surfaceVariant
                    val contentColor = if (hasColor) contentColorOn(cardColor) else MaterialTheme.colorScheme.onSurfaceVariant
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = cardColor, contentColor = contentColor)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stat.bookName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                            Text(
                                text = "(${stat.readCount})",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (stat.readCount > 0)
                                    if (hasColor) contentColor else MaterialTheme.colorScheme.primary
                                else
                                    if (hasColor) contentColor.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
    if (showImportConfirm) {
        AlertDialog(
            onDismissRequest = { showImportConfirm = false },
            title = { Text(stringResource(R.string.restore_progress_q)) },
            text = { Text(stringResource(R.string.restore_progress_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val json = pendingImportJson
                        if (json != null) {
                            scope.launch {
                                val success = viewModel.importData(json)
                                if (success) {
                                    snackbarHostState.showSnackbar(context.getString(R.string.progress_restored))
                                } else {
                                    snackbarHostState.showSnackbar(context.getString(R.string.restore_invalid))
                                }
                            }
                        }
                        showImportConfirm = false
                    }
                ) { Text(stringResource(R.string.action_restore), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}
