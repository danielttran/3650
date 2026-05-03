package com.Bible3650.www.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.Bible3650.www.data.BibleRegistry
import com.Bible3650.www.domain.PresetPlan
import com.Bible3650.www.domain.toKey
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.saveable.rememberSaveable

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

    var showListDialog by remember { mutableStateOf(false) }
    var editingList by remember { mutableStateOf<com.Bible3650.www.data.local.ReadingListEntity?>(null) }
    var listNameInput by remember { mutableStateOf("") }
    var selectedBooks by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedColor by remember { mutableStateOf(0) }
    var pendingDeleteList by remember { mutableStateOf<com.Bible3650.www.data.local.ReadingListEntity?>(null) }

    val validationResults by viewModel.validationResults.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val suggested = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringAfterLast(':')
                ?.ifBlank { "Audio Bible" }
                ?: "Audio Bible"
            sourceViewModel.onRootFolderPicked(uri, suggested)
        }
    }

    LaunchedEffect(detectionState) {
        if (detectionState is DetectionState.Done) {
            onReviewMappings((detectionState as DetectionState.Done).sourceId)
            sourceViewModel.resetDetectionState()
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

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
    ) { _ ->
        // ── Step 1: Preset picker ──────────────────────────────────────────────
        if (showResetPickerDialog) {
            AlertDialog(
                onDismissRequest = { showResetPickerDialog = false },
                title = { Text("Choose a Preset Plan") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Select a plan to restore. All current lists and reading progress will be erased.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        PresetPlan.all().forEach { plan ->
                            val isSelected = selectedPresetKey == plan.toKey()
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
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
                    TextButton(onClick = { showResetPickerDialog = false }) { Text("Cancel") }
                }
            )
        }

        // ── Step 2: Confirm destructive reset ──────────────────────────────────
        if (showResetConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showResetConfirmDialog = false },
                title = { Text("Reset to \"${selectedPreset.displayName}\"?") },
                text = { Text("This will delete all current lists and reading progress, and restore the \"${selectedPreset.displayName}\" plan. Your audio source mappings will not be deleted. This cannot be undone.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.resetToDefaults(selectedPreset)
                            showResetConfirmDialog = false
                        }
                    ) { Text("Reset", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirmDialog = false }) { Text("Cancel") }
                }
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ----------------------------------------------------------------
            // Audio Sources section
            // ----------------------------------------------------------------
            item(key = "sources_title") {
                Text(
                    "Audio Sources",
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
                            Text("Analyzing folder structure…", style = MaterialTheme.typography.bodyMedium)
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
                            "Detection error: ${(detectionState as DetectionState.Error).message}",
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
                    onMakeActive = { sourceViewModel.switchSource(swm.source) },
                    onReview     = { onReviewMappings(swm.source.sourceId) },
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
                        Text("Browse Audio")
                    }
                    OutlinedButton(
                        onClick = { showResetPickerDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Reset Lists", color = MaterialTheme.colorScheme.error)
                    }
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
                    "Listening Lists",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(8.dp))
            }

            if (lists.isNotEmpty() && !validationResults.isValid) {
                item(key = "validation_banner") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFF9C4),
                            contentColor = Color(0xFF5D4037)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = "Warning", tint = Color(0xFFFBC02D))
                                Spacer(Modifier.width(16.dp))
                                Text("List Configuration Warning", style = MaterialTheme.typography.titleSmall)
                            }
                            if (validationResults.missingBooks.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text("Missing Books (${validationResults.missingBooks.size})", style = MaterialTheme.typography.labelMedium)
                                Text(validationResults.missingBooks.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                            }
                            if (validationResults.duplicateBooks.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text("Duplicate Books (${validationResults.duplicateBooks.size})", style = MaterialTheme.typography.labelMedium)
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
            title = { Text("Delete \"${listToDelete.listName}\"?") },
            text = { Text("This will permanently delete the list and all reading progress. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteList(listToDelete)
                        pendingDeleteList = null
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteList = null }) { Text("Cancel") }
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
                        title = { Text(if (editingList?.listId == 0L) "Create New List" else "Edit List") },
                        navigationIcon = {
                            IconButton(onClick = { showListDialog = false }) {
                                Icon(Icons.Default.Close, contentDescription = "Close")
                            }
                        },
                        actions = {
                            TextButton(
                                onClick = {
                                    if (listNameInput.isNotBlank() && selectedBooks.isNotEmpty()) {
                                        if (editingList?.listId == 0L) {
                                            viewModel.createList(listNameInput, selectedBooks, selectedColor)
                                        } else {
                                            viewModel.updateList(
                                                editingList!!.copy(listName = listNameInput, listColor = selectedColor),
                                                selectedBooks
                                            )
                                        }
                                        showListDialog = false
                                    }
                                },
                                enabled = listNameInput.isNotBlank() && selectedBooks.isNotEmpty()
                            ) { Text("Save") }
                        }
                    )
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedTextField(
                            value = listNameInput,
                            onValueChange = { listNameInput = it },
                            label = { Text("List Name") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Color picker
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("List Color", style = MaterialTheme.typography.titleSmall)
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

                        Text("Selected Books", style = MaterialTheme.typography.titleSmall)
                        if (selectedBooks.isEmpty()) {
                            Text(
                                "No books selected",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(selectedBooks, key = { _, book -> "selected_$book" }) { idx, book ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
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
                        Text("Available Books (Tap to add)", style = MaterialTheme.typography.titleSmall)
                        val available = BibleRegistry.getAllBooks().filter { it !in selectedBooks }
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(available, key = { "available_$it" }) { book ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
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
    onMakeActive: () -> Unit,
    onReview: () -> Unit,
    onDelete: () -> Unit
) {
    val mapped = swm.mappings.size
    val total  = BibleRegistry.getAllBooks().size
    val allMapped = mapped == total

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (swm.source.isActive)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (allMapped) Icons.Filled.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (allMapped) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(swm.source.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "$mapped / $total books linked",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (swm.source.isActive) {
                    Badge { Text("Active") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReview, modifier = Modifier.weight(1f)) {
                    Text("Review")
                }
                if (!swm.source.isActive) {
                    Button(onClick = onMakeActive, modifier = Modifier.weight(1f)) {
                        Text("Use")
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
