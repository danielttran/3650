package com.Bible3650.www.audio

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test

class BookDetectionEngineLevenshteinTest {

    private val engine = BookDetectionEngine(
        fileSystem = object : FileSystemProvider {
            override fun getTreeDocumentId(treeUri: Uri): String = ""
            override fun getDocumentDisplayName(treeUri: Uri, docId: String): String = ""
            override fun listChildren(treeUri: Uri, parentDocId: String): List<FileNode> = emptyList()
        }
    )

    private fun levenshtein(a: String, b: String): Int {
        val method = BookDetectionEngine::class.java.getDeclaredMethod("levenshtein", String::class.java, String::class.java)
        method.isAccessible = true
        return method.invoke(engine, a, b) as Int
    }

    @Test
    fun `levenshtein handles insertions and deletions correctly`() {
        assertEquals(3, levenshtein("kitten", "sitting"))
        assertEquals(2, levenshtein("book", "back"))
        assertEquals(1, levenshtein("john", "johns"))
    }

    @Test
    fun `levenshtein handles empty strings`() {
        assertEquals(0, levenshtein("", ""))
        assertEquals(4, levenshtein("mark", ""))
        assertEquals(5, levenshtein("", "roman"))
    }
}
