package app.lumina.player.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.lumina.player.data.local.dao.BookmarkDao
import app.lumina.player.data.local.dao.MediaDao
import app.lumina.player.data.local.dao.PlaybackStateDao
import app.lumina.player.data.local.dao.PlaylistDao
import app.lumina.player.data.local.entity.MediaItemEntity
import app.lumina.player.data.local.entity.PlaybackStateEntity
import app.lumina.player.data.local.entity.PlaylistEntity
import app.lumina.player.data.local.entity.PlaylistEntryEntity
import app.lumina.player.data.local.entity.VideoBookmarkEntity

@Database(
    entities = [
        MediaItemEntity::class,
        PlaybackStateEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        VideoBookmarkEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class LuminaDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun playbackStateDao(): PlaybackStateDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        const val DATABASE_NAME = "lumina_media.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlists` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `created_at_epoch_ms` INTEGER NOT NULL,
                        `updated_at_epoch_ms` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlist_entries` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `playlist_id` INTEGER NOT NULL,
                        `media_uri` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `subtitle` TEXT,
                        `duration_ms` INTEGER NOT NULL,
                        `mime_type` TEXT,
                        `is_network_stream` INTEGER NOT NULL,
                        `hdr_badge` TEXT,
                        `position_index` INTEGER NOT NULL,
                        FOREIGN KEY(`playlist_id`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_entries_playlist_id` ON `playlist_entries` (`playlist_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_entries_playlist_id_position_index` ON `playlist_entries` (`playlist_id`, `position_index`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `video_bookmarks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `media_uri` TEXT NOT NULL,
                        `position_ms` INTEGER NOT NULL,
                        `label` TEXT NOT NULL,
                        `created_at_epoch_ms` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_video_bookmarks_media_uri` ON `video_bookmarks` (`media_uri`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_video_bookmarks_media_uri_position_ms` ON `video_bookmarks` (`media_uri`, `position_ms`)"
                )
            }
        }
    }
}

