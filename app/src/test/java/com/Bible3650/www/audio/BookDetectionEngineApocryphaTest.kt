package com.Bible3650.www.audio

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the (private) scoring path with apocrypha folder names. We test [computeScore]
 * directly via reflection because [BookDetectionEngine.detect] needs a real [Uri], which is
 * unavailable in plain JVM unit tests.
 */
class BookDetectionEngineApocryphaTest {

    private val engine = BookDetectionEngine(
        fileSystem = object : FileSystemProvider {
            override fun getTreeDocumentId(treeUri: Uri): String = ""
            override fun getDocumentDisplayName(treeUri: Uri, docId: String): String = ""
            override fun listChildren(treeUri: Uri, parentDocId: String): List<FileNode> = emptyList()
        }
    )

    private fun score(folder: String, book: String, fileCount: Int, allSame: Boolean = false): Float {
        val m = BookDetectionEngine::class.java.getDeclaredMethod(
            "computeScore",
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        )
        m.isAccessible = true
        return m.invoke(engine, folder, book, fileCount, allSame) as Float
    }

    private val threshold = BookDetectionEngine.MIN_CONFIDENCE_THRESHOLD

    @Test
    fun `apocrypha folders score above the detection threshold`() {
        assertTrue(score("sirach", "Sirach", 51) > threshold)
        assertTrue(score("ecclesiasticus", "Sirach", 51) > threshold)
        assertTrue(score("1 maccabees", "1 Maccabees", 16) > threshold)
        assertTrue(score("tobit", "Tobit", 14) > threshold)
        assertTrue(score("judith", "Judith", 16) > threshold)
    }

    @Test
    fun `apocrypha book does not match an unrelated canonical folder`() {
        assertEquals(0f, score("genesis", "Sirach", 50), 0.0001f)
        assertEquals(0f, score("matthew", "1 Maccabees", 28), 0.0001f)
    }

    @Test
    fun `canonical detection is unaffected`() {
        assertTrue(score("genesis", "Genesis", 50) > threshold)
    }
}
