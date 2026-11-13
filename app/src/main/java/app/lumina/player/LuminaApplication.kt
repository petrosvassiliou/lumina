package app.lumina.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point for Lumina – HDR Media Player.
 * Configures Hilt dependency injection and hardware-accelerated video frame thumbnail decoding.
 */
@HiltAndroidApp
class LuminaApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .allowHardware(false)
            .crossfade(true)
            .build()
    }
}
