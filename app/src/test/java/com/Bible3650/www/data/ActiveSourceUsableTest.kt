package com.Bible3650.www.data

import com.Bible3650.www.data.local.AudioSourceEntity
import com.Bible3650.www.data.local.BookMappingEntity
import com.Bible3650.www.data.local.SourceWithMappings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveSourceUsableTest {

    private fun source(
        id: Long,
        type: String,
        active: Boolean,
        mappings: Int = 0
    ) = SourceWithMappings(
        source = AudioSourceEntity(
            sourceId = id,
            displayName = "s$id",
            rootTreeUri = if (type == "TEXT") "text://$id" else "content://tree/$id",
            isActive = active,
            sourceType = type,
            translationId = if (type == "TEXT") id else null
        ),
        mappings = (1..mappings).map {
            BookMappingEntity(sourceId = id, bookName = "Book$it", folderDocId = "doc$it")
        }
    )

    @Test
    fun `active text source with no mappings is usable`() {
        // Regression: downloading a text Bible left the home screen empty because the active
        // TEXT source has no book_mappings and the gate was driven off mappings alone.
        val sources = listOf(source(id = 1, type = "TEXT", active = true, mappings = 0))
        assertTrue(activeSourceUsable(sources))
    }

    @Test
    fun `active audio source needs at least one mapping`() {
        assertFalse(activeSourceUsable(listOf(source(1, "AUDIO", active = true, mappings = 0))))
        assertTrue(activeSourceUsable(listOf(source(1, "AUDIO", active = true, mappings = 3))))
    }

    @Test
    fun `no active source is not usable`() {
        assertFalse(activeSourceUsable(emptyList()))
        assertFalse(activeSourceUsable(listOf(source(1, "TEXT", active = false))))
    }

    @Test
    fun `only the active source decides usability`() {
        // An inactive audio source with mappings must not make an active, empty text source
        // "usable" via the wrong row — but the active text source is usable on its own anyway.
        val sources = listOf(
            source(1, "AUDIO", active = false, mappings = 5),
            source(2, "TEXT", active = true, mappings = 0)
        )
        assertTrue(activeSourceUsable(sources))

        // Active audio source with no mappings, plus an inactive text source → not usable.
        val sources2 = listOf(
            source(1, "AUDIO", active = true, mappings = 0),
            source(2, "TEXT", active = false, mappings = 0)
        )
        assertFalse(activeSourceUsable(sources2))
    }
}
