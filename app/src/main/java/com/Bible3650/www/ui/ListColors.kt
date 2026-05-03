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
    Color(0xFFE2F0CB), // Light Lime
    Color(0xFFFFB7B2), // Pastel Pink
    Color(0xFFE0BBE4), // Pastel Purple
    Color(0xFF957DAD), // Muted Purple
    Color(0xFFD291BC), // Pastel Magenta
    Color(0xFFFEC8D8), // Cotton Candy
    Color(0xFFFFDFD3), // Pastel Orange
    Color(0xFFA8E6CF), // Pastel Green
    Color(0xFFDCEDC1), // Pastel Lime
    Color(0xFFFFD3B6), // Light Coral
    Color(0xFFFFAAA5), // Melon
    Color(0xFFFF8B94), // Soft Red
    Color(0xFFB5EAD7), // Seafoam
    Color(0xFFC7CEEA), // Periwinkle Blue
)

/** Returns the ARGB Int to persist for a newly created list at [listIndex]. */
fun nextSuggestedColorArgb(listIndex: Int): Int {
    val index = listIndex.coerceAtLeast(0) % ListColorPalette.size
    return ListColorPalette[index].toArgb()
}
