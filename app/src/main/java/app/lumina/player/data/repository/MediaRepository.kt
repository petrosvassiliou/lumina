package app.lumina.player.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import app.lumina.player.data.local.dao.MediaDao
import app.lumina.player.data.local.dao.PlaybackStateDao
import app.lumina.player.data.local.entity.MediaItemEntity
import app.lumina.player.data.local.entity.PlaybackStateEntity
import app.lumina.player.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Fast Scoped Storage media indexer & network stream repository.
 * Complies 100% with Google Play Protect (uses MediaStore + Photo/Document Picker URIs only).
 */
@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaDao: MediaDao,
    private val playbackStateDao: PlaybackStateDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    val localMediaFlow: Flow<List<MediaItemEntity>> = mediaDao.observeLocalMedia()
    val networkStreamsFlow: Flow<List<MediaItemEntity>> = mediaDao.observeNetworkStreams()
    val continueWatchingFlow: Flow<List<MediaItemEntity>> = mediaDao.observeContinueWatching()
    val playbackStatesFlow: Flow<List<PlaybackStateEntity>> = playbackStateDao.observeAllPlaybackStates()

    fun searchMedia(query: String): Flow<List<MediaItemEntity>> = mediaDao.searchMedia(query)

    /**
     * Scans Scoped Storage (MediaStore.Video & MediaStore.Audio) and updates Room in a single batch.
     */
    suspend fun refreshLocalMediaIndex(): Int = withContext(ioDispatcher) {
        val discovered = mutableListOf<MediaItemEntity>()

        // 1. Query Video Collection
        val videoCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val videoProjection = buildList {
            add(MediaStore.Video.Media._ID)
            add(MediaStore.Video.Media.DISPLAY_NAME)
            add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            add(MediaStore.Video.Media.MIME_TYPE)
            add(MediaStore.Video.Media.DURATION)
            add(MediaStore.Video.Media.SIZE)
            add(MediaStore.Video.Media.WIDTH)
            add(MediaStore.Video.Media.HEIGHT)
            add(MediaStore.Video.Media.DATE_ADDED)
        }.toTypedArray()

        var videoQuerySucceeded = false

        runCatching {
            context.contentResolver.query(
                videoCollection,
                videoProjection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                videoQuerySucceeded = true
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val bucketCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(videoCollection, id)
                    val displayName = cursor.getString(nameCol) ?: "Untitled Video"
                    val folder = if (bucketCol >= 0) {
                        cursor.getString(bucketCol) ?: "Videos"
                    } else {
                        "Videos"
                    }
                    val mime = cursor.getString(mimeCol) ?: "video/*"
                    val durationMs = cursor.getLong(durationCol).coerceAtLeast(0L)
                    val sizeBytes = cursor.getLong(sizeCol).coerceAtLeast(0L)
                    val width = cursor.getInt(widthCol).coerceAtLeast(0)
                    val height = cursor.getInt(heightCol).coerceAtLeast(0)
                    val dateAdded = cursor.getLong(dateAddedCol)

                    val inferredHdr = inferHdrBadgeFromFilename(displayName)
                    val inferredCodec = inferCodecFromFilename(displayName, mime)

                    discovered.add(
                        MediaItemEntity(
                            uri = contentUri.toString(),
                            title = displayName.substringBeforeLast('.', displayName),
                            folderName = folder,
                            mimeType = mime,
                            durationMs = durationMs,
                            sizeBytes = sizeBytes,
                            width = width,
                            height = height,
                            hdrBadge = inferredHdr,
                            videoCodec = inferredCodec,
                            isNetworkStream = false,
                            networkProtocol = null,
                            dateAddedEpochSec = dateAdded
                        )
                    )
                }
            }
        }

        // 2. Query Lossless / High-Res Audio (FLAC, Opus, WAV, etc.)
        val audioCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val audioProjection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED
        )

        var mediaStoreQuerySucceeded = false

        runCatching {
            context.contentResolver.query(
                audioCollection,
                audioProjection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                mediaStoreQuerySucceeded = true
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(audioCollection, id)
                    val displayName = cursor.getString(nameCol) ?: "Untitled Audio"
                    val album = if (albumCol >= 0) cursor.getString(albumCol) ?: "Audio" else "Audio"
                    val mime = cursor.getString(mimeCol) ?: "audio/*"

                    discovered.add(
                        MediaItemEntity(
                            uri = contentUri.toString(),
                            title = displayName.substringBeforeLast('.', displayName),
                            folderName = album,
                            mimeType = mime,
                            durationMs = cursor.getLong(durationCol).coerceAtLeast(0L),
                            sizeBytes = cursor.getLong(sizeCol).coerceAtLeast(0L),
                            width = 0,
                            height = 0,
                            hdrBadge = null,
                            videoCodec = inferCodecFromFilename(displayName, mime),
                            isNetworkStream = false,
                            networkProtocol = null,
                            dateAddedEpochSec = cursor.getLong(dateAddedCol)
                        )
                    )
                }
            }
        }

        if (discovered.isNotEmpty()) {
            mediaDao.upsertAll(discovered)
        }

        // Prune only stale MediaStore URIs (never wipe user-picked SAF documents or mounted folders),
        // chunked by 500 items to stay safely below SQLite's 999 bind-parameter limit.
        if (videoQuerySucceeded || mediaStoreQuerySucceeded || discovered.isNotEmpty()) {
            val activeMediaStoreUris = discovered.mapTo(HashSet(discovered.size)) { it.uri }
            val staleMediaStoreUris = mediaDao.getIndexedMediaStoreUris()
                .filterNot { it in activeMediaStoreUris }
            staleMediaStoreUris.chunked(500).forEach { batch ->
                mediaDao.deleteMediaByUris(batch)
            }
        }

        discovered.size
    }

    /**
     * Resolves display metadata for a persisted SAF folder tree URI (`OpenDocumentTree`).
     */
    suspend fun resolvePinnedSafFolderSummary(treeUriString: String): SafPinnedFolderSummary? =
        withContext(ioDispatcher) {
            runCatching {
                val treeUri = Uri.parse(treeUriString)
                val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext null
                val name = rootDoc.name ?: treeUri.lastPathSegment?.substringAfterLast(':') ?: "Local Folder"
                val readablePath = treeUri.lastPathSegment?.replace(':', '/') ?: name
                SafPinnedFolderSummary(
                    treeUriString = treeUriString,
                    treeUri = treeUri,
                    displayName = name,
                    displayPath = readablePath
                )
            }.getOrNull()
        }

    /**
     * Lists subdirectories and playable video files inside a SAF directory URI (`DocumentFile`),
     * sorted with folders first (alphabetical) followed by video files (alphabetical).
     */
    suspend fun listSafDirectoryEntries(
        treeRootUri: Uri,
        targetDirectoryUri: Uri = treeRootUri
    ): List<LocalFileEntry> = withContext(ioDispatcher) {
        val rootDoc = DocumentFile.fromTreeUri(context, treeRootUri) ?: return@withContext emptyList()
        val targetDoc = if (targetDirectoryUri == treeRootUri) {
            rootDoc
        } else {
            findDocumentByUri(rootDoc, targetDirectoryUri) ?: DocumentFile.fromTreeUri(context, targetDirectoryUri)
        } ?: return@withContext emptyList()

        val entries = mutableListOf<LocalFileEntry>()
        for (child in targetDoc.listFiles()) {
            val childName = child.name ?: continue
            if (childName.startsWith(".")) continue
            if (child.isDirectory) {
                entries.add(
                    LocalFileEntry(
                        uri = child.uri,
                        name = childName,
                        isDirectory = true,
                        sizeBytes = 0L,
                        mimeType = "vnd.android.document/directory",
                        hdrBadge = null,
                        videoCodec = null,
                        lastModifiedMs = child.lastModified()
                    )
                )
            } else {
                val mime = child.type ?: "application/octet-stream"
                if (isSupportedVideoFile(childName, mime)) {
                    entries.add(
                        LocalFileEntry(
                            uri = child.uri,
                            name = childName,
                            isDirectory = false,
                            sizeBytes = child.length().coerceAtLeast(0L),
                            mimeType = if (mime.startsWith("video/", ignoreCase = true)) mime else "video/*",
                            hdrBadge = inferHdrBadgeFromFilename(childName),
                            videoCodec = inferCodecFromFilename(childName, mime),
                            lastModifiedMs = child.lastModified()
                        )
                    )
                }
            }
        }

        entries.sortedWith(
            compareByDescending<LocalFileEntry> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
    }

    /**
     * Registers video files launched from the local folder browser into Room so playback
     * resume states and "Continue Watching" work identically to MediaStore items.
     */
    suspend fun registerFolderVideoEntries(
        folderDisplayName: String,
        videoEntries: List<LocalFileEntry>
    ) = withContext(ioDispatcher) {
        if (videoEntries.isEmpty()) return@withContext
        val nowSec = System.currentTimeMillis() / 1000L
        val entities = videoEntries.map { entry ->
            MediaItemEntity(
                uri = entry.uri.toString(),
                title = entry.name.substringBeforeLast('.', entry.name),
                folderName = folderDisplayName,
                mimeType = entry.mimeType,
                durationMs = 0L,
                sizeBytes = entry.sizeBytes,
                width = 0,
                height = 0,
                hdrBadge = entry.hdrBadge,
                videoCodec = entry.videoCodec,
                isNetworkStream = false,
                networkProtocol = null,
                dateAddedEpochSec = nowSec
            )
        }
        mediaDao.upsertAll(entities)
    }

    private fun findDocumentByUri(current: DocumentFile, targetUri: Uri): DocumentFile? {
        if (current.uri == targetUri) return current
        for (child in current.listFiles()) {
            if (child.uri == targetUri) return child
            if (child.isDirectory) {
                val nested = findDocumentByUri(child, targetUri)
                if (nested != null) return nested
            }
        }
        return null
    }

    private fun isSupportedVideoFile(fileName: String, mimeType: String): Boolean {
        if (mimeType.startsWith("video/", ignoreCase = true)) return true
        if (mimeType.equals("application/x-matroska", ignoreCase = true)) return true
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in SUPPORTED_VIDEO_EXTENSIONS
    }

    /**
     * Registers a URI chosen via Android Photo Picker or SAF Document Picker.
     */
    suspend fun registerPickedUri(uri: Uri): MediaItemEntity = withContext(ioDispatcher) {
        val doc = DocumentFile.fromSingleUri(context, uri)
        val rawName = doc?.name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Media"
        val rawMime = doc?.type ?: context.contentResolver.getType(uri) ?: "video/*"
        val ext = rawName.substringAfterLast('.', "").lowercase()
        val mime = when {
            rawMime.startsWith("audio/", ignoreCase = true) || ext in SUPPORTED_AUDIO_EXTENSIONS -> {
                if (rawMime.startsWith("audio/", ignoreCase = true)) rawMime else "audio/*"
            }
            else -> rawMime
        }
        val size = doc?.length() ?: 0L

        val entity = MediaItemEntity(
            uri = uri.toString(),
            title = rawName.substringBeforeLast('.', rawName),
            folderName = if (mime.startsWith("audio/", ignoreCase = true)) "Local Audio" else "Local Files",
            mimeType = mime,
            durationMs = 0L,
            sizeBytes = size,
            width = 0,
            height = 0,
            hdrBadge = if (mime.startsWith("audio/", ignoreCase = true)) null else inferHdrBadgeFromFilename(rawName),
            videoCodec = inferCodecFromFilename(rawName, mime),
            isNetworkStream = false,
            networkProtocol = null,
            dateAddedEpochSec = System.currentTimeMillis() / 1000L,
            lastPlayedAt = System.currentTimeMillis()
        )
        mediaDao.upsert(entity)
        entity
    }

    /**
     * Saves a network stream (SMB/NAS, FTP, UPnP/DLNA, HTTP/HLS, RTSP) into the library.
     */
    suspend fun addNetworkStream(
        streamUrl: String,
        customTitle: String? = null
    ): MediaItemEntity = withContext(ioDispatcher) {
        val trimmed = streamUrl.trim()
        val parsedUri = Uri.parse(trimmed)
        val scheme = parsedUri.scheme?.uppercase() ?: "HTTP"
        val fallbackTitle = parsedUri.lastPathSegment
            ?.takeIf { it.isNotBlank() }
            ?: parsedUri.host
            ?: trimmed

        val item = MediaItemEntity(
            uri = trimmed,
            title = customTitle?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            folderName = "$scheme Streams",
            mimeType = "video/*",
            durationMs = 0L,
            sizeBytes = 0L,
            width = 0,
            height = 0,
            hdrBadge = inferHdrBadgeFromFilename(trimmed),
            videoCodec = null,
            isNetworkStream = true,
            networkProtocol = scheme,
            dateAddedEpochSec = System.currentTimeMillis() / 1000L
        )
        mediaDao.upsert(item)
        item
    }

    suspend fun deleteNetworkStream(uri: String) = withContext(ioDispatcher) {
        mediaDao.deleteByUri(uri)
        playbackStateDao.clearPlaybackState(uri)
    }

    suspend fun toggleFavorite(uri: String, favorite: Boolean) = withContext(ioDispatcher) {
        mediaDao.setFavorite(uri, favorite)
    }

    private fun inferHdrBadgeFromFilename(name: String): String? {
        val upper = name.uppercase()
        return when {
            upper.contains("DV") || upper.contains("DOVI") || upper.contains("DOLBY.VISION") || upper.contains("DOLBY VISION") -> "Dolby Vision"
            upper.contains("HDR10+") || upper.contains("HDR10PLUS") -> "HDR10+"
            upper.contains("HLG") -> "HLG"
            upper.contains("HDR10") || upper.contains(".HDR.") || upper.contains("BT2020") || upper.contains("2160P.UHD") -> "HDR10"
            else -> null
        }
    }

    private fun inferCodecFromFilename(name: String, mimeType: String): String? {
        val upper = File(name).name.uppercase()
        return when {
            upper.contains("AV1") || mimeType.contains("av01", ignoreCase = true) -> "AV1"
            upper.contains("HEVC") || upper.contains("H265") || upper.contains("H.265") || upper.contains("X265") -> "HEVC"
            upper.contains("VP9") -> "VP9"
            upper.contains("H264") || upper.contains("H.264") || upper.contains("X264") || upper.contains("AVC") -> "H.264"
            upper.endsWith(".FLAC") || mimeType.contains("flac", ignoreCase = true) -> "FLAC"
            upper.endsWith(".MKV") -> "MKV"
            else -> null
        }
    }

    companion object {
        private val SUPPORTED_VIDEO_EXTENSIONS = setOf(
            "mkv", "mp4", "m4v", "mov", "avi", "webm", "ts", "m2ts",
            "mts", "flv", "wmv", "3gp", "mpg", "mpeg", "ogv", "vob"
        )
        private val SUPPORTED_AUDIO_EXTENSIONS = setOf(
            "flac", "wav", "mp3", "ogg", "opus", "m4a", "aac", "alac", "aiff", "dsf"
        )
    }
}

data class SafPinnedFolderSummary(
    val treeUriString: String,
    val treeUri: Uri,
    val displayName: String,
    val displayPath: String
)

data class LocalFileEntry(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val mimeType: String,
    val hdrBadge: String?,
    val videoCodec: String?,
    val lastModifiedMs: Long
)
