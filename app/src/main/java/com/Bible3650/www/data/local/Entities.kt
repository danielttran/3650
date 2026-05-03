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
    val isCompleted: Boolean,
    val fileUri: String = ""
)

@Entity(
    tableName = "reading_lists",
    indices = [Index(value = ["list_name"], unique = true)]
)
data class ReadingListEntity(
    @PrimaryKey(autoGenerate = true) val listId: Long = 0,
    @ColumnInfo(name = "list_name", collate = ColumnInfo.NOCASE) val listName: String,
    @ColumnInfo(name = "current_absolute_day") val currentDayIndex: Int = 1,
    @ColumnInfo(name = "is_completed_today") val isCompletedToday: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAtTimestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "list_order") val listOrder: Int = 0
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
    
    @Query("""
        UPDATE reading_lists 
        SET current_absolute_day = CASE WHEN is_completed_today = 1 THEN current_absolute_day + 1 ELSE current_absolute_day END,
            is_completed_today = 0
    """)
    suspend fun atomicAdvanceDay()

    @Query("UPDATE reading_lists SET current_absolute_day = current_absolute_day + 1, is_completed_today = 0 WHERE listId = :id")
    suspend fun advanceListDay(id: Long)

    @Query("UPDATE reading_lists SET is_completed_today = :status WHERE listId = :id")
    suspend fun updateTaskStatus(id: Long, status: Boolean)

    @Query("UPDATE reading_lists SET list_order = :order WHERE listId = :id")
    suspend fun updateListOrder(id: Long, order: Int)

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

    @Transaction
    suspend fun updateCustomList(list: ReadingListEntity, newBooks: List<String>) {
        updateList(list)
        deleteBooksForList(list.listId)
        val bookEntities = newBooks.mapIndexed { index, bookName ->
            ListBookEntity(listId = list.listId, bookName = bookName, sortOrder = index)
        }
        insertBooks(bookEntities)
    }
}
