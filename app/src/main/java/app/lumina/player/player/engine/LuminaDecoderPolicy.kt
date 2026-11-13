package app.lumina.player.player.engine

import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import app.lumina.player.data.preferences.HardwareAccelerationMode
import app.lumina.player.player.model.HdrFormat
import java.io.File
import java.util.Locale
import org.videolan.libvlc.Media

/**
 * Concrete libVLC 4.0 video decoder & output strategy resolved per playback session.
 */
enum class ResolvedVlcDecoderMode {
    /**
     * Hardware `MediaCodec` with zero-copy `AImageReader` / `ASurfaceControl` direct rendering.
     * Required for hardware HDR10 / HDR10+ / HLG / Dolby Vision passthrough.
     */
    HARDWARE_DIRECT_RENDERING,

    /**
     * Hardware `MediaCodec` decoding without opaque `AImageReader` / `ASurfaceControl` direct
     * rendering (`:no-mediacodec-dr`), rendering frames via OpenGL ES 2 (`gles2`) / `libplacebo`.
     * Prevents blank surfaces on ChromeOS/Googlebook Wayland sub-surfaces and high-level SDR H.264.
     */
    HARDWARE_NO_DIRECT_RENDERING,

    /**
     * Spec-compliant multi-threaded FFmpeg `avcodec` software decoding (`:no-hw-dec`) rendered
     * via OpenGL ES 2 (`gles2`). Guarantees frame output when no true hardware `MediaCodec`
     * supports the stream's profile, bit depth, resolution, or frame rate.
     */
    SOFTWARE_AVCODEC
}

/**
 * Lightweight pre-flight metadata extracted from local containers prior to libVLC initialization.
 */
data class ProbedVideoStreamInfo(
    val mimeType: String?,
    val width: Int,
    val height: Int,
    val frameRate: Float,
    val profile: Int,
    val colorTransfer: Int,
    val hasTrueHardwareDecoderSupport: Boolean?
) {
    /**
     * `COLOR_TRANSFER_ST2084` (6 = HDR10 PQ) and `COLOR_TRANSFER_HLG` (7 = Hybrid Log-Gamma),
     * plus Dolby Vision MIME types.
     */
    val isHdrStream: Boolean
        get() = colorTransfer == COLOR_TRANSFER_ST2084 ||
            colorTransfer == COLOR_TRANSFER_HLG ||
            mimeType.equals(MIME_DOLBY_VISION, ignoreCase = true)

    companion object {
        const val COLOR_TRANSFER_ST2084: Int = 6
        const val COLOR_TRANSFER_HLG: Int = 7
        const val MIME_AVC: String = "video/avc"
        const val MIME_DOLBY_VISION: String = "video/dolby-vision"
    }
}

/**
 * Resolves the safest high-performance libVLC 4.0 decoder configuration for any MP4/MKV/WebM stream
 * and provides deterministic step-down fallback tiers if a hardware video output fails at runtime.
 */
object LuminaDecoderPolicy {

    /**
     * H.264 / AVC profiles > 8-bit 4:2:0 (`High 10`, `High 4:2:2`, `High 4:4:4`, `CAVLC 4:4:4`)
     * expressed as both Android `MediaCodecInfo.CodecProfileLevel` bitmasks and ISO/IEC 14496-10 IDs.
     */
    private val NON_8BIT_420_AVC_PROFILES = setOf(
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10, // 0x10 (16)
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422, // 0x20 (32)
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444, // 0x40 (64)
        44,  // CAVLC 4:4:4 Intra
        110, // High 10 (Hi10P)
        122, // High 4:2:2 (Hi422P)
        244  // High 4:4:4 Predictive (Hi444PP)
    )

