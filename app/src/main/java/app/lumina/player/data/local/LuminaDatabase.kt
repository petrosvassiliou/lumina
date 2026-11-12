package app.lumina.player.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import app.lumina.player.data.local.dao.MediaDao
import app.lumina.player.data.local.dao.PlaybackStateDao
import app.lumina.player.data.local.entity.MediaItemEntity
import app.lumina.player.data.local.entity.PlaybackStateEntity

@Database(
    entities = [
        MediaItemEntity::class,
        PlaybackStateEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class LuminaDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun playbackStateDao(): PlaybackStateDao

    companion object {
        const val DATABASE_NAME = "lumina_media.db"
    }
}
