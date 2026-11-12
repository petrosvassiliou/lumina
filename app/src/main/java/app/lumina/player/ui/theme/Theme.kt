package app.lumina.player.ui.theme

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class LuminaGlassTokens(
    val canvasBlack: Color = LuminaOledBlack,
    val glassFill: Color = LuminaGlassFill,
    val glassFillStrong: Color = LuminaGlassFillStrong,
    val glassBorder: Color = LuminaGlassBorder,
    val glassBorderActive: Color = LuminaGlassBorderHighlight,
    val platinumAccent: Color = LuminaPlatinum,
    val cyanAccent: Color = LuminaCyan,
    val textPrimary: Color = LuminaTextPrimary,
    val textSecondary: Color = LuminaTextSecondary
)

/**
 * Material 3 Expressive motion physics tokens:
 * - **Spatial springs** allow subtle organic bounce for shape morphing, scale, and layout motion.
 * - **Effects springs** are critically damped (`dampingRatio = 1.0f`) so colors/alphas never overshoot.
 */
object LuminaExpressiveMotion {
    fun <T> spatialFast(): SpringSpec<T> = spring(dampingRatio = 0.60f, stiffness = 520f)
    fun <T> spatialStandard(): SpringSpec<T> = spring(dampingRatio = 0.64f, stiffness = 380f)
    fun <T> effectsFast(): SpringSpec<T> = spring(dampingRatio = 1.0f, stiffness = 560f)
    fun <T> effectsStandard(): SpringSpec<T> = spring(dampingRatio = 1.0f, stiffness = 380f)

    val spatialDpSpring: SpringSpec<Dp> = spring(dampingRatio = 0.60f, stiffness = 500f)
    val spatialFloatSpring: SpringSpec<Float> = spring(dampingRatio = 0.62f, stiffness = 480f)
    val effectsColorSpring: SpringSpec<Color> = spring(dampingRatio = 1.0f, stiffness = 520f)
}

/**
 * Material 3 Expressive corner shape scale combining architectural precision with organic curvature.
 */
val LuminaExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

val LocalLuminaGlass = staticCompositionLocalOf { LuminaGlassTokens() }

private val LuminaDarkColorScheme = darkColorScheme(
    primary = LuminaPlatinum,
    onPrimary = LuminaOledBlack,
    primaryContainer = LuminaPlatinumDim,
    onPrimaryContainer = LuminaPlatinumBright,
    secondary = LuminaCyan,
    onSecondary = LuminaOledBlack,
    secondaryContainer = LuminaCyanDim,
    onSecondaryContainer = LuminaCyanBright,
    tertiary = LuminaTeal,
    onTertiary = LuminaOledBlack,
    background = LuminaOledBlack,
    onBackground = LuminaTextPrimary,
    surface = LuminaObsidianSurface,
    onSurface = LuminaTextPrimary,
    surfaceVariant = LuminaObsidianElevated,
    onSurfaceVariant = LuminaTextSecondary,
    surfaceContainerLowest = LuminaOledBlack,
    surfaceContainerLow = Color(0xFF080B10),
    surfaceContainer = LuminaObsidianSurface,
    surfaceContainerHigh = LuminaObsidianElevated,
    surfaceContainerHighest = Color(0xFF171D29),
    outline = LuminaGlassBorder,
    outlineVariant = Color(0x14FFFFFF),
    error = LuminaDanger
)

/**
 * Root Material 3 Expressive theme for Lumina ("Lumina Crystal & Moonlight").
 * Enforces a deep OLED pitch-black (`#050507`) canvas paired with neutral smoked glass,
 * expressive shape-morphing controls, brushed moonlight platinum, and glacier-ice optical accents.
 */
@Composable
fun LuminaTheme(
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalLuminaGlass provides LuminaGlassTokens()) {
        MaterialTheme(
            colorScheme = LuminaDarkColorScheme,
            shapes = LuminaExpressiveShapes,
            typography = LuminaTypography,
            content = content
        )
    }
}

