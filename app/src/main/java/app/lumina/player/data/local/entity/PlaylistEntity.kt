package app.lumina.player.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * User-created custom media playlist persisted in Room (supports local SAF/MediaStore videos,
 * lossless audio tracks, and LAN/HTTP streams, plus `.m3u` import & export).
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Ordered entry inside a [PlaylistEntity]. Cascade-deleted automatically when parent playlist is removed.
 */
@Entity(
    tableName = "playlist_entries",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["playlist_id"]),
        Index(value = ["playlist_id", "position_index"])
    ]
)
data class PlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,

    @ColumnInfo(name = "media_uri")
    val mediaUri: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "subtitle")
    val subtitle: String? = null,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0L,

    @ColumnInfo(name = "mime_type")
    val mimeType: String? = null,

    @ColumnInfo(name = "is_network_stream")
    val isNetworkStream: Boolean = false,

    @ColumnInfo(name = "hdr_badge")
    val hdrBadge: String? = null,

    @ColumnInfo(name = "position_index")
    val positionIndex: Int = 0
)

/**
 * Lightweight summary projection combining playlist metadata with live track count & total duration.
 */
data class PlaylistSummaryRow(
    @ColumnInfo(name = "id")
    val id: Long,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long,

    @ColumnInfo(name = "item_count")
    val itemCount: Int,

    @ColumnInfo(name = "total_duration_ms")
    val totalDurationMs: Long
)
