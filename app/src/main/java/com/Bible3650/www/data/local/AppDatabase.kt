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
    version = 8,
    // #19: Schema export enabled — Room will write JSON schema files to the directory
    // configured via room.schemaLocation in build.gradle.kts. This acts as a free
    // migration safety net and documents the schema history in version control.
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bibleDao(): BibleDao
    abstract fun audioSourceDao(): AudioSourceDao
}

// #16: Handles upgrades from the very first schema (v1) which lacked list_order.
// Without this, any user who installed the earliest pre-release build would crash
// with "A migration from 1 to 2 was required but not found".
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // v1 → v2: add list_order column (was missing in the initial schema)
        db.execSQL("ALTER TABLE reading_lists ADD COLUMN list_order INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE reading_lists ADD COLUMN list_color INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE reading_lists ADD COLUMN manual_offset INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE reading_lists ADD COLUMN stat_reset_offset INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
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
        db.execSQL("""
            INSERT INTO reading_lists_new (listId, list_name, current_absolute_day, created_at, list_order)
            SELECT listId, list_name, current_absolute_day, created_at, list_order FROM reading_lists
        """.trimIndent())
        db.execSQL("DROP TABLE reading_lists")
        db.execSQL("ALTER TABLE reading_lists_new RENAME TO reading_lists")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_reading_lists_list_name` ON `reading_lists` (`list_name`)")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
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
        db.execSQL(
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
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_book_mappings_sourceId ON book_mappings(sourceId)"
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `list_books_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `listId` INTEGER NOT NULL,
                `bookName` TEXT NOT NULL,
                `sort_order` INTEGER NOT NULL,
                FOREIGN KEY(`listId`) REFERENCES `reading_lists`(`listId`) ON UPDATE CASCADE ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO list_books_new (listId, bookName, sort_order)
            SELECT listId, bookName, sort_order FROM list_books
        """.trimIndent())
        db.execSQL("DROP TABLE list_books")
        db.execSQL("ALTER TABLE list_books_new RENAME TO list_books")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_list_books_listId` ON `list_books` (`listId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_list_books_bookName` ON `list_books` (`bookName`)")
    }
}
