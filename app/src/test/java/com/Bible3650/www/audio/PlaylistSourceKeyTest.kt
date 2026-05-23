package com.Bible3650.www.audio

import com.Bible3650.www.data.local.AudioSourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlaylistSourceKeyTest {

    private fun audio(id: Long) =
        AudioSourceEntity(sourceId = id, displayName = "a", rootTreeUri = "content://t/$id", sourceType = "AUDIO")

    private fun text(id: Long, translationId: Long) =
        AudioSourceEntity(
            sourceId = id, displayName = "t", rootTreeUri = "text://$translationId",
            sourceType = "TEXT", translationId = translationId
        )

    @Test
    fun `audio and text sources produce different keys`() {
        // Regression: a TEXT source must never be mistaken for an AUDIO playlist (which would
        // replay recorded audio instead of synthesizing the text), even though their task
        // uniqueIds are identical.
        assertNotEquals(playlistSourceKey(audio(1)), playlistSourceKey(text(2, 10)))
    }

    @Test
    fun `different translations produce different keys`() {
        assertNotEquals(playlistSourceKey(text(1, 10)), playlistSourceKey(text(1, 11)))
    }

    @Test
    fun `same source produces a stable key`() {
        assertEquals(playlistSourceKey(audio(5)), playlistSourceKey(audio(5)))
        assertEquals(playlistSourceKey(text(5, 9)), playlistSourceKey(text(5, 9)))
    }

    @Test
    fun `null source has its own key`() {
        assertEquals("none", playlistSourceKey(null))
        assertNotEquals(playlistSourceKey(null), playlistSourceKey(audio(0)))
    }
}
