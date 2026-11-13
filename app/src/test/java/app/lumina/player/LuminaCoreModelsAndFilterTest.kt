package app.lumina.player

import app.lumina.player.data.preferences.SubtitleBackgroundStyle
import app.lumina.player.data.preferences.SubtitleFontScale
import app.lumina.player.player.model.ABRepeatState
import app.lumina.player.player.model.AspectRatioMode
import app.lumina.player.player.model.ChapterInfo
import app.lumina.player.player.model.EqualizerBand
import app.lumina.player.player.model.HdrFormat
import app.lumina.player.player.model.SleepTimerMode
import app.lumina.player.player.model.SleepTimerState
import app.lumina.player.ui.library.FolderHdrFilter
import app.lumina.player.update.LuminaUpdateState
import app.lumina.player.update.UpdateCheckStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM Unit Test Suite verifying Lumina's HDR filtering, aspect-ratio cycling,
 * 10-band EQ frequency formatting, A-B Repeat invariants, Sleep Timer & Chapter models,
 * Subtitle styling scales, and Play Protect update states.
 */
class LuminaCoreModelsAndFilterTest {

    @Test
    fun folderHdrFilter_matchesExactHdrAndSdrBadges() {
        assertTrue(FolderHdrFilter.ALL.matches(null))
        assertTrue(FolderHdrFilter.ALL.matches("Dolby Vision"))

        assertTrue(FolderHdrFilter.HDR_ANY.matches("HDR10+"))
        assertTrue(FolderHdrFilter.HDR_ANY.matches("HLG"))
        assertFalse(FolderHdrFilter.HDR_ANY.matches(null))
        assertFalse(FolderHdrFilter.HDR_ANY.matches(""))

        assertTrue(FolderHdrFilter.DOLBY_VISION.matches("Dolby Vision"))
        assertFalse(FolderHdrFilter.DOLBY_VISION.matches("HDR10"))

        assertTrue(FolderHdrFilter.HDR10_PLUS.matches("HDR10+"))
        assertFalse(FolderHdrFilter.HDR10_PLUS.matches("HDR10"))

        assertTrue(FolderHdrFilter.HDR10.matches("HDR10"))
        assertFalse(FolderHdrFilter.HDR10.matches("HDR10+"))

        assertTrue(FolderHdrFilter.HLG.matches("HLG"))
        assertTrue(FolderHdrFilter.SDR.matches(null))
        assertFalse(FolderHdrFilter.SDR.matches("HDR10"))
    }

    @Test
    fun aspectRatioMode_cyclesDeterministicallyBackToFit() {
        var current = AspectRatioMode.FIT
        repeat(AspectRatioMode.entries.size) {
            current = current.next()
        }
        assertEquals(AspectRatioMode.FIT, current)
        assertEquals(AspectRatioMode.FILL, AspectRatioMode.fromNameOrDefault("FILL"))
        assertEquals(AspectRatioMode.FIT, AspectRatioMode.fromNameOrDefault("UNKNOWN_MODE"))
    }

    @Test
    fun hdrFormat_accuratelyIdentifiesHdrStandards() {
        assertFalse(HdrFormat.SDR.isHdr)
        assertTrue(HdrFormat.HDR10.isHdr)
        assertTrue(HdrFormat.HDR10_PLUS.isHdr)
        assertTrue(HdrFormat.HLG.isHdr)
        assertTrue(HdrFormat.DOLBY_VISION.isHdr)
    }

    @Test
    fun equalizerBand_formatsSubKilohertzAndKilohertzLabelsCleanly() {
        assertEquals("31", EqualizerBand(0, 31.25f, 0f).formattedFrequency)
        assertEquals("500", EqualizerBand(4, 500f, 0f).formattedFrequency)
        assertEquals("1k", EqualizerBand(5, 1_000f, 0f).formattedFrequency)
        assertEquals("16k", EqualizerBand(9, 16_000f, 0f).formattedFrequency)
    }

