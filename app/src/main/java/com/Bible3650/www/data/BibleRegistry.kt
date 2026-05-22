package com.Bible3650.www.data

object BibleRegistry {
    enum class Canon { CANONICAL, APOCRYPHA }

    data class BibleBook(
        val name: String,
        val chapterCount: Int,
        val canon: Canon = Canon.CANONICAL
    )

    private val canonicalBooks = listOf(
        // Old Testament
        BibleBook("Genesis",        50),
        BibleBook("Exodus",         40),
        BibleBook("Leviticus",      27),
        BibleBook("Numbers",        36),
        BibleBook("Deuteronomy",    34),
        BibleBook("Joshua",         24),
        BibleBook("Judges",         21),
        BibleBook("Ruth",            4),
        BibleBook("1 Samuel",       31),
        BibleBook("2 Samuel",       24),
        BibleBook("1 Kings",        22),
        BibleBook("2 Kings",        25),
        BibleBook("1 Chronicles",   29),
        BibleBook("2 Chronicles",   36),
        BibleBook("Ezra",           10),
        BibleBook("Nehemiah",       13),
        BibleBook("Esther",         10),
        BibleBook("Job",            42),
        BibleBook("Psalm",         150),
        BibleBook("Proverbs",       31),
        BibleBook("Ecclesiastes",   12),
        BibleBook("Song of Songs",   8),
        BibleBook("Isaiah",         66),
        BibleBook("Jeremiah",       52),
        BibleBook("Lamentations",    5),
        BibleBook("Ezekiel",        48),
        BibleBook("Daniel",         12),
        BibleBook("Hosea",          14),
        BibleBook("Joel",            3),
        BibleBook("Amos",            9),
        BibleBook("Obadiah",         1),
        BibleBook("Jonah",           4),
        BibleBook("Micah",           7),
        BibleBook("Nahum",           3),
        BibleBook("Habakkuk",        3),
        BibleBook("Zephaniah",       3),
        BibleBook("Haggai",          2),
        BibleBook("Zechariah",      14),
        BibleBook("Malachi",         4),

        // New Testament
        BibleBook("Matthew",        28),
        BibleBook("Mark",           16),
        BibleBook("Luke",           24),
        BibleBook("John",           21),
        BibleBook("Acts",           28),
        BibleBook("Romans",         16),
        BibleBook("1 Corinthians",  16),
        BibleBook("2 Corinthians",  13),
        BibleBook("Galatians",       6),
        BibleBook("Ephesians",       6),
        BibleBook("Philippians",     4),
        BibleBook("Colossians",      4),
        BibleBook("1 Thessalonians", 5),
        BibleBook("2 Thessalonians", 3),
        BibleBook("1 Timothy",       6),
        BibleBook("2 Timothy",       4),
        BibleBook("Titus",           3),
        BibleBook("Philemon",        1),
        BibleBook("Hebrews",        13),
        BibleBook("James",           5),
        BibleBook("1 Peter",         5),
        BibleBook("2 Peter",         3),
        BibleBook("1 John",          5),
        BibleBook("2 John",          1),
        BibleBook("3 John",          1),
        BibleBook("Jude",            1),
        BibleBook("Revelation",     22)
    )

    // Deuterocanonical / apocryphal books (Catholic + Orthodox superset), in the traditional
    // (KJV-Apocrypha / LXX) ordering. Chapter counts follow common single-folder-per-book
    // layouts; one-part pieces use a count of 1 so they resolve to the first file.
    private val apocryphaBooks = listOf(
        BibleBook("1 Esdras",                 9, Canon.APOCRYPHA),
        BibleBook("2 Esdras",                16, Canon.APOCRYPHA),
        BibleBook("Tobit",                   14, Canon.APOCRYPHA),
        BibleBook("Judith",                  16, Canon.APOCRYPHA),
        BibleBook("Additions to Esther",      6, Canon.APOCRYPHA),
        BibleBook("Wisdom of Solomon",       19, Canon.APOCRYPHA),
        BibleBook("Sirach",                  51, Canon.APOCRYPHA),
        BibleBook("Baruch",                   6, Canon.APOCRYPHA),
        BibleBook("Prayer of Azariah",        1, Canon.APOCRYPHA),
        BibleBook("Susanna",                  1, Canon.APOCRYPHA),
        BibleBook("Bel and the Dragon",       1, Canon.APOCRYPHA),
        BibleBook("Prayer of Manasseh",       1, Canon.APOCRYPHA),
        BibleBook("1 Maccabees",             16, Canon.APOCRYPHA),
        BibleBook("2 Maccabees",             15, Canon.APOCRYPHA),
        BibleBook("3 Maccabees",              7, Canon.APOCRYPHA),
        BibleBook("4 Maccabees",             18, Canon.APOCRYPHA),
        BibleBook("Psalm 151",                1, Canon.APOCRYPHA)
    )

    private val allBooks = canonicalBooks + apocryphaBooks

    // Chapter counts cover BOTH canons so apocryphal books contribute chapters to the reading
    // plan and resolve audio files just like canonical books.
    private val chapterCountByName: Map<String, Int> = allBooks.associate { it.name to it.chapterCount }
    private val canonByName: Map<String, Canon> = allBooks.associate { it.name to it.canon }

    private val allBookNames: List<String> = canonicalBooks.map { it.name }
    private val apocryphaBookNames: List<String> = apocryphaBooks.map { it.name }
    private val allKnownBookNames: List<String> = allBooks.map { it.name }

    fun getChapterCount(bookName: String): Int = chapterCountByName[bookName] ?: 0

    /** The 66 canonical books. Unchanged contract — validation, default plans, "N/66" UI. */
    fun getAllBooks(): List<String> = allBookNames

    /** Alias of [getAllBooks] for call sites that want to be explicit about the canon. */
    fun getCanonicalBooks(): List<String> = allBookNames

    /** Apocryphal/deuterocanonical books in traditional order. */
    fun getApocryphalBooks(): List<String> = apocryphaBookNames

    /** Canonical 66 + apocrypha — used by detection so apocrypha folders can match. */
    fun getAllKnownBooks(): List<String> = allKnownBookNames

    fun getCanon(bookName: String): Canon? = canonByName[bookName]

    fun isApocryphal(bookName: String): Boolean = canonByName[bookName] == Canon.APOCRYPHA
}
