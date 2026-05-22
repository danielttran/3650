package com.Bible3650.www.data

/**
 * Natural (human/numeric) ordering for audio file names so that, e.g., "Chapter 2"
 * sorts before "Chapter 10". Pure logic, extracted so it can be unit-tested in isolation.
 */
object NaturalFileSort {

    /**
     * Given (displayName, docId) pairs, returns the docIds ordered by the natural sort
     * of their display names.
     */
    fun sortedDocIds(namesToDocIds: List<Pair<String, String>>): List<String> =
        namesToDocIds
            .map { it.second to tokenize(it.first) }
            .sortedWith { a, b -> compareTokens(a.second, b.second) }
            .map { it.first }

    /** Compares two names with natural numeric ordering. */
    fun compareNames(a: String, b: String): Int = compareTokens(tokenize(a), tokenize(b))

    private fun compareTokens(
        aToks: List<Pair<Boolean, String>>,
        bToks: List<Pair<Boolean, String>>
    ): Int {
        for (i in 0 until minOf(aToks.size, bToks.size)) {
            val (aNum, aStr) = aToks[i]; val (bNum, bStr) = bToks[i]
            val cmp = if (aNum && bNum) {
                val aLong = aStr.toLongOrNull() ?: Long.MAX_VALUE
                val bLong = bStr.toLongOrNull() ?: Long.MAX_VALUE
                aLong.compareTo(bLong)
            } else {
                aStr.compareTo(bStr, ignoreCase = true)
            }
            if (cmp != 0) return cmp
        }
        return aToks.size.compareTo(bToks.size)
    }

    private fun tokenize(s: String): List<Pair<Boolean, String>> {
        val result = mutableListOf<Pair<Boolean, String>>()
        var i = 0
        while (i < s.length) {
            val start = i
            if (s[i].isDigit()) {
                while (i < s.length && s[i].isDigit()) i++
                result.add(true to s.substring(start, i))
            } else {
                while (i < s.length && !s[i].isDigit()) i++
                result.add(false to s.substring(start, i))
            }
        }
        return result
    }
}
