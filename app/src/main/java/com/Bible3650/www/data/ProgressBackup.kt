package com.Bible3650.www.data

import com.Bible3650.www.data.local.AudioSourceEntity
import com.Bible3650.www.data.local.BookMappingEntity
import com.Bible3650.www.data.local.ListBookEntity
import com.Bible3650.www.data.local.ReadingListEntity
import kotlinx.serialization.Serializable

@Serializable
data class ProgressBackup(
    val version: Int = 1,
    val readingLists: List<ReadingListBackup?>? = emptyList(),
    val audioSources: List<AudioSourceBackup?>? = emptyList()
)

@Serializable
data class ReadingListBackup(
    val entity: ReadingListEntity?,
    val books: List<ListBookEntity>?
)

@Serializable
data class AudioSourceBackup(
    val entity: AudioSourceEntity?,
    val mappings: List<BookMappingEntity>?
)
