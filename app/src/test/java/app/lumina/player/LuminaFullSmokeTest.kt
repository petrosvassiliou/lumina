package app.lumina.player

import app.lumina.player.data.download.AdmDownloadPlanner
import app.lumina.player.data.download.DownloadStatus
import app.lumina.player.data.download.HlsParsedPlaylist
import app.lumina.player.data.preferences.HardwareAccelerationMode
import app.lumina.player.data.preferences.SubtitleBackgroundStyle
import app.lumina.player.data.preferences.SubtitleFontScale
import app.lumina.player.data.preferences.ToneMappingAlgorithm
import app.lumina.player.data.preferences.UserPreferences
import app.lumina.player.data.repository.MediaRepository
import app.lumina.player.player.engine.LuminaPlayerEngine
import app.lumina.player.player.model.ABRepeatState
import app.lumina.player.player.model.AspectRatioMode
import app.lumina.player.player.model.EqualizerBand
import app.lumina.player.player.model.HdrFormat
import app.lumina.player.player.model.HdrPipelineStatus
import app.lumina.player.player.model.LuminaPlayerState
import app.lumina.player.player.model.PlaybackStatus
import app.lumina.player.player.model.PlayerOrientationMode
import app.lumina.player.player.model.SleepTimerMode
import app.lumina.player.player.model.SleepTimerState
import app.lumina.player.ui.components.formatDurationMs
import app.lumina.player.util.SupportedLuminaLocale
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end automated smoke test suite verifying commands, menu/enum state machines,
 * HDR & audio classifiers, ADM HLS `#EXT-X-MAP` byte-range/encryption parsing,
 * locale-invariant formatting, and playback engine invariants across Lumina v1.2.4.
 */
class LuminaFullSmokeTest {

    @Test
    fun `inferHdrBadgeFromFilename detects delimited Dolby Vision and HDR formats without DVD or Adventure false positives`() {
        // True Dolby Vision filenames
        assertEquals("Dolby Vision", MediaRepository.inferHdrBadgeFromFilename("Dune.Part.Two.2024.2160p.DV.TrueHD.mkv"))
        assertEquals("Dolby Vision", MediaRepository.inferHdrBadgeFromFilename("Blade Runner 2049 [DV] [HEVC].mkv"))
        assertEquals("Dolby Vision", MediaRepository.inferHdrBadgeFromFilename("Sample_DV_Profile8.mp4"))
        assertEquals("Dolby Vision", MediaRepository.inferHdrBadgeFromFilename("Cinema-DV-Demo.ts"))
        assertEquals("Dolby Vision", MediaRepository.inferHdrBadgeFromFilename("Oppenheimer.DoVi.HDR10.mkv"))
        assertEquals("Dolby Vision", MediaRepository.inferHdrBadgeFromFilename("Planet.Earth.Dolby.Vision.UHD.mkv"))
        assertEquals("Dolby Vision", MediaRepository.inferHdrBadgeFromFilename("Cosmos_Dolby-Vision_60fps.mp4"))

        // Substring false-positive traps ("DVD", "Adventure", "Advisor") MUST remain SDR (null)
        assertNull(MediaRepository.inferHdrBadgeFromFilename("Family_Vacation_DVD_Rip.mp4"))
        assertNull(MediaRepository.inferHdrBadgeFromFilename("Adventure_Time_S01E01_1080p.mkv"))
        assertNull(MediaRepository.inferHdrBadgeFromFilename("Grand_Adventures_in_Alps.mov"))
        assertNull(MediaRepository.inferHdrBadgeFromFilename("Financial_Advisor_Keynote.mp4"))

        // Other HDR formats
        assertEquals("HDR10+", MediaRepository.inferHdrBadgeFromFilename("Samsung_Wonderland_HDR10+_Demo.mkv"))
        assertEquals("HDR10+", MediaRepository.inferHdrBadgeFromFilename("Clip.HDR10PLUS.2160p.mp4"))
        assertEquals("HLG", MediaRepository.inferHdrBadgeFromFilename("BBC_Live_UHD_HLG_Broadcast.ts"))
        assertEquals("HDR10", MediaRepository.inferHdrBadgeFromFilename("Interstellar.2160p.HDR10.HEVC.mkv"))
        assertEquals("HDR10", MediaRepository.inferHdrBadgeFromFilename("Nature.2160p.HDR.10bit.mkv"))
    }

