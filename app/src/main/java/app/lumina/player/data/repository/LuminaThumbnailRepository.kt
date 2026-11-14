package app.lumina.player.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import app.lumina.player.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Asynchronous, bounded-concurrency video frame extractor with two-tier caching
 * (in-memory [LruCache] + `cacheDir/lumina_scrub_frames` JPEG disk cache).
 *
 * Powers:
 * 1. Live Timeline Scrub / Pointer-Hover Frame Preview bubbles in `LuminaPlayerScreen`.
 * 2. Fast poster frame thumbnails for SAF `DocumentFile` video entries & Library cards.
 */
@Singleton
class LuminaThumbnailRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher ioDispatcher: CoroutineDispatcher
) {
    private val extractionDispatcher = ioDispatcher.limitedParallelism(2)

    private val memoryCache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    private val diskCacheDir: File by lazy {
        File(context.cacheDir, "lumina_scrub_frames").apply { mkdirs() }
    }

    /**
     * Extracts a compact 16:9 keyframe (`240x135`) quantized to 2-second buckets for live timeline scrubbing.
     * Returns `null` cleanly for non-local/live streams or unsupported containers without throwing.
     */
    suspend fun getScrubPreviewFrame(
        uri: Uri,
        targetTimeMs: Long,
        isNetworkStream: Boolean
    ): Bitmap? {
        if (isNetworkStream) return null
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "content" && scheme != "file") return null

        val quantizedSec = quantizeScrubBucketSeconds(targetTimeMs)
        val cacheKey = buildCacheKey(uri.toString(), quantizedSec, width = 240, height = 135)

        synchronized(memoryCache) {
            memoryCache.get(cacheKey)?.takeIf { !it.isRecycled }?.let { return it }
        }

        return withContext(extractionDispatcher) {
            synchronized(memoryCache) {
                memoryCache.get(cacheKey)?.takeIf { !it.isRecycled }?.let { return@withContext it }
            }

            val diskFile = File(diskCacheDir, "$cacheKey.jpg")
            if (diskFile.exists() && diskFile.length() > 0L) {
                val decoded = runCatching { BitmapFactory.decodeFile(diskFile.absolutePath) }.getOrNull()
                if (decoded != null) {
                    synchronized(memoryCache) { memoryCache.put(cacheKey, decoded) }
                    return@withContext decoded
                }
            }

            val extracted = extractScaledSyncFrame(
                uri = uri,
                timeUs = quantizedSec * 1_000_000L,
                targetWidth = 240,
                targetHeight = 135
            )
            if (extracted != null) {
                synchronized(memoryCache) { memoryCache.put(cacheKey, extracted) }
                runCatching {
                    pruneDiskCacheIfNeeded()
                    FileOutputStream(diskFile).use { out ->
                        extracted.compress(Bitmap.CompressFormat.JPEG, 78, out)
                    }
                }
            }
            extracted
        }
    }

    private fun extractScaledSyncFrame(
        uri: Uri,
        timeUs: Long,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            val sourceConfigured = configureRetrieverSource(retriever, uri)
            if (!sourceConfigured) return null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeUs.coerceAtLeast(0L),
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    targetWidth,
                    targetHeight
                )
            } else {
                val full = retriever.getFrameAtTime(
                    timeUs.coerceAtLeast(0L),
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: return null
                Bitmap.createScaledBitmap(full, targetWidth, targetHeight, true).also { scaled ->
                    if (scaled !== full) full.recycle()
                }
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun configureRetrieverSource(retriever: MediaMetadataRetriever, uri: Uri): Boolean {
        // Prefer ParcelFileDescriptor for SAF tree/document content:// URIs on Android & Googlebook OS
        val openedViaPfd = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
                true
            } ?: false
        }.getOrDefault(false)
        if (openedViaPfd) return true

        return runCatching {
            retriever.setDataSource(context, uri)
            true
        }.getOrDefault(false)
    }

    private fun pruneDiskCacheIfNeeded() {
        val files = diskCacheDir.listFiles() ?: return
        if (files.size <= MAX_DISK_CACHE_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_DISK_CACHE_FILES + 40)
            .forEach { runCatching { it.delete() } }
    }

    companion object {
        private const val MAX_DISK_CACHE_FILES = 320

        internal fun quantizeScrubBucketSeconds(targetTimeMs: Long): Long {
            val clampedSec = (targetTimeMs.coerceAtLeast(0L)) / 1_000L
            // 2-second keyframe buckets keep scrubbing snappy and hit memory cache during back-and-forth drags
            return (clampedSec / 2L) * 2L
        }

        internal fun computeRepresentativePosterTimeMs(durationMs: Long, resumePositionMs: Long? = null): Long {
            if (resumePositionMs != null && resumePositionMs > 1_000L) {
                return if (durationMs > 2_000L) {
                    resumePositionMs.coerceAtMost(durationMs - 1_000L)
                } else {
                    resumePositionMs
                }
            }
            if (durationMs <= 0L) return 4_000L
            // Pick ~12% into the video (capped at 15s) to avoid black 0:00 studio intro frames
            return (durationMs / 8L).coerceIn(1_000L, 15_000L).coerceAtMost((durationMs - 500L).coerceAtLeast(0L))
        }

        internal fun buildCacheKey(uriString: String, bucketSec: Long, width: Int, height: Int): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest("$uriString|$bucketSec|${width}x$height".toByteArray(Charsets.UTF_8))
            return bytes.take(16).joinToString("") { "%02x".format(it) }
        }
    }
}