    /**
     * Pure, deterministic resolver mapping user preference, probed stream capabilities, HDR status,
     * and desktop/ARC container traits to a concrete [ResolvedVlcDecoderMode].
     */
    fun resolveDecoderMode(
        userPreference: HardwareAccelerationMode,
        sessionFallbackOverride: ResolvedVlcDecoderMode? = null,
        probedInfo: ProbedVideoStreamInfo? = null,
        hdrHint: HdrFormat? = null,
        isArcOrDesktopContainer: Boolean = false
    ): ResolvedVlcDecoderMode {
        if (sessionFallbackOverride != null) {
            return sessionFallbackOverride
        }

        if (userPreference == HardwareAccelerationMode.SOFTWARE_FALLBACK) {
            return ResolvedVlcDecoderMode.SOFTWARE_AVCODEC
        }

        // If pre-flight confirmed NO true hardware MediaCodec on this device supports the stream
        // (e.g. 2880x1800@50fps Level 5.2 H.264 screen recording or Hi10P/4:4:4 MP4 where
        // mediacodec_ndk would fall onto CPU c2.android.* + AImageReader and render blank),
        // route directly to multi-threaded spec-compliant FFmpeg avcodec + OpenGL ES 2.
        if (probedInfo?.hasTrueHardwareDecoderSupport == false) {
            return ResolvedVlcDecoderMode.SOFTWARE_AVCODEC
        }

        val isAvc = probedInfo?.mimeType?.equals(ProbedVideoStreamInfo.MIME_AVC, ignoreCase = true) == true
        if (probedInfo != null &&
            isAvc &&
            probedInfo.profile in NON_8BIT_420_AVC_PROFILES &&
            probedInfo.hasTrueHardwareDecoderSupport != true
        ) {
            return ResolvedVlcDecoderMode.SOFTWARE_AVCODEC
        }

        if (userPreference == HardwareAccelerationMode.DECODING_ONLY) {
            return ResolvedVlcDecoderMode.HARDWARE_NO_DIRECT_RENDERING
        }

        val isHdr = (probedInfo?.isHdrStream == true) || (hdrHint?.isHdr == true)

        if (userPreference == HardwareAccelerationMode.AUTOMATIC && !isHdr) {
            // On ChromeOS / Googlebook OS ARC/ARCVM desktop containers, child ASurfaceControl
            // transactions from AImageReader opaque buffers can fail silently on SDR streams.
            // Disabling opaque direct-rendering keeps hardware MediaCodec decode while presenting
            // reliably via OpenGL ES 2 on the primary SurfaceView.
            if (isArcOrDesktopContainer) {
                return ResolvedVlcDecoderMode.HARDWARE_NO_DIRECT_RENDERING
            }

            if (probedInfo != null) {
                val hasOddPixelGeometry = probedInfo.width > 0 &&
                    probedInfo.height > 0 &&
                    (probedInfo.width % 2 != 0 || probedInfo.height % 2 != 0)

                val isHighLevelAvcScreenRecording = isAvc &&
                    (probedInfo.width > 1920 || probedInfo.height > 1088) &&
                    probedInfo.frameRate > 31f

                if (hasOddPixelGeometry || isHighLevelAvcScreenRecording) {
                    return ResolvedVlcDecoderMode.HARDWARE_NO_DIRECT_RENDERING
                }
            }
        }

        return ResolvedVlcDecoderMode.HARDWARE_DIRECT_RENDERING
    }

    /**
     * Returns the next safer decoder mode when a playback session produces zero video outputs
     * or zero decoded frames at runtime, or `null` if already on software `avcodec`.
     */
    fun nextFallbackTier(current: ResolvedVlcDecoderMode): ResolvedVlcDecoderMode? = when (current) {
        ResolvedVlcDecoderMode.HARDWARE_DIRECT_RENDERING -> ResolvedVlcDecoderMode.HARDWARE_NO_DIRECT_RENDERING
        ResolvedVlcDecoderMode.HARDWARE_NO_DIRECT_RENDERING -> ResolvedVlcDecoderMode.SOFTWARE_AVCODEC
        ResolvedVlcDecoderMode.SOFTWARE_AVCODEC -> null
    }

    /**
     * Applies the resolved [ResolvedVlcDecoderMode] flags to a libVLC 4.0 [Media] instance.
     */
    fun applyToMedia(media: Media, mode: ResolvedVlcDecoderMode) {
        when (mode) {
            ResolvedVlcDecoderMode.HARDWARE_DIRECT_RENDERING -> {
                media.setHWDecoderEnabled(true, false)
            }
            ResolvedVlcDecoderMode.HARDWARE_NO_DIRECT_RENDERING -> {
                media.setHWDecoderEnabled(true, false)
                media.addOption(":no-mediacodec-dr")
                media.addOption(":no-omxil-dr")
            }
            ResolvedVlcDecoderMode.SOFTWARE_AVCODEC -> {
                media.setHWDecoderEnabled(false, false)
            }
        }
    }

    /**
     * Detects whether the host environment is a Googlebook OS / ChromeOS ARC container.
     */
    fun isArcContainer(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        pm.hasSystemFeature("org.chromium.arc") ||
            pm.hasSystemFeature("org.chromium.arc.device_management") ||
            pm.hasSystemFeature(PackageManager.FEATURE_PC)
    }.getOrDefault(false)

