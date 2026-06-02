package com.Bible3650.www.ui

import com.Bible3650.www.data.local.AudioSourceEntity
import com.Bible3650.www.data.local.BibleTranslationEntity
import com.Bible3650.www.data.local.SourceWithMappings
import com.Bible3650.www.data.text.CatalogEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadedCatalogEntryIdsTest {
    private val entry = CatalogEntry(
        id = "kjv",
        name = "King James Version",
        abbrev = "KJV",
        adapter = "BULK_FILE",
        url = "https://example.test/kjv.json"
    )

    private val translation = BibleTranslationEntity(
        translationId = 7,
        name = entry.name,
        abbrev = entry.abbrev,
        origin = "DOWNLOAD"
    )

    @Test
    fun `catalog row is downloaded when translation has a selectable source`() {
        val source = SourceWithMappings(
            source = AudioSourceEntity(
                sourceId = 2,
                displayName = entry.name,
                rootTreeUri = "text://7",
                sourceType = "TEXT",
                translationId = translation.translationId
            ),
            mappings = emptyList()
        )

        assertEquals(setOf(entry.id), downloadedCatalogEntryIds(listOf(translation), listOf(source), listOf(entry)))
    }

    @Test
    fun `orphaned translation does not leave catalog row stuck as downloaded`() {
        assertEquals(emptySet<String>(), downloadedCatalogEntryIds(listOf(translation), emptyList(), listOf(entry)))
    }
}
