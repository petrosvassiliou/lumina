package app.lumina.player.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-video timestamp bookmark saved by the user during playback.
 */
@Entity(
    tableName = "video_bookmarks",
    indices = [
        Index(value = ["media_uri"]),
        Index(value = ["media_uri", "position_ms"])
    ]
)
data class VideoBookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "media_uri")
    val mediaUri: String,

    @ColumnInfo(name = "position_ms")
    val positionMs: Long,

    @ColumnInfo(name = "label")
    val label: String,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long = System.currentTimeMillis()
)
