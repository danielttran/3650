package com.Bible3650.www.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Soft, easy-on-the-eyes palette for reading list color coding.
 * Stored in Room as Int (ARGB via Color.toArgb()); reconstructed with Color(int).
 */
val ListColorPalette: List<Color> = listOf(
    Color(0xFFCFE2F3), // Cornflower Blue
    Color(0xFFD5E8D4), // Sage Green
    Color(0xFFFFE6CC), // Peach
    Color(0xFFE1D5E7), // Lavender
    Color(0xFFFFF2CC), // Butter Yellow
    Color(0xFFF8D7DA), // Blush Rose
    Color(0xFFD4EDDA), // Mint
    Color(0xFFFDE8D4), // Apricot
    Color(0xFFD1ECF1), // Soft Teal
    Color(0xFFE8D5E0), // Mauve
    Color(0xFFD5E5F5), // Periwinkle
    Color(0xFFF5ECD7), // Warm Cream
)

/** Returns the ARGB Int to persist for a newly created list at [listIndex]. */
fun nextSuggestedColorArgb(listIndex: Int): Int =
    ListColorPalette[listIndex.coerceAtLeast(0) % ListColorPalette.size].toArgb()
