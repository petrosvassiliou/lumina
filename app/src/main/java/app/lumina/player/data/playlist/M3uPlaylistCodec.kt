package app.lumina.player.data.playlist

import app.lumina.player.data.local.entity.PlaylistEntryEntity
import java.util.Locale

data class ParsedM3uPlaylist(
    val playlistName: String?,
    val entries: List<PlaylistEntryEntity>
)

/**
 * Pure Kotlin M3U / Extended M3U (`.m3u`, `.m3u8`) parser, serializer, and file-type classifier
 * for Lumina custom playlists and Googlebook OS drag-and-drop.
 */
object M3uPlaylistCodec {

    private val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub", "ttml", "dfxp")
    private val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8")
    private val NETWORK_SCHEMES = setOf("http", "https", "rtsp", "rtsps", "smb", "ftp", "ftps", "upnp", "mms", "rtp", "udp")

    fun isSubtitleFileName(fileNameOrUri: String, mimeType: String? = null): Boolean {
        if (mimeType != null) {
            val lowerMime = mimeType.lowercase(Locale.ROOT)
            if (
                lowerMime == "application/x-subrip" ||
                lowerMime == "text/vtt" ||
                lowerMime == "text/x-ssa" ||
                lowerMime == "text/x-ass" ||
                lowerMime == "application/ttml+xml"
            ) {
                return true
            }
        }
        val clean = fileNameOrUri.substringBefore('?').substringBefore('#')
        val ext = clean.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        return ext in SUBTITLE_EXTENSIONS
    }

    fun isM3uPlaylistFile(
        fileNameOrUri: String,
        mimeType: String? = null,
        isRemoteHlsStream: Boolean = false
    ): Boolean {
        if (isRemoteHlsStream) return false
        if (mimeType != null) {
            val lowerMime = mimeType.lowercase(Locale.ROOT)
            if (
                lowerMime == "audio/x-mpegurl" ||
                lowerMime == "audio/mpegurl" ||
                lowerMime == "application/mpegurl" ||
                lowerMime == "application/x-mpegurl"
            ) {
                return true
            }
        }
        val clean = fileNameOrUri.substringBefore('?').substringBefore('#')
        val ext = clean.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        // Remote http(s)://.../*.m3u8 is typically an HLS live/VOD stream rather than a user file playlist
        val scheme = fileNameOrUri.substringBefore("://", missingDelimiterValue = "").lowercase(Locale.ROOT)
        if (ext == "m3u8" && (scheme == "http" || scheme == "https" || scheme == "rtsp")) {
            return false
        }
        return ext in PLAYLIST_EXTENSIONS
    }

    fun parseM3u(rawContent: String, fallbackPlaylistName: String? = null): ParsedM3uPlaylist {
        val cleaned = rawContent.removePrefix("\uFEFF")
        val lines = cleaned.lineSequence().map { it.trim() }.toList()

        var discoveredPlaylistTitle: String? = null
        var pendingDurationMs = 0L
        var pendingTitle: String? = null
        val entries = mutableListOf<PlaylistEntryEntity>()

        for (line in lines) {
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> Unit

                line.startsWith("#PLAYLIST:", ignoreCase = true) -> {
                    val candidate = line.substringAfter(':', "").trim()
                    if (candidate.isNotEmpty()) {
                        discoveredPlaylistTitle = candidate
                    }
                }

                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val payload = line.substringAfter(':', "")
                    val commaIdx = payload.indexOf(',')
                    if (commaIdx >= 0) {
                        val secToken = payload.substring(0, commaIdx).trim().substringBefore(' ')
                        val seconds = secToken.toDoubleOrNull() ?: -1.0
                        pendingDurationMs = if (seconds > 0.0) (seconds * 1_000.0).toLong() else 0L
                        pendingTitle = payload.substring(commaIdx + 1).trim().takeIf { it.isNotEmpty() }
                    } else {
                        val seconds = payload.trim().toDoubleOrNull() ?: -1.0
                        pendingDurationMs = if (seconds > 0.0) (seconds * 1_000.0).toLong() else 0L
                    }
                }

                line.startsWith("#") -> Unit

                else -> {
                    val uriStr = line
                    val scheme = uriStr.substringBefore("://", missingDelimiterValue = "").lowercase(Locale.ROOT)
                    val isNetwork = scheme in NETWORK_SCHEMES
                    val derivedTitle = pendingTitle ?: deriveReadableTitle(uriStr)
                    entries.add(
                        PlaylistEntryEntity(
                            playlistId = 0L,
                            mediaUri = uriStr,
                            title = derivedTitle,
                            subtitle = if (isNetwork) scheme.uppercase(Locale.ROOT) else null,
                            durationMs = pendingDurationMs,
                            mimeType = null,
                            isNetworkStream = isNetwork,
                            hdrBadge = null,
                            positionIndex = entries.size
                        )
                    )
                    pendingDurationMs = 0L
                    pendingTitle = null
                }
            }
        }

        return ParsedM3uPlaylist(
            playlistName = discoveredPlaylistTitle ?: fallbackPlaylistName,
            entries = entries
        )
    }

    fun serializeM3u(playlistName: String, entries: List<PlaylistEntryEntity>): String {
        return buildString {
            appendLine("#EXTM3U")
            val cleanName = playlistName.replace('\n', ' ').replace('\r', ' ').trim()
            if (cleanName.isNotEmpty()) {
                appendLine("#PLAYLIST:$cleanName")
            }
            for (entry in entries) {
                val durationSec = if (entry.durationMs > 0L) (entry.durationMs / 1_000L) else -1L
                val cleanTitle = entry.title.replace('\n', ' ').replace('\r', ' ').trim()
                    .ifEmpty { deriveReadableTitle(entry.mediaUri) }
                appendLine("#EXTINF:$durationSec,$cleanTitle")
                appendLine(entry.mediaUri)
            }
        }
    }

    fun deriveReadableTitle(uriString: String): String {
        val clean = uriString.substringBefore('?').substringBefore('#').trimEnd('/')
        val segment = clean.substringAfterLast('/').ifEmpty { uriString }
        val decoded = runCatching { java.net.URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)
        return decoded.substringBeforeLast('.', decoded).ifBlank { decoded }
    }
}
