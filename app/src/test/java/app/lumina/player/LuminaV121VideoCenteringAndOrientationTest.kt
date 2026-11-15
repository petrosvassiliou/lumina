package app.lumina.player

import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import app.lumina.player.data.preferences.HardwareAccelerationMode
import app.lumina.player.player.engine.LuminaDecoderPolicy
import app.lumina.player.player.engine.LuminaPlayerEngine
import app.lumina.player.player.engine.ProbedVideoStreamInfo
import app.lumina.player.player.engine.ResolvedVlcDecoderMode
import app.lumina.player.player.model.HdrFormat
import app.lumina.player.player.model.PlayerOrientationMode
import app.lumina.player.player.ui.computeCenteredSurfaceBounds
import app.lumina.player.ui.adaptive.LuminaLayoutMode
import app.lumina.player.ui.adaptive.resolveAdaptiveLayoutMode
import app.lumina.player.ui.adaptive.resolveIsDesktopEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.videolan.libvlc.interfaces.IMedia

class LuminaV121VideoCenteringAndOrientationTest {

    @Test
    fun `computeCenteredSurfaceBounds centers portrait landscape foldable and overscan surfaces symmetrically`() {
        // 1. Unfolded foldable viewport (987x1024) playing 576x1024 upright video (VID-20261109-WA0000)
        val foldablePortraitVideo = computeCenteredSurfaceBounds(
            parentWidth = 987,
            parentHeight = 1024,
            childWidth = 576,
            childHeight = 1024
        )
        assertEquals(205, foldablePortraitVideo.left)
        assertEquals(0, foldablePortraitVideo.top)
        assertEquals(781, foldablePortraitVideo.right)
        assertEquals(1024, foldablePortraitVideo.bottom)
        assertEquals(576, foldablePortraitVideo.width)
        assertEquals(1024, foldablePortraitVideo.height)

        // 2. Portrait phone viewport (1080x2400) playing 16:9 horizontal video (1080x608) -> vertically centered!
        val portraitPhoneHorizontalVideo = computeCenteredSurfaceBounds(
            parentWidth = 1080,
            parentHeight = 2400,
            childWidth = 1080,
            childHeight = 608
        )
        assertEquals(0, portraitPhoneHorizontalVideo.left)
        assertEquals(896, portraitPhoneHorizontalVideo.top)
        assertEquals(1080, portraitPhoneHorizontalVideo.right)
        assertEquals(1504, portraitPhoneHorizontalVideo.bottom)

        // 3. Horizontal / Landscape phone viewport (2400x1080) playing 16:9 video (1920x1080) -> horizontally centered!
        val landscapePhone16x9 = computeCenteredSurfaceBounds(
            parentWidth = 2400,
            parentHeight = 1080,
            childWidth = 1920,
            childHeight = 1080
        )
        assertEquals(240, landscapePhone16x9.left)
        assertEquals(0, landscapePhone16x9.top)
        assertEquals(2160, landscapePhone16x9.right)
        assertEquals(1080, landscapePhone16x9.bottom)

        // 4. Fill / Crop-to-Screen mode (oversized child 2400x1350 inside 2400x1080) -> symmetric negative top/bottom crop
        val cropFillBounds = computeCenteredSurfaceBounds(
            parentWidth = 2400,
            parentHeight = 1080,
            childWidth = 2400,
            childHeight = 1350
        )
        assertEquals(0, cropFillBounds.left)
        assertEquals(-135, cropFillBounds.top)
        assertEquals(2400, cropFillBounds.right)
        assertEquals(1215, cropFillBounds.bottom)
    }

