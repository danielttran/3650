package com.Bible3650.www.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * An installed text translation (downloaded from the catalog or imported by the user).
 * The actual scripture lives in [BibleTextEntity], one row per chapter.
 */
@Serializable
@Entity(tableName = "bible_translations")
data class BibleTranslationEntity(
    @PrimaryKey(autoGenerate = true) val translationId: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "abbrev") val abbrev: String,
    @ColumnInfo(name = "language") val language: String = "en",
    @ColumnInfo(name = "origin") val origin: String = "DOWNLOAD", // DOWNLOAD | IMPORT
    @ColumnInfo(name = "has_apocrypha") val hasApocrypha: Boolean = false,
    @ColumnInfo(name = "license") val license: String? = null,
    @ColumnInfo(name = "attribution") val attribution: String? = null,
    @ColumnInfo(name = "installed_at") val installedAt: Long = System.currentTimeMillis()
)

/** One chapter of reading-ready prose (verse numbers / notes / headings already stripped). */
@Serializable
@Entity(
    tableName = "bible_texts",
    primaryKeys = ["translationId", "book", "chapter"],
    foreignKeys = [ForeignKey(
        entity = BibleTranslationEntity::class,
        parentColumns = ["translationId"],
        childColumns = ["translationId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("translationId")]
)
data class BibleTextEntity(
    val translationId: Long,
    @ColumnInfo(name = "book") val book: String,
    @ColumnInfo(name = "chapter") val chapter: Int,
    @ColumnInfo(name = "text") val text: String
)

@Dao
interface BibleTextDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTranslation(translation: BibleTranslationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapters(chapters: List<BibleTextEntity>)

    @Query("SELECT * FROM bible_texts WHERE translationId = :translationId AND book = :book AND chapter = :chapter LIMIT 1")
    suspend fun getChapter(translationId: Long, book: String, chapter: Int): BibleTextEntity?

    @Query("SELECT * FROM bible_translations ORDER BY installed_at ASC")
    fun observeTranslations(): Flow<List<BibleTranslationEntity>>

    @Query("SELECT * FROM bible_translations ORDER BY installed_at ASC")
    suspend fun getTranslations(): List<BibleTranslationEntity>

    @Query("SELECT * FROM bible_translations WHERE translationId = :id LIMIT 1")
    suspend fun getTranslation(id: Long): BibleTranslationEntity?

    @Query("SELECT DISTINCT book FROM bible_texts WHERE translationId = :translationId")
    suspend fun getBooksForTranslation(translationId: Long): List<String>

    @Query("SELECT COUNT(*) FROM bible_texts WHERE translationId = :translationId")
    suspend fun countChapters(translationId: Long): Int

    @Query("SELECT * FROM bible_texts WHERE translationId = :translationId ORDER BY book ASC, chapter ASC")
    suspend fun getChaptersForTranslation(translationId: Long): List<BibleTextEntity>

    @Delete
    suspend fun deleteTranslation(translation: BibleTranslationEntity)

    @Query("DELETE FROM bible_texts WHERE translationId = :translationId")
    suspend fun clearTextForTranslation(translationId: Long)

    @Query("DELETE FROM bible_translations")
    suspend fun clearAllTranslations()
}
