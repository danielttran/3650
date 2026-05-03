package com.Bible3650.www.audio

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import javax.inject.Inject
import javax.inject.Singleton

data class FileNode(
    val name: String,
    val docId: String,
    val isDirectory: Boolean,
    val mimeType: String
)

interface FileSystemProvider {
    fun getTreeDocumentId(treeUri: Uri): String
    fun getDocumentDisplayName(treeUri: Uri, docId: String): String
    fun listChildren(treeUri: Uri, parentDocId: String): List<FileNode>
}

@Singleton
class AndroidFileSystemProvider @Inject constructor(
    private val contentResolver: ContentResolver
) : FileSystemProvider {

    override fun getTreeDocumentId(treeUri: Uri): String {
        return DocumentsContract.getTreeDocumentId(treeUri)
    }

    override fun getDocumentDisplayName(treeUri: Uri, docId: String): String {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else ""
        } ?: ""
    }

    override fun listChildren(treeUri: Uri, parentDocId: String): List<FileNode> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val result = mutableListOf<FileNode>()
        try {
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val docId = cursor.getString(idIdx) ?: continue
                    val mime = cursor.getString(mimeIdx) ?: ""
                    result.add(
                        FileNode(
                            name = name,
                            docId = docId,
                            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                            mimeType = mime
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("FileSystemProvider", "Error listing children for $parentDocId", e)
        }
        return result
    }
}
