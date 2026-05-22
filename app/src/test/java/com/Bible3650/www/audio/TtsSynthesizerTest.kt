package com.Bible3650.www.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSynthesizerTest {

    @Test
    fun `short text is a single chunk`() {
        assertEquals(listOf("In the beginning."), TtsSynthesizer.chunkText("In the beginning.", 100))
    }

    @Test
    fun `empty text yields no chunks`() {
        assertEquals(emptyList<String>(), TtsSynthesizer.chunkText("   ", 100))
    }

    @Test
    fun `long text splits under the limit and preserves content`() {
        val sentence = "This is a verse of scripture. "
        val text = sentence.repeat(40).trim() // ~1180 chars
        val max = 100
        val chunks = TtsSynthesizer.chunkText(text, max)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= max })
        // Rejoining the chunks reproduces the original words.
        assertEquals(
            text.split(Regex("\\s+")),
            chunks.joinToString(" ").split(Regex("\\s+"))
        )
    }

    @Test
    fun `splits even without punctuation or spaces`() {
        val text = "a".repeat(250)
        val chunks = TtsSynthesizer.chunkText(text, 100)
        assertTrue(chunks.all { it.length <= 100 })
        assertEquals(250, chunks.sumOf { it.length })
    }
}
