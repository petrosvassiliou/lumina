package app.lumina.player.player.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.view.Gravity
import android.view.SurfaceHolder
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import app.lumina.player.player.engine.LuminaPlayerEngine
import kotlin.math.abs
import kotlin.math.roundToInt
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Pure-Kotlin window-space video bounds used for Android 12+ Picture-in-Picture `sourceRectHint`.
 */
data class PipSourceBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val isValid: Boolean get() = width >= 16 && height >= 16 && right > left && bottom > top

    fun isApproximatelyEqual(other: PipSourceBounds?, tolerancePx: Int = 2): Boolean {
        if (other == null) return false
        return abs(left - other.left) <= tolerancePx &&
            abs(top - other.top) <= tolerancePx &&
            abs(right - other.right) <= tolerancePx &&
            abs(bottom - other.bottom) <= tolerancePx
    }

    fun toAndroidRect(): Rect = Rect(left, top, right, bottom)
}

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
 * Resolves the target child `SurfaceView` / `TextureView` dimension inside [parentDimension].
 *
 * Ensures `MATCH_PARENT` (`-1`) immediately resolves to [parentDimension] rather than falling back
 * to a stale Fullscreen [measuredDimension] when shrinking into a compact PiP / Mini-Player window.
 */
internal fun resolveTargetChildSurfaceDimension(
    parentDimension: Int,
    layoutParamDimension: Int,
    measuredDimension: Int
): Int {
    val safeParent = parentDimension.coerceAtLeast(0)
    if (safeParent <= 0) return 0
    return when {
        layoutParamDimension == ViewGroup.LayoutParams.MATCH_PARENT -> safeParent
        layoutParamDimension > 0 -> layoutParamDimension
        measuredDimension > 0 -> measuredDimension
        else -> safeParent
    }
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
 * interop, Portrait <-> Horizontal/Landscape transitions, Picture-in-Picture (PiP), Foldables, and
 * Googlebook OS freeform / floating mini-player windows.
 *
 * Solves three framework-level rendering issues:
 * 1. In `libvlc-all:4.0.0-eap29`, `vlc_video_layout.xml` omits `android:layout_gravity="center"` on
 *    `R.id.player_surface_frame` and `R.id.subtitles_surface_stub`. This layout enforces `Gravity.CENTER`
 *    across every internal [FrameLayout], [SurfaceView], and [TextureView] and verifies centered child
 *    coordinates during [onLayout] so videos can never pin to `(0, 0)` top-left.
 * 2. When `VideoHelper` mutates `mVideoSurface.layoutParams` inside `OnLayoutChangeListener` (while
 *    `ViewRootImpl` still has `isInLayout == true`) or asynchronously on native vout callbacks,
 *    Jetpack Compose's `AndroidViewHolder` can skip re-measuring descendant `View`s. Overriding
 *    [requestLayout] schedules an explicit `measure(EXACTLY) + layout(...)` pass on the next Looper tick.
 * 3. Attaches a [SurfaceHolder.Callback] directly to the primary video [SurfaceView] so asynchronous
 *    hardware `Surface` buffer resizes during PiP / Mini-Player transitions immediately synchronize
 *    `vlcVout.setWindowSize` and OpenGL / MediaCodec surface bounds.
 */
class LuminaCenteredVideoLayout(context: Context) : VLCVideoLayout(context) {

    var onSurfaceGeometryChanged: (() -> Unit)? = null

    private var measureAndLayoutPosted: Boolean = false
    private var observedPrimarySurfaceView: SurfaceView? = null

    private val primarySurfaceHolderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (isAttachedToWindow) {
                scheduleCenteredRelayout()
                onSurfaceGeometryChanged?.invoke()
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (isAttachedToWindow && width > 0 && height > 0) {
                scheduleCenteredRelayout()
                onSurfaceGeometryChanged?.invoke()
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
    }

    private val measureAndLayoutRunnable = Runnable {
        try {
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
        } finally {
            measureAndLayoutPosted = false
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
        detachPrimarySurfaceHolderCallback()
        super.onDetachedFromWindow()
    }

    override fun requestLayout() {
        super.requestLayout()
        if (isAttachedToWindow && width > 0 && height > 0 && !measureAndLayoutPosted) {
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
        ensurePrimarySurfaceHolderCallback()
    }

    private fun ensurePrimarySurfaceHolderCallback() {
        val primary = findFirstSurfaceView(this)
        if (primary === observedPrimarySurfaceView) return
        detachPrimarySurfaceHolderCallback()
        if (primary != null) {
            runCatching { primary.holder?.addCallback(primarySurfaceHolderCallback) }
            observedPrimarySurfaceView = primary
        }
    }

    private fun detachPrimarySurfaceHolderCallback() {
        val prev = observedPrimarySurfaceView
        observedPrimarySurfaceView = null
        if (prev != null) {
            runCatching { prev.holder?.removeCallback(primarySurfaceHolderCallback) }
        }
    }

    private fun findFirstSurfaceView(view: View?): SurfaceView? {
        if (view == null) return null
        if (view is SurfaceView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findFirstSurfaceView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
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
                // VideoHelper.updateVideoSurfaces() always reports the full viewport size to vlcVout.setWindowSize()
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
                val lp = child.layoutParams
                val targetW = resolveTargetChildSurfaceDimension(
                    parentDimension = pw,
                    layoutParamDimension = lp?.width ?: ViewGroup.LayoutParams.MATCH_PARENT,
                    measuredDimension = child.measuredWidth
                )
                val targetH = resolveTargetChildSurfaceDimension(
                    parentDimension = ph,
                    layoutParamDimension = lp?.height ?: ViewGroup.LayoutParams.MATCH_PARENT,
                    measuredDimension = child.measuredHeight
                )
                if (targetW > 0 && targetH > 0) {
                    val expected = computeCenteredSurfaceBounds(pw, ph, targetW, targetH)
                    if (child.left != expected.left ||
                        child.top != expected.top ||
                        child.width != targetW ||
                        child.height != targetH
                    ) {
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
                val targetW = resolveTargetChildSurfaceDimension(
                    parentDimension = pw,
                    layoutParamDimension = lp?.width ?: ViewGroup.LayoutParams.MATCH_PARENT,
                    measuredDimension = child.measuredWidth
                )
                val targetH = resolveTargetChildSurfaceDimension(
                    parentDimension = ph,
                    layoutParamDimension = lp?.height ?: ViewGroup.LayoutParams.MATCH_PARENT,
                    measuredDimension = child.measuredHeight
                )
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
 * - Mounted once at the root of `LuminaPlayerScreen` so entering or exiting Android Picture-in-Picture,
 *   Foldable Tabletop, or Googlebook OS Floating Mini-Player **never** destroys or detaches the
 *   hardware `SurfaceView` mid-playback.
 * - Reports window-space bounds via [onVideoBoundsInWindowChanged] so Android 12+ seamless PiP resize
 *   animates from exact `sourceRectHint` coordinates without cropping to black.
 */
@Composable
fun LuminaVideoSurface(
    playerEngine: LuminaPlayerEngine,
    isPlaying: Boolean,
    zoomScale: Float = 1f,
    panOffsetX: Float = 0f,
    panOffsetY: Float = 0f,
    onVideoBoundsInWindowChanged: ((PipSourceBounds) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hostContext = remember(context) { context.findHostActivity() ?: context }
    val latestBoundsCallback by rememberUpdatedState(onVideoBoundsInWindowChanged)
    var lastReportedPipBounds by remember { mutableStateOf<PipSourceBounds?>(null) }

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

    DisposableEffect(videoLayout, playerEngine) {
        videoLayout.onSurfaceGeometryChanged = {
            if (videoLayout.isAttachedToWindow) {
                playerEngine.onSurfaceBoundsChanged(videoLayout)
            }
        }

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
                videoLayout.enforceCenteredChildHierarchy()
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
            videoLayout.enforceCenteredChildHierarchy()
            videoLayout.post {
                if (videoLayout.isAttachedToWindow) {
                    playerEngine.onSurfaceBoundsChanged(videoLayout)
                }
            }
        }

        onDispose {
            videoLayout.onSurfaceGeometryChanged = null
            videoLayout.removeOnLayoutChangeListener(layoutChangeListener)
            videoLayout.removeOnAttachStateChangeListener(attachStateListener)
            playerEngine.detachVideoSurface(videoLayout)
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(Color.Black)
            .onGloballyPositioned { coordinates ->
                val callback = latestBoundsCallback ?: return@onGloballyPositioned
                if (!coordinates.isAttached) return@onGloballyPositioned
                val windowRect = coordinates.boundsInWindow()
                val candidate = PipSourceBounds(
                    left = windowRect.left.roundToInt(),
                    top = windowRect.top.roundToInt(),
                    right = windowRect.right.roundToInt(),
                    bottom = windowRect.bottom.roundToInt()
                )
                if (candidate.isValid && !candidate.isApproximatelyEqual(lastReportedPipBounds)) {
                    lastReportedPipBounds = candidate
                    callback(candidate)
                }
            }
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

