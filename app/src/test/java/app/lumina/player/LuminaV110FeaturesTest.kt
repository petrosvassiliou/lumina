package app.lumina.player

import app.lumina.player.data.local.entity.PlaylistEntryEntity
import app.lumina.player.data.network.LuminaCredentialVault
import app.lumina.player.data.network.LuminaLanDiscoveryManager
import app.lumina.player.data.playlist.M3uPlaylistCodec
import app.lumina.player.data.repository.LuminaThumbnailRepository
import app.lumina.player.player.engine.LuminaPlayerEngine
import app.lumina.player.player.ui.clampVideoPanOffset
import app.lumina.player.player.ui.clampVideoZoomScale
import app.lumina.player.update.LuminaUpdateManager
import java.nio.charset.StandardCharsets
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.videolan.libvlc.interfaces.IMedia

/**
 * Unit test suite verifying all Lumina v1.1.0 upgrades:
 * 1. Interactive SMB / UPnP / DLNA / FTP directory classification & AES-256-GCM credential vault.
 * 2. Googlebook OS drag-and-drop classifiers (subtitles vs `.m3u8` playlists vs HLS streams) & 1.0x–4.0x video zoom/pan bounds.
 * 3. Representative video poster timestamps & quantized timeline scrub preview frame caching.
 * 4. Extended M3U (`.m3u` / `.m3u8`) round-trip serialization, parsing, and background audio continuation rules.
 */
class LuminaV110FeaturesTest {

    @Test
    fun m3uCodec_roundTripsExtendedPlaylistMetadataAndNetworkStreams() {
        val originalEntries = listOf(
            PlaylistEntryEntity(
                playlistId = 7L,
                mediaUri = "content://com.android.externalstorage.documents/document/Movies%2FDune_Part_Two_DV.mkv",
                title = "Dune: Part Two (Dolby Vision)",
                durationMs = 9_960_000L,
                isNetworkStream = false,
                positionIndex = 0
            ),
            PlaylistEntryEntity(
                playlistId = 7L,
                mediaUri = "smb://nas-cinema.local/uhd/BladeRunner2049_HDR10.mkv",
                title = "Blade Runner 2049",
                subtitle = "SMB",
                durationMs = 9_840_000L,
                isNetworkStream = true,
                positionIndex = 1
            )
        )

        val serialized = M3uPlaylistCodec.serializeM3u("Reference HDR Cinema", originalEntries)
        assertTrue(serialized.startsWith("#EXTM3U"))
        assertTrue(serialized.contains("#PLAYLIST:Reference HDR Cinema"))
        assertTrue(serialized.contains("#EXTINF:9960,Dune: Part Two (Dolby Vision)"))

        val parsed = M3uPlaylistCodec.parseM3u(serialized, fallbackPlaylistName = "Fallback")
        assertEquals("Reference HDR Cinema", parsed.playlistName)
        assertEquals(2, parsed.entries.size)

        assertEquals("Dune: Part Two (Dolby Vision)", parsed.entries[0].title)
        assertEquals(9_960_000L, parsed.entries[0].durationMs)
        assertFalse(parsed.entries[0].isNetworkStream)
        assertEquals(0, parsed.entries[0].positionIndex)

        assertEquals("Blade Runner 2049", parsed.entries[1].title)
        assertEquals(9_840_000L, parsed.entries[1].durationMs)
        assertTrue(parsed.entries[1].isNetworkStream)
        assertEquals("SMB", parsed.entries[1].subtitle)
        assertEquals(1, parsed.entries[1].positionIndex)
    }

    @Test
    fun m3uCodec_handlesBomWindowsNewlinesCommentsAndDerivedTitles() {
        val raw = "\uFEFF#EXTM3U\r\n# Comment line\r\n\r\nsmb://nas/Lossless/01%20-%20Solar%20Sailer.flac\r\nhttps://cdn.example.com/live/master.m3u8\r\n"
        val parsed = M3uPlaylistCodec.parseM3u(raw, fallbackPlaylistName = "Imported Mix")

        assertEquals("Imported Mix", parsed.playlistName)
        assertEquals(2, parsed.entries.size)
        assertEquals("01 - Solar Sailer", parsed.entries[0].title)
        assertTrue(parsed.entries[0].isNetworkStream)
        assertEquals("master", parsed.entries[1].title)
        assertEquals("HTTPS", parsed.entries[1].subtitle)
    }

