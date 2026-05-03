package com.Bible3650.www.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "audio_sources")
data class AudioSourceEntity(
    @PrimaryKey(autoGenerate = true) val sourceId: Long = 0,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "root_tree_uri") val rootTreeUri: String,
    @ColumnInfo(name = "is_active") val isActive: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

// Each row maps one Bible book to its folder within a source.
// overrideTreeUri is non-null only when the user manually picked a different folder
// (which may live outside the source's original root tree).
@Entity(
    tableName = "book_mappings",
    primaryKeys = ["sourceId", "bookName"],
    foreignKeys = [ForeignKey(
        entity = AudioSourceEntity::class,
        parentColumns = ["sourceId"],
        childColumns = ["sourceId"],
        onDelete = ForeignKey.CASCADE,
        onUpdate = ForeignKey.CASCADE
    )],
    indices = [Index("sourceId")]
)
data class BookMappingEntity(
    val sourceId: Long,
    @ColumnInfo(name = "bookName") val bookName: String,
    @ColumnInfo(name = "folder_doc_id") val folderDocId: String,
    @ColumnInfo(name = "confidence") val confidence: Float = 1.0f,
    @ColumnInfo(name = "file_count") val fileCount: Int = 0,
    @ColumnInfo(name = "override_tree_uri") val overrideTreeUri: String? = null
)

data class SourceWithMappings(
    @Embedded val source: AudioSourceEntity,
    @Relation(parentColumn = "sourceId", entityColumn = "sourceId")
    val mappings: List<BookMappingEntity>
)

@Dao
interface AudioSourceDao {

    @Transaction
    @Query("SELECT * FROM audio_sources ORDER BY created_at ASC")
    fun observeAllSources(): Flow<List<SourceWithMappings>>

    @Query("SELECT * FROM book_mappings WHERE sourceId = (SELECT sourceId FROM audio_sources WHERE is_active = 1 LIMIT 1)")
    fun observeActiveMappings(): Flow<List<BookMappingEntity>>

    @Query("SELECT * FROM audio_sources WHERE is_active = 1 LIMIT 1")
    suspend fun getActiveSource(): AudioSourceEntity?

    @Transaction
    @Query("SELECT * FROM audio_sources WHERE sourceId = :id")
    suspend fun getSourceWithMappings(id: Long): SourceWithMappings?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSource(source: AudioSourceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMappings(mappings: List<BookMappingEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMapping(mapping: BookMappingEntity)

    @Query("UPDATE audio_sources SET is_active = 0")
    suspend fun deactivateAll()

    @Query("UPDATE audio_sources SET is_active = 1 WHERE sourceId = :id")
    suspend fun setActive(id: Long)

    @Transaction
    suspend fun switchTo(id: Long) {
        deactivateAll()
        setActive(id)
    }

    @Delete
    suspend fun deleteSource(source: AudioSourceEntity)

    @Update
    suspend fun updateSource(source: AudioSourceEntity)
}
