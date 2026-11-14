package app.lumina.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point for Lumina – HDR Media Player.
 * Configures Hilt dependency injection and disk/memory-cached video frame thumbnail decoding.
 */
@HiltAndroidApp
class LuminaApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("lumina_video_thumbnails"))
                    .maxSizeBytes(128L * 1024L * 1024L) // 128 MB persistent frame cache
                    .build()
            }
            .allowHardware(false)
            .crossfade(true)
            .build()
    }
}
