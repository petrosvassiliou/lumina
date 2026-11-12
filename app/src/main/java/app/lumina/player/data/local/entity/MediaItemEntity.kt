package app.lumina.player.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents an indexed local media file or saved network stream (SMB/NAS, FTP, UPnP, HLS, RTSP)
 * in the Lumina library.
 */
@Entity(
    tableName = "media_items",
    indices = [
        Index(value = ["folder_name"]),
        Index(value = ["last_played_at"]),
        Index(value = ["is_network_stream"])
    ]
)
data class MediaItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "uri")
    val uri: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "folder_name")
    val folderName: String,

    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,

    @ColumnInfo(name = "width")
    val width: Int,

    @ColumnInfo(name = "height")
    val height: Int,

    @ColumnInfo(name = "hdr_badge")
    val hdrBadge: String?,

    @ColumnInfo(name = "video_codec")
    val videoCodec: String?,

    @ColumnInfo(name = "is_network_stream")
    val isNetworkStream: Boolean = false,

    @ColumnInfo(name = "network_protocol")
    val networkProtocol: String? = null,

    @ColumnInfo(name = "date_added_epoch_sec")
    val dateAddedEpochSec: Long,

    @ColumnInfo(name = "last_played_at")
    val lastPlayedAt: Long? = null,

    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false
)
