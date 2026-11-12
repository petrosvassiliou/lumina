package app.lumina.player.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persists per-media playback resume state, selected audio/subtitle tracks, sync offsets,
 * external subtitle URI, playback speed, and aspect ratio across sessions and foldable/desktop transitions.
 */
@Entity(tableName = "playback_states")
data class PlaybackStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_uri")
    val mediaUri: String,

    @ColumnInfo(name = "position_ms")
    val positionMs: Long,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,

    @ColumnInfo(name = "playback_speed")
    val playbackSpeed: Float = 1.0f,

    @ColumnInfo(name = "selected_audio_track_id")
    val selectedAudioTrackId: String? = null,

    @ColumnInfo(name = "selected_subtitle_track_id")
    val selectedSubtitleTrackId: String? = null,

    @ColumnInfo(name = "external_subtitle_uri")
    val externalSubtitleUri: String? = null,

    @ColumnInfo(name = "audio_delay_ms")
    val audioDelayMs: Long = 0L,

    @ColumnInfo(name = "subtitle_delay_ms")
    val subtitleDelayMs: Long = 0L,

    @ColumnInfo(name = "aspect_ratio_mode")
    val aspectRatioMode: String = "FIT",

    @ColumnInfo(name = "completed")
    val completed: Boolean = false,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
