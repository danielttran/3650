package com.Bible3650.www.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.Bible3650.www.data.BibleRegistry
import com.Bible3650.www.data.local.AudioSourceDao
import com.Bible3650.www.data.local.AudioSourceEntity
import com.Bible3650.www.data.local.BookMappingEntity
import com.Bible3650.www.data.local.SourceWithMappings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

data class MappingRowUi(
    val bookName: String,
    val folderDocId: String?,
    val confidence: Float,
    val fileCount: Int,
    val overrideTreeUri: String?
) {
    val isMapped: Boolean get() = folderDocId != null
    val isHighConfidence: Boolean get() = confidence >= 0.7f
}

data class ReviewUiState(
    val source: AudioSourceEntity? = null,
    val rows: List<MappingRowUi> = emptyList()
) {
    val mappedCount: Int get() = rows.count { it.isMapped }
    val allMapped: Boolean get() = mappedCount == rows.size
}

@HiltViewModel
class ReviewMappingsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val dao: AudioSourceDao
) : ViewModel() {

    private val sourceId: Long = checkNotNull(savedState["sourceId"])

    val uiState: StateFlow<ReviewUiState> = dao.observeAllSources()
        .map { allSources ->
            val swm: SourceWithMappings? = allSources.firstOrNull { it.source.sourceId == sourceId }
            if (swm == null) return@map ReviewUiState()

            val mappingsByBook = swm.mappings.associateBy { it.bookName }
            val rows = BibleRegistry.getAllBooks().map { bookName ->
                val m = mappingsByBook[bookName]
                MappingRowUi(
                    bookName      = bookName,
                    folderDocId   = m?.folderDocId,
                    confidence    = m?.confidence ?: 0f,
                    fileCount     = m?.fileCount  ?: 0,
                    overrideTreeUri = m?.overrideTreeUri
                )
            }
            ReviewUiState(source = swm.source, rows = rows)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReviewUiState())

    // Called after the user manually picks a folder for a specific book.
    // The picked treeUri is both the permission root and the folder itself.
    fun onManualBookFolderPicked(bookName: String, treeUri: Uri, fileCount: Int) {
        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            android.util.Log.e("ReviewMappings", "Failed to get docId for $treeUri", e)
            return
        }

        viewModelScope.launch {
            dao.upsertMapping(
                BookMappingEntity(
                    sourceId        = sourceId,
                    bookName        = bookName,
                    folderDocId     = docId,
                    confidence      = 1.0f,
                    fileCount       = fileCount,
                    overrideTreeUri = treeUri.toString()
                )
            )
        }
    }

    fun clearMapping(bookName: String) {
        viewModelScope.launch { dao.deleteMapping(sourceId, bookName) }
    }
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewMappingsScreen(
    onBack: () -> Unit,
    viewModel: ReviewMappingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Holds the book name currently awaiting a folder pick
    var pendingBook by remember { mutableStateOf<String?>(null) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null && pendingBook != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            // Count audio files in the picked folder to confirm it's the right one
            val fileCount = countAudioFilesInTree(context.contentResolver, uri)
            viewModel.onManualBookFolderPicked(pendingBook!!, uri, fileCount)
        }
        pendingBook = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.source?.displayName ?: "Review Mappings",
                             style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("${state.mappedCount} / ${state.rows.size} books mapped",
                             style = MaterialTheme.typography.bodySmall,
                             color = if (state.allMapped) MaterialTheme.colorScheme.primary
                                     else MaterialTheme.colorScheme.error)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!state.allMapped) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null,
                                 tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${state.rows.size - state.mappedCount} book(s) not yet linked. " +
                                "Tap the folder icon next to a book to manually select its folder.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            items(state.rows, key = { it.bookName }) { row ->
                MappingRow(
                    row = row,
                    onPickFolder = {
                        pendingBook = row.bookName
                        folderPickerLauncher.launch(null)
                    }
                )
            }
        }
    }
}

@Composable
private fun MappingRow(row: MappingRowUi, onPickFolder: () -> Unit) {
    val (iconTint, containerColor) = when {
        !row.isMapped       -> MaterialTheme.colorScheme.error to
                               MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        !row.isHighConfidence -> Color(0xFFF59E0B) to           // amber warning
                               Color(0xFFFEF3C7).copy(alpha = 0.4f)
        else                -> MaterialTheme.colorScheme.primary to
                               MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (row.isMapped) Icons.Filled.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(row.bookName, fontWeight = FontWeight.SemiBold,
                     style = MaterialTheme.typography.bodyMedium)
                if (row.isMapped) {
                    Text(
                        "${row.fileCount} files · confidence ${(row.confidence * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("Not linked — tap to pick folder",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.error)
                }
            }
            IconButton(onClick = onPickFolder) {
                Icon(Icons.Default.Edit, contentDescription = "Pick folder",
                     tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Utility — count audio files inside a tree URI (for confirming a manual pick)
// ---------------------------------------------------------------------------

private fun countAudioFilesInTree(resolver: android.content.ContentResolver, treeUri: Uri): Int {
    val docId = DocumentsContract.getTreeDocumentId(treeUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
    var count = 0
    try {
        resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: ""
                val mime = cursor.getString(mimeIdx) ?: ""
                if (mime.startsWith("audio/") ||
                    name.endsWith(".mp3", true) || name.endsWith(".m4a", true) ||
                    name.endsWith(".ogg", true) || name.endsWith(".flac", true)) count++
            }
        }
    } catch (_: Exception) {}
    return count
}