    @Test
    fun dragDropClassifiers_distinguishSubtitlesLocalM3uAndRemoteHlsStreams() {
        assertTrue(M3uPlaylistCodec.isSubtitleFileName("Movie.eng.srt"))
        assertTrue(M3uPlaylistCodec.isSubtitleFileName("file:///storage/emulated/0/Subs/anime.ASS"))
        assertTrue(M3uPlaylistCodec.isSubtitleFileName("content://provider/doc/12", mimeType = "text/vtt"))
        assertFalse(M3uPlaylistCodec.isSubtitleFileName("Movie.mkv"))

        assertTrue(M3uPlaylistCodec.isM3uPlaylistFile("file:///Downloads/favorites.m3u"))
        assertTrue(M3uPlaylistCodec.isM3uPlaylistFile("content://docs/cinema_queue.m3u8"))
        assertTrue(M3uPlaylistCodec.isM3uPlaylistFile("content://docs/99", mimeType = "audio/x-mpegurl"))

        // Remote HTTP(S) .m3u8 URLs are live/VOD HLS streams, NOT local playlist files to import
        assertFalse(M3uPlaylistCodec.isM3uPlaylistFile("https://demo.unified-streaming.com/k8s/live/scte35.isml/.m3u8"))
        assertFalse(M3uPlaylistCodec.isM3uPlaylistFile("http://192.168.1.50:8080/live/stream.m3u8"))
        assertFalse(M3uPlaylistCodec.isM3uPlaylistFile("content://docs/list.m3u8", isRemoteHlsStream = true))
    }

    @Test
    fun credentialVault_normalizesServerKeysAndStripsInlineUserInfo() {
        assertEquals(
            "smb://nas-01.local:445",
            LuminaCredentialVault.normalizeServerKey("SMB://petros:secret@NAS-01.local:445/Movies/UHD/Clip.mkv")
        )
        assertEquals(
            "ftp://192.168.1.20",
            LuminaCredentialVault.normalizeServerKey("ftp://admin@192.168.1.20/pub/video.mp4?token=1")
        )
        assertEquals(
            "smb://synology-ds923",
            LuminaCredentialVault.normalizeServerKey("synology-ds923/SharedVideos")
        )
        assertEquals("", LuminaCredentialVault.normalizeServerKey("   "))
    }