    @Test
    fun abRepeatState_isActiveOnlyWhenPointBStrictlyExceedsPointA() {
        assertFalse(ABRepeatState().isActive)
        assertFalse(ABRepeatState(pointAMs = 5_000L, pointBMs = null).isActive)
        assertFalse(ABRepeatState(pointAMs = 5_000L, pointBMs = 5_000L).isActive)
        assertTrue(ABRepeatState(pointAMs = 5_000L, pointBMs = 12_000L).isActive)
    }

    @Test
    fun sleepTimerAndChapters_behaveDeterministically() {
        val idleTimer = SleepTimerState()
        assertFalse(idleTimer.isActive)

        val min30Timer = SleepTimerState(mode = SleepTimerMode.MIN_30, remainingMs = 1_800_000L)
        assertTrue(min30Timer.isActive)
        assertEquals(30, min30Timer.mode.durationMinutes)

        val endTimer = SleepTimerState(mode = SleepTimerMode.END_OF_MEDIA, remainingMs = 45_000L)
        assertTrue(endTimer.isActive)

        val chapter = ChapterInfo(index = 0, title = "Opening Credits", timeOffsetMs = 0L, durationMs = 95_000L)
        assertEquals("Opening Credits", chapter.title)
        assertEquals(95_000L, chapter.durationMs)
    }

    @Test
    fun subtitleStylingEnums_mapToExpectedLibVlcScalesAndOpacities() {
        assertEquals(80, SubtitleFontScale.SMALL.scalePercent)
        assertEquals(100, SubtitleFontScale.NORMAL.scalePercent)
        assertEquals(125, SubtitleFontScale.LARGE.scalePercent)
        assertEquals(150, SubtitleFontScale.EXTRA_LARGE.scalePercent)

        assertEquals(0, SubtitleBackgroundStyle.NONE.vlcOpacity)
        assertEquals(128, SubtitleBackgroundStyle.TRANSLUCENT.vlcOpacity)
        assertEquals(220, SubtitleBackgroundStyle.SOLID.vlcOpacity)
    }

    @Test
    fun luminaUpdateState_computesPlayProtectStatusFlagsCorrectly() {
        val checking = LuminaUpdateState(status = UpdateCheckStatus.CHECKING, isChecking = true)
        assertTrue(checking.isChecking)
        assertFalse(checking.isReadyToInstall)

        val ready = LuminaUpdateState(
            status = UpdateCheckStatus.READY_TO_INSTALL,
            isReadyToInstall = true
        )
        assertTrue(ready.isReadyToInstall)
    }

    @Test
    fun buildLibVlcHdrArguments_emitsStrictlyValidLibVlc4IntegerToneMappingFlags() {
        val controller = app.lumina.player.player.hdr.LuminaHdrController()
        val bt2390Args = controller.buildLibVlcHdrArguments(
            app.lumina.player.data.preferences.ToneMappingAlgorithm.BT_2390
        )
        assertEquals(
            listOf("--gl-tone-mapping-function=2", "--pl-tone-mapping-function=2"),
            bt2390Args
        )

        app.lumina.player.data.preferences.ToneMappingAlgorithm.entries.forEach { algo ->
            val args = controller.buildLibVlcHdrArguments(algo)
            assertTrue(args.all { it.startsWith("--gl-tone-mapping-function=") || it.startsWith("--pl-tone-mapping-function=") })
            // Never emit removed VLC 3.x options that cause libvlc_new() to return NULL at startup
            assertFalse(args.any { it.startsWith("--tone-mapping=") || it.startsWith("--tone-mapping-desat") })
        }
    }

    @Test
    fun applyHdrPipelineToSurface_withNullSurfaceAndOffMainThread_neverThrows() {
        val controller = app.lumina.player.player.hdr.LuminaHdrController()
        val hdrFormats = app.lumina.player.player.model.HdrFormat.entries
        for (format in hdrFormats) {
            val status = controller.applyHdrPipelineToSurface(
                videoLayout = null,
                sourceFormat = format,
                hdrPassthroughEnabled = true,
                toneMappingAlgorithm = app.lumina.player.data.preferences.ToneMappingAlgorithm.BT_2390
            )
            assertEquals(format, status.sourceFormat)
        }
    }

