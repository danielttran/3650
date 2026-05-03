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
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bibleDao(): BibleDao
    abstract fun audioSourceDao(): AudioSourceDao
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // We need to add active_book and active_chapter, and remove is_completed_today
        // Standard procedure for removing a column in SQLite/Room Migration:
        // 1. Create new table
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `reading_lists_new` (
                `listId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                `list_name` TEXT NOT NULL, 
                `current_absolute_day` INTEGER NOT NULL, 
                `created_at` INTEGER NOT NULL, 
                `list_order` INTEGER NOT NULL, 
                `active_book` TEXT, 
                `active_chapter` INTEGER
            )
        """.trimIndent())

        // 2. Copy data (excluding is_completed_today)
        database.execSQL("""
            INSERT INTO reading_lists_new (listId, list_name, current_absolute_day, created_at, list_order)
            SELECT listId, list_name, current_absolute_day, created_at, list_order FROM reading_lists
        """.trimIndent())

        // 3. Drop old table
        database.execSQL("DROP TABLE reading_lists")

        // 4. Rename new table
        database.execSQL("ALTER TABLE reading_lists_new RENAME TO reading_lists")

        // 5. Re-create index
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_reading_lists_list_name` ON `reading_lists` (`list_name`)")
    }
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

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Room expects column order: id, listId, bookName, sort_order
        // We create the table with the exact structure from AppDatabase_Impl
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `list_books_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                `listId` INTEGER NOT NULL, 
                `bookName` TEXT NOT NULL, 
                `sort_order` INTEGER NOT NULL, 
                FOREIGN KEY(`listId`) REFERENCES `reading_lists`(`listId`) ON UPDATE CASCADE ON DELETE CASCADE 
            )
        """.trimIndent())
        
        // Copy data
        database.execSQL("""
            INSERT INTO list_books_new (listId, bookName, sort_order)
            SELECT listId, bookName, sort_order FROM list_books
        """.trimIndent())
        
        // Remove old table and rename new one
        database.execSQL("DROP TABLE list_books")
        database.execSQL("ALTER TABLE list_books_new RENAME TO list_books")
        
        // CREATE INDICES AFTER RENAME to ensure they are bound to the correct table name
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_list_books_listId` ON `list_books` (`listId`)")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_list_books_bookName` ON `list_books` (`bookName`)")
    }
}
