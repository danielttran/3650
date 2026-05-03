package com.Bible3650.www.ui

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.Bible3650.www.data.BibleRegistry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageListsScreen(
    viewModel: ManageListsViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lists by viewModel.listsFlow.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }
    var editingList by remember { mutableStateOf<com.Bible3650.www.data.local.ReadingListEntity?>(null) }
    
    // State for the visual builder
    var listNameInput by remember { mutableStateOf("") }
    var selectedBooks by remember { mutableStateOf<List<String>>(emptyList()) }

    // Validation calculation
    val allBooksInBible = BibleRegistry.getAllBooks()
    val allBooksInLists = lists.flatMap { it.books }.map { it.bookName }.toSet()
    val missingBooks = allBooksInBible.filter { !allBooksInLists.contains(it) }

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
                val nextOrder = lists.size
                editingList = com.Bible3650.www.data.local.ReadingListEntity(listName = "", listOrder = nextOrder)
                listNameInput = ""
                selectedBooks = emptyList()
                showDialog = true
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add List")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Validation Warning
            if (missingBooks.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = "Warning", tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("Missing Books!", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(
                                "You are missing ${missingBooks.size} books: ${missingBooks.joinToString(", ")}. Your reading plan must cover the entire Bible.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(lists, key = { it.readingList.listId }) { listData ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = listData.readingList.listName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = listData.books.sortedBy { it.sortOrder }.joinToString(", ") { it.bookName },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                            Column {
                                IconButton(onClick = {
                                    val index = lists.indexOf(listData)
                                    if (index > 0) {
                                        val mutableLists = lists.map { it.readingList }.toMutableList()
                                        val temp = mutableLists[index]
                                        mutableLists[index] = mutableLists[index - 1]
                                        mutableLists[index - 1] = temp
                                        viewModel.reorderLists(mutableLists)
                                    }
                                }) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up")
                                }
                                IconButton(onClick = {
                                    val index = lists.indexOf(listData)
                                    if (index < lists.size - 1) {
                                        val mutableLists = lists.map { it.readingList }.toMutableList()
                                        val temp = mutableLists[index]
                                        mutableLists[index] = mutableLists[index + 1]
                                        mutableLists[index + 1] = temp
                                        viewModel.reorderLists(mutableLists)
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
                                    showDialog = true
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                                }
                                IconButton(onClick = { viewModel.deleteList(listData.readingList) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        Dialog(
            onDismissRequest = { showDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TopAppBar(
                        title = { Text(if (editingList == null) "Create New List" else "Edit List") },
                        navigationIcon = {
                            IconButton(onClick = { showDialog = false }) {
                                Icon(Icons.Default.Close, contentDescription = "Close")
                            }
                        },
                        actions = {
                            TextButton(
                                onClick = {
                                    if (listNameInput.isNotBlank() && selectedBooks.isNotEmpty()) {
                                        if (editingList == null) {
                                            viewModel.createList(listNameInput, selectedBooks)
                                        } else {
                                            viewModel.updateList(editingList!!.copy(listName = listNameInput), selectedBooks)
                                        }
                                        showDialog = false
                                    }
                                },
                                enabled = listNameInput.isNotBlank() && selectedBooks.isNotEmpty()
                            ) {
                                Text("Save")
                            }
                        }
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
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
                            items(selectedBooks) { book ->
                                InputChip(
                                    selected = true,
                                    onClick = {
                                        selectedBooks = selectedBooks.filter { it != book }
                                    },
                                    label = { Text(book) },
                                    trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) }
                                )
                            }
                            if (selectedBooks.isEmpty()) {
                                item {
                                    Text("No books selected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Divider()

                        Text("Available Books (Tap to add):", fontWeight = FontWeight.Bold)

                        val availableBooks = allBooksInBible.filter { !selectedBooks.contains(it) }
                        
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(availableBooks) { book ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { selectedBooks = selectedBooks + book },
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        text = book,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
