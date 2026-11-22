package app.lumina.player.data.download

enum class DownloadStatus {
    QUEUED,
    PROBING,
    DOWNLOADING,
    MERGING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELED;

    val isActive: Boolean
        get() = this == QUEUED || this == PROBING || this == DOWNLOADING || this == MERGING

    val isResumable: Boolean
        get() = this == PAUSED || this == FAILED

    companion object {
        fun fromRaw(raw: String?): DownloadStatus {
            if (raw.isNullOrBlank()) return QUEUED
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: FAILED
        }
    }
}

data class DownloadSegment(
    val index: Int,
    val startByte: Long,
    val endByte: Long,
    val downloadedBytes: Long = 0L,
    val completed: Boolean = false
) {
    val expectedBytes: Long
        get() = if (endByte >= startByte && startByte >= 0L) (endByte - startByte + 1L) else -1L

    val progressFraction: Float
        get() = when {
            completed -> 1f
            expectedBytes > 0L -> (downloadedBytes.toFloat() / expectedBytes.toFloat()).coerceIn(0f, 1f)
            downloadedBytes > 0L -> 0.5f
            else -> 0f
        }
}

data class HlsVariantOption(
    val url: String,
    val bandwidth: Long,
    val resolution: String?,
    val frameRate: String?,
    val codecs: String?,
    val label: String
)

data class HlsEncryptionKeySpec(
    val method: String,
    val keyUrl: String?,
    val ivHex: String?
)

data class HlsSegmentSpec(
    val sequenceNumber: Long,
    val url: String,
    val durationSec: Double,
    val byteRangeOffset: Long? = null,
    val byteRangeLength: Long? = null,
    val encryptionKey: HlsEncryptionKeySpec? = null
)

sealed interface HlsParsedPlaylist {
    data class Master(
        val variants: List<HlsVariantOption>
    ) : HlsParsedPlaylist

    data class Media(
        val initMapUrl: String?,
        val segments: List<HlsSegmentSpec>,
        val hasEndList: Boolean,
        val targetDurationSec: Double,
        val initMapByteRangeOffset: Long? = null,
        val initMapByteRangeLength: Long? = null,
        val initMapEncryptionKey: HlsEncryptionKeySpec? = null
    ) : HlsParsedPlaylist
}

data class StreamProbeResult(
    val sourceUrl: String,
    val resolvedUrl: String,
    val suggestedFileName: String,
    val suggestedTitle: String,
    val mimeType: String,
    val totalBytes: Long,
    val acceptsRanges: Boolean,
    val isHlsStream: Boolean,
    val hlsVariants: List<HlsVariantOption> = emptyList(),
    val hlsSegmentCount: Int = 0
)

data class NewStreamDownloadRequest(
    val sourceUrl: String,
    val selectedStreamUrl: String = sourceUrl,
    val customTitle: String? = null,
    val customFileName: String? = null,
    val connections: Int = 8,
    val customHeaders: Map<String, String> = emptyMap(),
    val probeSnapshot: StreamProbeResult? = null
)

data class AdmDownloadItemUi(
    val id: Long,
    val sourceUrl: String,
    val resolvedUrl: String,
    val title: String,
    val fileName: String,
    val mimeType: String,
    val status: DownloadStatus,
    val isHlsStream: Boolean,
    val acceptsRanges: Boolean,
    val threadCount: Int,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val speedBytesPerSec: Long,
    val etaSeconds: Long,
    val overallProgress: Float,
    val segmentProgress: List<Float>,
    val exportedUri: String?,
    val errorMessage: String?,
    val createdAtEpochMs: Long
)
