package com.Bible3650.www.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class DailyTask(
    val listId: Long,
    val dayOffset: Int,
    val uniqueId: String,
    val listName: String,
    val targetBook: String,
    val targetChapter: Int,
    val totalChapters: Int = 0,
    val fileUri: String = "",
    val listColor: Int = 0
)

@Entity(
    tableName = "reading_lists",
    indices = [Index(value = ["list_name"], unique = true)]
)
data class ReadingListEntity(
    @PrimaryKey(autoGenerate = true) val listId: Long = 0,
    @ColumnInfo(name = "list_name", collate = ColumnInfo.NOCASE) val listName: String,
    @ColumnInfo(name = "current_absolute_day") val currentDayIndex: Int = 1,
    @ColumnInfo(name = "created_at") val createdAtTimestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "list_order") val listOrder: Int = 0,
    @ColumnInfo(name = "stat_reset_offset") val statResetOffset: Int = 0,
    @ColumnInfo(name = "manual_offset") val manualOffset: Int = 0,
    // FROZEN STATE: Caches the current task so mid-day list edits don't jar the UI
    @ColumnInfo(name = "active_book") val activeBook: String? = null,
    @ColumnInfo(name = "active_chapter") val activeChapter: Int? = null,
    // Color-coding for profile page; stored as ARGB Int (Color.toArgb()); 0 = unset
    @ColumnInfo(name = "list_color") val listColor: Int = 0
)

@Entity(
    tableName = "list_books",
    foreignKeys = [
        ForeignKey(
            entity = ReadingListEntity::class,
            parentColumns = ["listId"],
            childColumns = ["listId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("listId"),
        Index("bookName")
    ]
)
data class ListBookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: Long,
    @ColumnInfo(name = "bookName") val bookName: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int
)

data class ListWithBooks(
    @Embedded val readingList: ReadingListEntity,
    @Relation(
        parentColumn = "listId",
        entityColumn = "listId"
    )
    val books: List<ListBookEntity>
)

@Dao
interface BibleDao {
    @Transaction
    @Query("SELECT * FROM reading_lists ORDER BY list_order ASC, created_at ASC")
    fun observeActivePlaylists(): Flow<List<ListWithBooks>>

    @Query("SELECT MAX(list_order) FROM reading_lists")
    suspend fun getMaxListOrder(): Int?

    @Transaction
    @Query("SELECT * FROM reading_lists")
    suspend fun getAllLists(): List<ListWithBooks>

    @Query("SELECT COUNT(*) FROM reading_lists")
    suspend fun countLists(): Int

    @Query("SELECT * FROM reading_lists WHERE listId = :id")
    suspend fun getListById(id: Long): ReadingListEntity?

    @Query("UPDATE reading_lists SET current_absolute_day = :newIndex, active_book = :newBook, active_chapter = :newChapter WHERE listId = :id")
    suspend fun updateListProgress(id: Long, newIndex: Int, newBook: String?, newChapter: Int?)

    @Query("UPDATE reading_lists SET manual_offset = :newOffset, active_book = NULL, active_chapter = NULL WHERE listId = :id")
    suspend fun updateManualOffset(id: Long, newOffset: Int)

    @Query("UPDATE reading_lists SET list_color = :color WHERE listId = :id")
    suspend fun updateListColor(id: Long, color: Int)

    @Query("UPDATE reading_lists SET list_order = :order WHERE listId = :id")
    suspend fun updateListOrder(id: Long, order: Int)

    @Transaction
    suspend fun reorderLists(listIds: List<Long>) {
        listIds.forEachIndexed { index, id ->
            updateListOrder(id, index)
        }
    }

    @Query("UPDATE reading_lists SET stat_reset_offset = current_absolute_day - 1")
    suspend fun resetAllStats()

    @Transaction
    suspend fun createCustomList(list: ReadingListEntity, books: List<String>) {
        val insertedListId = insertList(list)
        val bookEntities = books.mapIndexed { index, bookName ->
            ListBookEntity(listId = insertedListId, bookName = bookName, sortOrder = index)
        }
        insertBooks(bookEntities)
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertList(list: ReadingListEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBooks(books: List<ListBookEntity>)

    @Delete
    suspend fun deleteList(list: ReadingListEntity)

    @Update
    suspend fun updateList(list: ReadingListEntity)

    @Query("DELETE FROM list_books WHERE listId = :listId")
    suspend fun deleteBooksForList(listId: Long)

    @Query("DELETE FROM list_books")
    suspend fun clearAllBooks()

    @Query("DELETE FROM reading_lists")
    suspend fun clearAllLists()

    @Transaction
    suspend fun updateCustomList(list: ReadingListEntity, newBooks: List<String>) {
        // Clear frozen state so stale book/chapter is never shown after a list edit
        updateList(list.copy(activeBook = null, activeChapter = null))
        deleteBooksForList(list.listId)
        val bookEntities = newBooks.mapIndexed { index, bookName ->
            ListBookEntity(listId = list.listId, bookName = bookName, sortOrder = index)
        }
        insertBooks(bookEntities)
    }
}
