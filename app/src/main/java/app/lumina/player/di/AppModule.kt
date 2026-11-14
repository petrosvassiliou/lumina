package app.lumina.player.di

import android.content.Context
import androidx.room.Room
import app.lumina.player.data.local.LuminaDatabase
import app.lumina.player.data.local.dao.BookmarkDao
import app.lumina.player.data.local.dao.MediaDao
import app.lumina.player.data.local.dao.PlaybackStateDao
import app.lumina.player.data.local.dao.PlaylistDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class IoDispatcher

@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class MainDispatcher

@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideLuminaDatabase(
        @ApplicationContext context: Context
    ): LuminaDatabase {
        return Room.databaseBuilder(
            context,
            LuminaDatabase::class.java,
            LuminaDatabase.DATABASE_NAME
        )
            .addMigrations(LuminaDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideMediaDao(database: LuminaDatabase): MediaDao = database.mediaDao()

    @Provides
    fun providePlaybackStateDao(database: LuminaDatabase): PlaybackStateDao =
        database.playbackStateDao()

    @Provides
    fun providePlaylistDao(database: LuminaDatabase): PlaylistDao = database.playlistDao()

    @Provides
    fun provideBookmarkDao(database: LuminaDatabase): BookmarkDao = database.bookmarkDao()

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @IoDispatcher ioDispatcher: CoroutineDispatcher
    ): CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)
}