    /**
     * Inspects a local `content://` or `file://` video container via Android's [MediaExtractor]
     * and checks whether any true hardware-accelerated [MediaCodecInfo] supports the exact stream.
     *
     * Runs strictly non-fatally in a few milliseconds and closes all descriptors in `finally`.
     */
    fun probeLocalVideoStream(context: Context, uri: Uri): ProbedVideoStreamInfo? {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "content" && scheme != "file") return null

        val extractor = MediaExtractor()
        val openedPfd = if (scheme == "content") {
            runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        } else {
            null
        }

        return try {
            val dataSourceReady = runCatching {
                when {
                    openedPfd != null -> {
                        extractor.setDataSource(openedPfd.fileDescriptor)
                        true
                    }
                    scheme == "file" -> {
                        val path = uri.path
                        if (!path.isNullOrBlank() && File(path).canRead()) {
                            extractor.setDataSource(path)
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }.getOrDefault(false)

            if (!dataSourceReady) return null

            var videoFormat: MediaFormat? = null
            var videoMime: String? = null
            for (i in 0 until extractor.trackCount) {
                val format = runCatching { extractor.getTrackFormat(i) }.getOrNull() ?: continue
                val mime = runCatching { format.getString(MediaFormat.KEY_MIME) }.getOrNull() ?: continue
                if (mime.startsWith("video/", ignoreCase = true)) {
                    videoFormat = format
                    videoMime = mime
                    break
                }
            }

            val fmt = videoFormat ?: return null
            val mime = videoMime ?: return null

            val width = fmt.getIntOrDefault(MediaFormat.KEY_WIDTH, 0)
            val height = fmt.getIntOrDefault(MediaFormat.KEY_HEIGHT, 0)
            val frameRate = fmt.getFloatOrIntOrDefault(MediaFormat.KEY_FRAME_RATE, 0f)
            val profile = fmt.getIntOrDefault(MediaFormat.KEY_PROFILE, -1)
            val colorTransfer = fmt.getIntOrDefault(MediaFormat.KEY_COLOR_TRANSFER, 0)

            val hwSupported = runCatching {
                hasTrueHardwareDecoderForFormat(fmt, mime, width, height, frameRate)
            }.getOrNull()

            ProbedVideoStreamInfo(
                mimeType = mime,
                width = width,
                height = height,
                frameRate = frameRate,
                profile = profile,
                colorTransfer = colorTransfer,
                hasTrueHardwareDecoderSupport = hwSupported
            )
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { extractor.release() }
            runCatching { openedPfd?.close() }
        }
    }

    private fun hasTrueHardwareDecoderForFormat(
        format: MediaFormat,
        mime: String,
        width: Int,
        height: Int,
        frameRate: Float
    ): Boolean {
        val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        for (info in codecInfos) {
            if (info.isEncoder) continue
            if (!isTrueHardwareCodec(info)) continue
            val supportsMime = info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            if (!supportsMime) continue

            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            val supported = runCatching {
                caps.isFormatSupported(format)
            }.recoverCatching {
                val videoCaps = caps.videoCapabilities ?: return@recoverCatching false
                when {
                    width <= 0 || height <= 0 -> true
                    frameRate > 1f -> videoCaps.areSizeAndRateSupported(width, height, frameRate.toDouble())
                    else -> videoCaps.isSizeSupported(width, height)
                }
            }.getOrDefault(false)

            if (supported) return true
        }
        return false
    }

    internal fun isKnownSoftwareCodecName(codecName: String): Boolean {
        val lower = codecName.lowercase(Locale.ROOT)
        return lower.startsWith("omx.google.") ||
            lower.startsWith("c2.android.") ||
            lower.startsWith("omx.ffmpeg.") ||
            lower.startsWith("omx.sec.avc.sw.") ||
            lower.contains(".sw.") ||
            lower.endsWith(".sw")
    }

    private fun isTrueHardwareCodec(info: MediaCodecInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated && !info.isSoftwareOnly && !isKnownSoftwareCodecName(info.name)
        } else {
            !isKnownSoftwareCodecName(info.name)
        }
    }

    private fun MediaFormat.getIntOrDefault(key: String, default: Int): Int {
        if (!containsKey(key)) return default
        return runCatching { getInteger(key) }.getOrDefault(default)
    }

    private fun MediaFormat.getFloatOrIntOrDefault(key: String, default: Float): Float {
        if (!containsKey(key)) return default
        return runCatching { getFloat(key) }
            .recoverCatching { getInteger(key).toFloat() }
            .getOrDefault(default)
    }
}
