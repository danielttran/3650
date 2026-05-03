package com.Bible3650.www.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.Bible3650.www.data.BibleRepository
import com.Bible3650.www.data.local.ListWithBooks
import com.Bible3650.www.data.local.ReadingListEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ManageListsViewModel @Inject constructor(
    private val repository: BibleRepository
) : ViewModel() {

    val listsFlow: StateFlow<List<ListWithBooks>> = repository.dao.observeActivePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteList(list: ReadingListEntity) {
        viewModelScope.launch {
            repository.dao.deleteList(list)
        }
    }

    fun createList(name: String, books: List<String>) {
        viewModelScope.launch {
            repository.dao.createCustomList(ReadingListEntity(listName = name), books)
        }
    }

    fun updateList(list: ReadingListEntity, newBooks: List<String>) {
        viewModelScope.launch {
            repository.dao.updateCustomList(list, newBooks)
        }
    }

    fun reorderLists(reorderedLists: List<ReadingListEntity>) {
        viewModelScope.launch {
            reorderedLists.forEachIndexed { index, list ->
                repository.dao.updateListOrder(list.listId, index)
            }
        }
    }
}
