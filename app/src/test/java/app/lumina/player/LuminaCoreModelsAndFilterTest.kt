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
}
