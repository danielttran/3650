package com.Bible3650.www.data

import com.Bible3650.www.data.local.AudioSourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestoredSourceRemappingTest {
    @Test
    fun `audio source restores without a translation mapping`() {
        val source = AudioSourceEntity(
            sourceId = 4,
            displayName = "Audio",
            rootTreeUri = "content://tree/audio",
            sourceType = "AUDIO"
        )

        assertEquals(source.copy(sourceId = 0), remapRestoredSource(source, emptyMap()))
    }

    @Test
    fun `text source restores with remapped translation uri`() {
        val source = AudioSourceEntity(
            sourceId = 5,
            displayName = "Text",
            rootTreeUri = "text://7",
            sourceType = "TEXT",
            translationId = 7
        )

        assertEquals(
            source.copy(sourceId = 0, rootTreeUri = "text://11", translationId = 11),
            remapRestoredSource(source, mapOf(7L to 11L))
        )
    }

    @Test
    fun `legacy text source without restored translation is skipped`() {
        val source = AudioSourceEntity(
            sourceId = 5,
            displayName = "Text",
            rootTreeUri = "text://7",
            sourceType = "TEXT",
            translationId = 7
        )

        assertNull(remapRestoredSource(source, emptyMap()))
    }
}
