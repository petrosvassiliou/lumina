package app.lumina.player.player.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Gravity
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.lumina.player.player.engine.LuminaPlayerEngine
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Exact centered child surface placement within a viewport container.
 */
internal data class CenteredSurfaceBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Computes symmetric centered bounds for a child video/subtitle surface inside [parentWidth] x [parentHeight].
 * Supports both letterboxed/pillarboxed (`FIT`, `16:9`, `4:3`, `1:1`) and symmetric crop-to-fill (`FILL`) bounds.
 */
internal fun computeCenteredSurfaceBounds(
    parentWidth: Int,
    parentHeight: Int,
    childWidth: Int,
    childHeight: Int
): CenteredSurfaceBounds {
    val safeParentW = parentWidth.coerceAtLeast(0)
    val safeParentH = parentHeight.coerceAtLeast(0)
    val safeChildW = childWidth.coerceAtLeast(0)
    val safeChildH = childHeight.coerceAtLeast(0)
    val left = (safeParentW - safeChildW) / 2
    val top = (safeParentH - safeChildH) / 2
    return CenteredSurfaceBounds(
        left = left,
        top = top,
        right = left + safeChildW,
        bottom = top + safeChildH
    )
}

/**
 * Centered subclass of libVLC 4.0 [VLCVideoLayout] designed specifically for Jetpack Compose [AndroidView]
 * interop, Portrait <-> Horizontal/Landscape transitions, Foldables, and Googlebook OS freeform windows.
 *
 * Solves two framework-level rendering issues:
 * 1. In `libvlc-all:4.0.0-eap29`, `vlc_video_layout.xml` omits `android:layout_gravity="center"` on
 *    `R.id.player_surface_frame` and `R.id.subtitles_surface_stub`. This layout enforces `Gravity.CENTER`
 *    across every internal [FrameLayout], [SurfaceView], and [TextureView] and verifies centered child
 *    coordinates during [onLayout] so videos can never pin to `(0, 0)` top-left.
 * 2. When `VideoHelper.onNewVideoLayout` asynchronously mutates `mVideoSurface.layoutParams` (`mPlaceWidth x mPlaceHeight`)
 *    without changing the outer `AndroidView` size, Jetpack Compose's `AndroidViewHolder` can skip re-measuring
 *    descendant `View`s. Overriding [requestLayout] schedules an explicit `measure(EXACTLY) + layout(...)` pass
 *    whenever `isLayoutRequested` remains pending.
 */
class LuminaCenteredVideoLayout(context: Context) : VLCVideoLayout(context) {

    private var measureAndLayoutPosted: Boolean = false

    private val measureAndLayoutRunnable = Runnable {
        measureAndLayoutPosted = false
        if (isAttachedToWindow && width > 0 && height > 0) {
            enforceCenteredChildHierarchy()
            if (isLayoutRequested || hasUnalignedRenderChild()) {
                measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
                )
                layout(left, top, right, bottom)
            } else {
                centerRenderedSurfacesInPlace()
            }
        }
    }

    init {
        clipChildren = true
        clipToPadding = true
        enforceCenteredChildHierarchy()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        enforceCenteredChildHierarchy()
        scheduleCenteredRelayout()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(measureAndLayoutRunnable)
        measureAndLayoutPosted = false
        super.onDetachedFromWindow()
    }

    override fun requestLayout() {
        super.requestLayout()
        if (!isInLayout && isAttachedToWindow && width > 0 && height > 0 && !measureAndLayoutPosted) {
            measureAndLayoutPosted = true
            post(measureAndLayoutRunnable)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        enforceCenteredChildHierarchy()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        enforceCenteredChildHierarchy()
        super.onLayout(changed, left, top, right, bottom)
        centerRenderedSurfacesInPlace()
    }

    fun scheduleCenteredRelayout() {
        if (!isAttachedToWindow) return
        enforceCenteredChildHierarchy()
        if (!measureAndLayoutPosted) {
            measureAndLayoutPosted = true
            post(measureAndLayoutRunnable)
        }
    }

    fun enforceCenteredChildHierarchy() {
        enforceFrameChildrenCentered(this, depth = 0)
    }

    private fun enforceFrameChildrenCentered(parent: FrameLayout, depth: Int) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) ?: continue
            val lp = child.layoutParams
            if (lp is LayoutParams) {
                if (lp.gravity != Gravity.CENTER) {
                    lp.gravity = Gravity.CENTER
                }
                // Keep outer FrameLayout & player_surface_frame spanning full viewport so
                // VideoHelper.updateVideoDimensions() always reports the full viewport size to vlcVout.setWindowSize()
                if (child is FrameLayout && depth < 2) {
                    if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    }
                    if (lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                    }
                }
            }
            if (child is FrameLayout && depth < 3) {
                enforceFrameChildrenCentered(child, depth + 1)
            }
        }
    }

    private fun hasUnalignedRenderChild(): Boolean {
        return checkUnalignedInFrame(this, depth = 0)
    }

    private fun checkUnalignedInFrame(parent: FrameLayout, depth: Int): Boolean {
        val pw = parent.width
        val ph = parent.height
        if (pw <= 0 || ph <= 0) return false
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) ?: continue
            if (child is ViewStub || child.visibility == View.GONE) continue
            if (child.isLayoutRequested) return true
            if (child is SurfaceView || child is TextureView) {
                val cw = child.width
                val ch = child.height
                if (cw > 0 && ch > 0) {
                    val expected = computeCenteredSurfaceBounds(pw, ph, cw, ch)
                    if (child.left != expected.left || child.top != expected.top) {
                        return true
                    }
                }
            } else if (child is FrameLayout && depth < 3) {
                if (child.width != pw || child.height != ph || child.left != 0 || child.top != 0) {
                    return true
                }
                if (checkUnalignedInFrame(child, depth + 1)) return true
            }
        }
        return false
    }

    private fun centerRenderedSurfacesInPlace() {
        centerChildrenInFrame(this, depth = 0)
    }

    private fun centerChildrenInFrame(parent: FrameLayout, depth: Int) {
        val pw = parent.width
        val ph = parent.height
        if (pw <= 0 || ph <= 0) return

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i) ?: continue
            if (child is ViewStub || child.visibility == View.GONE) continue

            if (child is FrameLayout && depth < 3) {
                if (child.left != 0 || child.top != 0 || child.right != pw || child.bottom != ph) {
                    child.layout(0, 0, pw, ph)
                }
                centerChildrenInFrame(child, depth + 1)
            } else if (child is SurfaceView || child is TextureView) {
                val lp = child.layoutParams
                val targetW = when {
                    lp != null && lp.width > 0 -> lp.width
                    child.measuredWidth > 0 -> child.measuredWidth
                    else -> pw
                }
                val targetH = when {
                    lp != null && lp.height > 0 -> lp.height
                    child.measuredHeight > 0 -> child.measuredHeight
                    else -> ph
                }
                val centered = computeCenteredSurfaceBounds(pw, ph, targetW, targetH)
                if (child.left != centered.left ||
                    child.top != centered.top ||
                    child.right != centered.right ||
                    child.bottom != centered.bottom
                ) {
                    child.layout(centered.left, centered.top, centered.right, centered.bottom)
                }
            }
        }
    }
}

