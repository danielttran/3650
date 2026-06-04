package com.Bible3650.www.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NaturalFileSort] — the natural/numeric ordering used to sort audio
 * chapter files so "Chapter 10" follows "Chapter 9" rather than "Chapter 1".
 */
class NaturalFileSortTest {

    private fun docOrder(vararg names: String): List<String> =
        NaturalFileSort.sortedDocIds(names.mapIndexed { i, n -> n to "doc$i" })
            .map { id -> names[id.removePrefix("doc").toInt()] }

    @Test
    fun `numbers order numerically not lexically`() {
        val sorted = docOrder("Chapter 1", "Chapter 10", "Chapter 2", "Chapter 9")
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 9", "Chapter 10"), sorted)
    }

    @Test
    fun `zero padded and unpadded numbers compare by value`() {
        assertTrue(NaturalFileSort.compareNames("Psalm 09", "Psalm 10") < 0)
        assertTrue(NaturalFileSort.compareNames("Psalm 2", "Psalm 19") < 0)
    }

    @Test
    fun `pure text compares case-insensitively`() {
        assertEquals(0, NaturalFileSort.compareNames("genesis.mp3", "GENESIS.MP3"))
    }

    @Test
    fun `mixed prefixes then numbers`() {
        val sorted = docOrder("track2.mp3", "track12.mp3", "track1.mp3")
        assertEquals(listOf("track1.mp3", "track2.mp3", "track12.mp3"), sorted)
    }

    @Test
    fun `shorter token sequence sorts first when prefixes equal`() {
        assertTrue(NaturalFileSort.compareNames("Mark", "Mark 1") < 0)
    }

    @Test
    fun `large chapter counts stay ordered`() {
        val names = (1..150).shuffled().map { "Psalm $it" }.toTypedArray()
        val sorted = docOrder(*names)
        assertEquals((1..150).map { "Psalm $it" }, sorted)
    }

    @Test
    fun `numeric tokens longer than Long do not overflow or tie`() {
        // 20+ digit runs overflow Long.MAX_VALUE; ensure they still order by numeric value
        // instead of collapsing to a single value and randomly tying.
        assertTrue(NaturalFileSort.compareNames("file99999999999999999999", "file100000000000000000000") < 0)
        assertTrue(NaturalFileSort.compareNames("file100000000000000000000", "file100000000000000000001") < 0)
        val sorted = docOrder(
            "track100000000000000000002.mp3",
            "track100000000000000000010.mp3",
            "track100000000000000000001.mp3"
        )
        assertEquals(
            listOf(
                "track100000000000000000001.mp3",
                "track100000000000000000002.mp3",
                "track100000000000000000010.mp3"
            ),
            sorted
        )
    }

    @Test
    fun `leading zeros do not change numeric ordering for large tokens`() {
        assertTrue(NaturalFileSort.compareNames("x0009999999999999999999", "x9999999999999999999999") < 0)
    }
}
