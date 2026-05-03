package com.Bible3650.www.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ReadingListEntity::class, ListBookEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bibleDao(): BibleDao
}
