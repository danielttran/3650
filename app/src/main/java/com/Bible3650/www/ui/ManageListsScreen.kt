package com.Bible3650.www.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.Bible3650.www.data.BibleRegistry
import com.Bible3650.www.data.local.AudioSourceEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageListsScreen(
    viewModel: ManageListsViewModel,
    sourceViewModel: SourceManagerViewModel = hiltViewModel(),
    onBackClick: () -> Unit,
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

    val allBooksInBible = BibleRegistry.getAllBooks()
    val allBooksInLists = lists.flatMap { it.books }.map { it.bookName }.toSet()
    val missingBooks = allBooksInBible.filter { !allBooksInLists.contains(it) }

    val context = LocalContext.current

    // SAF folder picker for adding a new audio source
    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            // Use the last path segment as a suggested name (user can rename later)
            val suggested = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringAfterLast(':')
                ?.ifBlank { "Audio Bible" }
                ?: "Audio Bible"
            sourceViewModel.onRootFolderPicked(uri, suggested)
        }
    }

    // Navigate to review screen once detection finishes
    LaunchedEffect(detectionState) {
        if (detectionState is DetectionState.Done) {
            onReviewMappings((detectionState as DetectionState.Done).sourceId)
            sourceViewModel.resetDetectionState()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Manage Lists") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editingList = com.Bible3650.www.data.local.ReadingListEntity(
                    listName = "", listOrder = lists.size
                )
                listNameInput = ""
                selectedBooks = emptyList()
                showListDialog = true
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add Reading List")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ----------------------------------------------------------------
            // Audio Sources section
            // ----------------------------------------------------------------
            item(key = "sources_title") {
                Text(
                    "Audio Sources",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
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
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
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
                    swm         = swm,
                    onMakeActive = { sourceViewModel.switchSource(swm.source) },
                    onReview     = { onReviewMappings(swm.source.sourceId) },
                    onDelete     = { sourceViewModel.deleteSource(swm.source) }
                )
            }

            item(key = "browse_button") {
                OutlinedButton(
                    onClick = { folderPickerLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = detectionState !is DetectionState.Running
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null,
                         modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Browse for Audio Bible Folder")
                }
                Spacer(Modifier.height(16.dp))
            }

            // ----------------------------------------------------------------
            // Reading Lists section
            // ----------------------------------------------------------------
            item(key = "lists_title") {
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Reading Lists",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
            }

            if (lists.isNotEmpty() && missingBooks.isNotEmpty()) {
                item(key = "missing_books_warning") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = "Warning",
                                 tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text("Missing Books!", fontWeight = FontWeight.Bold,
                                     color = MaterialTheme.colorScheme.onErrorContainer)
                                Text(
                                    "Missing ${missingBooks.size}: ${missingBooks.joinToString(", ")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }

            items(lists, key = { "list_${it.readingList.listId}" }) { listData ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(listData.readingList.listName,
                                 style = MaterialTheme.typography.titleMedium,
                                 fontWeight = FontWeight.Bold)
                            Text(
                                listData.books.sortedBy { it.sortOrder }.joinToString(", ") { it.bookName },
                                style = MaterialTheme.typography.bodyMedium,
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
                                showListDialog = true
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit")
                            }
                            IconButton(onClick = { viewModel.deleteList(listData.readingList) }) {
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
                                            viewModel.createList(listNameInput, selectedBooks)
                                        } else {
                                            viewModel.updateList(editingList!!.copy(listName = listNameInput), selectedBooks)
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
                        Text("Selected Books (Tap to remove):", fontWeight = FontWeight.Bold)
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(selectedBooks, key = { "selected_$it" }) { book ->
                                InputChip(
                                    selected = true,
                                    onClick = { selectedBooks = selectedBooks.filter { it != book } },
                                    label = { Text(book) },
                                    trailingIcon = {
                                        Icon(Icons.Default.Close, contentDescription = "Remove",
                                             modifier = Modifier.size(16.dp))
                                    }
                                )
                            }
                            if (selectedBooks.isEmpty()) {
                                item {
                                    Text("No books selected",
                                         style = MaterialTheme.typography.bodyMedium,
                                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        HorizontalDivider()
                        Text("Available Books (Tap to add):", fontWeight = FontWeight.Bold)
                        val available = allBooksInBible.filter { it !in selectedBooks }
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(available, key = { "available_$it" }) { book ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { selectedBooks = selectedBooks + book },
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(book, modifier = Modifier.padding(16.dp))
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
                    tint = if (allMapped) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(swm.source.displayName,
                         fontWeight = FontWeight.Bold,
                         style = MaterialTheme.typography.titleMedium)
                    Text("$mapped / $total books linked",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
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