    @Test
    fun `ProbedVideoStreamInfo normalizes rotation metadata and routes rotated videos away from UpdateASCGeometry crop bug`() {
        // Reproduces VID-20261109-WA0000: coded 1024x576 H.264 @ 30fps with 90-degree container rotation -> 576x1024 upright
        val rotatedWhatsappVideo = ProbedVideoStreamInfo(
            mimeType = "video/avc",
            width = 1024,
            height = 576,
            frameRate = 30f,
            profile = 8,
            colorTransfer = 3,
            hasTrueHardwareDecoderSupport = true,
            rotationDegrees = 90
        )
        assertEquals(90, rotatedWhatsappVideo.normalizedRotationDegrees)
        assertTrue(rotatedWhatsappVideo.isRotatedQuarterTurn)
        assertEquals(576, rotatedWhatsappVideo.displayWidth)
        assertEquals(1024, rotatedWhatsappVideo.displayHeight)

        // Even if user explicitly selected FULL_ACCELERATION, 90/270-degree rotated streams must use
        // HARDWARE_NO_DIRECT_RENDERING (:no-mediacodec-dr -> OpenGL ES 2) so libVLC 4.0 UpdateASCGeometry
        // never crops post-rotated 576x1024 buffers to unswapped 1024x576 bounds!
        assertEquals(
            ResolvedVlcDecoderMode.HARDWARE_NO_DIRECT_RENDERING,
            LuminaDecoderPolicy.resolveDecoderMode(
                userPreference = HardwareAccelerationMode.FULL_ACCELERATION,
                probedInfo = rotatedWhatsappVideo
            )
        )

        // Rotated 270-degree (-90) 4K HDR10 vertical camera clip also routes safely to HARDWARE_NO_DIRECT_RENDERING
        val rotatedHdrPortraitClip = ProbedVideoStreamInfo(
            mimeType = "video/hevc",
            width = 3840,
            height = 2160,
            frameRate = 59.94f,
            profile = 2,
            colorTransfer = ProbedVideoStreamInfo.COLOR_TRANSFER_ST2084,
            hasTrueHardwareDecoderSupport = true,
            rotationDegrees = -90
        )
        assertEquals(270, rotatedHdrPortraitClip.normalizedRotationDegrees)
        assertTrue(rotatedHdrPortraitClip.isRotatedQuarterTurn)
        assertEquals(2160, rotatedHdrPortraitClip.displayWidth)
        assertEquals(3840, rotatedHdrPortraitClip.displayHeight)
        assertEquals(
            ResolvedVlcDecoderMode.HARDWARE_NO_DIRECT_RENDERING,
            LuminaDecoderPolicy.resolveDecoderMode(
                userPreference = HardwareAccelerationMode.AUTOMATIC,
                probedInfo = rotatedHdrPortraitClip,
                hdrHint = HdrFormat.HDR10
            )
        )

        // Unrotated (0 or 180 deg) 4K HDR10 cinema stream keeps zero-copy HARDWARE_DIRECT_RENDERING
        val unrotatedHdr180 = rotatedHdrPortraitClip.copy(rotationDegrees = 180)
        assertFalse(unrotatedHdr180.isRotatedQuarterTurn)
        assertEquals(3840, unrotatedHdr180.displayWidth)
        assertEquals(2160, unrotatedHdr180.displayHeight)
        assertEquals(
            ResolvedVlcDecoderMode.HARDWARE_DIRECT_RENDERING,
            LuminaDecoderPolicy.resolveDecoderMode(
                userPreference = HardwareAccelerationMode.AUTOMATIC,
                probedInfo = unrotatedHdr180,
                hdrHint = HdrFormat.HDR10
            )
        )
    }

