package com.Bible3650.www.data.text

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BibleTextDownloaderTest {

    @Test
    fun `BULK_FILE fetches, parses, and returns cleaned chapters`() = runBlocking {
        val fake = object : HttpFetcher {
            override suspend fun get(url: String, headers: Map<String, String>): String =
                """{"Genesis":{"1":{"1":"In the beginning.","2":"And the earth."}}}"""
        }
        val entry = CatalogEntry(
            id = "t", name = "Test", abbrev = "T",
            adapter = "BULK_FILE", format = "JSON", url = "https://example.test/bible.json"
        )
        val chapters = BibleTextDownloader.fetchChapters(entry, fake)
        val gen1 = chapters.firstOrNull { it.book == "Genesis" && it.chapter == 1 }
        assertEquals("In the beginning. And the earth.", gen1?.text)
    }

    @Test
    fun `unknown adapter yields nothing`() = runBlocking {
        val fake = object : HttpFetcher {
            override suspend fun get(url: String, headers: Map<String, String>): String = "{}"
        }
        val entry = CatalogEntry(id = "x", name = "X", abbrev = "X", adapter = "NOPE", url = "u")
        assertEquals(emptyList<ParsedChapter>(), BibleTextDownloader.fetchChapters(entry, fake))
    }
}
