package com.Bible3650.www.data.text

import com.Bible3650.www.data.BookNameNormalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** One parsed, cleaned chapter ready to store. [book] is a canonical [BookNameNormalizer] name. */
data class ParsedChapter(val book: String, val chapter: Int, val text: String)

enum class TextFormat { JSON, USFM, OSIS, ZEFANIA, HELLOAO, UNKNOWN }

/**
 * Parses a whole-Bible (or partial) text document in any supported format into normalized,
 * TTS-ready [ParsedChapter]s. Per-chapter .txt folders are handled by the import flow, which
 * reads each file and calls [parsePlainChapter].
 */
object BibleTextImporter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun sniff(content: String): TextFormat {
        val t = content.trimStart().take(4000)
        return when {
            t.startsWith("{") && (
                t.contains("\"completeTranslationApiLink\"") ||
                    (t.contains("\"books\"") && t.contains("\"content\"") && t.contains("\"verse\""))
                ) -> TextFormat.HELLOAO
            t.startsWith("{") || t.startsWith("[") -> TextFormat.JSON
            t.startsWith("<") && t.contains("osis", ignoreCase = true) -> TextFormat.OSIS
            t.startsWith("<") && (t.contains("XMLBIBLE", ignoreCase = true) || t.contains("BIBLEBOOK", ignoreCase = true)) -> TextFormat.ZEFANIA
            Regex("\\\\(id|c|v)\\s").containsMatchIn(t) -> TextFormat.USFM
            t.startsWith("<") -> TextFormat.OSIS // generic XML: try OSIS shape
            else -> TextFormat.UNKNOWN
        }
    }

    fun parse(content: String, format: TextFormat = sniff(content)): List<ParsedChapter> = when (format) {
        TextFormat.JSON -> parseJson(content)
        TextFormat.USFM -> parseUsfm(content)
        TextFormat.OSIS -> parseOsis(content)
        TextFormat.ZEFANIA -> parseZefania(content)
        TextFormat.HELLOAO -> parseHelloao(content)
        TextFormat.UNKNOWN -> emptyList()
    }

    /** Build a single chapter from raw plain text (used by the .txt-folder importer). */
    fun parsePlainChapter(book: String, chapter: Int, rawText: String, aggressive: Boolean = false): ParsedChapter =
        ParsedChapter(book, chapter, ScriptureTextCleaner.cleanPlainChapter(rawText, aggressive))

    // ---------------------------------------------------------------- JSON

    private fun parseJson(content: String): List<ParsedChapter> {
        val root = runCatching { json.parseToJsonElement(content) }.getOrNull() ?: return emptyList()
        val acc = ChapterAccumulator()

        // Form B: a flat list of verse objects, possibly under a "verses"/"rows" key.
        val verseArray: JsonArray? = when {
            root is JsonArray -> root
            root is JsonObject -> (root["verses"] ?: root["rows"] ?: root["data"]) as? JsonArray
            else -> null
        }
        if (verseArray != null && verseArray.all { it is JsonObject }) {
            for (el in verseArray) {
                val o = el.jsonObject
                val bookRaw = o.str("book_name") ?: o.str("bookname") ?: o.str("book") ?: o.str("b") ?: continue
                val book = BookNameNormalizer.normalize(bookRaw) ?: continue
                val chapter = o.int("chapter") ?: o.int("c") ?: continue
                val text = o.str("text") ?: o.str("t") ?: continue
                acc.add(book, chapter, text)
            }
            if (acc.isNotEmpty()) return acc.build()
        }

        // Form A: nested object book -> chapter -> verse -> text
        if (root is JsonObject) {
            val booksContainer = (root["books"] as? JsonObject) ?: root
            for ((bookRaw, chaptersEl) in booksContainer) {
                val book = BookNameNormalizer.normalize(bookRaw) ?: continue
                val chapters = chaptersEl as? JsonObject ?: continue
                for ((chRaw, versesEl) in chapters) {
                    val chapter = chRaw.toIntOrNull() ?: continue
                    val verses = versesEl as? JsonObject ?: continue
                    val ordered = verses.entries.sortedBy { it.key.toIntOrNull() ?: 0 }
                    for ((_, vText) in ordered) {
                        val text = (vText as? JsonPrimitive)?.contentOrNull ?: continue
                        acc.add(book, chapter, text)
                    }
                }
            }
        }
        return acc.build()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? {
        val p = this[key] as? JsonPrimitive ?: return null
        return p.intOrNull ?: p.contentOrNull?.toIntOrNull()
    }

    // ---------------------------------------------------------------- helloao
    // bible.helloao.org "complete.json": { translation, books:[ { id, name, chapters:[
    // { chapter:{ number, content:[ {type:"verse",number,content:[ "text", {noteId},
    // {lineBreak} ]}, {type:"heading"...}, {type:"line_break"} ] } } ] } ] }
    private fun parseHelloao(content: String): List<ParsedChapter> {
        val root = runCatching { json.parseToJsonElement(content) }.getOrNull() as? JsonObject ?: return emptyList()
        val books = root["books"] as? JsonArray ?: return emptyList()
        val acc = ChapterAccumulator()
        for (bookEl in books) {
            val bookObj = bookEl as? JsonObject ?: continue
            val book = sequenceOf(bookObj.str("commonName"), bookObj.str("name"), bookObj.str("id"))
                .mapNotNull { it?.let(BookNameNormalizer::normalize) }
                .firstOrNull() ?: continue
            val chapters = bookObj["chapters"] as? JsonArray ?: continue
            for (chWrap in chapters) {
                val chObj = (chWrap as? JsonObject)?.get("chapter") as? JsonObject ?: continue
                val chapter = chObj.int("number") ?: continue
                val nodes = chObj["content"] as? JsonArray ?: continue
                for (node in nodes) {
                    val seg = node as? JsonObject ?: continue
                    if (seg.str("type") != "verse") continue
                    val parts = seg["content"] as? JsonArray ?: continue
                    val text = buildString {
                        for (p in parts) when (p) {
                            // plain prose; footnote ({noteId}) and {lineBreak} objects carry no text
                            is JsonPrimitive -> p.contentOrNull?.let { if (isNotEmpty()) append(' '); append(it) }
                            is JsonObject -> p.str("text")?.let { if (isNotEmpty()) append(' '); append(it) }
                            else -> {}
                        }
                    }.trim()
                    if (text.isNotBlank()) acc.add(book, chapter, text)
                }
            }
        }
        return acc.build()
    }

    // ---------------------------------------------------------------- USFM

    private val USFM_HEADING_PREFIXES = setOf(
        "h", "id", "ide", "rem", "sts", "toc", "toca", "mt", "ms", "mr", "s", "sr",
        "r", "d", "sp", "cl", "cp", "ca", "va", "vp", "periph", "iot", "io", "ip", "imt", "is"
    )

    private fun parseUsfm(content: String): List<ParsedChapter> {
        val noNotes = content
            .replace(Regex("\\\\f\\b.*?\\\\f\\*", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("\\\\x\\b.*?\\\\x\\*", RegexOption.DOT_MATCHES_ALL), " ")
        val acc = ChapterAccumulator()
        var book: String? = null
        var chapter = 0

        for (segment in noNotes.split('\\')) {
            if (segment.isBlank()) continue
            val marker = segment.takeWhile { !it.isWhitespace() }
            val rest = segment.drop(marker.length).trim()
            val base = marker.trimEnd('*').lowercase().trimEnd('1', '2', '3', '4')
            when {
                marker.endsWith("*") -> { /* closing inline marker, e.g. \wj* — ignore */ }
                base == "id" -> book = BookNameNormalizer.normalize(rest.takeWhile { !it.isWhitespace() })
                base == "c" -> chapter = rest.takeWhile { it.isDigit() }.toIntOrNull() ?: chapter
                base == "v" -> {
                    val b = book
                    if (b != null && chapter > 0) {
                        val text = rest.dropWhile { !it.isWhitespace() }.trim() // drop verse number token
                        acc.add(b, chapter, text)
                    }
                }
                base in USFM_HEADING_PREFIXES -> { /* heading / metadata — drop */ }
                else -> {
                    // Paragraph/character markers (p, q, m, wj, add, nd, ...): keep their text.
                    val b = book
                    if (b != null && chapter > 0 && rest.isNotEmpty()) acc.append(b, chapter, rest)
                }
            }
        }
        return acc.build()
    }

    // ---------------------------------------------------------------- XML helpers

    private fun parseXml(content: String): org.w3c.dom.Document? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        }
        factory.newDocumentBuilder().parse(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)))
    }.getOrNull()

    private fun local(node: Node): String = node.nodeName.substringAfterLast(':').lowercase()

    private fun attr(node: Node, name: String): String? {
        val attrs = node.attributes ?: return null
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            if (a.nodeName.substringAfterLast(':').equals(name, ignoreCase = true)) return a.nodeValue
        }
        return null
    }

    private fun descendants(node: Node, localName: String): List<Node> {
        val out = mutableListOf<Node>()
        fun walk(n: Node) {
            val kids = n.childNodes
            for (i in 0 until kids.length) {
                val c = kids.item(i)
                if (c.nodeType == Node.ELEMENT_NODE) {
                    if (local(c) == localName) out.add(c)
                    walk(c)
                }
            }
        }
        walk(node)
        return out
    }

    private val SKIP_TEXT_TAGS = setOf("note", "xref", "rdg", "gram")

    private fun collectText(node: Node): String {
        val sb = StringBuilder()
        fun walk(n: Node) {
            val kids = n.childNodes
            for (i in 0 until kids.length) {
                val c = kids.item(i)
                when (c.nodeType) {
                    Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> sb.append(c.nodeValue).append(' ')
                    Node.ELEMENT_NODE -> if (local(c) !in SKIP_TEXT_TAGS) walk(c)
                }
            }
        }
        walk(node)
        return sb.toString()
    }

    // ---------------------------------------------------------------- OSIS

    private fun parseOsis(content: String): List<ParsedChapter> {
        val doc = parseXml(content) ?: return emptyList()
        val acc = ChapterAccumulator()
        for (verse in descendants(doc, "verse")) {
            val osisId = attr(verse, "osisID") ?: continue
            val parts = osisId.split('.')
            if (parts.size < 3) continue
            val book = BookNameNormalizer.normalize(parts[0]) ?: continue
            val chapter = parts[1].toIntOrNull() ?: continue
            val text = collectText(verse)
            if (text.isNotBlank()) acc.add(book, chapter, text)
        }
        return acc.build()
    }

    // ---------------------------------------------------------------- Zefania

    private fun parseZefania(content: String): List<ParsedChapter> {
        val doc = parseXml(content) ?: return emptyList()
        val acc = ChapterAccumulator()
        for (bookEl in descendants(doc, "biblebook")) {
            val bookRaw = attr(bookEl, "bname") ?: attr(bookEl, "bsname")
            val byNumber = attr(bookEl, "bnumber")?.toIntOrNull()?.let { zefaniaBook(it) }
            val book = (bookRaw?.let { BookNameNormalizer.normalize(it) } ?: byNumber) ?: continue
            for (chEl in descendants(bookEl, "chapter")) {
                val chapter = attr(chEl, "cnumber")?.toIntOrNull() ?: continue
                for (versEl in descendants(chEl, "vers")) {
                    val text = collectText(versEl)
                    if (text.isNotBlank()) acc.add(book, chapter, text)
                }
            }
        }
        return acc.build()
    }

    // Zefania bnumber 1..66 follows the standard Protestant order.
    private fun zefaniaBook(n: Int): String? =
        com.Bible3650.www.data.BibleRegistry.getAllBooks().getOrNull(n - 1)

    // ---------------------------------------------------------------- accumulation

    private class ChapterAccumulator {
        private val buffers = LinkedHashMap<Pair<String, Int>, StringBuilder>()
        fun add(book: String, chapter: Int, verseText: String) {
            val sb = buffers.getOrPut(book to chapter) { StringBuilder() }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(verseText.trim())
        }
        fun append(book: String, chapter: Int, text: String) = add(book, chapter, text)
        fun isNotEmpty() = buffers.isNotEmpty()
        fun build(): List<ParsedChapter> = buffers.map { (key, sb) ->
            ParsedChapter(key.first, key.second, ScriptureTextCleaner.cleanVerse(sb.toString()))
        }
    }
}
