package app.lumina.player.player.ui

import android.graphics.Color as AndroidColor
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
 * - Keeps screen awake automatically while active.
 * - Preserves seamless playback across Foldable Tabletop transitions and Googlebook OS freeform
 *   window resizes via layout-change and Compose size listeners.
 * - Detaches cleanly on disposal without tearing down the underlying `LuminaPlayerEngine`.
 */
@Composable
fun LuminaVideoSurface(
    playerEngine: LuminaPlayerEngine,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val videoLayout = remember(context) {
        VLCVideoLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(AndroidColor.parseColor("#050507"))
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
            val widthChanged = (right - left) != (oldRight - oldLeft)
            val heightChanged = (bottom - top) != (oldBottom - oldTop)
            if (widthChanged || heightChanged) {
                playerEngine.onSurfaceBoundsChanged(videoLayout)
            }
        }
        videoLayout.addOnLayoutChangeListener(layoutChangeListener)
        playerEngine.attachVideoSurface(videoLayout)

        onDispose {
            videoLayout.removeOnLayoutChangeListener(layoutChangeListener)
            playerEngine.detachVideoSurface(videoLayout)
        }
    }

    Box(
        modifier = modifier
            .background(Color(0xFF050507))
            .onSizeChanged {
                playerEngine.onSurfaceBoundsChanged(videoLayout)
            }
    ) {
        AndroidView(
            factory = { videoLayout },
            update = { view ->
                view.keepScreenOn = isPlaying
                playerEngine.attachVideoSurface(view)
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