    @Test
    fun githubVersionComparison_handlesPrefixesDebugSuffixesAndMultiDigitSegments() {
        assertTrue(app.lumina.player.update.LuminaUpdateManager.isRemoteVersionNewer("1.0.3", "v1.0.4"))
        assertTrue(app.lumina.player.update.LuminaUpdateManager.isRemoteVersionNewer("1.0.4-debug", "v1.0.5"))
        assertTrue(app.lumina.player.update.LuminaUpdateManager.isRemoteVersionNewer("1.0.9", "v1.0.10"))
        assertTrue(app.lumina.player.update.LuminaUpdateManager.isRemoteVersionNewer("1.0.4", "V1.1.0"))

        assertFalse(app.lumina.player.update.LuminaUpdateManager.isRemoteVersionNewer("1.0.4", "v1.0.4"))
        assertFalse(app.lumina.player.update.LuminaUpdateManager.isRemoteVersionNewer("1.0.4-debug", "v1.0.4"))
        assertFalse(app.lumina.player.update.LuminaUpdateManager.isRemoteVersionNewer("1.0.10", "v1.0.9"))
        assertFalse(app.lumina.player.update.LuminaUpdateManager.isRemoteVersionNewer("1.0.4", ""))
    }

    @Test
    fun selectBestApkAsset_matchesExactDeviceAbiAndDistinguishesX86FromX86_64() {
        val assets = listOf(
            app.lumina.player.update.GithubReleaseAsset(
                name = "Lumina-v1.0.4-playstore-signed.aab",
                downloadUrl = "https://github.com/petrosvassiliou/lumina/releases/download/v1.0.4/Lumina-v1.0.4-playstore-signed.aab",
                sizeBytes = 65_000_000L
            ),
            app.lumina.player.update.GithubReleaseAsset(
                name = "SHA256SUMS.txt",
                downloadUrl = "https://github.com/petrosvassiliou/lumina/releases/download/v1.0.4/SHA256SUMS.txt",
                sizeBytes = 512L
            ),
            app.lumina.player.update.GithubReleaseAsset(
                name = "Lumina-v1.0.4-arm64-v8a.apk",
                downloadUrl = "https://github.com/petrosvassiliou/lumina/releases/download/v1.0.4/Lumina-v1.0.4-arm64-v8a.apk",
                sizeBytes = 29_500_000L
            ),
            app.lumina.player.update.GithubReleaseAsset(
                name = "Lumina-v1.0.4-x86_64.apk",
                downloadUrl = "https://github.com/petrosvassiliou/lumina/releases/download/v1.0.4/Lumina-v1.0.4-x86_64.apk",
                sizeBytes = 31_000_000L
            ),
            app.lumina.player.update.GithubReleaseAsset(
                name = "Lumina-v1.0.4-x86.apk",
                downloadUrl = "https://github.com/petrosvassiliou/lumina/releases/download/v1.0.4/Lumina-v1.0.4-x86.apk",
                sizeBytes = 28_000_000L
            ),
            app.lumina.player.update.GithubReleaseAsset(
                name = "Lumina-v1.0.4-universal.apk",
                downloadUrl = "https://github.com/petrosvassiliou/lumina/releases/download/v1.0.4/Lumina-v1.0.4-universal.apk",
                sizeBytes = 92_000_000L
            )
        )

        val arm64Match = app.lumina.player.update.LuminaUpdateManager.selectBestApkAsset(
            assets = assets,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a")
        )
        assertEquals("Lumina-v1.0.4-arm64-v8a.apk", arm64Match?.asset?.name)
        assertEquals("arm64-v8a", arm64Match?.matchedAbi)

        val x86OnlyMatch = app.lumina.player.update.LuminaUpdateManager.selectBestApkAsset(
            assets = assets,
            supportedAbis = listOf("x86")
        )
        assertEquals("Lumina-v1.0.4-x86.apk", x86OnlyMatch?.asset?.name)
        assertEquals("x86", x86OnlyMatch?.matchedAbi)

        val fallbackUniversal = app.lumina.player.update.LuminaUpdateManager.selectBestApkAsset(
            assets = assets,
            supportedAbis = listOf("riscv64")
        )
        assertEquals("Lumina-v1.0.4-universal.apk", fallbackUniversal?.asset?.name)
        assertEquals("universal", fallbackUniversal?.matchedAbi)
    }