    @Test
    fun `parseHlsPlaylist extracts EXT-X-MAP BYTERANGE and active AES-128 key for single-file fMP4 streams`() {
        val singleFileFmp4M3u8 = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example.com/drm/init.key",IV=0x00000000000000000000000000000001
            #EXT-X-MAP:URI="feature_uhd.mp4",BYTERANGE="820@0"
            #EXTINF:6.000,
            #EXT-X-BYTERANGE:1048576@820
            feature_uhd.mp4
            #EXTINF:6.000,
            #EXT-X-BYTERANGE:1048576
            feature_uhd.mp4
            #EXT-X-ENDLIST
        """.trimIndent()

        val parsed = AdmDownloadPlanner.parseHlsPlaylist(
            playlistText = singleFileFmp4M3u8,
            basePlaylistUrl = "https://cdn.example.com/vod/4k/stream.m3u8"
        )
        assertTrue(parsed is HlsParsedPlaylist.Media)
        val media = parsed as HlsParsedPlaylist.Media

        assertEquals("https://cdn.example.com/vod/4k/feature_uhd.mp4", media.initMapUrl)
        assertEquals(0L, media.initMapByteRangeOffset)
        assertEquals(820L, media.initMapByteRangeLength)
        assertNotNull(media.initMapEncryptionKey)
        assertEquals("AES-128", media.initMapEncryptionKey?.method)
        assertEquals("https://keys.example.com/drm/init.key", media.initMapEncryptionKey?.keyUrl)

        assertEquals(2, media.segments.size)
        assertEquals(820L, media.segments[0].byteRangeOffset)
        assertEquals(1_048_576L, media.segments[0].byteRangeLength)
        assertEquals(820L + 1_048_576L, media.segments[1].byteRangeOffset)
        assertEquals(1_048_576L, media.segments[1].byteRangeLength)
    }

    @Test
    fun `isLikelyAudioOnlyMedia identifies local M3U and LAN audio tracks while excluding video containers`() {
        // Explicit audio/* MIME type
        assertTrue(
            LuminaPlayerEngine.isLikelyAudioOnlyMedia(
                mimeType = "audio/flac",
                title = "01 - Solar Sailer",
                uriString = "content://media/external/audio/media/42"
            )
        )
        // Null MIME type from M3U / SMB / HTTP with query string & fragment
        assertTrue(
            LuminaPlayerEngine.isLikelyAudioOnlyMedia(
                mimeType = null,
                title = "Symphony No. 9",
                uriString = "https://cdn.example.com/lossless/track09.flac?token=xyz#start"
            )
        )
        assertTrue(
            LuminaPlayerEngine.isLikelyAudioOnlyMedia(
                mimeType = null,
                title = "Podcast_Episode_42.opus",
                uriString = "smb://nas.local/Audio/stream_id_99"
            )
        )

        // Video containers & HLS manifests must NOT be treated as audio-only
        assertFalse(
            LuminaPlayerEngine.isLikelyAudioOnlyMedia(
                mimeType = "video/x-matroska",
                title = "Dune Part Two",
                uriString = "content://media/external/video/media/101"
            )
        )
        assertFalse(
            LuminaPlayerEngine.isLikelyAudioOnlyMedia(
                mimeType = null,
                title = "Live Sports UHD",
                uriString = "https://cdn.example.com/live/master.m3u8?token=abc"
            )
        )
        assertFalse(
            LuminaPlayerEngine.isLikelyAudioOnlyMedia(
                mimeType = null,
                title = "HomeClip.mp4",
                uriString = "file:///storage/emulated/0/DCIM/Camera/HomeClip.mp4"
            )
        )
    }

    @Test
    fun `player command cycles cover all AspectRatioMode PlayerOrientationMode and SleepTimerMode states`() {
        // Orientation cycle: AUTO -> LANDSCAPE -> PORTRAIT -> AUTO
        var orientation = PlayerOrientationMode.AUTO
        orientation = orientation.next()
        assertEquals(PlayerOrientationMode.LANDSCAPE, orientation)
        orientation = orientation.next()
        assertEquals(PlayerOrientationMode.PORTRAIT, orientation)
        orientation = orientation.next()
        assertEquals(PlayerOrientationMode.AUTO, orientation)

        // Aspect ratio cycle: 7 distinct presets returning cleanly to FIT
        val visited = mutableListOf<AspectRatioMode>()
        var aspect = AspectRatioMode.FIT
        repeat(AspectRatioMode.entries.size) {
            visited += aspect
            aspect = aspect.next()
        }
        assertEquals(AspectRatioMode.entries, visited)
        assertEquals(AspectRatioMode.FIT, aspect)
        assertEquals(AspectRatioMode.RATIO_21_9, AspectRatioMode.fromNameOrDefault("RATIO_21_9"))
        assertEquals(AspectRatioMode.FIT, AspectRatioMode.fromNameOrDefault("UNKNOWN_PRESET"))
        assertEquals(AspectRatioMode.FIT, AspectRatioMode.fromNameOrDefault(null))

        // Sleep timer & A-B repeat state checks
        assertFalse(SleepTimerState(mode = SleepTimerMode.OFF).isActive)
        assertTrue(SleepTimerState(mode = SleepTimerMode.MIN_30, remainingMs = 1_800_000L).isActive)
        assertTrue(SleepTimerState(mode = SleepTimerMode.END_OF_MEDIA, remainingMs = 0L).isActive)

        val loop = ABRepeatState(pointAMs = 10_000L, pointBMs = 25_000L)
        assertTrue(loop.isActive)
        assertFalse(LuminaPlayerEngine.shouldClearAbRepeatOnManualSeek(loop, 15_000L))
        assertTrue(LuminaPlayerEngine.shouldClearAbRepeatOnManualSeek(loop, 30_000L))
        assertTrue(LuminaPlayerEngine.shouldClearAbRepeatOnManualSeek(loop, 4_000L))
    }

    @Test
    fun `locale-sensitive formatters and SupportedLuminaLocale resolve deterministically across all 6 languages`() {
        val previousLocale = Locale.getDefault()
        try {
            // Under comma-decimal locale (German), Equalizer kHz labels & durations must stay dot-formatted
            Locale.setDefault(Locale.GERMANY)
            assertEquals("62", EqualizerBand(index = 1, frequencyHz = 62.5f, gainDb = 0f).formattedFrequency)
            assertEquals("1k", EqualizerBand(index = 5, frequencyHz = 1000f, gainDb = 0f).formattedFrequency)
            assertEquals("1.5k", EqualizerBand(index = 6, frequencyHz = 1500f, gainDb = 0f).formattedFrequency)
            assertEquals("16k", EqualizerBand(index = 9, frequencyHz = 16000f, gainDb = 0f).formattedFrequency)

            assertEquals("00:00", formatDurationMs(0L))
            assertEquals("00:00", formatDurationMs(-500L))
            assertEquals("05:09", formatDurationMs(309_000L))
            assertEquals("2:04:07", formatDurationMs(7_447_000L))
        } finally {
            Locale.setDefault(previousLocale)
        }

        assertEquals(SupportedLuminaLocale.SYSTEM, SupportedLuminaLocale.fromTag(""))
        assertEquals(SupportedLuminaLocale.ENGLISH, SupportedLuminaLocale.fromTag("en-US"))
        assertEquals(SupportedLuminaLocale.GERMAN, SupportedLuminaLocale.fromTag("de-DE"))
        assertEquals(SupportedLuminaLocale.ITALIAN, SupportedLuminaLocale.fromTag("it-IT"))
        assertEquals(SupportedLuminaLocale.FRENCH, SupportedLuminaLocale.fromTag("fr-FR"))
        assertEquals(SupportedLuminaLocale.SPANISH, SupportedLuminaLocale.fromTag("es-419"))
        assertEquals(SupportedLuminaLocale.GREEK, SupportedLuminaLocale.fromTag("el-GR"))
        assertEquals(SupportedLuminaLocale.SYSTEM, SupportedLuminaLocale.fromTag("ja-JP"))
    }

    @Test
    fun `UserPreferences DownloadStatus HdrPipelineStatus and codec label helpers enforce strict bounds`() {
        val prefs = UserPreferences(equalizerBandsCsv = "-25, -4.5, 0, 3.25, 24, 1, 2, 3, 4, 5")
        val bands = prefs.parsedEqualizerBands()
        assertEquals(10, bands.size)
        assertEquals(-20f, bands[0], 0.001f)
        assertEquals(-4.5f, bands[1], 0.001f)
        assertEquals(20f, bands[4], 0.001f)

        val corruptedPrefs = UserPreferences(equalizerBandsCsv = "bad,csv")
        assertEquals(List(10) { 0f }, corruptedPrefs.parsedEqualizerBands())

        // Verify all preference enums have expected entries
        assertEquals(4, HardwareAccelerationMode.entries.size)
        assertEquals(4, ToneMappingAlgorithm.entries.size)
        assertEquals(4, SubtitleFontScale.entries.size)
        assertEquals(3, SubtitleBackgroundStyle.entries.size)

        // DownloadStatus state classifications
        assertEquals(DownloadStatus.QUEUED, DownloadStatus.fromRaw(null))
        assertEquals(DownloadStatus.DOWNLOADING, DownloadStatus.fromRaw("downloading"))
        assertEquals(DownloadStatus.FAILED, DownloadStatus.fromRaw("corrupt_state"))
        assertTrue(DownloadStatus.DOWNLOADING.isActive)
        assertTrue(DownloadStatus.PAUSED.isResumable)
        assertTrue(DownloadStatus.FAILED.isResumable)
        assertFalse(DownloadStatus.COMPLETED.isActive)
        assertFalse(DownloadStatus.COMPLETED.isResumable)

        // HDR & codec telemetry helpers
        assertFalse(HdrFormat.SDR.isHdr)
        assertTrue(HdrFormat.DOLBY_VISION.isHdr)
        assertTrue(HdrPipelineStatus(displaySupportsHdr10Plus = true).displaySupportsAnyHdr)
        assertFalse(HdrPipelineStatus().displaySupportsAnyHdr)
        assertEquals("Dolby Vision", LuminaPlayerEngine.formatConciseVideoCodecLabel("dvhe.08.06 (Dolby Vision)"))
        assertEquals("HEVC", LuminaPlayerEngine.formatConciseVideoCodecLabel("MPEG-H Part2/HEVC (H.265)"))
        assertEquals("AV1", LuminaPlayerEngine.formatConciseVideoCodecLabel("AOMedia Video 1 (AV01)"))

        // Default idle player state bounds
        val idleState = LuminaPlayerState(status = PlaybackStatus.IDLE)
        assertNull(idleState.currentMedia)
        assertFalse(idleState.hasNext)
        assertFalse(idleState.hasPrevious)
    }
}
