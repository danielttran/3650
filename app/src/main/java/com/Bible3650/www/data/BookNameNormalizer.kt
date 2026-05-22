package com.Bible3650.www.data

/**
 * Maps the many ways a book is identified across text formats — USFM/Paratext codes,
 * OSIS osisIDs, and free-text names/abbreviations — onto the canonical names used by
 * [BibleRegistry] (canonical 66 + apocrypha). Returns null when nothing matches.
 */
object BookNameNormalizer {

    // appName -> identifiers (USFM code, OSIS id, common variants). The app name itself is
    // always added automatically, so only extra identifiers are listed here.
    private val identifiers: Map<String, List<String>> = mapOf(
        "Genesis" to listOf("GEN", "Gen"),
        "Exodus" to listOf("EXO", "Exod", "Exo"),
        "Leviticus" to listOf("LEV", "Lev"),
        "Numbers" to listOf("NUM", "Num"),
        "Deuteronomy" to listOf("DEU", "Deut", "Deu"),
        "Joshua" to listOf("JOS", "Josh", "Jos"),
        "Judges" to listOf("JDG", "Judg", "Jdg"),
        "Ruth" to listOf("RUT", "Ruth", "Rut"),
        "1 Samuel" to listOf("1SA", "1Sam", "1Sa", "1 Sm", "i samuel", "first samuel"),
        "2 Samuel" to listOf("2SA", "2Sam", "2Sa", "2 Sm", "ii samuel", "second samuel"),
        "1 Kings" to listOf("1KI", "1Kgs", "1Ki", "i kings", "first kings"),
        "2 Kings" to listOf("2KI", "2Kgs", "2Ki", "ii kings", "second kings"),
        "1 Chronicles" to listOf("1CH", "1Chr", "1Ch", "i chronicles", "first chronicles"),
        "2 Chronicles" to listOf("2CH", "2Chr", "2Ch", "ii chronicles", "second chronicles"),
        "Ezra" to listOf("EZR", "Ezra", "Ezr"),
        "Nehemiah" to listOf("NEH", "Neh"),
        "Esther" to listOf("EST", "Esth", "Est"),
        "Job" to listOf("JOB", "Job"),
        "Psalm" to listOf("PSA", "Ps", "Psa", "Psalms", "Pss"),
        "Proverbs" to listOf("PRO", "Prov", "Pro", "Prv"),
        "Ecclesiastes" to listOf("ECC", "Eccl", "Ecc", "Qoh"),
        "Song of Songs" to listOf("SNG", "Song", "SoS", "Song of Solomon", "Canticles"),
        "Isaiah" to listOf("ISA", "Isa"),
        "Jeremiah" to listOf("JER", "Jer"),
        "Lamentations" to listOf("LAM", "Lam"),
        "Ezekiel" to listOf("EZK", "Ezek", "Eze"),
        "Daniel" to listOf("DAN", "Dan"),
        "Hosea" to listOf("HOS", "Hos"),
        "Joel" to listOf("JOL", "Joel", "Joe"),
        "Amos" to listOf("AMO", "Amos", "Amo"),
        "Obadiah" to listOf("OBA", "Obad", "Oba"),
        "Jonah" to listOf("JON", "Jonah", "Jon"),
        "Micah" to listOf("MIC", "Mic"),
        "Nahum" to listOf("NAM", "Nah"),
        "Habakkuk" to listOf("HAB", "Hab"),
        "Zephaniah" to listOf("ZEP", "Zeph", "Zep"),
        "Haggai" to listOf("HAG", "Hag"),
        "Zechariah" to listOf("ZEC", "Zech", "Zec"),
        "Malachi" to listOf("MAL", "Mal"),
        "Matthew" to listOf("MAT", "Matt", "Mat", "Mt"),
        "Mark" to listOf("MRK", "Mark", "Mar", "Mk"),
        "Luke" to listOf("LUK", "Luke", "Luk", "Lk"),
        "John" to listOf("JHN", "John", "Joh", "Jn"),
        "Acts" to listOf("ACT", "Acts", "Act"),
        "Romans" to listOf("ROM", "Rom"),
        "1 Corinthians" to listOf("1CO", "1Cor", "1Co", "i corinthians", "first corinthians"),
        "2 Corinthians" to listOf("2CO", "2Cor", "2Co", "ii corinthians", "second corinthians"),
        "Galatians" to listOf("GAL", "Gal"),
        "Ephesians" to listOf("EPH", "Eph"),
        "Philippians" to listOf("PHP", "Phil", "Php"),
        "Colossians" to listOf("COL", "Col"),
        "1 Thessalonians" to listOf("1TH", "1Thess", "1Th", "i thessalonians", "first thessalonians"),
        "2 Thessalonians" to listOf("2TH", "2Thess", "2Th", "ii thessalonians", "second thessalonians"),
        "1 Timothy" to listOf("1TI", "1Tim", "1Ti", "i timothy", "first timothy"),
        "2 Timothy" to listOf("2TI", "2Tim", "2Ti", "ii timothy", "second timothy"),
        "Titus" to listOf("TIT", "Titus", "Tit"),
        "Philemon" to listOf("PHM", "Phlm", "Phm"),
        "Hebrews" to listOf("HEB", "Heb"),
        "James" to listOf("JAS", "Jas", "Jms"),
        "1 Peter" to listOf("1PE", "1Pet", "1Pe", "i peter", "first peter"),
        "2 Peter" to listOf("2PE", "2Pet", "2Pe", "ii peter", "second peter"),
        "1 John" to listOf("1JN", "1John", "1Jn", "1Jo", "i john", "first john"),
        "2 John" to listOf("2JN", "2John", "2Jn", "2Jo", "ii john", "second john"),
        "3 John" to listOf("3JN", "3John", "3Jn", "3Jo", "iii john", "third john"),
        "Jude" to listOf("JUD", "Jude", "Jud"),
        "Revelation" to listOf("REV", "Rev", "Revelation of John", "Apocalypse"),

        // Apocrypha
        "1 Esdras" to listOf("1ES", "1Esd", "1Es", "3 Ezra", "i esdras"),
        "2 Esdras" to listOf("2ES", "2Esd", "2Es", "4 Ezra", "ii esdras"),
        "Tobit" to listOf("TOB", "Tob", "Tobias"),
        "Judith" to listOf("JDT", "Jdt", "Jdth"),
        "Additions to Esther" to listOf("ESG", "AddEsth", "Add Esth", "Rest of Esther", "Greek Esther"),
        "Wisdom of Solomon" to listOf("WIS", "Wis", "Wisdom"),
        "Sirach" to listOf("SIR", "Sir", "Ecclesiasticus", "Ben Sira"),
        "Baruch" to listOf("BAR", "Bar"),
        "Prayer of Azariah" to listOf("S3Y", "PrAzar", "Song of the Three", "Song of Three", "Azariah"),
        "Susanna" to listOf("SUS", "Sus"),
        "Bel and the Dragon" to listOf("BEL", "Bel"),
        "Prayer of Manasseh" to listOf("MAN", "PrMan", "Manasseh", "Manasses"),
        "1 Maccabees" to listOf("1MA", "1Macc", "1Mac", "i maccabees", "first maccabees"),
        "2 Maccabees" to listOf("2MA", "2Macc", "2Mac", "ii maccabees", "second maccabees"),
        "3 Maccabees" to listOf("3MA", "3Macc", "3Mac", "iii maccabees", "third maccabees"),
        "4 Maccabees" to listOf("4MA", "4Macc", "4Mac", "iv maccabees", "fourth maccabees"),
        "Psalm 151" to listOf("PS2", "Ps151")
    )

    // Letter of Jeremiah is treated as Baruch chapter 6 in this app's model.
    private val foldedIntoBaruch = listOf("LJE", "EpJer", "Letter of Jeremiah", "Epistle of Jeremy")

    private fun key(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private val lookup: Map<String, String> = buildMap {
        for (book in BibleRegistry.getAllKnownBooks()) {
            put(key(book), book)
        }
        for ((book, ids) in identifiers) {
            for (id in ids) put(key(id), book)
        }
        for (id in foldedIntoBaruch) put(key(id), "Baruch")
    }

    /** Returns the canonical [BibleRegistry] book name for any known identifier, or null. */
    fun normalize(raw: String): String? {
        if (raw.isBlank()) return null
        return lookup[key(raw)]
    }
}