    @Test
    fun credentialVault_aes256GcmEncryptsRoundTripsAndRejectsTampering() {
        val keyGen = KeyGenerator.getInstance("AES").apply { init(256) }
        val secretKey = keyGen.generateKey()
        val plaintext = """{"u":"cinema_user","p":"S3cr3t!Pass#2026","d":"WORKGROUP"}"""
            .toByteArray(StandardCharsets.UTF_8)

        val (iv1, cipher1) = LuminaCredentialVault.encryptAesGcmBytes(plaintext, secretKey)
        val (iv2, _) = LuminaCredentialVault.encryptAesGcmBytes(plaintext, secretKey)

        // Distinct random GCM IVs per encryption operation
        assertEquals(12, iv1.size)
        assertFalse(iv1.contentEquals(iv2))

        val decrypted = LuminaCredentialVault.decryptAesGcmBytes(iv1, cipher1, secretKey)
        assertArrayEquals(plaintext, decrypted)

        // Flip 1 bit in authenticated ciphertext -> GCM tag check MUST fail
        val tampered = cipher1.copyOf()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0x01).toByte()
        try {
            LuminaCredentialVault.decryptAesGcmBytes(iv1, tampered, secretKey)
            fail("Expected AEADBadTagException on tampered AES-GCM ciphertext")
        } catch (_: AEADBadTagException) {
            // Expected authenticated encryption rejection
        }
    }

    @Test
    fun lanDiscoveryHelpers_classifyRemoteDirectoriesPlayableMediaAndHdrBadges() {
        assertTrue(
            LuminaLanDiscoveryManager.isLikelyRemoteDirectory(
                uriStr = "smb://nas/Movies",
                name = "Movies",
                mediaType = IMedia.Type.Directory
            )
        )
        assertTrue(
            LuminaLanDiscoveryManager.isLikelyRemoteDirectory(
                uriStr = "upnp://192.168.1.10:8200/ctl/ContentDir?ObjectID=0_1",
                name = "4K Remuxes",
                mediaType = IMedia.Type.Unknown
            )
        )
        assertFalse(
            LuminaLanDiscoveryManager.isLikelyRemoteDirectory(
                uriStr = "smb://nas/Movies/Clip.mkv",
                name = "Clip.mkv",
                mediaType = IMedia.Type.File
            )
        )

        assertTrue(LuminaLanDiscoveryManager.isSupportedRemotePlayableFile("Sample.mkv", "smb://nas/Sample.mkv"))
        assertTrue(LuminaLanDiscoveryManager.isSupportedRemotePlayableFile("Track.flac", "ftp://nas/Track.flac"))
        assertTrue(LuminaLanDiscoveryManager.isSupportedRemotePlayableFile("DLNA Stream", "http://192.168.1.10:8200/MediaItems/42"))
        assertFalse(LuminaLanDiscoveryManager.isSupportedRemotePlayableFile("poster.jpg", "smb://nas/poster.jpg"))
        assertFalse(LuminaLanDiscoveryManager.isSupportedRemotePlayableFile("thumbs.db", "smb://nas/thumbs.db"))

        assertEquals("Dolby Vision", LuminaLanDiscoveryManager.inferRemoteHdrBadge("Dune.2024.2160p.DoVi.TrueHD.mkv"))
        assertEquals("HDR10+", LuminaLanDiscoveryManager.inferRemoteHdrBadge("Demo_HDR10+_60fps.mp4"))
        assertEquals("HDR10", LuminaLanDiscoveryManager.inferRemoteHdrBadge("Planet.Earth.HDR10.HEVC.mkv"))
        assertEquals("HLG", LuminaLanDiscoveryManager.inferRemoteHdrBadge("Broadcast.Test.HLG.ts"))
        assertNull(LuminaLanDiscoveryManager.inferRemoteHdrBadge("Classic_Film_1080p_SDR.mp4"))
    }

    @Test
    fun thumbnailRepository_computesRepresentativePosterTimeAndQuantizedScrubKeys() {
        // Short clip (3s) avoids overshooting EOF
        assertEquals(1_000L, LuminaThumbnailRepository.computeRepresentativePosterTimeMs(durationMs = 3_000L))
        // Feature film (2 hours) caps representative frame at 15s to skip black 0:00 frames quickly
        assertEquals(15_000L, LuminaThumbnailRepository.computeRepresentativePosterTimeMs(durationMs = 7_200_000L))
        // Saved resume position takes priority so Continue Watching shows exact paused scene
        assertEquals(
            42_500L,
            LuminaThumbnailRepository.computeRepresentativePosterTimeMs(
                durationMs = 120_000L,
                resumePositionMs = 42_500L
            )
        )

        // 2-second scrub quantization buckets
        assertEquals(0L, LuminaThumbnailRepository.quantizeScrubBucketSeconds(0L))
        assertEquals(0L, LuminaThumbnailRepository.quantizeScrubBucketSeconds(1_950L))
        assertEquals(2L, LuminaThumbnailRepository.quantizeScrubBucketSeconds(2_000L))
        assertEquals(2L, LuminaThumbnailRepository.quantizeScrubBucketSeconds(3_899L))
        assertEquals(64L, LuminaThumbnailRepository.quantizeScrubBucketSeconds(65_400L))

        val keyA = LuminaThumbnailRepository.buildCacheKey("content://video/1", 64L, 320, 180)
        val keyB = LuminaThumbnailRepository.buildCacheKey("content://video/1", 66L, 320, 180)
        assertEquals(32, keyA.length)
        assertNotEquals(keyA, keyB)
    }

    @Test
    fun videoZoomAndPanHelpers_clampScaleAndViewportBoundsCleanly() {
        assertEquals(1.0f, clampVideoZoomScale(0.4f), 0.0001f)
        assertEquals(2.5f, clampVideoZoomScale(2.5f), 0.0001f)
        assertEquals(4.0f, clampVideoZoomScale(9.0f), 0.0001f)

        // At 1.0x zoom, pan is always locked to 0px
        assertEquals(0f, clampVideoPanOffset(rawPanPx = 150f, zoomScale = 1.0f, viewportDimensionPx = 1920f), 0.0001f)

        // At 2.0x zoom on a 1920px wide viewport, max horizontal pan is +/- 960px
        assertEquals(400f, clampVideoPanOffset(rawPanPx = 400f, zoomScale = 2.0f, viewportDimensionPx = 1920f), 0.0001f)
        assertEquals(960f, clampVideoPanOffset(rawPanPx = 1800f, zoomScale = 2.0f, viewportDimensionPx = 1920f), 0.0001f)
        assertEquals(-960f, clampVideoPanOffset(rawPanPx = -1400f, zoomScale = 2.0f, viewportDimensionPx = 1920f), 0.0001f)
    }

    @Test
    fun backgroundAudioContinuationAndVersionComparison_behaveAsExpected() {
        assertFalse(
            LuminaPlayerEngine.shouldContinueBackgroundAudioOnStop(
                isInPipOrMiniPlayer = false,
                isAudioOnlySession = false,
                continueAudioInBackgroundPref = false,
                explicitBackgroundAudioMode = false
            )
        )
        assertTrue(
            LuminaPlayerEngine.shouldContinueBackgroundAudioOnStop(
                isInPipOrMiniPlayer = false,
                isAudioOnlySession = false,
                continueAudioInBackgroundPref = true,
                explicitBackgroundAudioMode = false
            )
        )
        assertTrue(
            LuminaPlayerEngine.shouldContinueBackgroundAudioOnStop(
                isInPipOrMiniPlayer = false,
                isAudioOnlySession = false,
                continueAudioInBackgroundPref = false,
                explicitBackgroundAudioMode = true
            )
        )

        assertTrue(LuminaUpdateManager.isRemoteVersionNewer(installedVersion = "1.0.10", remoteTagOrVersion = "v1.1.0"))
        assertFalse(LuminaUpdateManager.isRemoteVersionNewer(installedVersion = "1.1.0", remoteTagOrVersion = "v1.1.0"))
    }
}
