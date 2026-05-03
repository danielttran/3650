package com.Bible3650.www.audio

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.Bible3650.www.data.BibleRegistry
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

data class DetectionResult(
    val bookName: String,
    val folderDocId: String?,       // null = not found; relative to the source's rootTreeUri
    val confidence: Float,          // 0..1
    val fileCount: Int,
    val matchedFolderName: String = ""
)

@Singleton
class BookDetectionEngine @Inject constructor(
    private val contentResolver: ContentResolver
) {
    private data class FolderInfo(val displayName: String, val docId: String, val mp3Count: Int)

    // ---------------------------------------------------------------------------
    // Alias table — all entries lowercase, no punctuation.
    // Longer aliases are tried first so "1 samuel" wins before "sam".
    // Numbered-book aliases always include the digit to prevent "01 john" from
    // matching the alias "1 john" of 1 John via a mid-token digit hit (the
    // word-boundary check in aliasMatches() handles this separately).
    // ---------------------------------------------------------------------------
    private val aliases: Map<String, List<String>> = mapOf(
        "Genesis"          to listOf("genesis", "gen"),
        "Exodus"           to listOf("exodus", "exod", "exo"),
        "Leviticus"        to listOf("leviticus", "lev"),
        "Numbers"          to listOf("numbers", "num"),
        "Deuteronomy"      to listOf("deuteronomy", "deut", "deu"),
        "Joshua"           to listOf("joshua", "josh", "jos"),
        "Judges"           to listOf("judges", "judg", "jdg"),
        "Ruth"             to listOf("ruth"),
        "1 Samuel"         to listOf("1 samuel", "1samuel", "1 sam", "1sam"),
        "2 Samuel"         to listOf("2 samuel", "2samuel", "2 sam", "2sam"),
        "1 Kings"          to listOf("1 kings", "1kings", "1 ki", "1ki", "1kin"),
        "2 Kings"          to listOf("2 kings", "2kings", "2 ki", "2ki", "2kin"),
        "1 Chronicles"     to listOf("1 chronicles", "1chronicles", "1 chron", "1chron", "1chr"),
        "2 Chronicles"     to listOf("2 chronicles", "2chronicles", "2 chron", "2chron", "2chr"),
        "Ezra"             to listOf("ezra"),
        "Nehemiah"         to listOf("nehemiah", "neh"),
        "Esther"           to listOf("esther", "esth"),
        "Job"              to listOf("job"),
        "Psalm"            to listOf("psalms", "psalm", "psa"),
        "Proverbs"         to listOf("proverbs", "prov"),
        "Ecclesiastes"     to listOf("ecclesiastes", "eccles", "eccl"),
        "Song of Songs"    to listOf("song of songs", "song of solomon", "song of sol",
                                     "canticles", "songs"),
        "Isaiah"           to listOf("isaiah", "isa"),
        "Jeremiah"         to listOf("jeremiah", "jer"),
        "Lamentations"     to listOf("lamentations", "lam"),
        "Ezekiel"          to listOf("ezekiel", "ezek"),
        "Daniel"           to listOf("daniel", "dan"),
        "Hosea"            to listOf("hosea", "hos"),
        "Joel"             to listOf("joel"),
        "Amos"             to listOf("amos"),
        "Obadiah"          to listOf("obadiah", "obad"),
        "Jonah"            to listOf("jonah"),
        "Micah"            to listOf("micah", "mic"),
        "Nahum"            to listOf("nahum"),
        "Habakkuk"         to listOf("habakkuk", "hab"),
        "Zephaniah"        to listOf("zephaniah", "zeph"),
        "Haggai"           to listOf("haggai"),
        "Zechariah"        to listOf("zechariah", "zech"),
        "Malachi"          to listOf("malachi", "mal"),
        "Matthew"          to listOf("matthew", "matt"),
        "Mark"             to listOf("mark"),
        "Luke"             to listOf("luke"),
        "John"             to listOf("john", "jhn"),         // standalone; numbered below
        "Acts"             to listOf("acts"),
        "Romans"           to listOf("romans", "rom"),
        "1 Corinthians"    to listOf("1 corinthians", "1corinthians", "1 cor", "1cor"),
        "2 Corinthians"    to listOf("2 corinthians", "2corinthians", "2 cor", "2cor"),
        "Galatians"        to listOf("galatians", "gal"),
        "Ephesians"        to listOf("ephesians", "eph"),
        "Philippians"      to listOf("philippians", "phil", "php"),
        "Colossians"       to listOf("colossians", "col"),
        "1 Thessalonians"  to listOf("1 thessalonians", "1thessalonians", "1 thess", "1thess", "1thes"),
        "2 Thessalonians"  to listOf("2 thessalonians", "2thessalonians", "2 thess", "2thess", "2thes"),
        "1 Timothy"        to listOf("1 timothy", "1timothy", "1 tim", "1tim"),
        "2 Timothy"        to listOf("2 timothy", "2timothy", "2 tim", "2tim"),
        "Titus"            to listOf("titus"),
        "Philemon"         to listOf("philemon", "phlm"),
        "Hebrews"          to listOf("hebrews", "heb"),
        "James"            to listOf("james", "jas"),
        "1 Peter"          to listOf("1 peter", "1peter", "1 pet", "1pet"),
        "2 Peter"          to listOf("2 peter", "2peter", "2 pet", "2pet"),
        "1 John"           to listOf("1 john", "1john", "1 joh", "1joh", "1jn"),
        "2 John"           to listOf("2 john", "2john", "2 joh", "2joh", "2jn"),
        "3 John"           to listOf("3 john", "3john", "3 joh", "3joh", "3jn"),
        "Jude"             to listOf("jude"),
        "Revelation"       to listOf("revelation", "revelations", "rev")
    )

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    fun detect(rootTreeUri: Uri): List<DetectionResult> {
        val rootDocId = DocumentsContract.getTreeDocumentId(rootTreeUri)
        val leafFolders = mutableListOf<FolderInfo>()
        collectLeafFolders(rootTreeUri, rootDocId, "root", leafFolders, depth = 0)

        if (leafFolders.isEmpty()) {
            return BibleRegistry.getAllBooks().map { DetectionResult(it, null, 0f, 0) }
        }

        // Score every (folder, book) combination
        data class Candidate(val folder: FolderInfo, val bookName: String, val score: Float)
        val candidates = mutableListOf<Candidate>()
        for (folder in leafFolders) {
            for (bookName in BibleRegistry.getAllBooks()) {
                val score = computeScore(folder.displayName, bookName, folder.mp3Count)
                if (score > 0.3f) candidates.add(Candidate(folder, bookName, score))
            }
        }

        // Greedy one-to-one assignment: highest score first, each folder and each book used once
        val usedFolders = mutableSetOf<String>()
        val assignments = mutableMapOf<String, Candidate>()
        for (c in candidates.sortedByDescending { it.score }) {
            if (c.folder.docId !in usedFolders && c.bookName !in assignments) {
                assignments[c.bookName] = c
                usedFolders.add(c.folder.docId)
            }
        }

        return BibleRegistry.getAllBooks().map { bookName ->
            val a = assignments[bookName]
            if (a != null) {
                DetectionResult(bookName, a.folder.docId, a.score, a.folder.mp3Count, a.folder.displayName)
            } else {
                DetectionResult(bookName, null, 0f, 0)
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Tree traversal — collects every directory that directly contains audio files
    // ---------------------------------------------------------------------------

    private fun collectLeafFolders(
        treeUri: Uri,
        dirDocId: String,
        dirName: String,
        result: MutableList<FolderInfo>,
        depth: Int
    ) {
        if (depth > 3) return

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
        val subDirs = mutableListOf<Pair<String, String>>() // (displayName, docId)
        var audioCount = 0

        try {
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val idIdx   = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val docId = cursor.getString(idIdx)  ?: continue
                    val mime  = cursor.getString(mimeIdx) ?: ""

                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR ->
                            subDirs.add(name to docId)
                        isAudioFile(mime, name) -> audioCount++
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("BookDetection", "Error reading $dirDocId: ${e.message}")
            return
        }

        if (audioCount > 0) result.add(FolderInfo(dirName, dirDocId, audioCount))

        for ((subName, subDocId) in subDirs) {
            collectLeafFolders(treeUri, subDocId, subName, result, depth + 1)
        }
    }

    private fun isAudioFile(mime: String, name: String): Boolean =
        mime.startsWith("audio/") ||
        name.endsWith(".mp3",  ignoreCase = true) ||
        name.endsWith(".m4a",  ignoreCase = true) ||
        name.endsWith(".ogg",  ignoreCase = true) ||
        name.endsWith(".flac", ignoreCase = true)

    // ---------------------------------------------------------------------------
    // Scoring
    // ---------------------------------------------------------------------------

    private fun computeScore(folderName: String, bookName: String, fileCount: Int): Float {
        val normalized = folderName.lowercase()
            .replace(Regex("[_\\-./\\\\]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val bookAliases = aliases[bookName] ?: return 0f
        val folderNoSpaces = normalized.replace(" ", "")

        var bestNameScore = 0f
        // Try longest alias first so "1 corinthians" beats "cor"
        for (alias in bookAliases.sortedByDescending { it.length }) {
            if (aliasMatches(normalized, alias)) {
                val aliasLen = alias.replace(" ", "").length.toFloat()
                val folderLen = folderNoSpaces.length.coerceAtLeast(1).toFloat()
                bestNameScore = maxOf(bestNameScore, 0.4f + (aliasLen / folderLen) * 0.3f)
                break
            }
        }
        if (bestNameScore == 0f) return 0f

        val expected = BibleRegistry.getChapterCount(bookName)
        val chapterBonus = when {
            fileCount == expected              -> 0.30f
            abs(fileCount - expected) <= 2    -> 0.15f
            fileCount in 1..(expected + 5)    -> 0.05f
            else                              -> 0f
        }

        return (bestNameScore + chapterBonus).coerceIn(0f, 1f)
    }

    // Match alias against a normalised folder name with word-boundary awareness.
    // Prevents "01 john" from matching alias "1 john" (the '0' before '1' is a digit).
    private fun aliasMatches(normalized: String, alias: String): Boolean {
        val idx = normalized.indexOf(alias)
        if (idx == -1) return false
        // Check left boundary: must not be preceded by a letter or digit
        if (idx > 0 && normalized[idx - 1].isLetterOrDigit()) return false
        // Check right boundary: must not be followed by a letter or digit
        val end = idx + alias.length
        if (end < normalized.length && normalized[end].isLetterOrDigit()) return false
        return true
    }
}