/**
 * Hardware-accelerated HDR Compose video surface wrapping [LuminaCenteredVideoLayout] (`SurfaceView`).
 *
 * - Always supplies the unwrapped host [Activity] context to `VLCVideoLayout` so libVLC's
 *   `VideoHelper.updateVideoSurfaces()` (`mVideoSurfaceFrame.getContext() instanceof Activity`)
 *   computes exact surface dimensions across Hilt / per-app-locale context wrappers.
 * - Guarantees vertical and horizontal centering of every video aspect ratio and rotation across
 *   Portrait, Horizontal/Landscape, Foldable Tabletop, and Googlebook OS freeform windows.
 * - Preserves `VLCVideoLayout`'s native pure-black hole-punching without custom alpha/color layers.
 */
@Composable
fun LuminaVideoSurface(
    playerEngine: LuminaPlayerEngine,
    isPlaying: Boolean,
    zoomScale: Float = 1f,
    panOffsetX: Float = 0f,
    panOffsetY: Float = 0f,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val hostContext = remember(context) { context.findHostActivity() ?: context }

    val videoLayout = remember(hostContext) {
        LuminaCenteredVideoLayout(hostContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
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
                videoLayout.scheduleCenteredRelayout()
                playerEngine.onSurfaceBoundsChanged(videoLayout)
            }
        }

        val attachStateListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                playerEngine.attachVideoSurface(videoLayout)
                videoLayout.post {
                    if (videoLayout.isAttachedToWindow) {
                        playerEngine.onSurfaceBoundsChanged(videoLayout)
                    }
                }
            }

            override fun onViewDetachedFromWindow(v: View) {
                playerEngine.detachVideoSurface(videoLayout)
            }
        }

        videoLayout.addOnLayoutChangeListener(layoutChangeListener)
        videoLayout.addOnAttachStateChangeListener(attachStateListener)
        if (videoLayout.isAttachedToWindow) {
            playerEngine.attachVideoSurface(videoLayout)
            videoLayout.post {
                if (videoLayout.isAttachedToWindow) {
                    playerEngine.onSurfaceBoundsChanged(videoLayout)
                }
            }
        }

        onDispose {
            videoLayout.removeOnLayoutChangeListener(layoutChangeListener)
            videoLayout.removeOnAttachStateChangeListener(attachStateListener)
            playerEngine.detachVideoSurface(videoLayout)
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(Color.Black)
            .onSizeChanged { size ->
                if (videoLayout.isAttachedToWindow && size.width > 0 && size.height > 0) {
                    videoLayout.post {
                        if (videoLayout.isAttachedToWindow) {
                            playerEngine.onSurfaceBoundsChanged(videoLayout)
                        }
                    }
                }
            }
    ) {
        val safeScale = clampVideoZoomScale(zoomScale)
        val safePanX = if (safeScale <= 1.001f) 0f else panOffsetX
        val safePanY = if (safeScale <= 1.001f) 0f else panOffsetY
        AndroidView(
            factory = { videoLayout },
            update = { view ->
                view.keepScreenOn = isPlaying
                view.pivotX = view.width * 0.5f
                view.pivotY = view.height * 0.5f
                if (view.scaleX != safeScale) view.scaleX = safeScale
                if (view.scaleY != safeScale) view.scaleY = safeScale
                if (view.translationX != safePanX) view.translationX = safePanX
                if (view.translationY != safePanY) view.translationY = safePanY
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

internal fun clampVideoZoomScale(rawScale: Float): Float =
    rawScale.coerceIn(1.0f, 4.0f)

internal fun clampVideoPanOffset(
    rawPanPx: Float,
    zoomScale: Float,
    viewportDimensionPx: Float
): Float {
    val clampedScale = clampVideoZoomScale(zoomScale)
    if (clampedScale <= 1.001f || viewportDimensionPx <= 0f) return 0f
    val maxTranslation = ((clampedScale - 1.0f) * viewportDimensionPx * 0.5f).coerceAtLeast(0f)
    return rawPanPx.coerceIn(-maxTranslation, maxTranslation)
}

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext?.findHostActivity()
    else -> null
}
