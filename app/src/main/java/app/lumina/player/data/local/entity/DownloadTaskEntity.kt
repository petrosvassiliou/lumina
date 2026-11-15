package app.lumina.player.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "stream_downloads",
    indices = [
        Index(value = ["status"]),
        Index(value = ["created_at_epoch_ms"])
    ]
)
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "source_url")
    val sourceUrl: String,

    @ColumnInfo(name = "resolved_url")
    val resolvedUrl: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "file_name")
    val fileName: String,

    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "is_hls_stream")
    val isHlsStream: Boolean,

    @ColumnInfo(name = "accepts_ranges")
    val acceptsRanges: Boolean,

    @ColumnInfo(name = "thread_count")
    val threadCount: Int,

    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long,

    @ColumnInfo(name = "downloaded_bytes")
    val downloadedBytes: Long,

    @ColumnInfo(name = "segments_json")
    val segmentsJson: String,

    @ColumnInfo(name = "headers_json")
    val headersJson: String,

    @ColumnInfo(name = "staging_file_path")
    val stagingFilePath: String,

    @ColumnInfo(name = "exported_uri")
    val exportedUri: String? = null,

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,

    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long
)
