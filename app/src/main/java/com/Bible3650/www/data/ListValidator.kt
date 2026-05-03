package com.Bible3650.www.data

import com.Bible3650.www.data.local.ListWithBooks

data class ValidationResult(
    val missingBooks: List<String>,
    val duplicateBooks: List<String>,
    val isValid: Boolean
)

object ListValidator {
    fun validateCustomLists(allLists: List<ListWithBooks>): ValidationResult {
        val allConfiguredBooks = allLists.flatMap { it.books }.map { it.bookName }
        val uniqueConfiguredBooks = allConfiguredBooks.toSet()
        val allCanonicalBooks = BibleRegistry.getAllBooks()

        val missing = (allCanonicalBooks.toSet() - uniqueConfiguredBooks).toList().sorted()
        
        val duplicates = allConfiguredBooks
            .groupingBy { it }
            .eachCount()
            .filter { it.value > 1 }
            .keys
            .toList()
            .sorted()

        return ValidationResult(
            missingBooks = missing,
            duplicateBooks = duplicates,
            isValid = missing.isEmpty() && duplicates.isEmpty()
        )
    }
}
