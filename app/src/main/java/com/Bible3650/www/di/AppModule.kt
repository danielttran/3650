package com.Bible3650.www.di

import android.content.ContentResolver
import android.content.Context
import androidx.room.Room
import com.Bible3650.www.data.local.AppDatabase
import com.Bible3650.www.data.local.AudioSourceDao
import com.Bible3650.www.data.local.BibleDao
import com.Bible3650.www.data.local.MIGRATION_2_3
import com.Bible3650.www.data.local.MIGRATION_3_4
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
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "bible_database")
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
            .build()

    @Provides
    fun provideBibleDao(db: AppDatabase): BibleDao = db.bibleDao()

    @Provides
    fun provideAudioSourceDao(db: AppDatabase): AudioSourceDao = db.audioSourceDao()

    @Provides
    fun provideContentResolver(@ApplicationContext context: Context): ContentResolver =
        context.contentResolver
}
