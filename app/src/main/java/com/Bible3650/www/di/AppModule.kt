package com.Bible3650.www.di

import android.content.ContentResolver
import android.content.Context
import androidx.room.Room
import com.Bible3650.www.audio.AndroidFileSystemProvider
import com.Bible3650.www.audio.FileSystemProvider
import com.Bible3650.www.data.text.HttpFetcher
import com.Bible3650.www.data.text.UrlHttpFetcher
import com.Bible3650.www.data.local.AppDatabase
import com.Bible3650.www.data.local.AudioSourceDao
import com.Bible3650.www.data.local.BibleDao
import com.Bible3650.www.data.local.BibleTextDao
import com.Bible3650.www.data.local.MIGRATION_1_2
import com.Bible3650.www.data.local.MIGRATION_2_3
import com.Bible3650.www.data.local.MIGRATION_3_4
import com.Bible3650.www.data.local.MIGRATION_4_5
import com.Bible3650.www.data.local.MIGRATION_5_6
import com.Bible3650.www.data.local.MIGRATION_6_7
import com.Bible3650.www.data.local.MIGRATION_7_8
import com.Bible3650.www.data.local.MIGRATION_8_9
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindFileSystemProvider(impl: AndroidFileSystemProvider): FileSystemProvider

    @Binds
    @Singleton
    abstract fun bindHttpFetcher(impl: UrlHttpFetcher): HttpFetcher

    companion object {
        @Provides
        @Singleton
        fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "bible_database")
                // #16: MIGRATION_1_2 added so pre-release v1 users upgrade cleanly.
                // All paths from v1→v8 are explicit — no fallback so a missing migration
                // crashes loudly rather than silently wiping user reading progress.
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .build()

        @Provides
        fun provideBibleDao(db: AppDatabase): BibleDao = db.bibleDao()

        @Provides
        fun provideAudioSourceDao(db: AppDatabase): AudioSourceDao = db.audioSourceDao()

        @Provides
        fun provideBibleTextDao(db: AppDatabase): BibleTextDao = db.bibleTextDao()

        @Provides
        fun provideContentResolver(@ApplicationContext context: Context): ContentResolver =
            context.contentResolver
    }
}
