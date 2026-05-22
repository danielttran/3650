package com.Bible3650.www.data.text

/**
 * Produces reading-ready prose so TTS speaks scripture, not verse numbers or footnote
 * markers. Structured parsers (USFM/OSIS/Zefania/JSON) already drop verse numbers as
 * metadata and call [cleanVerse]/[joinVerses]; plain text goes through [cleanPlainChapter],
 * a deliberately conservative, position-anchored pass so real numbers ("5 loaves", "the 12",
 * "A.D. 70") survive.
 */
object ScriptureTextCleaner {

    private val WHITESPACE = Regex("\\s+")
    // Footnote / cross-reference markers: bracketed letters/numbers, daggers, pilcrow, etc.
    private val MARKERS = Regex("\\[[A-Za-z0-9]{1,3}]|[¶†‡*]|[\\u00B9\\u00B2\\u00B3\\u2070-\\u209F]")
    // A leading verse number or chapter:verse token at the very start of a line.
    private val LEADING_VERSE_NO = Regex("^\\s*\\d+(:\\d+)?[.):\\]]?\\s+")
    // An inline chapter:verse reference surrounded by whitespace (aggressive mode only).
    private val INLINE_REF = Regex("(?<=\\s)\\d+:\\d+(?=\\s)")

    private fun normalizeWhitespace(s: String): String = WHITESPACE.replace(s, " ").trim()

    private fun stripMarkers(s: String): String = MARKERS.replace(s, "")

    /** Normalize one already-segmented verse (number/markers handled structurally upstream). */
    fun cleanVerse(text: String): String = normalizeWhitespace(stripMarkers(text))

    /** Join segmented verses into a single chapter of prose. */
    fun joinVerses(verses: List<String>): String =
        normalizeWhitespace(verses.joinToString(" ") { cleanVerse(it) })

    /**
     * Clean a chapter of PLAIN text. [aggressive] additionally removes inline chapter:verse
     * reference tokens (e.g. "1:1") found mid-line — off by default because it can clip
     * legitimate cross-references.
     */
    fun cleanPlainChapter(raw: String, aggressive: Boolean = false): String {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cleaned = lines.map { line ->
            var s = LEADING_VERSE_NO.replace(line, "")
            s = stripMarkers(s)
            if (aggressive) s = INLINE_REF.replace(s, " ")
            normalizeWhitespace(s)
        }.filter { it.isNotBlank() }
        return normalizeWhitespace(cleaned.joinToString(" "))
    }
}
