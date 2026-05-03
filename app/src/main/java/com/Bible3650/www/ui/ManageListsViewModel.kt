package com.Bible3650.www.ui

import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.Bible3650.www.data.BibleRepository
import com.Bible3650.www.data.ListValidator
import com.Bible3650.www.data.ValidationResult
import com.Bible3650.www.data.local.ListWithBooks
import com.Bible3650.www.data.local.ReadingListEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.Bible3650.www.domain.PresetPlan
import javax.inject.Inject

@HiltViewModel
class ManageListsViewModel @Inject constructor(
    private val repository: BibleRepository
) : ViewModel() {

    val listsFlow: StateFlow<List<ListWithBooks>> = repository.dao.observeActivePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val validationResults: StateFlow<ValidationResult> = listsFlow.map {
        ListValidator.validateCustomLists(it)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ValidationResult(emptyList(), emptyList(), true)
    )

    private val _uiEvents = MutableSharedFlow<String>()
    val uiEvents = _uiEvents.asSharedFlow()

    fun resetToDefaults(plan: PresetPlan) {
        viewModelScope.launch {
            try {
                repository.resetToDefaults(plan)
                _uiEvents.emit("Lists restored to \"${plan.displayName}\".")
            } catch (e: Exception) {
                android.util.Log.e("ManageListsVM", "Error resetting lists", e)
                _uiEvents.emit("Failed to reset lists.")
            }
        }
    }

    fun deleteList(list: ReadingListEntity) {
        viewModelScope.launch {
            try {
                repository.dao.deleteList(list)
            } catch (e: Exception) {
                android.util.Log.e("ManageListsVM", "Error deleting list", e)
                _uiEvents.emit("Failed to delete list.")
            }
        }
    }

    fun createList(name: String, books: List<String>, colorArgb: Int) {
        viewModelScope.launch {
            try {
                val maxOrder = repository.dao.getMaxListOrder() ?: -1
                repository.dao.createCustomList(
                    ReadingListEntity(listName = name, listColor = colorArgb, listOrder = maxOrder + 1),
                    books
                )
            } catch (e: Exception) {
                android.util.Log.e("ManageListsVM", "Error creating list", e)
                _uiEvents.emit("Failed to create list.")
            }
        }
    }

    fun updateList(list: ReadingListEntity, newBooks: List<String>) {
        viewModelScope.launch {
            try {
                repository.dao.updateCustomList(list, newBooks)
            } catch (e: Exception) {
                android.util.Log.e("ManageListsVM", "Error updating list", e)
                _uiEvents.emit("Failed to update list.")
            }
        }
    }

    fun reorderLists(reorderedLists: List<ReadingListEntity>) {
        viewModelScope.launch {
            try {
                repository.dao.reorderLists(reorderedLists.map { it.listId })
            } catch (e: Exception) {
                android.util.Log.e("ManageListsVM", "Error reordering lists", e)
                _uiEvents.emit("Failed to reorder lists.")
            }
        }
    }
}
