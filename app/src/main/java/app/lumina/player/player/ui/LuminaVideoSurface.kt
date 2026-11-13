package app.lumina.player.player.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.lumina.player.player.engine.LuminaPlayerEngine
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Hardware-accelerated HDR Compose video surface wrapping libVLC 4.0 `VLCVideoLayout` (`SurfaceView`).
 *
 * - Always supplies the unwrapped host [Activity] context to `VLCVideoLayout` so libVLC's
 *   `VideoHelper.updateVideoSurfaces()` (`mVideoSurfaceFrame.getContext() instanceof Activity`)
 *   computes exact surface dimensions across Hilt / per-app-locale context wrappers.
 * - Preserves `VLCVideoLayout`'s native pure-black hole-punching without custom alpha/color layers.
 * - Preserves seamless playback across Foldable Tabletop transitions and Googlebook OS freeform
 *   window resizes via layout-change and Compose size listeners.
 */
@Composable
fun LuminaVideoSurface(
    playerEngine: LuminaPlayerEngine,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val hostContext = remember(context) { context.findHostActivity() ?: context }

    val videoLayout = remember(hostContext) {
        VLCVideoLayout(hostContext).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            fitsSystemWindows = false
            keepScreenOn = true
        }
    }

    DisposableEffect(lifecycleOwner, playerEngine) {
        lifecycleOwner.lifecycle.addObserver(playerEngine)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(playerEngine)
        }
    }

    DisposableEffect(videoLayout, playerEngine) {
        val layoutChangeListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            val widthChanged = width != (oldRight - oldLeft)
            val heightChanged = height != (oldBottom - oldTop)
            if (videoLayout.isAttachedToWindow && width > 0 && height > 0 && (widthChanged || heightChanged)) {
                playerEngine.onSurfaceBoundsChanged(videoLayout)
            }
        }

        val attachStateListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                playerEngine.attachVideoSurface(videoLayout)
            }

            override fun onViewDetachedFromWindow(v: View) {
                playerEngine.detachVideoSurface(videoLayout)
            }
        }

        videoLayout.addOnLayoutChangeListener(layoutChangeListener)
        videoLayout.addOnAttachStateChangeListener(attachStateListener)
        if (videoLayout.isAttachedToWindow) {
            playerEngine.attachVideoSurface(videoLayout)
        }

        onDispose {
            videoLayout.removeOnLayoutChangeListener(layoutChangeListener)
            videoLayout.removeOnAttachStateChangeListener(attachStateListener)
            playerEngine.detachVideoSurface(videoLayout)
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .onSizeChanged { size ->
                if (videoLayout.isAttachedToWindow && size.width > 0 && size.height > 0) {
                    playerEngine.onSurfaceBoundsChanged(videoLayout)
                }
            }
    ) {
        AndroidView(
            factory = { videoLayout },
            update = { view ->
                view.keepScreenOn = isPlaying
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext?.findHostActivity()
    else -> null
}
