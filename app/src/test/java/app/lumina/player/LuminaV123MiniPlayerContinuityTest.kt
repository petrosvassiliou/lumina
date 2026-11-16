package app.lumina.player

import android.view.ViewGroup
import app.lumina.player.player.engine.LuminaPlayerEngine
import app.lumina.player.player.model.AspectRatioMode
import app.lumina.player.player.ui.PipSourceBounds
import app.lumina.player.player.ui.computeCenteredSurfaceBounds
import app.lumina.player.player.ui.resolveTargetChildSurfaceDimension
import app.lumina.player.ui.player.clampFloatingMiniPlayerOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LuminaV123MiniPlayerContinuityTest {

    @Test
    fun `resolveTargetChildSurfaceDimension resolves MATCH_PARENT to PiP viewport instead of stale Fullscreen measured size`() {
        // Regression test for OpenGL glViewport(0, 0, 440, 248) rendering off-screen at (-980, -416)
        // when shrinking from 2400x1080 Fullscreen into a 440x248 Picture-in-Picture mini-player window:
        val pipTargetWidth = resolveTargetChildSurfaceDimension(
            parentDimension = 440,
            layoutParamDimension = ViewGroup.LayoutParams.MATCH_PARENT,
            measuredDimension = 2400
        )
        val pipTargetHeight = resolveTargetChildSurfaceDimension(
            parentDimension = 248,
            layoutParamDimension = ViewGroup.LayoutParams.MATCH_PARENT,
            measuredDimension = 1080
        )
        assertEquals(440, pipTargetWidth)
        assertEquals(248, pipTargetHeight)

        val centeredInPip = computeCenteredSurfaceBounds(
            parentWidth = 440,
            parentHeight = 248,
            childWidth = pipTargetWidth,
            childHeight = pipTargetHeight
        )
        assertEquals(0, centeredInPip.left)
        assertEquals(0, centeredInPip.top)
        assertEquals(440, centeredInPip.right)
        assertEquals(248, centeredInPip.bottom)

        // Explicit positive layoutParams (e.g., VideoHelper letterboxed 440x188 for 21:9 inside 16:9 PiP) are preserved:
        val letterboxedHeight = resolveTargetChildSurfaceDimension(
            parentDimension = 248,
            layoutParamDimension = 188,
            measuredDimension = 1080
        )
        assertEquals(188, letterboxedHeight)

        // Zero or unmeasured parent safely resolves to 0:
        assertEquals(
            0,
            resolveTargetChildSurfaceDimension(
                parentDimension = 0,
                layoutParamDimension = ViewGroup.LayoutParams.MATCH_PARENT,
                measuredDimension = 1920
            )
        )
    }

    @Test
    fun `computeClampedPipAspectRatio handles landscape vertical ultrawide and unknown streams`() {
        assertEquals(1778 to 1000, MainActivity.computeClampedPipAspectRatio(1920, 1080))
        assertEquals(563 to 1000, MainActivity.computeClampedPipAspectRatio(1080, 1920))
        assertEquals(2380 to 1000, MainActivity.computeClampedPipAspectRatio(3840, 1080))
        assertEquals(420 to 1000, MainActivity.computeClampedPipAspectRatio(400, 2000))
        assertEquals(16 to 9, MainActivity.computeClampedPipAspectRatio(0, 0))
    }

    @Test
    fun `sanitizePipSourceBounds fits centered video rect for Android 12+ seamless PiP resize`() {
        // 1. Portrait smartphone (1080x2400) playing 16:9 video -> centered 1080x607 sourceRectHint
        val portraitBounds = MainActivity.sanitizePipSourceBounds(
            bounds = PipSourceBounds(0, 0, 1080, 2400),
            windowWidth = 1080,
            windowHeight = 2400,
            aspectRatioNum = 1778,
            aspectRatioDen = 1000
        )
        assertNotNull(portraitBounds)
        assertEquals(0, portraitBounds!!.left)
        assertEquals(896, portraitBounds.top)
        assertEquals(1080, portraitBounds.right)
        assertEquals(1503, portraitBounds.bottom)
        assertTrue(portraitBounds.isValid)

        // 2. Landscape smartphone (2400x1080) playing 16:9 video -> centered 1920x1080 sourceRectHint
        val landscapeBounds = MainActivity.sanitizePipSourceBounds(
            bounds = PipSourceBounds(0, 0, 2400, 1080),
            windowWidth = 2400,
            windowHeight = 1080,
            aspectRatioNum = 1778,
            aspectRatioDen = 1000
        )
        assertNotNull(landscapeBounds)
        assertEquals(240, landscapeBounds!!.left)
        assertEquals(0, landscapeBounds.top)
        assertEquals(2160, landscapeBounds.right)
        assertEquals(1080, landscapeBounds.bottom)

        // 3. Out-of-window bounds are clamped cleanly to window coordinates
        val clamped = MainActivity.sanitizePipSourceBounds(
            bounds = PipSourceBounds(-40, -20, 2500, 1200),
            windowWidth = 1920,
            windowHeight = 1080
        )
        assertEquals(PipSourceBounds(0, 0, 1920, 1080), clamped)

        // 4. Degenerate or < 16px bounds return null so setSeamlessResizeEnabled(false) avoids black crop
        assertNull(
            MainActivity.sanitizePipSourceBounds(
                bounds = PipSourceBounds(0, 0, 8, 8),
                windowWidth = 1080,
                windowHeight = 2400
            )
        )
        assertNull(MainActivity.sanitizePipSourceBounds(bounds = null, windowWidth = 1080, windowHeight = 2400))
    }

    @Test
    fun `PipSourceBounds approximate equality prevents redundant setPictureInPictureParams churn`() {
        val base = PipSourceBounds(240, 0, 2160, 1080)
        assertTrue(base.isApproximatelyEqual(PipSourceBounds(241, 1, 2159, 1080), tolerancePx = 2))
        assertFalse(base.isApproximatelyEqual(PipSourceBounds(246, 0, 2160, 1080), tolerancePx = 2))
        assertFalse(base.isApproximatelyEqual(null))
    }

    @Test
    fun `shouldKeepVideoSurfaceActiveOnStop prevents video track disable during PiP entry races`() {
        // Even if Activity.onStop arrives before Compose/engine state flips, Activity.isInPictureInPictureMode protects video
        assertTrue(
            LuminaPlayerEngine.shouldKeepVideoSurfaceActiveOnStop(
                isStateInPipOrMiniPlayer = false,
                isActivityInPipMode = true
            )
        )
        // Pre-signaled engine state protects video before Activity.isInPictureInPictureMode flips
        assertTrue(
            LuminaPlayerEngine.shouldKeepVideoSurfaceActiveOnStop(
                isStateInPipOrMiniPlayer = true,
                isActivityInPipMode = false
            )
        )
        assertTrue(
            LuminaPlayerEngine.shouldKeepVideoSurfaceActiveOnStop(
                isStateInPipOrMiniPlayer = true,
                isActivityInPipMode = true
            )
        )
        // Normal app backgrounding outside PiP/Mini-Player allows background audio / pause policy
        assertFalse(
            LuminaPlayerEngine.shouldKeepVideoSurfaceActiveOnStop(
                isStateInPipOrMiniPlayer = false,
                isActivityInPipMode = false
            )
        )
    }

    @Test
    fun `resolveEffectiveSurfaceAspectMode uses FIT in PiP or MiniPlayer and restores user aspect in Fullscreen`() {
        for (mode in AspectRatioMode.entries) {
            assertEquals(
                AspectRatioMode.FIT,
                LuminaPlayerEngine.resolveEffectiveSurfaceAspectMode(
                    userAspectMode = mode,
                    isInPipOrMiniPlayer = true
                )
            )
            assertEquals(
                mode,
                LuminaPlayerEngine.resolveEffectiveSurfaceAspectMode(
                    userAspectMode = mode,
                    isInPipOrMiniPlayer = false
                )
            )
        }
    }

    @Test
    fun `clampFloatingMiniPlayerOffset keeps Googlebook floating mini-player inside viewport bounds`() {
        assertEquals(
            12f,
            clampFloatingMiniPlayerOffset(
                rawOffsetPx = -120f,
                cardDimensionPx = 380f,
                viewportDimensionPx = 1280f,
                edgeMarginPx = 12f
            ),
            0.01f
        )
        assertEquals(
            888f,
            clampFloatingMiniPlayerOffset(
                rawOffsetPx = 1400f,
                cardDimensionPx = 380f,
                viewportDimensionPx = 1280f,
                edgeMarginPx = 12f
            ),
            0.01f
        )
        // Compact freeform window narrower than card + margin never throws IllegalArgumentException
        assertEquals(
            0f,
            clampFloatingMiniPlayerOffset(
                rawOffsetPx = 50f,
                cardDimensionPx = 380f,
                viewportDimensionPx = 360f,
                edgeMarginPx = 12f
            ),
            0.01f
        )
    }
}
