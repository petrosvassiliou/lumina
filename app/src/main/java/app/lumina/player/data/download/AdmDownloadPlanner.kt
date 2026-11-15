package app.lumina.player.data.download

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * Pure JVM planner, HTTP header/filename normalizer, byte-range partitioner,
 * and HLS (.m3u8) Master/Media playlist parser for Lumina's ADM-style downloader.
 */
object AdmDownloadPlanner {

    const val MIN_ADM_CONNECTIONS = 1
    const val MAX_ADM_CONNECTIONS = 16
    const val DEFAULT_MIN_SEGMENT_BYTES = 262_144L // 256 KiB

    /**
     * Partitions [totalBytes] into `1..16` non-overlapping inclusive HTTP Range segments.
     * Falls back to a single unbounded segment when length is unknown (`<= 0L`).
     */
    fun planByteSegments(
        totalBytes: Long,
        requestedThreads: Int,
        minSegmentBytes: Long = DEFAULT_MIN_SEGMENT_BYTES
    ): List<DownloadSegment> {
        val clampedThreads = requestedThreads.coerceIn(MIN_ADM_CONNECTIONS, MAX_ADM_CONNECTIONS)
        if (totalBytes <= 0L) {
            return listOf(
                DownloadSegment(
                    index = 0,
                    startByte = 0L,
                    endByte = -1L,
                    downloadedBytes = 0L,
                    completed = false
                )
            )
        }

        val safeMinChunk = minSegmentBytes.coerceAtLeast(1L)
        val maxSensibleSegments = (totalBytes / safeMinChunk).coerceAtLeast(1L)
        val segmentCount = minOf(clampedThreads.toLong(), maxSensibleSegments, totalBytes).toInt()

        val baseChunkSize = totalBytes / segmentCount
        val remainder = totalBytes % segmentCount

        val segments = ArrayList<DownloadSegment>(segmentCount)
        var cursor = 0L
        for (idx in 0 until segmentCount) {
            val chunkLength = baseChunkSize + if (idx < remainder) 1L else 0L
            val endInclusive = cursor + chunkLength - 1L
            segments.add(
                DownloadSegment(
                    index = idx,
                    startByte = cursor,
                    endByte = endInclusive,
                    downloadedBytes = 0L,
                    completed = false
                )
            )
            cursor = endInclusive + 1L
        }
        return segments
    }

    fun serializeSegments(segments: List<DownloadSegment>): String {
        if (segments.isEmpty()) return ""
        return segments.joinToString(separator = ";") { seg ->
            "${seg.index}:${seg.startByte}:${seg.endByte}:${seg.downloadedBytes}:${if (seg.completed) 1 else 0}"
        }
    }

    fun deserializeSegments(encoded: String): List<DownloadSegment> {
        if (encoded.isBlank()) return emptyList()
        return encoded.split(';')
            .mapNotNull { token ->
                val parts = token.trim().split(':')
                if (parts.size < 5) return@mapNotNull null
                val index = parts[0].toIntOrNull() ?: return@mapNotNull null
                val startByte = parts[1].toLongOrNull() ?: return@mapNotNull null
                val endByte = parts[2].toLongOrNull() ?: return@mapNotNull null
                val downloaded = parts[3].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                val completed = parts[4] == "1"
                DownloadSegment(
                    index = index,
                    startByte = startByte,
                    endByte = endByte,
                    downloadedBytes = downloaded,
                    completed = completed
                )
            }
            .sortedBy { it.index }
    }

