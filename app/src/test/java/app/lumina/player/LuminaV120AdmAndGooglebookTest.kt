package app.lumina.player

import app.lumina.player.data.download.AdmDownloadPlanner
import app.lumina.player.data.download.DownloadSegment
import app.lumina.player.data.download.HlsParsedPlaylist
import app.lumina.player.update.GithubReleaseAsset
import app.lumina.player.update.LuminaUpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LuminaV120AdmAndGooglebookTest {

    @Test
    fun `planByteSegments partitions exact non-overlapping inclusive byte ranges across 1 4 8 and 16 connections`() {
        val totalSize = 104_857_603L // ~100 MiB + 3 odd bytes to exercise remainder distribution
        for (threads in listOf(1, 4, 8, 16)) {
            val segments = AdmDownloadPlanner.planByteSegments(
                totalBytes = totalSize,
                requestedThreads = threads
            )
            assertEquals(threads, segments.size)
            assertEquals(0L, segments.first().startByte)
            assertEquals(totalSize - 1L, segments.last().endByte)

            var expectedStart = 0L
            var summedBytes = 0L
            segments.forEachIndexed { idx, seg ->
                assertEquals(idx, seg.index)
                assertEquals(expectedStart, seg.startByte)
                assertTrue(seg.endByte >= seg.startByte)
                val segLen = seg.expectedBytes
                assertTrue(segLen > 0L)
                summedBytes += segLen
                expectedStart = seg.endByte + 1L
            }
            assertEquals(totalSize, summedBytes)
        }
    }

    @Test
    fun `planByteSegments caps segments for tiny files and falls back to unbounded stream when size unknown`() {
        val tinySegments = AdmDownloadPlanner.planByteSegments(
            totalBytes = 100_000L, // < 256 KiB min chunk
            requestedThreads = 16
        )
        assertEquals(1, tinySegments.size)
        assertEquals(0L, tinySegments[0].startByte)
        assertEquals(99_999L, tinySegments[0].endByte)

        val unknownSegments = AdmDownloadPlanner.planByteSegments(
            totalBytes = -1L,
            requestedThreads = 8
        )
        assertEquals(1, unknownSegments.size)
        assertEquals(0L, unknownSegments[0].startByte)
        assertEquals(-1L, unknownSegments[0].endByte)
    }

    @Test
    fun `serializeSegments and deserializeSegments preserve resume progress across restarts`() {
        val original = listOf(
            DownloadSegment(index = 0, startByte = 0L, endByte = 999L, downloadedBytes = 1000L, completed = true),
            DownloadSegment(index = 1, startByte = 1000L, endByte = 1999L, downloadedBytes = 420L, completed = false),
            DownloadSegment(index = 2, startByte = 2000L, endByte = 2999L, downloadedBytes = 0L, completed = false)
        )
        val encoded = AdmDownloadPlanner.serializeSegments(original)
        val restored = AdmDownloadPlanner.deserializeSegments(encoded)
        assertEquals(original, restored)
        assertEquals(1.0f, restored[0].progressFraction, 0.0001f)
        assertEquals(0.42f, restored[1].progressFraction, 0.001f)
        assertEquals(1420L, restored[1].startByte + restored[1].downloadedBytes)
    }

    @Test
    fun `parseCustomHeadersText strips CRLF injection and round-trips through URL encoding`() {
        val headers = AdmDownloadPlanner.parseCustomHeadersText(
            rawLines = "X-Custom-Token: alpha-123\r\nOrigin: https://cinema.example.org",
            referer = "https://cinema.example.org/watch\r\nInjected: bad",
            userAgent = "LuminaTest/1.2",
            authorization = "Bearer secret_token_xyz"
        )
        assertEquals("alpha-123", headers["X-Custom-Token"])
        assertEquals("https://cinema.example.org", headers["Origin"])
        assertEquals("https://cinema.example.org/watchInjected: bad", headers["Referer"])
        assertFalse(headers.values.any { it.contains('\r') || it.contains('\n') })

        val encoded = AdmDownloadPlanner.serializeHeaders(headers)
        val decoded = AdmDownloadPlanner.deserializeHeaders(encoded)
        assertEquals(headers, decoded)
    }

    @Test
    fun `parseContentRangeTotalBytes and extractFirstStreamUrlFromSharedText handle edge cases`() {
        assertEquals(104857600L, AdmDownloadPlanner.parseContentRangeTotalBytes("bytes 0-0/104857600"))
        assertEquals(-1L, AdmDownloadPlanner.parseContentRangeTotalBytes("bytes 0-1023/*"))
        assertEquals(-1L, AdmDownloadPlanner.parseContentRangeTotalBytes(null))

        val shared = "Watch this HDR stream: https://cdn.example.com/hdr/cosmos_4k.m3u8?token=abc)."
        assertEquals(
            "https://cdn.example.com/hdr/cosmos_4k.m3u8?token=abc",
            AdmDownloadPlanner.extractFirstStreamUrlFromSharedText(shared)
        )
        assertNull(AdmDownloadPlanner.extractFirstStreamUrlFromSharedText("Just plain text without URL"))
    }

    @Test
    fun `extractSuggestedFileName resolves Content-Disposition RFC5987 and HLS container extensions`() {
        val rfcName = AdmDownloadPlanner.extractSuggestedFileName(
            url = "https://cdn.example.com/download?id=99",
            contentDisposition = "attachment; filename*=UTF-8''Dune%20HDR10%2B.mkv",
            mimeType = "video/x-matroska"
        )
        assertEquals("Dune HDR10+.mkv", rfcName)

        val hlsTsName = AdmDownloadPlanner.extractSuggestedFileName(
            url = "https://cdn.example.com/live/master.m3u8?sig=1",
            contentDisposition = null,
            mimeType = "application/vnd.apple.mpegurl",
            isHls = true,
            hasFmp4InitSegment = false
        )
        assertEquals("master.ts", hlsTsName)

        val hlsFmp4Name = AdmDownloadPlanner.extractSuggestedFileName(
            url = "https://cdn.example.com/vod/dolby_stream.m3u8",
            contentDisposition = null,
            mimeType = "application/vnd.apple.mpegurl",
            isHls = true,
            hasFmp4InitSegment = true
        )
        assertEquals("dolby_stream.mp4", hlsFmp4Name)
    }

    @Test
    fun `parseHlsPlaylist parses Master variants sorted by highest bandwidth and resolves relative URLs`() {
        val masterM3u8 = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080,FRAME-RATE=24.000,CODECS="avc1.640028,mp4a.40.2"
            1080p/prog_index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=18500000,AVERAGE-BANDWIDTH=16200000,RESOLUTION=3840x2160,FRAME-RATE=59.940,CODECS="hvc1.2.4.L153.B0"
            /vod/2160p_hdr/index.m3u8
        """.trimIndent()

        val parsed = AdmDownloadPlanner.parseHlsPlaylist(
            playlistText = masterM3u8,
            basePlaylistUrl = "https://streams.example.com/cinema/master.m3u8"
        )
        assertTrue(parsed is HlsParsedPlaylist.Master)
        val variants = (parsed as HlsParsedPlaylist.Master).variants
        assertEquals(2, variants.size)
        assertEquals("https://streams.example.com/vod/2160p_hdr/index.m3u8", variants[0].url)
        assertTrue(variants[0].label.contains("2160p"))
        assertEquals("https://streams.example.com/cinema/1080p/prog_index.m3u8", variants[1].url)
    }

    @Test
    fun `parseHlsPlaylist parses Media playlist with EXT-X-MAP AES-128 key and byte ranges`() {
        val mediaM3u8 = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:100
            #EXT-X-MAP:URI="init_header.mp4"
            #EXT-X-KEY:METHOD=AES-128,URI="keys/main.key",IV=0x00000000000000000000000000000064
            #EXTINF:6.006,
            #EXT-X-BYTERANGE:524288@0
            chunk_01.m4s
            #EXTINF:6.006,
            #EXT-X-BYTERANGE:524288
            chunk_01.m4s
            #EXT-X-ENDLIST
        """.trimIndent()

        val parsed = AdmDownloadPlanner.parseHlsPlaylist(
            playlistText = mediaM3u8,
            basePlaylistUrl = "https://streams.example.com/vod/hdr/index.m3u8"
        )
        assertTrue(parsed is HlsParsedPlaylist.Media)
        val media = parsed as HlsParsedPlaylist.Media
        assertEquals("https://streams.example.com/vod/hdr/init_header.mp4", media.initMapUrl)
        assertTrue(media.hasEndList)
        assertEquals(2, media.segments.size)

        val first = media.segments[0]
        assertEquals(100L, first.sequenceNumber)
        assertEquals("https://streams.example.com/vod/hdr/chunk_01.m4s", first.url)
        assertEquals(0L, first.byteRangeOffset)
        assertEquals(524288L, first.byteRangeLength)
        assertNotNull(first.encryptionKey)
        assertEquals("AES-128", first.encryptionKey?.method)
        assertEquals("https://streams.example.com/vod/hdr/keys/main.key", first.encryptionKey?.keyUrl)

        val second = media.segments[1]
        assertEquals(101L, second.sequenceNumber)
        assertEquals(524288L, second.byteRangeOffset)
        assertEquals(524288L, second.byteRangeLength)
    }

    @Test
    fun `selectBestApkAsset prioritizes googlebook-os installer on Googlebook OS and ABI split on phones`() {
        val releaseAssets = listOf(
            GithubReleaseAsset(
                name = "Lumina-v1.2.0-arm64-v8a.apk",
                downloadUrl = "https://github.com/petrosvassiliou/lumina/releases/download/v1.2.0/Lumina-v1.2.0-arm64-v8a.apk",
                sizeBytes = 42_000_000L
            ),
            GithubReleaseAsset(
                name = "Lumina-v1.2.0-x86_64.apk",
                downloadUrl = "https://github.com/petrosvassiliou/lumina/releases/download/v1.2.0/Lumina-v1.2.0-x86_64.apk",
                sizeBytes = 45_000_000L
            ),
            GithubReleaseAsset(
                name = "Lumina-v1.2.0-googlebook-os.apk",
                downloadUrl = "https://github.com/petrosvassiliou/lumina/releases/download/v1.2.0/Lumina-v1.2.0-googlebook-os.apk",
                sizeBytes = 148_000_000L
            ),
            GithubReleaseAsset(
                name = "Lumina-v1.2.0-universal.apk",
                downloadUrl = "https://github.com/petrosvassiliou/lumina/releases/download/v1.2.0/Lumina-v1.2.0-universal.apk",
                sizeBytes = 148_000_000L
            )
        )

        val googlebookMatch = LuminaUpdateManager.selectBestApkAsset(
            assets = releaseAssets,
            supportedAbis = listOf("x86_64", "arm64-v8a"),
            preferGooglebookUniversal = true
        )
        assertNotNull(googlebookMatch)
        assertEquals("Lumina-v1.2.0-googlebook-os.apk", googlebookMatch?.asset?.name)
        assertEquals("googlebook-os", googlebookMatch?.matchedAbi)

        val phoneMatch = LuminaUpdateManager.selectBestApkAsset(
            assets = releaseAssets,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            preferGooglebookUniversal = false
        )
        assertNotNull(phoneMatch)
        assertEquals("Lumina-v1.2.0-arm64-v8a.apk", phoneMatch?.asset?.name)
        assertEquals("arm64-v8a", phoneMatch?.matchedAbi)
    }
}
