package com.Bible3650.www.data.text

import android.content.Context
import com.Bible3650.www.data.BibleRegistry
import com.Bible3650.www.data.local.BibleTextDao
import com.Bible3650.www.data.local.BibleTextEntity
import com.Bible3650.www.data.local.BibleTranslationEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A downloadable translation described by the bundled catalog. */
@Serializable
data class CatalogEntry(
    val id: String,
    val name: String,
    val abbrev: String,
    val language: String = "en",
    val hasApocrypha: Boolean = false,
    val adapter: String,                 // BULK_FILE | PASSAGE_API
    val format: String = "JSON",         // BULK_FILE: JSON | USFM | OSIS | ZEFANIA
    val url: String,                     // bulk: file URL; passage: template with {book}/{chapter}
    val needsKey: Boolean = false,
    val license: String? = null,
    val attribution: String? = null,
    val bookNameOverrides: Map<String, String> = emptyMap() // app name -> request name
)

@Singleton
class BibleTextDownloader @Inject constructor(
    private val http: HttpFetcher,
    private val textDao: BibleTextDao,
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun loadCatalog(): List<CatalogEntry> {
        val raw = context.assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() }
        return json.decodeFromString(ListSerializer(CatalogEntry.serializer()), raw)
    }

    /** Downloads + stores a translation; returns the new translationId. [onProgress] = (done, total). */
    suspend fun download(entry: CatalogEntry, onProgress: (Int, Int) -> Unit = { _, _ -> }): Long {
        val chapters = fetchChapters(entry, http, onProgress)
        if (chapters.isEmpty()) throw IOException("No chapters parsed for ${entry.id}")
        val translationId = textDao.insertTranslation(
            BibleTranslationEntity(
                name = entry.name,
                abbrev = entry.abbrev,
                language = entry.language,
                origin = "DOWNLOAD",
                hasApocrypha = entry.hasApocrypha,
                license = entry.license,
                attribution = entry.attribution
            )
        )
        chapters.chunked(CHAPTER_BATCH).forEach { batch ->
            textDao.upsertChapters(batch.map { BibleTextEntity(translationId, it.book, it.chapter, it.text) })
        }
        return translationId
    }

    companion object {
        const val CATALOG_ASSET = "text_catalog.json"
        private const val CHAPTER_BATCH = 200
        private const val THROTTLE_MS = 60L

        /**
         * Pure-ish fetch+parse (no DB) so it is unit-testable with a fake [HttpFetcher].
         * BULK_FILE downloads one document; PASSAGE_API iterates the canon chapter by chapter.
         */
        suspend fun fetchChapters(
            entry: CatalogEntry,
            http: HttpFetcher,
            onProgress: (Int, Int) -> Unit = { _, _ -> }
        ): List<ParsedChapter> = when (entry.adapter) {
            "BULK_FILE" -> {
                onProgress(0, 1)
                val content = http.get(entry.url)
                val fmt = runCatching { TextFormat.valueOf(entry.format.uppercase()) }.getOrDefault(TextFormat.UNKNOWN)
                // Parsing a whole-Bible document (helloao bulk files are several MB) is CPU-heavy;
                // keep it off the caller's thread so a download can never block the UI / cause an ANR.
                withContext(Dispatchers.IO) { BibleTextImporter.parse(content, fmt) }.also { onProgress(1, 1) }
            }
            "PASSAGE_API" -> {
                val books = BibleRegistry.getAllBooks()
                val total = books.sumOf { BibleRegistry.getChapterCount(it) }
                var done = 0
                val out = ArrayList<ParsedChapter>(total)
                for (book in books) {
                    val requestBook = (entry.bookNameOverrides[book] ?: book).replace(" ", "+")
                    for (ch in 1..BibleRegistry.getChapterCount(book)) {
                        val url = entry.url.replace("{book}", requestBook).replace("{chapter}", ch.toString())
                        val resp = runCatching { http.get(url) }.getOrNull()
                        if (resp != null) out += BibleTextImporter.parse(resp, TextFormat.JSON)
                        done++
                        onProgress(done, total)
                        delay(THROTTLE_MS)
                    }
                }
                out
            }
            else -> emptyList()
        }
    }
}
