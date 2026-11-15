package app.lumina.player.data.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.lumina.player.data.local.dao.DownloadDao
import app.lumina.player.data.local.entity.DownloadTaskEntity
import app.lumina.player.data.preferences.LuminaPreferences
import app.lumina.player.data.repository.MediaRepository
import app.lumina.player.di.ApplicationScope
import app.lumina.player.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * ADM-style multi-connection stream & media download engine for Lumina.
 *
 * Capabilities:
 * - Parallel HTTP/HTTPS `Range` segmented downloads (`1..16` concurrent connections) writing via
 *   positional [java.nio.channels.FileChannel] (`RandomAccessFile`) with zero merge overhead.
 * - Automatic single-connection fallback when servers ignore `Range` (`200 OK`) or stream over FTP.
 * - Parallel HLS (`.m3u8`) downloader supporting Master variant selection, fMP4 `#EXT-X-MAP`,
 *   and `#EXT-X-KEY:METHOD=AES-128` decryption with resumable per-segment caching.
 * - Scoped Storage export into `Downloads/Lumina` via `MediaStore.Downloads` + automatic indexing
 *   into Lumina's Library (`Lumina Downloads`).
 */
@Singleton
class LuminaStreamDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
    private val mediaRepository: MediaRepository,
    private val preferences: LuminaPreferences,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private data class LiveTaskTelemetry(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long,
        val segmentProgress: List<Float>
    )

    private val activeJobs = ConcurrentHashMap<Long, Job>()
    private val pauseRequestedIds = ConcurrentHashMap.newKeySet<Long>()
    private val cancelRequestedIds = ConcurrentHashMap.newKeySet<Long>()
    private val schedulerMutex = Mutex()
    private val liveTelemetryFlow = MutableStateFlow<Map<Long, LiveTaskTelemetry>>(emptyMap())

    private val stagingRootDir: File by lazy {
        File(context.noBackupFilesDir, "adm_staging").apply { mkdirs() }
    }

    val downloadsUiFlow: Flow<List<AdmDownloadItemUi>> = combine(
        downloadDao.observeAllDownloads(),
        liveTelemetryFlow
    ) { entities, telemetryMap ->
        entities.map { entity ->
            val status = DownloadStatus.fromRaw(entity.status)
            val live = telemetryMap[entity.id]
            val downloadedBytes = if (status.isActive && live != null) {
                maxOf(entity.downloadedBytes, live.downloadedBytes)
            } else {
                entity.downloadedBytes
            }
            val totalBytes = if (live != null && live.totalBytes > 0L) {
                live.totalBytes
            } else {
                entity.totalBytes
            }
            val speed = if (status == DownloadStatus.DOWNLOADING) {
                live?.speedBytesPerSec ?: 0L
            } else {
                0L
            }
            val remaining = if (totalBytes > downloadedBytes) totalBytes - downloadedBytes else -1L
            val etaSec = if (status == DownloadStatus.DOWNLOADING) {
                AdmDownloadPlanner.computeEtaSeconds(remaining, speed)
            } else {
                -1L
            }

            val storedSegments = AdmDownloadPlanner.deserializeSegments(entity.segmentsJson)
            val segFractions = when {
                status == DownloadStatus.COMPLETED -> {
                    val count = entity.threadCount.coerceIn(1, 16)
                    List(count) { 1f }
                }
                live != null && live.segmentProgress.isNotEmpty() -> live.segmentProgress
                storedSegments.isNotEmpty() -> storedSegments.map { it.progressFraction }
                else -> List(entity.threadCount.coerceIn(1, 16)) { 0f }
            }

            val overallProgress = when {
                status == DownloadStatus.COMPLETED -> 1f
                totalBytes > 0L -> (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                segFractions.isNotEmpty() -> segFractions.average().toFloat().coerceIn(0f, 1f)
                else -> 0f
            }

            AdmDownloadItemUi(
                id = entity.id,
                sourceUrl = entity.sourceUrl,
                resolvedUrl = entity.resolvedUrl,
                title = entity.title,
                fileName = entity.fileName,
                mimeType = entity.mimeType,
                status = status,
                isHlsStream = entity.isHlsStream,
                acceptsRanges = entity.acceptsRanges,
                threadCount = entity.threadCount.coerceIn(1, 16),
                totalBytes = totalBytes,
                downloadedBytes = downloadedBytes,
                speedBytesPerSec = speed,
                etaSeconds = etaSec,
                overallProgress = overallProgress,
                segmentProgress = segFractions,
                exportedUri = entity.exportedUri,
                errorMessage = entity.errorMessage,
                createdAtEpochMs = entity.createdAtEpochMs
            )
        }
    }

    init {
        appScope.launch(ioDispatcher) {
            reconcileInterruptedDownloadsOnBoot()
            pumpQueue()
        }
    }

    private suspend fun reconcileInterruptedDownloadsOnBoot() {
        val existing = downloadDao.getAllDownloadsChronological()
        val now = System.currentTimeMillis()
        for (task in existing) {
            val status = DownloadStatus.fromRaw(task.status)
            if (status == DownloadStatus.DOWNLOADING ||
                status == DownloadStatus.PROBING ||
                status == DownloadStatus.MERGING
            ) {
                downloadDao.updateDownload(
                    task.copy(
                        status = DownloadStatus.PAUSED.name,
                        updatedAtEpochMs = now
                    )
                )
            }
        }
    }

    /**
     * Inspects a stream URL before or during enqueue: resolves redirects, checks `Range: bytes=0-0`
     * support, extracts filename & total size, and parses HLS `.m3u8` Master variants if applicable.
     */
    suspend fun probeStream(
        url: String,
        customHeaders: Map<String, String> = emptyMap()
    ): Result<StreamProbeResult> = withContext(ioDispatcher) {
        runCatching {
            val cleanUrl = url.trim()
            require(AdmDownloadPlanner.isDownloadableStreamProtocol(cleanUrl)) {
                "Unsupported download protocol. Supported: HTTP, HTTPS, HLS (.m3u8), FTP."
            }

            if (cleanUrl.startsWith("ftp://", ignoreCase = true)) {
                val conn = URL(cleanUrl).openConnection().apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                }
                val len = conn.contentLengthLong
                val fileName = AdmDownloadPlanner.extractSuggestedFileName(
                    url = cleanUrl,
                    contentDisposition = null,
                    mimeType = conn.contentType,
                    isHls = false
                )
                val mime = AdmDownloadPlanner.inferMimeTypeForFileName(fileName, conn.contentType)
                return@runCatching StreamProbeResult(
                    sourceUrl = cleanUrl,
                    resolvedUrl = cleanUrl,
                    suggestedFileName = fileName,
                    suggestedTitle = fileName.substringBeforeLast('.', fileName),
                    mimeType = mime,
                    totalBytes = if (len > 0L) len else -1L,
                    acceptsRanges = false,
                    isHlsStream = false
                )
            }

            // Probe via GET with Range: bytes=0-0 (more reliable than HEAD on signed CDNs and S3 URLs)
            val probeHeaders = LinkedHashMap(customHeaders).apply {
                putIfAbsent("User-Agent", DEFAULT_USER_AGENT)
                put("Range", "bytes=0-0")
            }

            val (conn, resolvedUrl) = openHttpConnectionWithRedirects(
                initialUrl = cleanUrl,
                method = "GET",
                headers = probeHeaders
            )

            try {
                val code = conn.responseCode
                if (code >= 400 && code != HttpURLConnection.HTTP_PARTIAL && code != 416) {
                    throw IOException("Server returned HTTP $code ${conn.responseMessage.orEmpty()}".trim())
                }

                val contentType = conn.contentType?.substringBefore(';')?.trim()
                val contentDisposition = conn.getHeaderField("Content-Disposition")
                val acceptRangesHdr = conn.getHeaderField("Accept-Ranges")
                val contentRangeHdr = conn.getHeaderField("Content-Range")

                val isHls = AdmDownloadPlanner.isLikelyHlsStream(resolvedUrl, contentType) ||
                    AdmDownloadPlanner.isLikelyHlsStream(cleanUrl, contentType)

                if (isHls) {
                    conn.disconnect()
                    val playlistText = fetchTextResource(resolvedUrl, customHeaders)
                    val parsed = AdmDownloadPlanner.parseHlsPlaylist(playlistText, resolvedUrl)
                    val variants = (parsed as? HlsParsedPlaylist.Master)?.variants.orEmpty()
                    val mediaPlan = parsed as? HlsParsedPlaylist.Media
                    val hasFmp4 = mediaPlan?.initMapUrl != null
                    val fileName = AdmDownloadPlanner.extractSuggestedFileName(
                        url = cleanUrl,
                        contentDisposition = contentDisposition,
                        mimeType = if (hasFmp4) "video/mp4" else "video/mp2t",
                        isHls = true,
                        hasFmp4InitSegment = hasFmp4
                    )
                    return@runCatching StreamProbeResult(
                        sourceUrl = cleanUrl,
                        resolvedUrl = variants.firstOrNull()?.url ?: resolvedUrl,
                        suggestedFileName = fileName,
                        suggestedTitle = fileName.substringBeforeLast('.', fileName),
                        mimeType = if (hasFmp4) "video/mp4" else "video/mp2t",
                        totalBytes = -1L,
                        acceptsRanges = true,
                        isHlsStream = true,
                        hlsVariants = variants,
                        hlsSegmentCount = mediaPlan?.segments?.size ?: 0
                    )
                }

                val acceptsRanges = code == HttpURLConnection.HTTP_PARTIAL ||
                    acceptRangesHdr?.contains("bytes", ignoreCase = true) == true
                val rangeTotal = AdmDownloadPlanner.parseContentRangeTotalBytes(contentRangeHdr)
                val headerLen = conn.contentLengthLong
                val totalBytes = when {
                    rangeTotal > 0L -> rangeTotal
                    code == HttpURLConnection.HTTP_OK && headerLen > 0L -> headerLen
                    else -> -1L
                }

                // If server ignored Range and returned 200 without Content-Length, check HEAD once
                val finalTotal = if (totalBytes <= 0L && acceptsRanges) {
                    probeContentLengthViaHead(resolvedUrl, customHeaders)
                } else {
                    totalBytes
                }

                val fileName = AdmDownloadPlanner.extractSuggestedFileName(
                    url = resolvedUrl,
                    contentDisposition = contentDisposition,
                    mimeType = contentType,
                    isHls = false
                )
                val mime = AdmDownloadPlanner.inferMimeTypeForFileName(fileName, contentType)

                StreamProbeResult(
                    sourceUrl = cleanUrl,
                    resolvedUrl = resolvedUrl,
                    suggestedFileName = fileName,
                    suggestedTitle = fileName.substringBeforeLast('.', fileName),
                    mimeType = mime,
                    totalBytes = finalTotal,
                    acceptsRanges = acceptsRanges && finalTotal > 0L,
                    isHlsStream = false
                )
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun probeContentLengthViaHead(url: String, customHeaders: Map<String, String>): Long {
        return runCatching {
            val headers = LinkedHashMap(customHeaders).apply {
                putIfAbsent("User-Agent", DEFAULT_USER_AGENT)
            }
            val (conn, _) = openHttpConnectionWithRedirects(url, "HEAD", headers)
            try {
                conn.contentLengthLong.takeIf { it > 0L } ?: -1L
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(-1L)
    }

    suspend fun enqueueDownload(request: NewStreamDownloadRequest): Long = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        val sourceUrl = request.sourceUrl.trim()
        val selectedUrl = request.selectedStreamUrl.trim().ifBlank { sourceUrl }
        val probe = request.probeSnapshot
        val isHls = probe?.isHlsStream ?: AdmDownloadPlanner.isLikelyHlsStream(selectedUrl, null)

        val fallbackName = probe?.suggestedFileName ?: AdmDownloadPlanner.extractSuggestedFileName(
            url = selectedUrl,
            contentDisposition = null,
            mimeType = null,
            isHls = isHls
        )
        val rawFileName = request.customFileName?.trim()?.takeIf { it.isNotBlank() } ?: fallbackName
        val safeFileName = ensureFileExtension(AdmDownloadPlanner.sanitizeFileName(rawFileName), fallbackName)
        val cleanTitle = request.customTitle?.trim()?.takeIf { it.isNotBlank() }
            ?: safeFileName.substringBeforeLast('.', safeFileName)
        val mime = AdmDownloadPlanner.inferMimeTypeForFileName(safeFileName, probe?.mimeType)

        val connections = request.connections.coerceIn(
            AdmDownloadPlanner.MIN_ADM_CONNECTIONS,
            AdmDownloadPlanner.MAX_ADM_CONNECTIONS
        )
        val totalBytes = if (!isHls && selectedUrl == probe?.resolvedUrl) probe.totalBytes else -1L
        val acceptsRanges = if (!isHls && selectedUrl == probe?.resolvedUrl) probe.acceptsRanges else false

        val initialSegments = if (!isHls && acceptsRanges && totalBytes > 0L) {
            AdmDownloadPlanner.planByteSegments(totalBytes, connections)
        } else {
            emptyList()
        }

        val tempPlaceholder = File(stagingRootDir, "pending_${now}.part")
        val entity = DownloadTaskEntity(
            sourceUrl = sourceUrl,
            resolvedUrl = selectedUrl,
            title = cleanTitle,
            fileName = safeFileName,
            mimeType = mime,
            status = DownloadStatus.QUEUED.name,
            isHlsStream = isHls,
            acceptsRanges = acceptsRanges,
            threadCount = if (initialSegments.isNotEmpty()) initialSegments.size else connections,
            totalBytes = totalBytes,
            downloadedBytes = 0L,
            segmentsJson = AdmDownloadPlanner.serializeSegments(initialSegments),
            headersJson = AdmDownloadPlanner.serializeHeaders(request.customHeaders),
            stagingFilePath = tempPlaceholder.absolutePath,
            exportedUri = null,
            errorMessage = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )
        val insertedId = downloadDao.insertDownload(entity)
        val finalStagingFile = File(stagingRootDir, "download_${insertedId}.part")
        downloadDao.updateDownload(
            entity.copy(
                id = insertedId,
                stagingFilePath = finalStagingFile.absolutePath
            )
        )
        pumpQueue()
        insertedId
    }

    private fun ensureFileExtension(fileName: String, referenceName: String): String {
        val hasExt = fileName.contains('.') && fileName.substringAfterLast('.').length in 1..6
        if (hasExt) return fileName
        val refExt = referenceName.substringAfterLast('.', "mp4")
        return "$fileName.$refExt"
    }

    fun pauseDownload(downloadId: Long) {
        appScope.launch(ioDispatcher) {
            pauseRequestedIds.add(downloadId)
            val job = activeJobs[downloadId]
            if (job != null) {
                job.cancel()
            } else {
                val task = downloadDao.getDownloadById(downloadId) ?: return@launch
                val status = DownloadStatus.fromRaw(task.status)
                if (status == DownloadStatus.QUEUED || status == DownloadStatus.PROBING) {
                    downloadDao.updateDownload(
                        task.copy(
                            status = DownloadStatus.PAUSED.name,
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                    )
                }
                pauseRequestedIds.remove(downloadId)
            }
        }
    }

    fun resumeDownload(downloadId: Long) {
        appScope.launch(ioDispatcher) {
            pauseRequestedIds.remove(downloadId)
            cancelRequestedIds.remove(downloadId)
            val task = downloadDao.getDownloadById(downloadId) ?: return@launch
            val status = DownloadStatus.fromRaw(task.status)
            if (status == DownloadStatus.COMPLETED || activeJobs.containsKey(downloadId)) return@launch
            downloadDao.updateDownload(
                task.copy(
                    status = DownloadStatus.QUEUED.name,
                    errorMessage = null,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
            pumpQueue()
        }
    }

    fun retryDownload(downloadId: Long) {
        resumeDownload(downloadId)
    }

    fun cancelAndDeleteDownload(downloadId: Long, deleteExportedFile: Boolean = false) {
        appScope.launch(ioDispatcher) {
            cancelRequestedIds.add(downloadId)
            activeJobs[downloadId]?.cancel()
            val task = downloadDao.getDownloadById(downloadId)
            if (task != null) {
                cleanupStagingArtifacts(task)
                if (deleteExportedFile && !task.exportedUri.isNullOrBlank()) {
                    runCatching {
                        val uri = Uri.parse(task.exportedUri)
                        if (uri.scheme == "content") {
                            context.contentResolver.delete(uri, null, null)
                        } else if (uri.scheme == "file") {
                            uri.path?.let { File(it).delete() }
                        }
                    }
                }
            }
            removeLiveTelemetry(downloadId)
            downloadDao.deleteDownloadById(downloadId)
            cancelRequestedIds.remove(downloadId)
            pauseRequestedIds.remove(downloadId)
            pumpQueue()
        }
    }

    fun clearCompletedDownloads() {
        appScope.launch(ioDispatcher) {
            downloadDao.clearCompletedDownloads()
        }
    }

    private suspend fun pumpQueue() {
        schedulerMutex.withLock {
            val prefs = preferences.userPreferencesFlow.first()
            val maxConcurrent = prefs.admMaxConcurrentDownloads.coerceIn(1, 4)

            activeJobs.entries.removeIf { !it.value.isActive }
            val availableSlots = (maxConcurrent - activeJobs.size).coerceAtLeast(0)
            if (availableSlots == 0) return

            val queuedTasks = downloadDao.getAllDownloadsChronological()
                .filter {
                    DownloadStatus.fromRaw(it.status) == DownloadStatus.QUEUED &&
                        !activeJobs.containsKey(it.id)
                }
                .take(availableSlots)

            for (task in queuedTasks) {
                val job = appScope.launch(ioDispatcher + SupervisorJob()) {
                    executeDownloadTask(task.id)
                }
                activeJobs[task.id] = job
                job.invokeOnCompletion {
                    activeJobs.remove(task.id)
                    appScope.launch(ioDispatcher) {
                        pumpQueue()
                    }
                }
            }
        }
    }

    private suspend fun executeDownloadTask(downloadId: Long) {
        var current = downloadDao.getDownloadById(downloadId) ?: return
        val customHeaders = AdmDownloadPlanner.deserializeHeaders(current.headersJson)

        try {
            current = current.copy(
                status = DownloadStatus.PROBING.name,
                errorMessage = null,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            downloadDao.updateDownload(current)

            // Probe if metadata not yet resolved
            val needsProbe = !current.isHlsStream &&
                current.totalBytes <= 0L &&
                AdmDownloadPlanner.deserializeSegments(current.segmentsJson).isEmpty()

            if (needsProbe) {
                val probe = probeStream(current.resolvedUrl, customHeaders).getOrNull()
                if (probe != null) {
                    val updatedSegments = if (!probe.isHlsStream && probe.acceptsRanges && probe.totalBytes > 0L) {
                        AdmDownloadPlanner.planByteSegments(probe.totalBytes, current.threadCount)
                    } else if (!probe.isHlsStream) {
                        AdmDownloadPlanner.planByteSegments(-1L, 1)
                    } else {
                        emptyList()
                    }
                    current = current.copy(
                        resolvedUrl = probe.resolvedUrl,
                        isHlsStream = probe.isHlsStream,
                        acceptsRanges = probe.acceptsRanges,
                        totalBytes = probe.totalBytes,
                        threadCount = if (updatedSegments.isNotEmpty()) updatedSegments.size else current.threadCount,
                        segmentsJson = AdmDownloadPlanner.serializeSegments(updatedSegments),
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                    downloadDao.updateDownload(current)
                }
            }

            if (current.isHlsStream) {
                executeHlsDownload(current, customHeaders)
            } else {
                executeHttpOrFtpDownload(current, customHeaders)
            }
        } catch (ce: CancellationException) {
            handleTaskCancellation(downloadId)
            throw ce
        } catch (t: Throwable) {
            if (cancelRequestedIds.contains(downloadId) || pauseRequestedIds.contains(downloadId)) {
                handleTaskCancellation(downloadId)
            } else {
                val latest = downloadDao.getDownloadById(downloadId)
                if (latest != null) {
                    downloadDao.updateDownload(
                        latest.copy(
                            status = DownloadStatus.FAILED.name,
                            errorMessage = t.message?.take(240) ?: "Stream connection interrupted",
                            updatedAtEpochMs = System.currentTimeMillis()
                        )
                    )
                }
                removeLiveTelemetry(downloadId)
            }
        }
    }

    private suspend fun handleTaskCancellation(downloadId: Long) {
        removeLiveTelemetry(downloadId)
        if (cancelRequestedIds.remove(downloadId)) {
            val latest = downloadDao.getDownloadById(downloadId)
            if (latest != null) cleanupStagingArtifacts(latest)
            downloadDao.deleteDownloadById(downloadId)
            pauseRequestedIds.remove(downloadId)
            return
        }
        val isPaused = pauseRequestedIds.remove(downloadId)
        val latest = downloadDao.getDownloadById(downloadId) ?: return
        if (isPaused || DownloadStatus.fromRaw(latest.status).isActive) {
            downloadDao.updateDownload(
                latest.copy(
                    status = DownloadStatus.PAUSED.name,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun executeHttpOrFtpDownload(
        initialEntity: DownloadTaskEntity,
        customHeaders: Map<String, String>
    ) = coroutineScope {
        val stagingFile = File(initialEntity.stagingFilePath).apply {
            parentFile?.mkdirs()
        }

        var segments = AdmDownloadPlanner.deserializeSegments(initialEntity.segmentsJson)
            .toMutableList()
        if (segments.isEmpty()) {
            val planned = if (initialEntity.acceptsRanges && initialEntity.totalBytes > 0L) {
                AdmDownloadPlanner.planByteSegments(initialEntity.totalBytes, initialEntity.threadCount)
            } else {
                AdmDownloadPlanner.planByteSegments(-1L, 1)
            }
            segments = planned.toMutableList()
        }

        // If staging file was wiped externally, reset segment offsets
        if (!stagingFile.exists() && segments.any { it.downloadedBytes > 0L || it.completed }) {
            segments = segments.map { it.copy(downloadedBytes = 0L, completed = false) }.toMutableList()
        }

        val multiPartActive = initialEntity.acceptsRanges &&
            initialEntity.totalBytes > 0L &&
            segments.size > 1

        if (!multiPartActive && segments.size > 1) {
            segments = mutableListOf(
                DownloadSegment(index = 0, startByte = 0L, endByte = initialEntity.totalBytes - 1L)
            )
        }

        val segmentDownloaded = AtomicLongArrayCompat(segments.size).apply {
            segments.forEachIndexed { idx, seg -> set(idx, seg.downloadedBytes) }
        }
        val segmentCompleted = Array(segments.size) { idx -> AtomicBoolean(segments[idx].completed) }
        val totalBytesRef = AtomicLong(initialEntity.totalBytes)
        val fallbackSingleStreamTriggered = AtomicBoolean(false)

        var entity = initialEntity.copy(
            status = DownloadStatus.DOWNLOADING.name,
            threadCount = segments.size,
            downloadedBytes = segments.sumOf { it.downloadedBytes },
            segmentsJson = AdmDownloadPlanner.serializeSegments(segments),
            updatedAtEpochMs = System.currentTimeMillis()
        )
        downloadDao.updateDownload(entity)

        RandomAccessFile(stagingFile, "rw").use { raf ->
            if (multiPartActive && initialEntity.totalBytes > 0L && raf.length() != initialEntity.totalBytes) {
                raf.setLength(initialEntity.totalBytes)
            }
            val fileChannel = raf.channel

            val monitorJob = launch {
                var lastBytes = segmentDownloaded.sum()
                var lastTickMs = System.currentTimeMillis()
                var smoothedSpeed = 0L
                var lastDbPersistMs = lastTickMs

                while (isActive) {
                    delay(TELEMETRY_INTERVAL_MS)
                    val now = System.currentTimeMillis()
                    val currentBytes = segmentDownloaded.sum()
                    val dtMs = (now - lastTickMs).coerceAtLeast(1L)
                    val instantSpeed = ((currentBytes - lastBytes).coerceAtLeast(0L) * 1000L) / dtMs
                    smoothedSpeed = if (smoothedSpeed == 0L) {
                        instantSpeed
                    } else {
                        ((smoothedSpeed * 65L) + (instantSpeed * 35L)) / 100L
                    }
                    lastBytes = currentBytes
                    lastTickMs = now

                    val snapshotSegments = segments.mapIndexed { idx, seg ->
                        seg.copy(
                            downloadedBytes = segmentDownloaded.get(idx),
                            completed = segmentCompleted[idx].get()
                        )
                    }
                    updateLiveTelemetry(
                        downloadId = entity.id,
                        telemetry = LiveTaskTelemetry(
                            downloadedBytes = currentBytes,
                            totalBytes = totalBytesRef.get(),
                            speedBytesPerSec = smoothedSpeed,
                            segmentProgress = snapshotSegments.map { it.progressFraction }
                        )
                    )

                    if (now - lastDbPersistMs >= DB_PERSIST_INTERVAL_MS) {
                        lastDbPersistMs = now
                        downloadDao.updateDownload(
                            entity.copy(
                                totalBytes = totalBytesRef.get(),
                                downloadedBytes = currentBytes,
                                segmentsJson = AdmDownloadPlanner.serializeSegments(snapshotSegments),
                                updatedAtEpochMs = now
                            )
                        )
                    }
                }
            }

            try {
                if (entity.resolvedUrl.startsWith("ftp://", ignoreCase = true)) {
                    downloadFtpSingleStream(
                        url = entity.resolvedUrl,
                        fileChannel = fileChannel,
                        segmentDownloaded = segmentDownloaded,
                        segmentCompleted = segmentCompleted,
                        totalBytesRef = totalBytesRef
                    )
                } else {
                    val workerJobs = segments.mapIndexed { idx, seg ->
                        async {
                            if (segmentCompleted[idx].get()) return@async
                            downloadHttpSegment(
                                url = entity.resolvedUrl,
                                customHeaders = customHeaders,
                                segment = seg,
                                isMultiSegment = segments.size > 1,
                                fileChannel = fileChannel,
                                segmentDownloaded = segmentDownloaded,
                                segmentCompleted = segmentCompleted,
                                totalBytesRef = totalBytesRef,
                                fallbackSingleStreamTriggered = fallbackSingleStreamTriggered
                            )
                        }
                    }
                    workerJobs.awaitAll()
                }
            } finally {
                monitorJob.cancel()
                val finalDownloaded = segmentDownloaded.sum()
                val finalSegments = segments.mapIndexed { idx, seg ->
                    seg.copy(
                        downloadedBytes = segmentDownloaded.get(idx),
                        completed = segmentCompleted[idx].get()
                    )
                }
                entity = entity.copy(
                    totalBytes = totalBytesRef.get().takeIf { it > 0L } ?: finalDownloaded,
                    downloadedBytes = finalDownloaded,
                    segmentsJson = AdmDownloadPlanner.serializeSegments(finalSegments),
                    updatedAtEpochMs = System.currentTimeMillis()
                )
                downloadDao.updateDownload(entity)
            }
        }

        // Export completed file to MediaStore.Downloads & register in Lumina Library
        finalizeAndExportTask(entity, stagingFile)
    }

    private suspend fun downloadFtpSingleStream(
        url: String,
        fileChannel: java.nio.channels.FileChannel,
        segmentDownloaded: AtomicLongArrayCompat,
        segmentCompleted: Array<AtomicBoolean>,
        totalBytesRef: AtomicLong
    ) = coroutineScope {
        val conn: URLConnection = URL(url).openConnection().apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        val len = conn.contentLengthLong
        if (len > 0L) totalBytesRef.set(len)
        segmentDownloaded.set(0, 0L)

        BufferedInputStream(conn.getInputStream(), IO_BUFFER_BYTES).use { input ->
            val buf = ByteArray(IO_BUFFER_BYTES)
            var writePos = 0L
            while (isActive) {
                val read = input.read(buf)
                if (read <= 0) break
                writeFullyAt(fileChannel, buf, read, writePos)
                writePos += read
                segmentDownloaded.set(0, writePos)
            }
        }
        segmentCompleted[0].set(true)
    }

    private suspend fun downloadHttpSegment(
        url: String,
        customHeaders: Map<String, String>,
        segment: DownloadSegment,
        isMultiSegment: Boolean,
        fileChannel: java.nio.channels.FileChannel,
        segmentDownloaded: AtomicLongArrayCompat,
        segmentCompleted: Array<AtomicBoolean>,
        totalBytesRef: AtomicLong,
        fallbackSingleStreamTriggered: AtomicBoolean
    ) = coroutineScope {
        var attempt = 0
        while (isActive) {
            if (fallbackSingleStreamTriggered.get() && segment.index > 0) {
                segmentCompleted[segment.index].set(true)
                return@coroutineScope
            }

            val alreadyDone = segmentDownloaded.get(segment.index)
            val resumeOffset = segment.startByte + alreadyDone
            if (segment.endByte >= 0L && resumeOffset > segment.endByte) {
                segmentCompleted[segment.index].set(true)
                return@coroutineScope
            }

            val reqHeaders = LinkedHashMap(customHeaders).apply {
                putIfAbsent("User-Agent", DEFAULT_USER_AGENT)
                if (segment.endByte >= 0L) {
                    put("Range", "bytes=$resumeOffset-${segment.endByte}")
                } else if (resumeOffset > 0L) {
                    put("Range", "bytes=$resumeOffset-")
                }
            }

            try {
                val (conn, _) = openHttpConnectionWithRedirects(url, "GET", reqHeaders)
                try {
                    val code = conn.responseCode
                    if (code == 416) {
                        segmentCompleted[segment.index].set(true)
                        return@coroutineScope
                    }
                    if (code !in 200..299) {
                        throw IOException("HTTP $code ${conn.responseMessage.orEmpty()}".trim())
                    }

                    val serverHonoredRange = code == HttpURLConnection.HTTP_PARTIAL
                    var writeOffset = resumeOffset
                    var localDownloaded = alreadyDone

                    if (!serverHonoredRange) {
                        // Server ignored Range and returned full 200 OK stream
                        if (isMultiSegment && segment.index > 0) {
                            fallbackSingleStreamTriggered.set(true)
                            segmentDownloaded.set(segment.index, 0L)
                            segmentCompleted[segment.index].set(true)
                            return@coroutineScope
                        }
                        fallbackSingleStreamTriggered.set(true)
                        writeOffset = 0L
                        localDownloaded = 0L
                        segmentDownloaded.set(segment.index, 0L)
                        val fullLen = conn.contentLengthLong
                        if (fullLen > 0L) totalBytesRef.set(fullLen)
                    } else if (totalBytesRef.get() <= 0L) {
                        val crTotal = AdmDownloadPlanner.parseContentRangeTotalBytes(
                            conn.getHeaderField("Content-Range")
                        )
                        if (crTotal > 0L) totalBytesRef.set(crTotal)
                    }

                    val maxExpectedForSegment = if (serverHonoredRange && segment.endByte >= segment.startByte) {
                        segment.endByte - segment.startByte + 1L
                    } else {
                        Long.MAX_VALUE
                    }

                    BufferedInputStream(conn.inputStream, IO_BUFFER_BYTES).use { input ->
                        val buffer = ByteArray(IO_BUFFER_BYTES)
                        while (isActive && localDownloaded < maxExpectedForSegment) {
                            val maxToRead = minOf(
                                buffer.size.toLong(),
                                maxExpectedForSegment - localDownloaded
                            ).toInt()
                            val bytesRead = input.read(buffer, 0, maxToRead)
                            if (bytesRead < 0) break
                            if (bytesRead == 0) continue

                            writeFullyAt(fileChannel, buffer, bytesRead, writeOffset)
                            writeOffset += bytesRead
                            localDownloaded += bytesRead
                            segmentDownloaded.set(segment.index, localDownloaded)
                        }
                    }

                    if (maxExpectedForSegment != Long.MAX_VALUE && localDownloaded < maxExpectedForSegment) {
                        throw IOException("Premature EOF on segment ${segment.index + 1}")
                    }

                    segmentCompleted[segment.index].set(true)
                    return@coroutineScope
                } finally {
                    conn.disconnect()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ioe: IOException) {
                attempt++
                if (attempt > MAX_SEGMENT_RETRIES || !isActive) {
                    throw ioe
                }
                delay(RETRY_BACKOFF_BASE_MS * attempt)
            }
        }
    }

    private fun writeFullyAt(
        channel: java.nio.channels.FileChannel,
        bytes: ByteArray,
        length: Int,
        position: Long
    ) {
        val byteBuffer = ByteBuffer.wrap(bytes, 0, length)
        var pos = position
        while (byteBuffer.hasRemaining()) {
            val written = channel.write(byteBuffer, pos)
            if (written <= 0) break
            pos += written
        }
    }

    // =========================================================================
    // HLS (.m3u8) Multi-Worker Segment Downloader + AES-128 Decryption
    // =========================================================================

    private suspend fun executeHlsDownload(
        initialEntity: DownloadTaskEntity,
        customHeaders: Map<String, String>
    ) = coroutineScope {
        var playlistUrl = initialEntity.resolvedUrl
        var playlistText = fetchTextResource(playlistUrl, customHeaders)
        var parsed = AdmDownloadPlanner.parseHlsPlaylist(playlistText, playlistUrl)

        if (parsed is HlsParsedPlaylist.Master) {
            val bestVariant = parsed.variants.firstOrNull()
                ?: throw IOException("HLS Master playlist contains no playable variants.")
            playlistUrl = bestVariant.url
            playlistText = fetchTextResource(playlistUrl, customHeaders)
            parsed = AdmDownloadPlanner.parseHlsPlaylist(playlistText, playlistUrl)
        }

        val mediaPlan = parsed as? HlsParsedPlaylist.Media
            ?: throw IOException("Unable to parse HLS media segments.")
        if (mediaPlan.segments.isEmpty()) {
            throw IOException("HLS playlist contains zero media segments.")
        }

        val hasFmp4Init = mediaPlan.initMapUrl != null
        val adjustedFileName = if (hasFmp4Init && initialEntity.fileName.endsWith(".ts", ignoreCase = true)) {
            initialEntity.fileName.substringBeforeLast('.') + ".mp4"
        } else {
            initialEntity.fileName
        }
        val adjustedMime = if (hasFmp4Init) "video/mp4" else initialEntity.mimeType

        val hlsWorkDir = File(stagingRootDir, "hls_${initialEntity.id}").apply { mkdirs() }
        val totalSegments = mediaPlan.segments.size
        val workerCount = minOf(initialEntity.threadCount.coerceIn(1, 16), totalSegments)
        val bucketCount = workerCount.coerceAtLeast(1)

        val downloadedBytesCounter = AtomicLong(0L)
        val completedSegmentFlags = Array(totalSegments) { AtomicBoolean(false) }

        // Restore already cached segments if resuming
        for (idx in 0 until totalSegments) {
            val partFile = File(hlsWorkDir, "seg_${idx}.part")
            if (partFile.exists() && partFile.length() > 0L) {
                completedSegmentFlags[idx].set(true)
                downloadedBytesCounter.addAndGet(partFile.length())
            }
        }

        var entity = initialEntity.copy(
            resolvedUrl = playlistUrl,
            fileName = adjustedFileName,
            mimeType = adjustedMime,
            status = DownloadStatus.DOWNLOADING.name,
            threadCount = workerCount,
            downloadedBytes = downloadedBytesCounter.get(),
            updatedAtEpochMs = System.currentTimeMillis()
        )
        downloadDao.updateDownload(entity)

        // Fetch fMP4 #EXT-X-MAP init segment if present
        val initPartFile = if (mediaPlan.initMapUrl != null) {
            val f = File(hlsWorkDir, "init.part")
            if (!f.exists() || f.length() == 0L) {
                val initBytes = fetchBinaryResource(mediaPlan.initMapUrl, customHeaders)
                f.writeBytes(initBytes)
            }
            f
        } else {
            null
        }

        val keyCache = ConcurrentHashMap<String, ByteArray>()
        val keyMutex = Mutex()
        val semaphore = Semaphore(workerCount)

        fun computeBucketFractions(): List<Float> {
            return (0 until bucketCount).map { b ->
                val startIdx = (b * totalSegments) / bucketCount
                val endIdxExclusive = (((b + 1) * totalSegments) / bucketCount).coerceAtLeast(startIdx + 1)
                var done = 0
                for (i in startIdx until endIdxExclusive.coerceAtMost(totalSegments)) {
                    if (completedSegmentFlags[i].get()) done++
                }
                val span = (endIdxExclusive.coerceAtMost(totalSegments) - startIdx).coerceAtLeast(1)
                (done.toFloat() / span.toFloat()).coerceIn(0f, 1f)
            }
        }

        val monitorJob = launch {
            var lastBytes = downloadedBytesCounter.get()
            var lastTickMs = System.currentTimeMillis()
            var smoothedSpeed = 0L
            var lastPersistMs = lastTickMs

            while (isActive) {
                delay(TELEMETRY_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val curBytes = downloadedBytesCounter.get()
                val dtMs = (now - lastTickMs).coerceAtLeast(1L)
                val instantSpeed = ((curBytes - lastBytes).coerceAtLeast(0L) * 1000L) / dtMs
                smoothedSpeed = if (smoothedSpeed == 0L) {
                    instantSpeed
                } else {
                    ((smoothedSpeed * 65L) + (instantSpeed * 35L)) / 100L
                }
                lastBytes = curBytes
                lastTickMs = now

                val doneCount = completedSegmentFlags.count { it.get() }
                val estimatedTotal = if (doneCount > 0) {
                    ((curBytes.toDouble() / doneCount.toDouble()) * totalSegments.toDouble()).toLong()
                } else {
                    -1L
                }
                val buckets = computeBucketFractions()

                updateLiveTelemetry(
                    downloadId = entity.id,
                    telemetry = LiveTaskTelemetry(
                        downloadedBytes = curBytes,
                        totalBytes = estimatedTotal,
                        speedBytesPerSec = smoothedSpeed,
                        segmentProgress = buckets
                    )
                )

                if (now - lastPersistMs >= DB_PERSIST_INTERVAL_MS) {
                    lastPersistMs = now
                    val pseudoSegments = buckets.mapIndexed { idx, frac ->
                        DownloadSegment(
                            index = idx,
                            startByte = 0L,
                            endByte = 1000L,
                            downloadedBytes = (frac * 1000f).toLong(),
                            completed = frac >= 0.999f
                        )
                    }
                    downloadDao.updateDownload(
                        entity.copy(
                            totalBytes = estimatedTotal,
                            downloadedBytes = curBytes,
                            segmentsJson = AdmDownloadPlanner.serializeSegments(pseudoSegments),
                            updatedAtEpochMs = now
                        )
                    )
                }
            }
        }

        try {
            val jobs = mediaPlan.segments.mapIndexed { idx, spec ->
                async {
                    if (completedSegmentFlags[idx].get()) return@async
                    semaphore.withPermit {
                        if (!isActive) return@withPermit
                        val rawBytes = fetchHlsSegmentWithRetry(spec, customHeaders)
                        val finalBytes = decryptHlsSegmentIfNeeded(
                            rawBytes = rawBytes,
                            spec = spec,
                            customHeaders = customHeaders,
                            keyCache = keyCache,
                            keyMutex = keyMutex
                        )
                        val tmpPart = File(hlsWorkDir, "seg_${idx}.tmp")
                        val finalPart = File(hlsWorkDir, "seg_${idx}.part")
                        tmpPart.writeBytes(finalBytes)
                        if (!tmpPart.renameTo(finalPart)) {
                            tmpPart.copyTo(finalPart, overwrite = true)
                            tmpPart.delete()
                        }
                        downloadedBytesCounter.addAndGet(finalBytes.size.toLong())
                        completedSegmentFlags[idx].set(true)
                    }
                }
            }
            jobs.awaitAll()
        } finally {
            monitorJob.cancel()
        }

        // Merge segments sequentially into stagingFile
        val stagingFile = File(entity.stagingFilePath).apply { parentFile?.mkdirs() }
        entity = entity.copy(
            status = DownloadStatus.MERGING.name,
            downloadedBytes = downloadedBytesCounter.get(),
            totalBytes = downloadedBytesCounter.get(),
            updatedAtEpochMs = System.currentTimeMillis()
        )
        downloadDao.updateDownload(entity)

        FileOutputStream(stagingFile, false).buffered(IO_BUFFER_BYTES).use { out ->
            if (initPartFile != null && initPartFile.exists()) {
                FileInputStream(initPartFile).use { it.copyTo(out, IO_BUFFER_BYTES) }
            }
            for (idx in 0 until totalSegments) {
                val partFile = File(hlsWorkDir, "seg_${idx}.part")
                if (!partFile.exists()) {
                    throw IOException("Missing HLS segment #${idx + 1} during stream assembly.")
                }
                FileInputStream(partFile).use { it.copyTo(out, IO_BUFFER_BYTES) }
            }
            out.flush()
        }

        hlsWorkDir.deleteRecursively()
        val finalSize = stagingFile.length()
        val doneSegments = List(bucketCount) { b ->
            DownloadSegment(index = b, startByte = 0L, endByte = 1000L, downloadedBytes = 1000L, completed = true)
        }
        entity = entity.copy(
            totalBytes = finalSize,
            downloadedBytes = finalSize,
            segmentsJson = AdmDownloadPlanner.serializeSegments(doneSegments),
            updatedAtEpochMs = System.currentTimeMillis()
        )
        downloadDao.updateDownload(entity)

        finalizeAndExportTask(entity, stagingFile)
    }

    private suspend fun fetchHlsSegmentWithRetry(
        spec: HlsSegmentSpec,
        customHeaders: Map<String, String>
    ): ByteArray {
        val segHeaders = LinkedHashMap(customHeaders)
        if (spec.byteRangeOffset != null && spec.byteRangeLength != null) {
            val end = spec.byteRangeOffset + spec.byteRangeLength - 1L
            segHeaders["Range"] = "bytes=${spec.byteRangeOffset}-$end"
        }
        var attempt = 0
        while (true) {
            try {
                return fetchBinaryResource(spec.url, segHeaders)
            } catch (ce: CancellationException) {
                throw ce
            } catch (ioe: IOException) {
                attempt++
                if (attempt > MAX_SEGMENT_RETRIES) throw ioe
                delay(RETRY_BACKOFF_BASE_MS * attempt)
            }
        }
    }

    private suspend fun decryptHlsSegmentIfNeeded(
        rawBytes: ByteArray,
        spec: HlsSegmentSpec,
        customHeaders: Map<String, String>,
        keyCache: ConcurrentHashMap<String, ByteArray>,
        keyMutex: Mutex
    ): ByteArray {
        val keySpec = spec.encryptionKey ?: return rawBytes
        if (!keySpec.method.equals("AES-128", ignoreCase = true)) {
            if (keySpec.method.equals("NONE", ignoreCase = true)) return rawBytes
            throw IOException("Unsupported HLS DRM encryption method: ${keySpec.method}")
        }
        val keyUrl = keySpec.keyUrl ?: throw IOException("Missing HLS AES-128 key URI.")
        val keyBytes = keyCache[keyUrl] ?: keyMutex.withLock {
            keyCache[keyUrl] ?: fetchBinaryResource(keyUrl, customHeaders).also { fetched ->
                if (fetched.size != 16) {
                    throw IOException("Invalid HLS AES-128 key size (${fetched.size} bytes).")
                }
                keyCache[keyUrl] = fetched
            }
        }
        val ivBytes = parseHlsIvBytes(keySpec.ivHex, spec.sequenceNumber)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        return cipher.doFinal(rawBytes)
    }

    private fun parseHlsIvBytes(ivHex: String?, sequenceNumber: Long): ByteArray {
        val cleaned = ivHex?.trim()?.removePrefix("0x")?.removePrefix("0X")
        if (!cleaned.isNullOrBlank()) {
            val padded = cleaned.padStart(32, '0').takeLast(32)
            val out = ByteArray(16)
            for (i in 0 until 16) {
                out[i] = padded.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return out
        }
        val out = ByteArray(16)
        var seq = sequenceNumber
        for (i in 15 downTo 8) {
            out[i] = (seq and 0xFFL).toByte()
            seq = seq ushr 8
        }
        return out
    }

    // =========================================================================
    // Scoped Storage Export (`Downloads/Lumina`) + Library Registration
    // =========================================================================

    private suspend fun finalizeAndExportTask(entity: DownloadTaskEntity, stagingFile: File) {
        if (!stagingFile.exists() || stagingFile.length() <= 0L) {
            throw IOException("Downloaded stream output was empty.")
        }
        val finalSize = stagingFile.length()
        val exportedUri = exportStagingFileToScopedDownloads(
            stagingFile = stagingFile,
            fileName = entity.fileName,
            mimeType = entity.mimeType
        )
        stagingFile.delete()
        removeLiveTelemetry(entity.id)

        mediaRepository.registerDownloadedMedia(
            uri = exportedUri,
            title = entity.title,
            fileName = entity.fileName,
            mimeType = entity.mimeType,
            sizeBytes = finalSize
        )

        val doneCount = entity.threadCount.coerceIn(1, 16)
        val completedSegments = List(doneCount) { idx ->
            DownloadSegment(
                index = idx,
                startByte = 0L,
                endByte = maxOf(0L, finalSize - 1L),
                downloadedBytes = finalSize / doneCount,
                completed = true
            )
        }

        downloadDao.updateDownload(
            entity.copy(
                status = DownloadStatus.COMPLETED.name,
                totalBytes = finalSize,
                downloadedBytes = finalSize,
                segmentsJson = AdmDownloadPlanner.serializeSegments(completedSegments),
                exportedUri = exportedUri.toString(),
                errorMessage = null,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    private fun exportStagingFileToScopedDownloads(
        stagingFile: File,
        fileName: String,
        mimeType: String
    ): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Lumina")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val inserted = runCatching { resolver.insert(collection, values) }.getOrNull()
            if (inserted != null) {
                try {
                    resolver.openOutputStream(inserted, "w")?.buffered(IO_BUFFER_BYTES)?.use { out ->
                        FileInputStream(stagingFile).buffered(IO_BUFFER_BYTES).use { input ->
                            input.copyTo(out, IO_BUFFER_BYTES)
                        }
                        out.flush()
                    } ?: throw IOException("Unable to open MediaStore output stream.")

                    val publishValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(inserted, publishValues, null, null)
                    return inserted
                } catch (e: Exception) {
                    runCatching { resolver.delete(inserted, null, null) }
                }
            }
        }

        // Fallback for API 26–28 or restricted MediaStore containers
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        val luminaDir = File(baseDir, "Lumina").apply { mkdirs() }
        val targetFile = uniqueDestinationFile(luminaDir, fileName)
        stagingFile.copyTo(targetFile, overwrite = true)
        return Uri.fromFile(targetFile)
    }

    private fun uniqueDestinationFile(dir: File, desiredName: String): File {
        var candidate = File(dir, desiredName)
        if (!candidate.exists()) return candidate
        val stem = desiredName.substringBeforeLast('.', desiredName)
        val ext = desiredName.substringAfterLast('.', "")
        var counter = 1
        while (candidate.exists()) {
            val nextName = if (ext.isNotEmpty()) "${stem}_($counter).$ext" else "${stem}_($counter)"
            candidate = File(dir, nextName)
            counter++
        }
        return candidate
    }

    private fun cleanupStagingArtifacts(task: DownloadTaskEntity) {
        runCatching { File(task.stagingFilePath).delete() }
        runCatching { File(stagingRootDir, "hls_${task.id}").deleteRecursively() }
    }

    // =========================================================================
    // Low-Level HTTP Helpers with Cross-Scheme Redirect & Header Preservation
    // =========================================================================

    private fun fetchTextResource(url: String, customHeaders: Map<String, String>): String {
        val bytes = fetchBinaryResource(url, customHeaders)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun fetchBinaryResource(url: String, customHeaders: Map<String, String>): ByteArray {
        val headers = LinkedHashMap(customHeaders).apply {
            putIfAbsent("User-Agent", DEFAULT_USER_AGENT)
        }
        val (conn, _) = openHttpConnectionWithRedirects(url, "GET", headers)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code ${conn.responseMessage.orEmpty()}".trim())
            }
            return BufferedInputStream(conn.inputStream, IO_BUFFER_BYTES).use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun openHttpConnectionWithRedirects(
        initialUrl: String,
        method: String,
        headers: Map<String, String>,
        maxRedirects: Int = 6
    ): Pair<HttpURLConnection, String> {
        var currentUrl = initialUrl
        var redirects = 0

        while (true) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            if (code in REDIRECT_CODES) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank() || redirects >= maxRedirects) {
                    throw IOException("Too many or invalid HTTP redirects from $currentUrl")
                }
                currentUrl = AdmDownloadPlanner.resolveRelativeUrl(currentUrl, location)
                redirects++
                continue
            }
            return conn to currentUrl
        }
    }

    private fun updateLiveTelemetry(downloadId: Long, telemetry: LiveTaskTelemetry) {
        val updated = LinkedHashMap(liveTelemetryFlow.value)
        updated[downloadId] = telemetry
        liveTelemetryFlow.value = updated
    }

    private fun removeLiveTelemetry(downloadId: Long) {
        if (!liveTelemetryFlow.value.containsKey(downloadId)) return
        val updated = LinkedHashMap(liveTelemetryFlow.value)
        updated.remove(downloadId)
        liveTelemetryFlow.value = updated
    }

    private class AtomicLongArrayCompat(val size: Int) {
        private val cells = Array(size) { AtomicLong(0L) }
        fun get(index: Int): Long = cells[index].get()
        fun set(index: Int, value: Long) = cells[index].set(value)
        fun sum(): Long {
            var acc = 0L
            for (c in cells) acc += c.get()
            return acc
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 25_000
        private const val IO_BUFFER_BYTES = 64 * 1024
        private const val TELEMETRY_INTERVAL_MS = 250L
        private const val DB_PERSIST_INTERVAL_MS = 1_250L
        private const val MAX_SEGMENT_RETRIES = 4
        private const val RETRY_BACKOFF_BASE_MS = 750L
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) LuminaPlayer/1.2.0 LibVLC/4.0"
    }
}
