package com.Bible3650.www.data

object BibleRegistry {
    data class BibleBook(
        val name: String,
        val folderName: String,
        val filePrefix: String,
        val chapterCount: Int,
        val testament: String,
        val padding: Int = 2
    )

    private val books = listOf(
        // Old Testament
        BibleBook("Genesis", "01 Genesis", "01_Genesis", 50, "Old Testament"),
        BibleBook("Exodus", "02 Exodus", "02_Exodus", 40, "Old Testament"),
        BibleBook("Leviticus", "03 Leviticus", "03_Leviticus", 27, "Old Testament"),
        BibleBook("Numbers", "04 Numbers", "04_Numbers", 36, "Old Testament"),
        BibleBook("Deuteronomy", "05 Deuteronomy", "05_Deuteronomy", 34, "Old Testament"),
        BibleBook("Joshua", "06 Joshua", "06_Joshua", 24, "Old Testament"),
        BibleBook("Judges", "07 Judges", "07_Judges", 21, "Old Testament"),
        BibleBook("Ruth", "08 Ruth", "08_Ruth", 4, "Old Testament"),
        BibleBook("1 Samuel", "09 1 Samuel", "09_1 Samuel", 31, "Old Testament"),
        BibleBook("2 Samuel", "10 2 Samuel", "10_2 Samuel", 24, "Old Testament"),
        BibleBook("1 Kings", "11 1 Kings", "11_1 Kings", 22, "Old Testament"),
        BibleBook("2 Kings", "12 2 Kings", "12_2 Kings", 25, "Old Testament"),
        BibleBook("1 Chronicles", "13 1 Chronicles", "13_1 Chronicles", 29, "Old Testament"),
        BibleBook("2 Chronicles", "14 2 Chronicles", "14_2 Chronicles", 36, "Old Testament"),
        BibleBook("Ezra", "15 Ezra", "15_Ezra", 10, "Old Testament"),
        BibleBook("Nehemiah", "16 Nehemiah", "16_Nehemiah", 13, "Old Testament"),
        BibleBook("Esther", "17 Esther", "17_Esther", 10, "Old Testament"),
        BibleBook("Job", "18 Job", "18_Job", 42, "Old Testament"),
        BibleBook("Psalm", "19 Psalm", "19_Psalm", 150, "Old Testament", padding = 3),
        BibleBook("Proverbs", "20 Proverbs", "20_Proverbs", 31, "Old Testament"),
        BibleBook("Ecclesiastes", "21 Ecclesiastes", "21_Ecclesiastes", 12, "Old Testament"),
        BibleBook("Song of Songs", "22 Song of Songs", "22_Song of Songs", 8, "Old Testament"),
        BibleBook("Isaiah", "23 Isaiah", "23_Isaiah", 66, "Old Testament"),
        BibleBook("Jeremiah", "24 Jeremiah", "24_Jeremiah", 52, "Old Testament"),
        BibleBook("Lamentations", "25 Lamentations", "25_Lamentations", 5, "Old Testament"),
        BibleBook("Ezekiel", "26 Ezekiel", "26_Ezekiel", 48, "Old Testament"),
        BibleBook("Daniel", "27 Daniel", "27_Daniel", 12, "Old Testament"),
        BibleBook("Hosea", "28 Hosea", "28_Hosea", 14, "Old Testament"),
        BibleBook("Joel", "29 Joel", "29_Joel", 3, "Old Testament"),
        BibleBook("Amos", "30 Amos", "30_Amos", 9, "Old Testament"),
        BibleBook("Obadiah", "31 Obadiah", "31_Obadiah", 1, "Old Testament"),
        BibleBook("Jonah", "32 Jonah", "32_Jonah", 4, "Old Testament"),
        BibleBook("Micah", "33 Micah", "33_Micah", 7, "Old Testament"),
        BibleBook("Nahum", "34 Nahum", "34_Nahum", 3, "Old Testament"),
        BibleBook("Habakkuk", "35 Habakkuk", "35_Habakkuk", 3, "Old Testament"),
        BibleBook("Zephaniah", "36 Zephaniah", "36_Zephaniah", 3, "Old Testament"),
        BibleBook("Haggai", "37 Haggai", "37_Haggai", 2, "Old Testament"),
        BibleBook("Zechariah", "38 Zechariah", "38_Zechariah", 14, "Old Testament"),
        BibleBook("Malachi", "39 Malachi", "39_Malachi", 4, "Old Testament"),

        // New Testament
        BibleBook("Matthew", "01 Matthew", "01_Matthew", 28, "New Testament"),
        BibleBook("Mark", "02 Mark", "02_Mark", 16, "New Testament"),
        BibleBook("Luke", "03 Luke", "03_Luke", 24, "New Testament"),
        BibleBook("John", "04 John", "04_John", 21, "New Testament"),
        BibleBook("Acts", "05 Acts", "05_Acts", 28, "New Testament"),
        BibleBook("Romans", "06 Romans", "06_Romans", 16, "New Testament"),
        BibleBook("1 Corinthians", "07 1 Corinthians", "07_1 Corinthians", 16, "New Testament"),
        BibleBook("2 Corinthians", "08 2 Corinthians", "08_2 Corinthians", 13, "New Testament"),
        BibleBook("Galatians", "09 Galatians", "09_Galatians", 6, "New Testament"),
        BibleBook("Ephesians", "10 Ephesians", "10_Ephesians", 6, "New Testament"),
        BibleBook("Philippians", "11 Philippians", "11_Philippians", 4, "New Testament"),
        BibleBook("Colossians", "12 Colossians", "12_Colossians", 4, "New Testament"),
        BibleBook("1 Thessalonians", "13 1 Thessalonians", "13_1 Thessalonians", 5, "New Testament"),
        BibleBook("2 Thessalonians", "14 2 Thessalonians", "14_2 Thessalonians", 3, "New Testament"),
        BibleBook("1 Timothy", "15 1 Timothy", "15_1 Timothy", 6, "New Testament"),
        BibleBook("2 Timothy", "16 2 Timothy", "16_2 Timothy", 4, "New Testament"),
        BibleBook("Titus", "17 Titus", "17_Titus", 3, "New Testament"),
        BibleBook("Philemon", "18 Philemon", "18_Philemon", 1, "New Testament"),
        BibleBook("Hebrews", "19 Hebrews", "19_Hebrews", 13, "New Testament"),
        BibleBook("James", "20 James", "20_James", 5, "New Testament"),
        BibleBook("1 Peter", "21 1 Peter", "21_1 Peter", 5, "New Testament"),
        BibleBook("2 Peter", "22 2 Peter", "22_2 Peter", 3, "New Testament"),
        BibleBook("1 John", "23 1 John", "23_1 John", 5, "New Testament"),
        BibleBook("2 John", "24 2 John", "24_2 John", 1, "New Testament"),
        BibleBook("3 John", "25 3 John", "25_3 John", 1, "New Testament"),
        BibleBook("Jude", "26 Jude", "26_Jude", 1, "New Testament"),
        BibleBook("Revelation", "27 Revelation", "27_Revelation", 22, "New Testament")
    )

    fun getBook(name: String): BibleBook? = books.find { it.name == name }

    fun getChapterCount(bookName: String): Int = getBook(bookName)?.chapterCount ?: 0

    fun getTestament(bookName: String): String = getBook(bookName)?.testament ?: ""

    fun getAllBooks(): List<String> = books.map { it.name }
}