    @Test
    fun `resolveDisplayVideoDimensions swaps quarter-turn VLC orientations and applies SAR pixel aspect ratio`() {
        // 90-degree RightTop / LeftBottom swaps 1024x576 -> 576x1024
        assertEquals(
            576 to 1024,
            LuminaPlayerEngine.resolveDisplayVideoDimensions(
                rawWidth = 1024,
                rawHeight = 576,
                vlcOrientation = IMedia.VideoTrack.Orientation.RightTop
            )
        )
        assertEquals(
            576 to 1024,
            LuminaPlayerEngine.resolveDisplayVideoDimensions(
                rawWidth = 1024,
                rawHeight = 576,
                vlcOrientation = IMedia.VideoTrack.Orientation.LeftBottom
            )
        )

        // Normal TopLeft & 180-degree BottomRight preserve landscape 1920x1080
        assertEquals(
            1920 to 1080,
            LuminaPlayerEngine.resolveDisplayVideoDimensions(
                rawWidth = 1920,
                rawHeight = 1080,
                vlcOrientation = IMedia.VideoTrack.Orientation.TopLeft
            )
        )
        assertEquals(
            1920 to 1080,
            LuminaPlayerEngine.resolveDisplayVideoDimensions(
                rawWidth = 1920,
                rawHeight = 1080,
                vlcOrientation = IMedia.VideoTrack.Orientation.BottomRight
            )
        )

        // Anamorphic DVD 720x576 with SAR 64:45 -> 1024x576 display dimensions
        assertEquals(
            1024 to 576,
            LuminaPlayerEngine.resolveDisplayVideoDimensions(
                rawWidth = 720,
                rawHeight = 576,
                vlcOrientation = IMedia.VideoTrack.Orientation.TopLeft,
                sarNum = 64,
                sarDen = 45
            )
        )
    }

    @Test
    fun `formatConciseVideoCodecLabel normalizes verbose libVLC codec strings for crisp mobile header typography`() {
        assertEquals("HEVC", LuminaPlayerEngine.formatConciseVideoCodecLabel("MPEG-H Part2/HEVC (H.265)"))
        assertEquals("HEVC", LuminaPlayerEngine.formatConciseVideoCodecLabel("MPEG-H PART2/HEVC (H.265)"))
        assertEquals("H.264", LuminaPlayerEngine.formatConciseVideoCodecLabel("H264 - MPEG-4 AVC (part 10) (avc1)"))
        assertEquals("AV1", LuminaPlayerEngine.formatConciseVideoCodecLabel("AOMedia's AV1 Video"))
        assertEquals("VP9", LuminaPlayerEngine.formatConciseVideoCodecLabel("Google/On2's VP9 Video"))
        assertEquals("Dolby Vision", LuminaPlayerEngine.formatConciseVideoCodecLabel("Dolby Vision HEVC (dvhe)"))
        assertEquals("MPEG-4", LuminaPlayerEngine.formatConciseVideoCodecLabel("MPEG-4 Video (mp4v)"))
        assertEquals("ProRes", LuminaPlayerEngine.formatConciseVideoCodecLabel("Apple ProRes 422 HQ"))
        assertEquals(null, LuminaPlayerEngine.formatConciseVideoCodecLabel("   "))
        assertEquals(null, LuminaPlayerEngine.formatConciseVideoCodecLabel(null))
    }