    fun parseCustomHeadersText(
        rawLines: String = "",
        referer: String = "",
        userAgent: String = "",
        authorization: String = ""
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        rawLines.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx <= 0) return@forEach
            val name = sanitizeHeaderToken(trimmed.substring(0, colonIdx))
            val value = sanitizeHeaderToken(trimmed.substring(colonIdx + 1))
            if (name.isNotEmpty() && value.isNotEmpty()) {
                result[name] = value
            }
        }
        val cleanReferer = sanitizeHeaderToken(referer)
        if (cleanReferer.isNotEmpty()) result["Referer"] = cleanReferer

        val cleanUa = sanitizeHeaderToken(userAgent)
        if (cleanUa.isNotEmpty()) result["User-Agent"] = cleanUa

        val cleanAuth = sanitizeHeaderToken(authorization)
        if (cleanAuth.isNotEmpty()) result["Authorization"] = cleanAuth

        return result
    }

    private fun sanitizeHeaderToken(input: String): String {
        return input.replace("\r", "").replace("\n", "").trim()
    }

    fun serializeHeaders(headers: Map<String, String>): String {
        if (headers.isEmpty()) return ""
        return headers.entries.joinToString("&") { (k, v) ->
            val ek = URLEncoder.encode(k, "UTF-8")
            val ev = URLEncoder.encode(v, "UTF-8")
            "$ek=$ev"
        }
    }

    fun deserializeHeaders(encoded: String): Map<String, String> {
        if (encoded.isBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        encoded.split('&').forEach { pair ->
            val eqIdx = pair.indexOf('=')
            if (eqIdx <= 0) return@forEach
            val key = runCatching { URLDecoder.decode(pair.substring(0, eqIdx), "UTF-8") }.getOrNull()
            val value = runCatching { URLDecoder.decode(pair.substring(eqIdx + 1), "UTF-8") }.getOrNull()
            if (!key.isNullOrBlank() && value != null) {
                map[key] = value
            }
        }
        return map
    }

    /**
     * Parses total resource size from an HTTP `Content-Range` response header, e.g.:
     * `bytes 0-0/104857600` -> `104857600L`.
     */
    fun parseContentRangeTotalBytes(contentRangeHeader: String?): Long {
        if (contentRangeHeader.isNullOrBlank()) return -1L
        val slashIdx = contentRangeHeader.lastIndexOf('/')
        if (slashIdx < 0 || slashIdx >= contentRangeHeader.length - 1) return -1L
        val totalPart = contentRangeHeader.substring(slashIdx + 1).trim()
        if (totalPart == "*") return -1L
        return totalPart.toLongOrNull()?.takeIf { it > 0L } ?: -1L
    }

    fun isLikelyHlsStream(url: String, contentType: String?): Boolean {
        val cleanPath = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        if (cleanPath.endsWith(".m3u8")) return true
        val lowerMime = contentType?.lowercase(Locale.ROOT).orEmpty()
        return lowerMime.contains("application/vnd.apple.mpegurl") ||
            lowerMime.contains("application/x-mpegurl") ||
            lowerMime.contains("audio/mpegurl")
    }

    fun isDownloadableStreamProtocol(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val scheme = url.trim().substringBefore("://", "").lowercase(Locale.ROOT)
        return scheme == "http" || scheme == "https" || scheme == "ftp"
    }

    fun extractFirstStreamUrlFromSharedText(sharedText: String?): String? {
        if (sharedText.isNullOrBlank()) return null
        val regex = Regex("""(?i)\b(?:https?|ftp)://[^\s<>"']+""")
        val match = regex.find(sharedText)?.value ?: return null
        return match.trimEnd('.', ',', ';', ')', ']')
    }

    fun extractSuggestedFileName(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        isHls: Boolean = false,
        hasFmp4InitSegment: Boolean = false
    ): String {
        val fromHeader = parseContentDispositionFileName(contentDisposition)
        val fromUrl = runCatching {
            val cleanUrl = url.substringBefore('?').substringBefore('#')
            val rawLast = cleanUrl.substringAfterLast('/')
            URLDecoder.decode(rawLast, "UTF-8")
        }.getOrNull()?.takeIf { it.isNotBlank() }

        var candidate = sanitizeFileName(fromHeader ?: fromUrl ?: "Lumina_Stream")
        if (candidate.isBlank() || candidate == "." || candidate == "..") {
            candidate = "Lumina_Stream"
        }

        val currentExt = candidate.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (isHls) {
            val base = if (currentExt == "m3u8" || currentExt == "m3u") {
                candidate.substringBeforeLast('.')
            } else if (currentExt.isEmpty()) {
                candidate
            } else {
                candidate.substringBeforeLast('.')
            }
            val targetExt = if (hasFmp4InitSegment) "mp4" else "ts"
            return "${base.ifBlank { "Lumina_HLS_Stream" }}.$targetExt"
        }

        if (currentExt.isEmpty() || currentExt.length > 5) {
            val inferredExt = inferExtensionFromMime(mimeType) ?: "mp4"
            return "$candidate.$inferredExt"
        }
        return candidate
    }

    fun inferMimeTypeForFileName(fileName: String, fallbackMime: String? = null): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "ts", "m2ts", "mts" -> "video/mp2t"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a", "aac" -> "audio/mp4"
            "ogg", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> fallbackMime?.substringBefore(';')?.trim()?.takeIf {
                it.startsWith("video/", ignoreCase = true) || it.startsWith("audio/", ignoreCase = true)
            } ?: "video/mp4"
        }
    }

    private fun parseContentDispositionFileName(header: String?): String? {
        if (header.isNullOrBlank()) return null
        // 1. RFC 5987 filename*=UTF-8''...
        val rfc5987 = Regex("""(?i)filename\*\s*=\s*([^;]+)""").find(header)?.groupValues?.get(1)?.trim()
        if (!rfc5987.isNullOrBlank()) {
            val unquoted = rfc5987.trim('"')
            val encodedPart = unquoted.substringAfter("''", unquoted)
            val decoded = runCatching { URLDecoder.decode(encodedPart, "UTF-8") }.getOrNull()
            if (!decoded.isNullOrBlank()) return decoded
        }
        // 2. Quoted filename="..."
        val quoted = Regex("""(?i)filename\s*=\s*"([^"]+)"""").find(header)?.groupValues?.get(1)?.trim()
        if (!quoted.isNullOrBlank()) return quoted
        // 3. Unquoted filename=...
        val plain = Regex("""(?i)filename\s*=\s*([^;\s]+)""").find(header)?.groupValues?.get(1)?.trim()
        return plain?.trim('"')?.takeIf { it.isNotBlank() }
    }

    private fun inferExtensionFromMime(mimeType: String?): String? {
        val clean = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT) ?: return null
        return when (clean) {
            "video/mp4", "application/mp4" -> "mp4"
            "video/x-matroska" -> "mkv"
            "video/webm" -> "webm"
            "video/mp2t" -> "ts"
            "video/quicktime" -> "mov"
            "video/x-msvideo" -> "avi"
            "audio/mpeg" -> "mp3"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/mp4", "audio/aac" -> "m4a"
            "audio/ogg", "audio/opus" -> "ogg"
            "audio/wav", "audio/x-wav" -> "wav"
            else -> null
        }
    }

    fun sanitizeFileName(raw: String): String {
        val cleaned = raw
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('.')
        return if (cleaned.length > 180) {
            val ext = cleaned.substringAfterLast('.', "")
            val stem = cleaned.substringBeforeLast('.', cleaned).take(165)
            if (ext.isNotEmpty() && ext.length <= 8) "$stem.$ext" else stem
        } else {
            cleaned
        }
    }

    fun resolveRelativeUrl(baseUrl: String, reference: String): String {
        val trimmed = reference.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("ftp://", ignoreCase = true)
        ) {
            return trimmed
        }
        return runCatching {
            URI(baseUrl.trim()).resolve(trimmed).toString()
        }.getOrElse {
            val baseDir = baseUrl.substringBefore('?').substringBeforeLast('/', baseUrl)
            if (trimmed.startsWith("/")) {
                val originMatch = Regex("""^(https?://[^/]+)""", RegexOption.IGNORE_CASE)
                    .find(baseUrl)?.groupValues?.get(1)
                if (originMatch != null) "$originMatch$trimmed" else "$baseDir$trimmed"
            } else {
                "$baseDir/$trimmed"
            }
        }
    }

    /**
     * Parses either an HLS Master Playlist (`#EXT-X-STREAM-INF`) or Media Playlist (`#EXTINF`).
     */
    fun parseHlsPlaylist(playlistText: String, basePlaylistUrl: String): HlsParsedPlaylist {
        val lines = playlistText.lineSequence().map { it.trim() }.toList()
        val isMaster = lines.any { it.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) }
        if (isMaster) {
            val variants = mutableListOf<HlsVariantOption>()
            var idx = 0
            while (idx < lines.size) {
                val line = lines[idx]
                if (line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) {
                    val attrs = parseHlsAttributeList(line.substringAfter(':'))
                    val bandwidth = attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull()
                        ?: attrs["BANDWIDTH"]?.toLongOrNull()
                        ?: 0L
                    val resolution = attrs["RESOLUTION"]
                    val frameRate = attrs["FRAME-RATE"]
                    val codecs = attrs["CODECS"]

                    var uriIdx = idx + 1
                    while (uriIdx < lines.size && (lines[uriIdx].isEmpty() || lines[uriIdx].startsWith("#"))) {
                        uriIdx++
                    }
                    if (uriIdx < lines.size) {
                        val resolvedUrl = resolveRelativeUrl(basePlaylistUrl, lines[uriIdx])
                        variants.add(
                            HlsVariantOption(
                                url = resolvedUrl,
                                bandwidth = bandwidth,
                                resolution = resolution,
                                frameRate = frameRate,
                                codecs = codecs,
                                label = buildVariantLabel(resolution, frameRate, bandwidth)
                            )
                        )
                        idx = uriIdx
                    }
                }
                idx++
            }
            val sorted = variants
                .distinctBy { it.url }
                .sortedByDescending { it.bandwidth }
            return HlsParsedPlaylist.Master(sorted)
        }

        // Media playlist
        var mediaSequence = 0L
        var targetDuration = 0.0
        var hasEndList = false
        var initMapUrl: String? = null
        var activeKey: HlsEncryptionKeySpec? = null
        var pendingDuration = 0.0
        var pendingByteRangeLength: Long? = null
        var pendingByteRangeOffset: Long? = null
        var lastByteRangeEnd = 0L

        val segments = mutableListOf<HlsSegmentSpec>()
        var segmentCounter = 0L

        for (line in lines) {
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:", ignoreCase = true) -> {
                    mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                }
                line.startsWith("#EXT-X-TARGETDURATION:", ignoreCase = true) -> {
                    targetDuration = line.substringAfter(':').trim().toDoubleOrNull() ?: 0.0
                }
                line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    val attrs = parseHlsAttributeList(line.substringAfter(':'))
                    val mapUri = attrs["URI"]
                    if (!mapUri.isNullOrBlank()) {
                        initMapUrl = resolveRelativeUrl(basePlaylistUrl, mapUri)
                    }
                }
                line.startsWith("#EXT-X-KEY:", ignoreCase = true) -> {
                    val attrs = parseHlsAttributeList(line.substringAfter(':'))
                    val method = attrs["METHOD"]?.uppercase(Locale.ROOT) ?: "NONE"
                    activeKey = if (method == "NONE") {
                        null
                    } else {
                        val keyUri = attrs["URI"]?.let { resolveRelativeUrl(basePlaylistUrl, it) }
                        HlsEncryptionKeySpec(
                            method = method,
                            keyUrl = keyUri,
                            ivHex = attrs["IV"]
                        )
                    }
                }
                line.startsWith("#EXT-X-BYTERANGE:", ignoreCase = true) -> {
                    val spec = line.substringAfter(':').trim()
                    val lenStr = spec.substringBefore('@')
                    val offStr = spec.substringAfter('@', "")
                    val len = lenStr.toLongOrNull()
                    if (len != null && len > 0L) {
                        val offset = offStr.toLongOrNull() ?: lastByteRangeEnd
                        pendingByteRangeLength = len
                        pendingByteRangeOffset = offset
                        lastByteRangeEnd = offset + len
                    }
                }
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val durPart = line.substringAfter(':').substringBefore(',').trim()
                    pendingDuration = durPart.toDoubleOrNull() ?: 0.0
                }
                line.equals("#EXT-X-ENDLIST", ignoreCase = true) -> {
                    hasEndList = true
                }
                !line.startsWith("#") -> {
                    val seq = mediaSequence + segmentCounter
                    segments.add(
                        HlsSegmentSpec(
                            sequenceNumber = seq,
                            url = resolveRelativeUrl(basePlaylistUrl, line),
                            durationSec = pendingDuration,
                            byteRangeOffset = pendingByteRangeOffset,
                            byteRangeLength = pendingByteRangeLength,
                            encryptionKey = activeKey
                        )
                    )
                    segmentCounter++
                    pendingDuration = 0.0
                    pendingByteRangeOffset = null
                    pendingByteRangeLength = null
                }
            }
        }

        return HlsParsedPlaylist.Media(
            initMapUrl = initMapUrl,
            segments = segments,
            hasEndList = hasEndList,
            targetDurationSec = targetDuration
        )
    }

    private fun parseHlsAttributeList(rawAttrs: String): Map<String, String> {
        val attrs = LinkedHashMap<String, String>()
        val regex = Regex("""([A-Z0-9\-]+)\s*=\s*("([^"]*)"|[^,]*)""", RegexOption.IGNORE_CASE)
        regex.findAll(rawAttrs).forEach { match ->
            val key = match.groupValues[1].uppercase(Locale.ROOT)
            val rawVal = match.groupValues[2].trim()
            val cleanVal = if (rawVal.startsWith("\"") && rawVal.endsWith("\"") && rawVal.length >= 2) {
                rawVal.substring(1, rawVal.length - 1)
            } else {
                rawVal
            }
            attrs[key] = cleanVal
        }
        return attrs
    }

    private fun buildVariantLabel(resolution: String?, frameRate: String?, bandwidth: Long): String {
        val heightLabel = resolution?.substringAfterLast('x', "")?.toIntOrNull()?.let { "${it}p" }
            ?: resolution
        val fpsLabel = frameRate?.toDoubleOrNull()?.let { fps ->
            if (fps >= 48.0) "${fps.toInt()}fps" else null
        }
        val mbpsLabel = if (bandwidth > 0L) {
            String.format(Locale.US, "%.1f Mbps", bandwidth / 1_000_000.0)
        } else {
            null
        }
        val prefix = listOfNotNull(heightLabel, fpsLabel).joinToString(" • ").ifBlank { "Auto Stream" }
        return if (mbpsLabel != null) "$prefix ($mbpsLabel)" else prefix
    }

    fun computeEtaSeconds(remainingBytes: Long, speedBytesPerSec: Long): Long {
        if (remainingBytes <= 0L || speedBytesPerSec <= 0L) return -1L
        return (remainingBytes / speedBytesPerSec).coerceAtLeast(1L)
    }
}
