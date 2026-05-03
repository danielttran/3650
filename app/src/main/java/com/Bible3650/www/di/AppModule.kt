package com.Bible3650.www.di

import android.content.Context
import androidx.room.Room
import com.Bible3650.www.data.local.AppDatabase
import com.Bible3650.www.data.local.BibleDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "bible_database"
        )
        .fallbackToDestructiveMigration()
        .build()
    }

    @Provides
    fun provideBibleDao(database: AppDatabase): BibleDao {
        return database.bibleDao()
    }
}
