package com.Bible3650.www.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.Bible3650.www.data.BibleRegistry
import com.Bible3650.www.domain.PresetPlan
import com.Bible3650.www.domain.toKey
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.saveable.rememberSaveable
import com.Bible3650.www.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageListsScreen(
    viewModel: ManageListsViewModel,
    sourceViewModel: SourceManagerViewModel = hiltViewModel(),
    onReviewMappings: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val lists by viewModel.listsFlow.collectAsStateWithLifecycle()
    val sources by sourceViewModel.sources.collectAsStateWithLifecycle()
    val detectionState by sourceViewModel.detectionState.collectAsStateWithLifecycle()
    val unavailableSourceIds by sourceViewModel.unavailableSourceIds.collectAsStateWithLifecycle()
    val apocryphaSuggestion by sourceViewModel.apocryphaSuggestion.collectAsStateWithLifecycle()
    val catalog by sourceViewModel.catalog.collectAsStateWithLifecycle()
    val textOpState by sourceViewModel.textOpState.collectAsStateWithLifecycle()
    var showTextBibleDialog by remember { mutableStateOf(false) }

    var showListDialog by remember { mutableStateOf(false) }
    var editingList by remember { mutableStateOf<com.Bible3650.www.data.local.ReadingListEntity?>(null) }
    var listNameInput by remember { mutableStateOf("") }
    var selectedBooks by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedColor by remember { mutableStateOf(0) }
    var pendingDeleteList by remember { mutableStateOf<com.Bible3650.www.data.local.ReadingListEntity?>(null) }
    var pendingRelinkSourceId by remember { mutableStateOf<Long?>(null) }

    val validationResults by viewModel.validationResults.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // #12: Check whether the saved audio folders are still accessible whenever the screen opens.
    LaunchedEffect(Unit) { sourceViewModel.refreshSourceHealth() }

    fun suggestedNameFor(uri: android.net.Uri): String =
        uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.ifBlank { "Audio Bible" }
            ?: "Audio Bible"

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // 3A: Some providers (cloud storage, third-party file managers) don't support
            // persistable URI permissions and throw SecurityException. Catch it gracefully.
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                android.util.Log.e("ManageLists", "Provider does not support persistable URIs", e)
            }
            sourceViewModel.onRootFolderPicked(uri, suggestedNameFor(uri))
        }
    }

    val relinkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val sourceId = pendingRelinkSourceId
        pendingRelinkSourceId = null
        if (uri != null && sourceId != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                android.util.Log.e("ManageLists", "Provider does not support persistable URIs", e)
            }
            sourceViewModel.relinkSource(sourceId, uri, suggestedNameFor(uri))
        }
    }

    LaunchedEffect(detectionState) {
        if (detectionState is DetectionState.Done) {
            onReviewMappings((detectionState as DetectionState.Done).sourceId)
            sourceViewModel.resetDetectionState()
        }
    }

    val importTextLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                android.util.Log.w("ManageLists", "No persistable permission for imported text", e)
            }
            sourceViewModel.importText(uri, suggestedNameFor(uri))
            showTextBibleDialog = false
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    // Step 1: show preset picker; step 2: confirm selected plan
    var showResetPickerDialog  by rememberSaveable { mutableStateOf(false) }
    var showResetConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var selectedPresetKey by rememberSaveable { mutableStateOf("GrantHorner") }
    val selectedPreset = remember(selectedPresetKey) { PresetPlan.fromKey(selectedPresetKey) }

    LaunchedEffect(viewModel) {
        viewModel.uiEvents.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(sourceViewModel) {
        sourceViewModel.uiEvents.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    if (apocryphaSuggestion.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { sourceViewModel.dismissApocryphaSuggestion() },
            title = { Text(stringResource(R.string.apocrypha_detected_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.apocrypha_detected_message,
                        apocryphaSuggestion.size,
                        apocryphaSuggestion.joinToString(", ")
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { sourceViewModel.createOrUpdateApocryphaList() }) {
                    Text(stringResource(R.string.apocrypha_create_list))
                }
            },
            dismissButton = {
                TextButton(onClick = { sourceViewModel.dismissApocryphaSuggestion() }) {
                    Text(stringResource(R.string.action_not_now))
                }
            }
        )
    }

    if (showTextBibleDialog) {
        val op = textOpState
        AlertDialog(
            onDismissRequest = {
                if (op !is TextOpState.Running && op !is TextOpState.Progress) {
                    showTextBibleDialog = false
                    sourceViewModel.resetTextOpState()
                }
            },
            title = { Text(stringResource(R.string.add_text_bible)) },
            text = {
                Column {
                    Text(stringResource(R.string.text_bible_dialog_desc), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    when (op) {
                        is TextOpState.Running -> Text(stringResource(R.string.text_op_preparing), style = MaterialTheme.typography.bodySmall)
                        is TextOpState.Progress -> Text(
                            stringResource(R.string.text_op_progress, op.done, op.total),
                            style = MaterialTheme.typography.bodySmall
                        )
                        is TextOpState.Error -> Text(op.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextOpState.Idle -> {
                            catalog.forEach { entry ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(entry.name, style = MaterialTheme.typography.titleSmall)
                                        entry.attribution?.let {
                                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    TextButton(onClick = { sourceViewModel.downloadTranslation(entry) }) {
                                        Text(stringResource(R.string.action_download))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { importTextLauncher.launch(arrayOf("application/json", "text/*", "application/xml", "*/*")) },
                    enabled = op !is TextOpState.Running && op !is TextOpState.Progress
                ) { Text(stringResource(R.string.action_import_file)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showTextBibleDialog = false
                    sourceViewModel.resetTextOpState()
                }) { Text(stringResource(R.string.action_close)) }
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.title_reading_lists)) })
        },
        floatingActionButtonPosition = FabPosition.Start,
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editingList = com.Bible3650.www.data.local.ReadingListEntity(
                    listName = "", listOrder = lists.size
                )
                listNameInput = ""
                selectedBooks = emptyList()
                selectedColor = nextSuggestedColorArgb(lists.size)
                showListDialog = true
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add Reading List")
            }
        }
    ) { paddingValues ->
        // #9: Apply paddingValues so LazyColumn content is not hidden behind the FAB
        // or the system navigation bar at the bottom of the screen.
        // ── Step 1: Preset picker ──────────────────────────────────────────────
        if (showResetPickerDialog) {
            AlertDialog(
                onDismissRequest = { showResetPickerDialog = false },
                title = { Text(stringResource(R.string.choose_preset_plan)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.preset_picker_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        PresetPlan.all().forEach { plan ->
                            val isSelected = selectedPresetKey == plan.toKey()
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { 
                                        selectedPresetKey = plan.toKey()
                                        showResetPickerDialog = false
                                        showResetConfirmDialog = true
                                    },
                                color = if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant,
                                tonalElevation = if (isSelected) 4.dp else 0.dp
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        plan.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSelected)
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        else
                                            MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        plan.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSelected)
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showResetPickerDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            )
        }

        // ── Step 2: Confirm destructive reset ──────────────────────────────────
        if (showResetConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showResetConfirmDialog = false },
                title = { Text(stringResource(R.string.reset_to_plan, selectedPreset.displayName)) },
                text = { Text(stringResource(R.string.reset_to_plan_warning, selectedPreset.displayName)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.resetToDefaults(selectedPreset)
                            showResetConfirmDialog = false
                        }
                    ) { Text(stringResource(R.string.action_reset), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirmDialog = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ----------------------------------------------------------------
            // Audio Sources section
            // ----------------------------------------------------------------
            item(key = "sources_title") {
                Text(
                    stringResource(R.string.audio_sources),
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(8.dp))
            }

            if (detectionState is DetectionState.Running) {
                item(key = "detection_running") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.analyzing_folder), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            if (detectionState is DetectionState.Error) {
                item(key = "detection_error") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(R.string.detection_error, (detectionState as DetectionState.Error).message),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            items(sources, key = { "source_${it.source.sourceId}" }) { swm ->
                SourceCard(
                    swm          = swm,
                    isUnavailable = swm.source.sourceId in unavailableSourceIds,
                    onMakeActive = { sourceViewModel.switchSource(swm.source) },
                    onReview     = { onReviewMappings(swm.source.sourceId) },
                    onRelink     = {
                        pendingRelinkSourceId = swm.source.sourceId
                        relinkLauncher.launch(null)
                    },
                    onDelete     = { sourceViewModel.deleteSource(swm.source) }
                )
            }

            item(key = "browse_button") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { folderPickerLauncher.launch(null) },
                        modifier = Modifier.weight(1f),
                        enabled = detectionState !is DetectionState.Running
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.browse_audio))
                    }
                    OutlinedButton(
                        onClick = { showResetPickerDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.reset_lists), color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        sourceViewModel.loadCatalog()
                        showTextBibleDialog = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = detectionState !is DetectionState.Running
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add_text_bible))
                }
                Spacer(Modifier.height(8.dp))
            }

            // ----------------------------------------------------------------
            // Listening Lists section
            // ----------------------------------------------------------------
            item(key = "lists_title") {
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.listening_lists),
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(8.dp))
            }

            if (lists.isNotEmpty() && !validationResults.isValid) {
                item(key = "validation_banner") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        // #11: Use Material3 theme tokens instead of hard-coded colors so the
                        // banner renders correctly in both light/dark and dynamic-color modes.
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = "Warning",
                                     tint = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(Modifier.width(16.dp))
                                Text(stringResource(R.string.list_config_warning), style = MaterialTheme.typography.titleSmall)
                            }
                            if (validationResults.missingBooks.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(stringResource(R.string.missing_books_count, validationResults.missingBooks.size), style = MaterialTheme.typography.labelMedium)
                                Text(validationResults.missingBooks.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                            }
                            if (validationResults.duplicateBooks.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(stringResource(R.string.duplicate_books_count, validationResults.duplicateBooks.size), style = MaterialTheme.typography.labelMedium)
                                Text(validationResults.duplicateBooks.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            items(lists, key = { "list_${it.readingList.listId}" }) { listData ->
                val cardColor = if (listData.readingList.listColor != 0)
                    Color(listData.readingList.listColor).copy(alpha = 0.35f)
                else
                    MaterialTheme.colorScheme.surfaceVariant

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Color swatch dot
                        if (listData.readingList.listColor != 0) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(listData.readingList.listColor))
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                listData.readingList.listName,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                listData.books.sortedBy { it.sortOrder }.joinToString(", ") { it.bookName },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        Column {
                            IconButton(onClick = {
                                val idx = lists.indexOf(listData)
                                if (idx > 0) {
                                    val m = lists.map { it.readingList }.toMutableList()
                                    m[idx] = m[idx - 1].also { m[idx - 1] = m[idx] }
                                    viewModel.reorderLists(m)
                                }
                            }) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up")
                            }
                            IconButton(onClick = {
                                val idx = lists.indexOf(listData)
                                if (idx < lists.size - 1) {
                                    val m = lists.map { it.readingList }.toMutableList()
                                    m[idx] = m[idx + 1].also { m[idx + 1] = m[idx] }
                                    viewModel.reorderLists(m)
                                }
                            }) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down")
                            }
                        }
                        Column {
                            IconButton(onClick = {
                                editingList = listData.readingList
                                listNameInput = listData.readingList.listName
                                selectedBooks = listData.books.sortedBy { it.sortOrder }.map { it.bookName }
                                selectedColor = listData.readingList.listColor
                                showListDialog = true
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit")
                            }
                            IconButton(onClick = { pendingDeleteList = listData.readingList }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete",
                                     tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    // ----------------------------------------------------------------
    // Delete confirmation dialog
    // ----------------------------------------------------------------
    pendingDeleteList?.let { listToDelete ->
        AlertDialog(
            onDismissRequest = { pendingDeleteList = null },
            title = { Text(stringResource(R.string.delete_list_title, listToDelete.listName)) },
            text = { Text(stringResource(R.string.delete_list_warning)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteList(listToDelete)
                        pendingDeleteList = null
                    }
                ) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteList = null }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    // ----------------------------------------------------------------
    // Add / Edit reading list dialog
    // ----------------------------------------------------------------
    if (showListDialog) {
        Dialog(
            onDismissRequest = { showListDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TopAppBar(
                        title = { Text(if (editingList?.listId == 0L) stringResource(R.string.create_new_list) else stringResource(R.string.edit_list)) },
                        navigationIcon = {
                            IconButton(onClick = { showListDialog = false }) {
                                Icon(Icons.Default.Close, contentDescription = "Close")
                            }
                        },
                        actions = {
                            TextButton(
                                onClick = {
                                    if (listNameInput.isNotBlank() && selectedBooks.isNotEmpty()) {
                                        val el = editingList
                                        if (el != null) {
                                            if (el.listId == 0L) {
                                                viewModel.createList(listNameInput, selectedBooks, selectedColor)
                                            } else {
                                                viewModel.updateList(
                                                    el.copy(listName = listNameInput, listColor = selectedColor),
                                                    selectedBooks
                                                )
                                            }
                                        }
                                        showListDialog = false
                                    }
                                },
                                enabled = listNameInput.isNotBlank() && selectedBooks.isNotEmpty()
                            ) { Text(stringResource(R.string.action_save)) }
                        }
                    )
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedTextField(
                            value = listNameInput,
                            onValueChange = { listNameInput = it },
                            label = { Text(stringResource(R.string.list_name)) },
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Color picker
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.list_color), style = MaterialTheme.typography.titleSmall)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .border(
                                                width = if (selectedColor == 0) 3.dp else 1.dp,
                                                color = if (selectedColor == 0)
                                                    MaterialTheme.colorScheme.primary
                                                else
                                                    MaterialTheme.colorScheme.outlineVariant,
                                                shape = CircleShape
                                            )
                                            .clickable { selectedColor = 0 },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "No Color",
                                            modifier = Modifier.size(20.dp),
                                            tint = if (selectedColor == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                items(ListColorPalette) { color ->
                                    val argb = color.toArgb()
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(color)
                                            .border(
                                                width = if (selectedColor == argb) 3.dp else 1.dp,
                                                color = if (selectedColor == argb)
                                                    MaterialTheme.colorScheme.primary
                                                else
                                                    MaterialTheme.colorScheme.outlineVariant,
                                                shape = CircleShape
                                            )
                                            .clickable { selectedColor = argb }
                                    )
                                }
                            }
                        }

                        Text(stringResource(R.string.selected_books_hint), style = MaterialTheme.typography.titleSmall)
                        if (selectedBooks.isEmpty()) {
                            Text(
                                stringResource(R.string.no_books_selected),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            // #23: Drag-to-reorder. The list reorders once on drop (no mid-drag
                            // mutation), so it's robust; the arrow buttons remain as a fallback.
                            var draggingIndex by remember { mutableStateOf<Int?>(null) }
                            var dragOffsetY by remember { mutableStateOf(0f) }
                            var itemHeightPx by remember { mutableStateOf(1) }
                            val spacingPx = with(LocalDensity.current) { 8.dp.toPx() }

                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(selectedBooks, key = { _, book -> "selected_$book" }) { idx, book ->
                                    val isDragging = idx == draggingIndex
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .onSizeChanged { if (it.height > 0) itemHeightPx = it.height }
                                            .zIndex(if (isDragging) 1f else 0f)
                                            .graphicsLayer { translationY = if (isDragging) dragOffsetY else 0f },
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isDragging)
                                                MaterialTheme.colorScheme.secondaryContainer
                                            else
                                                MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.DragHandle,
                                                contentDescription = "Drag to reorder",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.pointerInput(book, selectedBooks.size) {
                                                    detectDragGesturesAfterLongPress(
                                                        onDragStart = { draggingIndex = idx; dragOffsetY = 0f },
                                                        onDrag = { change, amount ->
                                                            change.consume()
                                                            dragOffsetY += amount.y
                                                        },
                                                        onDragEnd = {
                                                            val step = itemHeightPx + spacingPx
                                                            val delta = if (step > 0f) (dragOffsetY / step).roundToInt() else 0
                                                            val target = (idx + delta).coerceIn(0, selectedBooks.size - 1)
                                                            if (target != idx) {
                                                                val m = selectedBooks.toMutableList()
                                                                m.add(target, m.removeAt(idx))
                                                                selectedBooks = m
                                                            }
                                                            draggingIndex = null
                                                            dragOffsetY = 0f
                                                        },
                                                        onDragCancel = {
                                                            draggingIndex = null
                                                            dragOffsetY = 0f
                                                        }
                                                    )
                                                }
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(book, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                            IconButton(
                                                onClick = {
                                                    if (idx > 0) {
                                                        val m = selectedBooks.toMutableList()
                                                        m[idx] = m[idx - 1].also { m[idx - 1] = m[idx] }
                                                        selectedBooks = m
                                                    }
                                                },
                                                enabled = idx > 0
                                            ) {
                                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up")
                                            }
                                            IconButton(
                                                onClick = {
                                                    if (idx < selectedBooks.size - 1) {
                                                        val m = selectedBooks.toMutableList()
                                                        m[idx] = m[idx + 1].also { m[idx + 1] = m[idx] }
                                                        selectedBooks = m
                                                    }
                                                },
                                                enabled = idx < selectedBooks.size - 1
                                            ) {
                                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down")
                                            }
                                            IconButton(
                                                onClick = { selectedBooks = selectedBooks.filter { it != book } }
                                            ) {
                                                Icon(Icons.Default.Close, contentDescription = "Remove")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                        Text(stringResource(R.string.available_books_hint), style = MaterialTheme.typography.titleSmall)
                        var bookSearch by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = bookSearch,
                            onValueChange = { bookSearch = it },
                            label = { Text(stringResource(R.string.search_books)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        val query = bookSearch.trim()
                        val available = BibleRegistry.getAllBooks()
                            .filter { it !in selectedBooks && it.contains(query, ignoreCase = true) }
                        if (available.isEmpty()) {
                            Text(
                                if (query.isEmpty()) stringResource(R.string.all_books_added)
                                else stringResource(R.string.no_books_match, query),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(available, key = { "available_$it" }) { book ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            if (book !in selectedBooks) {
                                                selectedBooks = selectedBooks + book
                                            }
                                        },
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(book, modifier = Modifier.padding(16.dp),
                                         style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Source card
// ---------------------------------------------------------------------------

@Composable
private fun SourceCard(
    swm: com.Bible3650.www.data.local.SourceWithMappings,
    isUnavailable: Boolean,
    onMakeActive: () -> Unit,
    onReview: () -> Unit,
    onRelink: () -> Unit,
    onDelete: () -> Unit
) {
    val isText = swm.source.sourceType == "TEXT"
    val mapped = swm.mappings.size
    val total  = BibleRegistry.getAllBooks().size
    val allMapped = mapped == total
    val ok = if (isText) !isUnavailable else (allMapped && !isUnavailable)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isUnavailable      -> MaterialTheme.colorScheme.errorContainer
                swm.source.isActive -> MaterialTheme.colorScheme.primaryContainer
                else               -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(swm.source.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (isText) stringResource(R.string.text_source_subtitle)
                        else stringResource(R.string.books_linked, mapped, total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isText) {
                    Badge { Text(stringResource(R.string.badge_text)) }
                    Spacer(Modifier.width(4.dp))
                }
                if (swm.source.isActive) {
                    Badge { Text(stringResource(R.string.active)) }
                }
            }

            if (isUnavailable) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.source_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isUnavailable) {
                    Button(onClick = onRelink, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.action_relink))
                    }
                } else {
                    if (!isText) {
                        OutlinedButton(onClick = onReview, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.action_review))
                        }
                    }
                    if (!swm.source.isActive) {
                        Button(onClick = onMakeActive, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.action_use))
                        }
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete source",
                         tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
