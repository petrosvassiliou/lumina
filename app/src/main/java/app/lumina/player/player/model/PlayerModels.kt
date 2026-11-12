package app.lumina.player.player.model

import android.net.Uri
import org.videolan.libvlc.MediaPlayer

/**
 * Supported High Dynamic Range & Standard Dynamic Range formats detected by Lumina.
 */
enum class HdrFormat(val displayBadge: String) {
    SDR("SDR"),
    HDR10("HDR10"),
    HDR10_PLUS("HDR10+"),
    HLG("HLG"),
    DOLBY_VISION("Dolby Vision");

    val isHdr: Boolean
        get() = this != SDR
}

/**
 * Active HDR rendering pipeline mode depending on stream metadata, user preference, and display EOTF support.
 */
enum class HdrOutputMode {
    /** Native hardware passthrough (BT.2020 PQ / BT.2020 HLG + OS HDR10 Window mode) */
    HARDWARE_HDR_PASSTHROUGH,
    /** Dynamic GPU tone-mapping fallback via libVLC for SDR displays */
    LIBVLC_DYNAMIC_TONE_MAPPING,
    /** Standard BT.709 SDR pipeline */
    STANDARD_SDR
}

/**
 * Real-time telemetry of the Lumina HDR Surface & Display pipeline.
 */
data class HdrPipelineStatus(
    val sourceFormat: HdrFormat = HdrFormat.SDR,
    val outputMode: HdrOutputMode = HdrOutputMode.STANDARD_SDR,
    val activeColorSpaceName: String = "BT.709 (sRGB)",
    val displaySupportsHdr10: Boolean = false,
    val displaySupportsHdr10Plus: Boolean = false,
    val displaySupportsHlg: Boolean = false,
    val displaySupportsDolbyVision: Boolean = false,
    val displayMaxLuminanceNits: Float = 0f,
    val activeToneMapper: String? = null
) {
    val displaySupportsAnyHdr: Boolean
        get() = displaySupportsHdr10 || displaySupportsHdr10Plus || displaySupportsHlg || displaySupportsDolbyVision
}

/**
 * Video aspect ratio & surface scaling presets (cycles cleanly via HUD button or 'C' key on Googlebook OS).
 */
enum class AspectRatioMode(
    val label: String,
    val scaleType: MediaPlayer.ScaleType,
    val customAspectRatio: String? = null
) {
    FIT("Fit", MediaPlayer.ScaleType.SURFACE_BEST_FIT, null),
    FILL("Fill", MediaPlayer.ScaleType.SURFACE_FIT_SCREEN, null),
    RATIO_16_9("16:9", MediaPlayer.ScaleType.SURFACE_16_9, null),
    RATIO_21_9("21:9", MediaPlayer.ScaleType.SURFACE_BEST_FIT, "21:9"),
    STRETCH("Stretch", MediaPlayer.ScaleType.SURFACE_FILL, null),
    RATIO_4_3("4:3", MediaPlayer.ScaleType.SURFACE_4_3, null),
    ORIGINAL("Original (1:1)", MediaPlayer.ScaleType.SURFACE_ORIGINAL, null);

    fun next(): AspectRatioMode {
        val all = entries
        return all[(ordinal + 1) % all.size]
    }

    companion object {
        fun fromNameOrDefault(name: String?): AspectRatioMode {
            return entries.firstOrNull { it.name == name } ?: FIT
        }
    }
}

/**
 * Audio track descriptor mapped from libVLC 4.0 IMedia.AudioTrack.
 */
data class AudioTrackInfo(
    val id: String,
    val title: String,
    val language: String?,
    val codec: String?,
    val channels: Int,
    val sampleRateHz: Int,
    val bitrateKbps: Int,
    val isSelected: Boolean
)

/**
 * Subtitle (SPU) track descriptor mapped from libVLC 4.0 IMedia.Track.
 */
data class SubtitleTrackInfo(
    val id: String,
    val title: String,
    val language: String?,
    val codec: String?,
    val isExternal: Boolean = false,
    val isSelected: Boolean
)

/**
 * Single band of the 10-band libVLC hardware/DSP equalizer.
 */