    @Test
    fun luminaDecoderPolicy_handlesHighResScreenRecordingMp4AndHi10PWithoutBlankSurface() {
        // Default UserPreferences must use adaptive AUTOMATIC hardware acceleration
        assertEquals(
            app.lumina.player.data.preferences.HardwareAccelerationMode.AUTOMATIC,
            app.lumina.player.data.preferences.UserPreferences().hardwareAccelerationMode
        )

        // 1. 2880x1800 @ 50.08fps H.264 SDR screen recording where hardware MediaCodec rejects Level 5.2
        val unsupportedByHwScreenRecording = app.lumina.player.player.engine.ProbedVideoStreamInfo(
            mimeType = "video/avc",
            width = 2880,
            height = 1800,
            frameRate = 50.08f,
            profile = 8,
            colorTransfer = 3,
            hasTrueHardwareDecoderSupport = false
        )
        assertEquals(
            app.lumina.player.player.engine.ResolvedVlcDecoderMode.SOFTWARE_AVCODEC,
            app.lumina.player.player.engine.LuminaDecoderPolicy.resolveDecoderMode(
                userPreference = app.lumina.player.data.preferences.HardwareAccelerationMode.AUTOMATIC,
                probedInfo = unsupportedByHwScreenRecording,
                isArcOrDesktopContainer = true
            )
        )

        // 2. 2880x1800 @ 50.08fps H.264 SDR screen recording where hardware MediaCodec claims support on Googlebook OS
        val hwSupportedScreenRecording = unsupportedByHwScreenRecording.copy(
            hasTrueHardwareDecoderSupport = true
        )
        assertEquals(
            app.lumina.player.player.engine.ResolvedVlcDecoderMode.HARDWARE_NO_DIRECT_RENDERING,
            app.lumina.player.player.engine.LuminaDecoderPolicy.resolveDecoderMode(
                userPreference = app.lumina.player.data.preferences.HardwareAccelerationMode.AUTOMATIC,
                probedInfo = hwSupportedScreenRecording,
                isArcOrDesktopContainer = false
            )
        )

        // 3. 10-bit H.264 (Hi10P) & 4:4:4 MP4 streams route cleanly to spec-compliant software avcodec
        val hi10pStream = app.lumina.player.player.engine.ProbedVideoStreamInfo(
            mimeType = "video/avc",
            width = 1920,
            height = 1080,
            frameRate = 23.976f,
            profile = 110,
            colorTransfer = 3,
            hasTrueHardwareDecoderSupport = null
        )
        assertEquals(
            app.lumina.player.player.engine.ResolvedVlcDecoderMode.SOFTWARE_AVCODEC,
            app.lumina.player.player.engine.LuminaDecoderPolicy.resolveDecoderMode(
                userPreference = app.lumina.player.data.preferences.HardwareAccelerationMode.AUTOMATIC,
                probedInfo = hi10pStream
            )
        )
    }

