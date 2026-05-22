package com.Bible3650.www.data

import com.Bible3650.www.data.local.AudioSourceEntity
import com.Bible3650.www.data.local.BookMappingEntity
import com.Bible3650.www.data.local.ListBookEntity
import com.Bible3650.www.data.local.ReadingListEntity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the kotlinx.serialization backup format: round-trip integrity, compatibility
 * with the legacy (Gson-produced) JSON shape, default handling, and forward-compatibility.
 */
class ProgressBackupSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private fun sampleBackup() = ProgressBackup(
        readingLists = listOf(
            ReadingListBackup(
                entity = ReadingListEntity(
                    listId = 1, listName = "List 1:", currentDayIndex = 42,
                    createdAtTimestamp = 1000L, listOrder = 0, statResetOffset = 5,
                    manualOffset = -3, activeBook = "Psalm", activeChapter = 12, listColor = -1234
                ),
                books = listOf(
                    ListBookEntity(id = 1, listId = 1, bookName = "Psalm", sortOrder = 0),
                    ListBookEntity(id = 2, listId = 1, bookName = "Proverbs", sortOrder = 1)
                )
            )
        ),
        audioSources = listOf(
            AudioSourceBackup(
                entity = AudioSourceEntity(sourceId = 7, displayName = "KJV", rootTreeUri = "content://x", isActive = true, createdAt = 2000L),
                mappings = listOf(
                    BookMappingEntity(sourceId = 7, bookName = "Psalm", folderDocId = "doc:9", confidence = 0.95f, fileCount = 150)
                )
            )
        )
    )

    @Test
    fun `round-trip preserves all data`() {
        val backup = sampleBackup()
        val decoded = json.decodeFromString<ProgressBackup>(json.encodeToString(backup))
        assertEquals(backup, decoded)
        assertEquals(1, decoded.version)
    }

    @Test
    fun `serialized json uses property-name keys for legacy compatibility`() {
        val s = json.encodeToString(sampleBackup())
        assertTrue(s.contains("\"currentDayIndex\""))
        assertTrue(s.contains("\"activeChapter\""))
        assertTrue(s.contains("\"folderDocId\""))
        assertTrue(s.contains("\"rootTreeUri\""))
    }

    @Test
    fun `legacy backup with missing optional fields decodes with defaults`() {
        val legacy = """
            {"version":1,"readingLists":[{"entity":{"listId":3,"listName":"Old","currentDayIndex":9},"books":[{"listId":3,"bookName":"John","sortOrder":0}]}]}
        """.trimIndent()
        val decoded = json.decodeFromString<ProgressBackup>(legacy)
        val rl = decoded.readingLists?.firstOrNull()?.entity
        assertNotNull(rl)
        assertEquals("Old", rl!!.listName)
        assertEquals(9, rl.currentDayIndex)
        assertEquals(0, rl.manualOffset)
        assertEquals(null, rl.activeBook)
        assertEquals(0, rl.listColor)
    }

    @Test
    fun `unknown future keys are ignored`() {
        val futur = """{"version":1,"somethingNew":true,"readingLists":[]}"""
        val decoded = json.decodeFromString<ProgressBackup>(futur)
        assertNotNull(decoded.readingLists)
        assertTrue(decoded.readingLists!!.isEmpty())
    }
}