    @Test
    fun `PlayerOrientationMode cycles deterministically and smart fullscreen auto-landscape respects video geometry`() {
        assertEquals(PlayerOrientationMode.LANDSCAPE, PlayerOrientationMode.AUTO.next())
        assertEquals(PlayerOrientationMode.PORTRAIT, PlayerOrientationMode.LANDSCAPE.next())
        assertEquals(PlayerOrientationMode.AUTO, PlayerOrientationMode.PORTRAIT.next())

        // Horizontal 16:9 video on portrait phone entering fullscreen -> auto-rotates to horizontal landscape!
        assertTrue(
            MainActivity.shouldAutoRotateToLandscapeOnFullscreen(
                enteringFullscreen = true,
                currentOrientationMode = PlayerOrientationMode.AUTO,
                isCurrentlyLandscape = false,
                isGooglebookOrDesktop = false,
                isTabletopMode = false,
                isInMultiWindowMode = false,
                videoWidth = 1920,
                videoHeight = 1080
            )
        )

        // Upright 9:16 portrait video (576x1024) entering fullscreen -> stays portrait!
        assertFalse(
            MainActivity.shouldAutoRotateToLandscapeOnFullscreen(
                enteringFullscreen = true,
                currentOrientationMode = PlayerOrientationMode.AUTO,
                isCurrentlyLandscape = false,
                isGooglebookOrDesktop = false,
                isTabletopMode = false,
                isInMultiWindowMode = false,
                videoWidth = 576,
                videoHeight = 1024
            )
        )

        // Desktop / Googlebook OS and Foldable Tabletop never force sensor orientation override on fullscreen
        assertFalse(
            MainActivity.shouldAutoRotateToLandscapeOnFullscreen(
                enteringFullscreen = true,
                currentOrientationMode = PlayerOrientationMode.AUTO,
                isCurrentlyLandscape = false,
                isGooglebookOrDesktop = true,
                isTabletopMode = false,
                isInMultiWindowMode = false,
                videoWidth = 1920,
                videoHeight = 1080
            )
        )
        assertFalse(
            MainActivity.shouldAutoRotateToLandscapeOnFullscreen(
                enteringFullscreen = true,
                currentOrientationMode = PlayerOrientationMode.AUTO,
                isCurrentlyLandscape = false,
                isGooglebookOrDesktop = false,
                isTabletopMode = true,
                isInMultiWindowMode = false,
                videoWidth = 1920,
                videoHeight = 1080
            )
        )
    }

    @Test
    fun `resolveIsDesktopEnvironment keeps landscape phones and touch foldables in MEDIUM_ADAPTIVE with full touch and orientation controls`() {
        // Smartphone rotated to Horizontal / Landscape (914x411dp -> Expanded width, Compact height, smallestWidth = 411dp)
        val landscapePhoneIsDesktop = resolveIsDesktopEnvironment(
            hasPcFeature = false,
            hasHardwareKeyboard = false,
            widthSizeClass = WindowWidthSizeClass.Expanded,
            heightSizeClass = WindowHeightSizeClass.Compact,
            smallestScreenWidthDp = 411
        )
        assertFalse(landscapePhoneIsDesktop)
        assertEquals(
            LuminaLayoutMode.MEDIUM_ADAPTIVE,
            resolveAdaptiveLayoutMode(
                isTabletopPosture = false,
                isDesktopEnvironment = landscapePhoneIsDesktop,
                widthSizeClass = WindowWidthSizeClass.Expanded
            )
        )

        // Unfolded touch foldable (880x840dp -> Expanded width, Medium height, smallestWidth = 673dp, no hardware keyboard)
        val unfoldedFoldableIsDesktop = resolveIsDesktopEnvironment(
            hasPcFeature = false,
            hasHardwareKeyboard = false,
            widthSizeClass = WindowWidthSizeClass.Expanded,
            heightSizeClass = WindowHeightSizeClass.Medium,
            smallestScreenWidthDp = 673
        )
        assertFalse(unfoldedFoldableIsDesktop)
        assertEquals(
            LuminaLayoutMode.MEDIUM_ADAPTIVE,
            resolveAdaptiveLayoutMode(
                isTabletopPosture = false,
                isDesktopEnvironment = unfoldedFoldableIsDesktop,
                widthSizeClass = WindowWidthSizeClass.Expanded
            )
        )

        // Googlebook OS laptop / Chromebox (hasPcFeature = true) -> GOOGLEBOOK_DESKTOP
        val googlebookIsDesktop = resolveIsDesktopEnvironment(
            hasPcFeature = true,
            hasHardwareKeyboard = true,
            widthSizeClass = WindowWidthSizeClass.Expanded,
            heightSizeClass = WindowHeightSizeClass.Medium,
            smallestScreenWidthDp = 800
        )
        assertTrue(googlebookIsDesktop)
        assertEquals(
            LuminaLayoutMode.GOOGLEBOOK_DESKTOP,
            resolveAdaptiveLayoutMode(
                isTabletopPosture = false,
                isDesktopEnvironment = googlebookIsDesktop,
                widthSizeClass = WindowWidthSizeClass.Expanded
            )
        )
    }
}
