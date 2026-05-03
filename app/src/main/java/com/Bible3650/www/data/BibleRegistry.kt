package com.Bible3650.www.data

object BibleRegistry {
    data class BibleBook(val name: String, val chapterCount: Int, val testament: String)

    private val books = listOf(
        // Old Testament
        BibleBook("Genesis",        50, "Old Testament"),
        BibleBook("Exodus",         40, "Old Testament"),
        BibleBook("Leviticus",      27, "Old Testament"),
        BibleBook("Numbers",        36, "Old Testament"),
        BibleBook("Deuteronomy",    34, "Old Testament"),
        BibleBook("Joshua",         24, "Old Testament"),
        BibleBook("Judges",         21, "Old Testament"),
        BibleBook("Ruth",            4, "Old Testament"),
        BibleBook("1 Samuel",       31, "Old Testament"),
        BibleBook("2 Samuel",       24, "Old Testament"),
        BibleBook("1 Kings",        22, "Old Testament"),
        BibleBook("2 Kings",        25, "Old Testament"),
        BibleBook("1 Chronicles",   29, "Old Testament"),
        BibleBook("2 Chronicles",   36, "Old Testament"),
        BibleBook("Ezra",           10, "Old Testament"),
        BibleBook("Nehemiah",       13, "Old Testament"),
        BibleBook("Esther",         10, "Old Testament"),
        BibleBook("Job",            42, "Old Testament"),
        BibleBook("Psalm",         150, "Old Testament"),
        BibleBook("Proverbs",       31, "Old Testament"),
        BibleBook("Ecclesiastes",   12, "Old Testament"),
        BibleBook("Song of Songs",   8, "Old Testament"),
        BibleBook("Isaiah",         66, "Old Testament"),
        BibleBook("Jeremiah",       52, "Old Testament"),
        BibleBook("Lamentations",    5, "Old Testament"),
        BibleBook("Ezekiel",        48, "Old Testament"),
        BibleBook("Daniel",         12, "Old Testament"),
        BibleBook("Hosea",          14, "Old Testament"),
        BibleBook("Joel",            3, "Old Testament"),
        BibleBook("Amos",            9, "Old Testament"),
        BibleBook("Obadiah",         1, "Old Testament"),
        BibleBook("Jonah",           4, "Old Testament"),
        BibleBook("Micah",           7, "Old Testament"),
        BibleBook("Nahum",           3, "Old Testament"),
        BibleBook("Habakkuk",        3, "Old Testament"),
        BibleBook("Zephaniah",       3, "Old Testament"),
        BibleBook("Haggai",          2, "Old Testament"),
        BibleBook("Zechariah",      14, "Old Testament"),
        BibleBook("Malachi",         4, "Old Testament"),

        // New Testament
        BibleBook("Matthew",        28, "New Testament"),
        BibleBook("Mark",           16, "New Testament"),
        BibleBook("Luke",           24, "New Testament"),
        BibleBook("John",           21, "New Testament"),
        BibleBook("Acts",           28, "New Testament"),
        BibleBook("Romans",         16, "New Testament"),
        BibleBook("1 Corinthians",  16, "New Testament"),
        BibleBook("2 Corinthians",  13, "New Testament"),
        BibleBook("Galatians",       6, "New Testament"),
        BibleBook("Ephesians",       6, "New Testament"),
        BibleBook("Philippians",     4, "New Testament"),
        BibleBook("Colossians",      4, "New Testament"),
        BibleBook("1 Thessalonians", 5, "New Testament"),
        BibleBook("2 Thessalonians", 3, "New Testament"),
        BibleBook("1 Timothy",       6, "New Testament"),
        BibleBook("2 Timothy",       4, "New Testament"),
        BibleBook("Titus",           3, "New Testament"),
        BibleBook("Philemon",        1, "New Testament"),
        BibleBook("Hebrews",        13, "New Testament"),
        BibleBook("James",           5, "New Testament"),
        BibleBook("1 Peter",         5, "New Testament"),
        BibleBook("2 Peter",         3, "New Testament"),
        BibleBook("1 John",          5, "New Testament"),
        BibleBook("2 John",          1, "New Testament"),
        BibleBook("3 John",          1, "New Testament"),
        BibleBook("Jude",            1, "New Testament"),
        BibleBook("Revelation",     22, "New Testament")
    )

    private val chapterCountByName: Map<String, Int> = books.associate { it.name to it.chapterCount }
    private val allBookNames: List<String> = books.map { it.name }

    fun getChapterCount(bookName: String): Int = chapterCountByName[bookName] ?: 0
    fun getAllBooks(): List<String> = allBookNames
}
