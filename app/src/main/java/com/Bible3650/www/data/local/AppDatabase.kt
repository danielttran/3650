package com.Bible3650.www.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ReadingListEntity::class,
        ListBookEntity::class,
        AudioSourceEntity::class,
        BookMappingEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bibleDao(): BibleDao
    abstract fun audioSourceDao(): AudioSourceDao
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS audio_sources (
                sourceId    INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                display_name TEXT NOT NULL,
                root_tree_uri TEXT NOT NULL,
                is_active   INTEGER NOT NULL DEFAULT 0,
                created_at  INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS book_mappings (
                sourceId          INTEGER NOT NULL,
                bookName          TEXT NOT NULL,
                folder_doc_id     TEXT NOT NULL,
                confidence        REAL NOT NULL DEFAULT 1.0,
                file_count        INTEGER NOT NULL DEFAULT 0,
                override_tree_uri TEXT,
                PRIMARY KEY (sourceId, bookName),
                FOREIGN KEY (sourceId) REFERENCES audio_sources(sourceId)
                    ON DELETE CASCADE ON UPDATE CASCADE
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_book_mappings_sourceId ON book_mappings(sourceId)"
        )
    }
}