data class EqualizerBand(
    val index: Int,
    val frequencyHz: Float,
    val gainDb: Float
) {
    val formattedFrequency: String
        get() = if (frequencyHz >= 1000f) {
            val khz = frequencyHz / 1000f
            if (khz % 1f == 0f) "${khz.toInt()}k" else String.format("%.1fk", khz)
        } else {
            "${frequencyHz.toInt()}"
        }
}

/**
 * Complete state of the 10-band Audio Equalizer and presets.
 */
data class EqualizerState(
    val enabled: Boolean = false,
    val preAmpDb: Float = 0f,
    val presets: List<String> = emptyList(),
    val selectedPresetIndex: Int = 0,
    val bands: List<EqualizerBand> = emptyList()
)

/**
 * Embedded MKV / MP4 chapter descriptor mapped from libVLC 4.0 `MediaPlayer.Chapter`.
 */
data class ChapterInfo(
    val index: Int,
    val title: String,
    val timeOffsetMs: Long,
    val durationMs: Long
)

/**
 * Cinema Sleep Timer presets for automatic pause.
 */
enum class SleepTimerMode(val durationMinutes: Int) {
    OFF(0),
    MIN_15(15),
    MIN_30(30),
    MIN_45(45),
    MIN_60(60),
    END_OF_MEDIA(-1)
}

/**
 * Active Sleep Timer countdown state.
 */
data class SleepTimerState(
    val mode: SleepTimerMode = SleepTimerMode.OFF,
    val remainingMs: Long = 0L
) {
    val isActive: Boolean
        get() = mode != SleepTimerMode.OFF
}

/**
 * Seamless A-B Repeat loop marker state.
 */
data class ABRepeatState(
    val pointAMs: Long? = null,
    val pointBMs: Long? = null
) {
    val isActive: Boolean
        get() = pointAMs != null && pointBMs != null && pointBMs > pointAMs
}

/**
 * Playable item in the active Lumina playback queue.
 */
data class MediaQueueItem(
    val uri: Uri,
    val title: String,
    val subtitle: String? = null,
    val durationMs: Long = 0L,
    val mimeType: String? = null,
    val isNetworkStream: Boolean = false,
    val hdrHint: HdrFormat? = null
)

enum class PlaybackStatus {
    IDLE,
    OPENING,
    BUFFERING,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR
}

/**
 * Immutable, reactive UI & Engine snapshot exposed via StateFlow by LuminaPlayerEngine.
 */
data class LuminaPlayerState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val isPlaying: Boolean = false,
    val isSeekable: Boolean = false,
    val bufferingPercent: Float = 0f,
    val currentTimeMs: Long = 0L,
    val durationMs: Long = 0L,
    val progress: Float = 0f,
    val playbackSpeed: Float = 1.0f,
    /** Supports 0..100 standard range and up to 150 software boost */
    val volume: Int = 100,
    val isMuted: Boolean = false,
    val aspectRatioMode: AspectRatioMode = AspectRatioMode.FIT,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoCodecLabel: String? = null,
    val frameRate: Float = 0f,
    val audioTracks: List<AudioTrackInfo> = emptyList(),
    val subtitleTracks: List<SubtitleTrackInfo> = emptyList(),
    val subtitlesEnabled: Boolean = true,
    val chapters: List<ChapterInfo> = emptyList(),
    val currentChapterIndex: Int = -1,
    /** Audio sync offset in milliseconds (positive delays audio, negative advances audio) */
    val audioDelayMs: Long = 0L,
    /** Subtitle sync offset in milliseconds (positive delays subtitles, negative advances subtitles) */
    val subtitleDelayMs: Long = 0L,
    val externalSubtitleUri: Uri? = null,
    val abRepeat: ABRepeatState = ABRepeatState(),
    val sleepTimer: SleepTimerState = SleepTimerState(),
    val isTouchLocked: Boolean = false,
    val equalizer: EqualizerState = EqualizerState(),
    val hdrStatus: HdrPipelineStatus = HdrPipelineStatus(),
    val queue: List<MediaQueueItem> = emptyList(),
    val currentQueueIndex: Int = -1,
    val isInPipOrMiniPlayer: Boolean = false,
    val errorMessage: String? = null
) {
    val currentMedia: MediaQueueItem?
        get() = queue.getOrNull(currentQueueIndex)
}

