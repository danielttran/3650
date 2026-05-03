package com.Bible3650.www.domain

sealed class PresetPlan(val displayName: String, val description: String) {
    object GrantHorner : PresetPlan(
        displayName = "Grant Horner Lists",
        description = "10 reading lists covering the entire Bible across 10 streams."
    )
    object Daniel7 : PresetPlan(
        displayName = "Daniel 7 Lists",
        description = "7 reading lists with a balanced mix of Old and New Testament books."
    )

    companion object {
        fun all(): List<PresetPlan> = listOf(GrantHorner, Daniel7)
        
        fun fromKey(key: String): PresetPlan = when (key) {
            "Daniel7" -> Daniel7
            else -> GrantHorner
        }
    }
}

fun PresetPlan.toKey(): String = when (this) {
    is PresetPlan.GrantHorner -> "GrantHorner"
    is PresetPlan.Daniel7 -> "Daniel7"
}

object DefaultsProvider {
    /** Original Grant Horner 10-list system */
    val grantHornerLists = listOf(
        "List 1:"   to listOf("Matthew", "Mark", "Luke", "John"),
        "List 2:"   to listOf("Genesis", "Exodus", "Leviticus", "Numbers", "Deuteronomy"),
        "List 3:"   to listOf("Romans", "1 Corinthians", "2 Corinthians", "Galatians", "Ephesians", "Philippians", "Colossians", "Hebrews"),
        "List 4:"   to listOf("1 Thessalonians", "2 Thessalonians", "1 Timothy", "2 Timothy", "Titus", "Philemon", "James", "1 Peter", "2 Peter", "1 John", "2 John", "3 John", "Jude", "Revelation"),
        "List 5:"   to listOf("Job", "Ecclesiastes", "Song of Songs"),
        "List 6:"   to listOf("Psalm"),
        "List 7:"   to listOf("Proverbs"),
        "List 8:"   to listOf("Joshua", "Judges", "Ruth", "1 Samuel", "2 Samuel", "1 Kings", "2 Kings", "1 Chronicles", "2 Chronicles", "Ezra", "Nehemiah", "Esther"),
        "List 9:"   to listOf("Isaiah", "Jeremiah", "Lamentations", "Ezekiel", "Daniel", "Hosea", "Joel", "Amos", "Obadiah", "Jonah", "Micah", "Nahum", "Habakkuk", "Zephaniah", "Haggai", "Zechariah", "Malachi"),
        "List 10:"  to listOf("Acts")
    )

    /** Daniel 7-list system */
    val daniel7Lists = listOf(
        "List 1:"   to listOf("Matthew", "Mark", "Luke", "John", "Galatians", "Romans"),
        "List 2:"   to listOf("Genesis", "Exodus", "Leviticus", "Numbers", "Deuteronomy", "Joshua", "Judges", "Ruth", "1 Samuel", "2 Samuel", "1 Kings", "2 Kings"),
        "List 3:"   to listOf("Psalm"),
        "List 4:"   to listOf("1 Thessalonians", "2 Thessalonians", "1 Corinthians", "2 Corinthians", "Colossians", "Ephesians", "Philemon", "Philippians", "1 Timothy", "2 Timothy", "Titus", "Hebrews", "James", "1 Peter", "2 Peter", "1 John", "2 John", "3 John", "Jude", "Revelation"),
        "List 5:"   to listOf("Lamentations", "Song of Songs", "Job", "1 Chronicles", "2 Chronicles", "Ezra", "Nehemiah", "Esther", "Daniel"),
        "List 6:"   to listOf("Proverbs", "Ecclesiastes", "Acts"),
        "List 7:"   to listOf("Jonah", "Amos", "Hosea", "Micah", "Isaiah", "Zephaniah", "Nahum", "Habakkuk", "Jeremiah", "Obadiah", "Ezekiel", "Haggai", "Zechariah", "Joel", "Malachi")
    )

    fun listsFor(plan: PresetPlan) = when (plan) {
        is PresetPlan.GrantHorner -> grantHornerLists
        is PresetPlan.Daniel7     -> daniel7Lists
    }

    // Legacy alias used by the old insertDefaultLists path
    val standardLists get() = grantHornerLists
}
