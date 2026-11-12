package app.lumina.player.ui.adaptive

import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/**
 * Adaptive layout mode across Smartphones, Foldables, and Googlebook OS Laptops/Desktops.
 */
enum class LuminaLayoutMode {
    /** Compact phone layout: Bottom Navigation Bar + touch swipe gestures */
    COMPACT_PHONE,
    /** Half-folded horizontal hinge: HDR video cleanly above hinge, glass controls & queue below */
    FOLDABLE_TABLETOP,
    /** Unfolded foldable or tablet medium pane: Navigation Rail */
    MEDIUM_ADAPTIVE,
    /** Googlebook OS / Large Desktop environment: Collapsible sidebar, caption bar, hover & shortcuts */
    GOOGLEBOOK_DESKTOP
}

/**
 * Snapshot of the window size, physical foldable hinge geometry, and desktop/freeform capabilities.
 */
data class LuminaAdaptiveEnvironment(
    val layoutMode: LuminaLayoutMode,
    val windowSizeClass: WindowSizeClass,
    val isGooglebookOrDesktop: Boolean,
    val isFreeformMultiWindow: Boolean,
    val hingeTopDp: Dp = 0.dp,
    val hingeHeightDp: Dp = 0.dp
) {
    val isTabletopMode: Boolean
        get() = layoutMode == LuminaLayoutMode.FOLDABLE_TABLETOP
}

/**
 * Observes `WindowSizeClass`, Jetpack `FoldingFeature` posture, and Googlebook OS hardware traits
 * in real time without restarting playback.
 */
@Composable
fun rememberLuminaAdaptiveEnvironment(activity: Activity): LuminaAdaptiveEnvironment {
    val windowSizeClass = calculateWindowSizeClass(activity)
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    val windowLayoutFlow = remember(activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)
    }
    val windowLayoutInfo by windowLayoutFlow.collectAsState(initial = null)

    val hasPcFeature = remember(activity) {
        val pm = activity.packageManager
        pm.hasSystemFeature(PackageManager.FEATURE_PC) ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    val hasHardwareKeyboard = configuration.keyboard == Configuration.KEYBOARD_QWERTY &&
        configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO

    val isMultiWindow = activity.isInMultiWindowMode

    val foldingFeature = windowLayoutInfo?.displayFeatures
        ?.filterIsInstance<FoldingFeature>()
        ?.firstOrNull()

    val isTabletopPosture = foldingFeature != null &&
        foldingFeature.state == FoldingFeature.State.HALF_OPENED &&
        foldingFeature.orientation == FoldingFeature.Orientation.HORIZONTAL

    val hingeTopDp = if (isTabletopPosture && foldingFeature != null) {
        with(density) { foldingFeature.bounds.top.toDp() }
    } else {
        0.dp
    }

    val hingeHeightDp = if (isTabletopPosture && foldingFeature != null) {
        with(density) { foldingFeature.bounds.height().toDp().coerceAtLeast(2.dp) }
    } else {
        0.dp
    }

    val isDesktopEnvironment = hasPcFeature ||
        windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded ||
        (hasHardwareKeyboard && windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact)

    val layoutMode = when {
        isTabletopPosture -> LuminaLayoutMode.FOLDABLE_TABLETOP
        isDesktopEnvironment -> LuminaLayoutMode.GOOGLEBOOK_DESKTOP
        windowSizeClass.widthSizeClass == WindowWidthSizeClass.Medium -> LuminaLayoutMode.MEDIUM_ADAPTIVE
        else -> LuminaLayoutMode.COMPACT_PHONE
    }

    return LuminaAdaptiveEnvironment(
        layoutMode = layoutMode,
        windowSizeClass = windowSizeClass,
        isGooglebookOrDesktop = isDesktopEnvironment,
        isFreeformMultiWindow = isMultiWindow,
        hingeTopDp = hingeTopDp,
        hingeHeightDp = hingeHeightDp
    )
}
