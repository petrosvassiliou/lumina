package app.lumina.player.player.hdr

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.ColorSpace
import android.hardware.DataSpace
import android.os.Build
import android.view.Display
import android.view.SurfaceControl
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import app.lumina.player.data.preferences.ToneMappingAlgorithm
import app.lumina.player.player.model.HdrFormat
import app.lumina.player.player.model.HdrOutputMode
import app.lumina.player.player.model.HdrPipelineStatus
import javax.inject.Inject
import javax.inject.Singleton
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Manages Lumina's True HDR Pipeline (HDR10, HDR10+, HLG, and Dolby Vision passthrough)
 * across Android Phones, Foldables, and Googlebook OS displays, with automatic dynamic
 * tone-mapping fallback for SDR displays.
 */
@Singleton
class LuminaHdrController @Inject constructor() {

    companion object {
        /**
         * OS-level Window HDR10 / Wide-Gamut HDR color mode constant.
         * Maps to Android's official WindowManager.LayoutParams / ActivityInfo HDR color mode value (2).
         */
        const val COLOR_MODE_HDR10: Int = ActivityInfo.COLOR_MODE_HDR
        const val COLOR_MODE_DEFAULT_SDR: Int = ActivityInfo.COLOR_MODE_DEFAULT
    }

    /**
     * Queries the current Display's hardware HDR EOTF capabilities and peak luminance.
     */
    @Suppress("DEPRECATION")
    fun queryDisplayCapabilities(display: Display?): HdrPipelineStatus {
        if (display == null) return HdrPipelineStatus()

        return runCatching {
            val hdrCapabilities = display.hdrCapabilities ?: return HdrPipelineStatus()
            val supportedTypes: IntArray = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                display.mode?.supportedHdrTypes ?: hdrCapabilities.supportedHdrTypes
            } else {
                hdrCapabilities.supportedHdrTypes
            }

            val supportsHdr10 = supportedTypes.contains(Display.HdrCapabilities.HDR_TYPE_HDR10)
            val supportsHdr10Plus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                supportedTypes.contains(Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS)
            } else {
                false
            }
            val supportsHlg = supportedTypes.contains(Display.HdrCapabilities.HDR_TYPE_HLG)
            val supportsDolbyVision = supportedTypes.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)

            HdrPipelineStatus(
                displaySupportsHdr10 = supportsHdr10,
                displaySupportsHdr10Plus = supportsHdr10Plus,
                displaySupportsHlg = supportsHlg,
                displaySupportsDolbyVision = supportsDolbyVision,
                displayMaxLuminanceNits = hdrCapabilities.desiredMaxLuminance.coerceAtLeast(0f)
            )
        }.getOrDefault(HdrPipelineStatus())
    }

    /**
     * Inspects libVLC 4.0 VideoTrack codec/profile/fourcc/description and optional URI hints
     * to classify the source stream's HDR format.
     */
    fun detectStreamHdrFormat(
        videoTrack: IMedia.VideoTrack?,
        mediaTitleOrUri: String?,
        fallbackHint: HdrFormat? = null
    ): HdrFormat {
        if (videoTrack != null) {
            val codec = (videoTrack.codec ?: "").lowercase()
            val originalCodec = (videoTrack.originalCodec ?: "").lowercase()
            val description = (videoTrack.description ?: "").lowercase()
            val name = (videoTrack.name ?: "").lowercase()
            val fourccString = fourccToString(videoTrack.fourcc).lowercase()
            val profile = videoTrack.profile

            val combinedMeta = "$codec $originalCodec $description $name $fourccString"

            // 1. Dolby Vision FourCCs & Codec tags (dvhe, dvh1, dav1, dovi)
            if (
                combinedMeta.contains("dvhe") ||
                combinedMeta.contains("dvh1") ||
                combinedMeta.contains("dav1") ||
                combinedMeta.contains("dovi") ||
                combinedMeta.contains("dolby vision")
            ) {
                return HdrFormat.DOLBY_VISION
            }

            // 2. HDR10+ dynamic metadata indicators
            if (combinedMeta.contains("hdr10+") || combinedMeta.contains("smpte2094")) {
                return HdrFormat.HDR10_PLUS
            }

            // 3. Hybrid Log-Gamma (HLG / ARIB STD-B67)
            if (combinedMeta.contains("hlg") || combinedMeta.contains("arib-std-b67")) {
                return HdrFormat.HLG
            }

            // 4. HDR10 PQ (HEVC Main 10 profile = 2, AV1 Main 10, VP9 Profile 2 = 2)
            val is10BitHdrProfile =
                (codec.contains("hevc") || codec.contains("h265") || fourccString == "hevc") && profile >= 2 ||
                (codec.contains("vp9") && profile >= 2) ||
                (codec.contains("av01") || codec.contains("av1")) && profile >= 1 && videoTrack.height >= 1080

            if (
                combinedMeta.contains("pq") ||
                combinedMeta.contains("smpte2084") ||
                combinedMeta.contains("bt2020") ||
                combinedMeta.contains("hdr10") ||
                is10BitHdrProfile
            ) {
                return fallbackHint?.takeIf { it.isHdr } ?: HdrFormat.HDR10
            }
        }

        // Fallback to title/container naming conventions if stream metadata is still warming up
        val hintText = (mediaTitleOrUri ?: "").uppercase()
        return when {
            fallbackHint != null && fallbackHint.isHdr -> fallbackHint
            hintText.contains("DOLBY.VISION") || hintText.contains("DOVI") || hintText.contains(".DV.") -> HdrFormat.DOLBY_VISION
            hintText.contains("HDR10+") || hintText.contains("HDR10PLUS") -> HdrFormat.HDR10_PLUS
            hintText.contains(".HLG.") || hintText.contains("-HLG") -> HdrFormat.HLG
            hintText.contains("HDR10") || hintText.contains(".HDR.") || hintText.contains("BT2020") -> HdrFormat.HDR10
            else -> HdrFormat.SDR
        }
    }

    /**
     * Configures both the OS Window HDR mode (`COLOR_MODE_HDR10`) and the underlying `SurfaceView`
     * color space (`ColorSpace.Named.BT2020_PQ` / `ColorSpace.Named.BT2020_HLG`) when HDR passthrough
     * is supported, or engages SDR mode for dynamic tone-mapping fallback.
     */
    fun applyHdrPipelineToSurface(
        videoLayout: VLCVideoLayout?,
        sourceFormat: HdrFormat,
        hdrPassthroughEnabled: Boolean,
        toneMappingAlgorithm: ToneMappingAlgorithm
    ): HdrPipelineStatus {
        val display = videoLayout?.display
        val baseStatus = queryDisplayCapabilities(display)
        val activity = videoLayout?.context?.findHostActivity()
        val window = activity?.window

        val canPassthrough = hdrPassthroughEnabled && sourceFormat.isHdr && when (sourceFormat) {
            HdrFormat.DOLBY_VISION -> baseStatus.displaySupportsDolbyVision || baseStatus.displaySupportsHdr10
            HdrFormat.HDR10_PLUS -> baseStatus.displaySupportsHdr10Plus || baseStatus.displaySupportsHdr10
            HdrFormat.HDR10 -> baseStatus.displaySupportsHdr10 || baseStatus.displaySupportsDolbyVision
            HdrFormat.HLG -> baseStatus.displaySupportsHlg || baseStatus.displaySupportsHdr10
            HdrFormat.SDR -> false
        }

        val targetColorSpace: ColorSpace = when {
            !canPassthrough -> ColorSpace.get(ColorSpace.Named.SRGB)
            sourceFormat == HdrFormat.HLG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ColorSpace.get(ColorSpace.Named.BT2020_HLG)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ColorSpace.get(ColorSpace.Named.BT2020_PQ)
            else -> ColorSpace.get(ColorSpace.Named.BT2020)
        }

        // 1. Trigger OS-level Window HDR mode (WindowManager.LayoutParams / Window.setColorMode)
        if (window != null) {
            configureWindowHdrMode(window, enableHdr = canPassthrough)
        }

        // 2. Configure the primary video SurfaceView inside VLCVideoLayout
        val primaryVideoSurface = videoLayout?.findPrimaryVideoSurfaceView()
        if (primaryVideoSurface != null) {
            configureVideoSurfaceColorSpace(
                surfaceView = primaryVideoSurface,
                sourceFormat = sourceFormat,
                enableHdrPassthrough = canPassthrough
            )
        }

        return when {
            canPassthrough -> baseStatus.copy(
                sourceFormat = sourceFormat,
                outputMode = HdrOutputMode.HARDWARE_HDR_PASSTHROUGH,
                activeColorSpaceName = targetColorSpace.name,
                activeToneMapper = null
            )
            sourceFormat.isHdr -> baseStatus.copy(
                sourceFormat = sourceFormat,
                outputMode = HdrOutputMode.LIBVLC_DYNAMIC_TONE_MAPPING,
                activeColorSpaceName = "BT.709 (SDR Tone-Mapped)",
                activeToneMapper = toneMappingAlgorithm.vlcValue.uppercase()
            )
            else -> baseStatus.copy(
                sourceFormat = HdrFormat.SDR,
                outputMode = HdrOutputMode.STANDARD_SDR,
                activeColorSpaceName = "BT.709 (sRGB)",
                activeToneMapper = null
            )
        }
    }

    /**
     * Resets the host Activity Window back to default SDR color mode when leaving HDR playback.
     */
    fun resetWindowHdrMode(context: Context?) {
        val window = context?.findHostActivity()?.window ?: return
        configureWindowHdrMode(window, enableHdr = false)
    }

    /**
     * Builds libVLC 4.0 initialization arguments using verified OpenGL/libplacebo tone-mapping
     * function IDs (`--gl-tone-mapping-function` and `--pl-tone-mapping-function`) so SDR screens
     * render HDR10 / HLG / Dolby Vision without washed-out highlights or clipping.
     */
    fun buildLibVlcHdrArguments(toneMappingAlgorithm: ToneMappingAlgorithm): List<String> {
        val fnId = toneMappingAlgorithm.vlc4FunctionId
        return listOf(
            "--gl-tone-mapping-function=$fnId",
            "--pl-tone-mapping-function=$fnId"
        )
    }

    private fun configureWindowHdrMode(window: Window, enableHdr: Boolean) {
        val desiredMode = if (enableHdr) COLOR_MODE_HDR10 else COLOR_MODE_DEFAULT_SDR
        runCatching {
            if (window.colorMode != desiredMode) {
                window.colorMode = desiredMode
            }
        }

        runCatching {
            val params: WindowManager.LayoutParams = window.attributes
            if (params.colorMode != desiredMode) {
                params.colorMode = desiredMode
                window.attributes = params
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching {
                // Allow full HDR headroom on Android 15+ / Googlebook OS HDR displays (0f = system optimal)
                window.desiredHdrHeadroom = if (enableHdr) 0f else 1.0f
            }
        }
    }

    private fun configureVideoSurfaceColorSpace(
        surfaceView: SurfaceView,
        sourceFormat: HdrFormat,
        enableHdrPassthrough: Boolean
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val surfaceControl = surfaceView.surfaceControl
            if (surfaceControl.isValid) {
                val dataSpace = when {
                    !enableHdrPassthrough -> DataSpace.DATASPACE_SRGB
                    sourceFormat == HdrFormat.HLG -> DataSpace.pack(
                        DataSpace.STANDARD_BT2020,
                        DataSpace.TRANSFER_HLG,
                        DataSpace.RANGE_LIMITED
                    )
                    else -> DataSpace.pack(
                        DataSpace.STANDARD_BT2020,
                        DataSpace.TRANSFER_ST2084,
                        DataSpace.RANGE_LIMITED
                    )
                }

                runCatching {
                    SurfaceControl.Transaction().use { transaction ->
                        transaction.setDataSpace(surfaceControl, dataSpace)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            transaction.setExtendedRangeBrightness(
                                surfaceControl,
                                if (enableHdrPassthrough) 1.0f else 1.0f,
                                if (enableHdrPassthrough) 4.0f else 1.0f
                            )
                        }
                        transaction.apply()
                    }
                }
            }
        }
    }

    /**
     * Traverses VLCVideoLayout to locate the active hardware video SurfaceView
     * (distinct from the Z-media-overlay subtitle SurfaceView).
     */
    fun VLCVideoLayout.findPrimaryVideoSurfaceView(): SurfaceView? {
        val surfaces = mutableListOf<SurfaceView>()
        collectSurfaceViews(this, surfaces)
        // VLCVideoLayout inflates video SurfaceView first and subtitle overlay SurfaceView second
        return surfaces.firstOrNull()
    }

    private fun collectSurfaceViews(view: View, out: MutableList<SurfaceView>) {
        if (view is SurfaceView) {
            out.add(view)
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectSurfaceViews(view.getChildAt(i), out)
            }
        }
    }

    private fun fourccToString(fourcc: Int): String {
        if (fourcc == 0) return ""
        val chars = charArrayOf(
            (fourcc and 0xFF).toChar(),
            ((fourcc shr 8) and 0xFF).toChar(),
            ((fourcc shr 16) and 0xFF).toChar(),
            ((fourcc shr 24) and 0xFF).toChar()
        )
        return String(chars).trim()
    }

    private tailrec fun Context.findHostActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findHostActivity()
        else -> null
    }
}