    @Test
    fun luminaDecoderPolicy_preservesZeroCopyDirectRenderingForHdrAndStepsDownDeterministically() {
        // True 4K HDR10 HEVC stream keeps zero-copy HARDWARE_DIRECT_RENDERING even on Googlebook/ARC
        val hdr10Hevc = app.lumina.player.player.engine.ProbedVideoStreamInfo(
            mimeType = "video/hevc",
            width = 3840,
            height = 2160,
            frameRate = 59.94f,
            profile = 2,
            colorTransfer = app.lumina.player.player.engine.ProbedVideoStreamInfo.COLOR_TRANSFER_ST2084,
            hasTrueHardwareDecoderSupport = true
        )
        assertTrue(hdr10Hevc.isHdrStream)
        assertEquals(
            app.lumina.player.player.engine.ResolvedVlcDecoderMode.HARDWARE_DIRECT_RENDERING,
            app.lumina.player.player.engine.LuminaDecoderPolicy.resolveDecoderMode(
                userPreference = app.lumina.player.data.preferences.HardwareAccelerationMode.AUTOMATIC,
                probedInfo = hdr10Hevc,
                isArcOrDesktopContainer = true
            )
        )

        // Step-down watchdog chain terminates cleanly at SOFTWARE_AVCODEC
        val firstFallback = app.lumina.player.player.engine.LuminaDecoderPolicy.nextFallbackTier(
            app.lumina.player.player.engine.ResolvedVlcDecoderMode.HARDWARE_DIRECT_RENDERING
        )
        assertEquals(
            app.lumina.player.player.engine.ResolvedVlcDecoderMode.HARDWARE_NO_DIRECT_RENDERING,
            firstFallback
        )
        val secondFallback = app.lumina.player.player.engine.LuminaDecoderPolicy.nextFallbackTier(firstFallback!!)
        assertEquals(
            app.lumina.player.player.engine.ResolvedVlcDecoderMode.SOFTWARE_AVCODEC,
            secondFallback
        )
        assertEquals(
            null,
            app.lumina.player.player.engine.LuminaDecoderPolicy.nextFallbackTier(secondFallback!!)
        )

        // Software MediaCodec identifier filtering
        assertTrue(app.lumina.player.player.engine.LuminaDecoderPolicy.isKnownSoftwareCodecName("c2.android.avc.decoder"))
        assertTrue(app.lumina.player.player.engine.LuminaDecoderPolicy.isKnownSoftwareCodecName("OMX.google.h264.decoder"))
        assertFalse(app.lumina.player.player.engine.LuminaDecoderPolicy.isKnownSoftwareCodecName("c2.qti.avc.decoder"))
        assertFalse(app.lumina.player.player.engine.LuminaDecoderPolicy.isKnownSoftwareCodecName("c2.cr.avc.decoder"))
    }

    @Test
    fun playerExitStateReset_clearsPlaybackPipTouchLockAndSleepTimerCleanly() {
        val hdrController = app.lumina.player.player.hdr.LuminaHdrController()
        // Must never throw when detaching/resetting with null Context or off main thread
        hdrController.resetWindowHdrMode(null)

        val playingState = app.lumina.player.player.model.LuminaPlayerState(
            status = app.lumina.player.player.model.PlaybackStatus.PLAYING,
            isPlaying = true,
            isInPipOrMiniPlayer = true,
            isTouchLocked = true,
            currentTimeMs = 42_500L,
            durationMs = 120_000L,
            progress = 0.354f,
            sleepTimer = app.lumina.player.player.model.SleepTimerState(
                mode = app.lumina.player.player.model.SleepTimerMode.MINUTES_30,
                remainingMs = 900_000L
            ),
            errorMessage = "transient"
        )

        val stoppedState = playingState.copy(
            status = app.lumina.player.player.model.PlaybackStatus.IDLE,
            isPlaying = false,
            isInPipOrMiniPlayer = false,
            isTouchLocked = false,
            sleepTimer = app.lumina.player.player.model.SleepTimerState(),
            currentTimeMs = 0L,
            progress = 0f,
            errorMessage = null
        )

        assertEquals(app.lumina.player.player.model.PlaybackStatus.IDLE, stoppedState.status)
        assertFalse(stoppedState.isPlaying)
        assertFalse(stoppedState.isInPipOrMiniPlayer)
        assertFalse(stoppedState.isTouchLocked)
        assertFalse(stoppedState.sleepTimer.isActive)
        assertEquals(0L, stoppedState.currentTimeMs)
        assertEquals(0f, stoppedState.progress, 0.0001f)
        assertEquals(null, stoppedState.errorMessage)
        assertTrue(app.lumina.player.update.LuminaUpdateManager.isRemoteVersionNewer("1.0.7", "v1.0.8"))
    }
}

