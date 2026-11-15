package app.lumina.player.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import app.lumina.player.ui.theme.LuminaCyan
import app.lumina.player.ui.theme.LuminaExpressiveMotion
import app.lumina.player.ui.theme.LuminaGlassBorder
import app.lumina.player.ui.theme.LuminaGlassBorderHighlight
import app.lumina.player.ui.theme.LuminaGlassFill
import app.lumina.player.ui.theme.LuminaGlassFillStrong
import app.lumina.player.ui.theme.LuminaOledBlack
import app.lumina.player.ui.theme.LuminaPlatinum
import app.lumina.player.ui.theme.LuminaTeal
import app.lumina.player.ui.theme.LuminaTextPrimary
import app.lumina.player.ui.theme.LuminaTextSecondary
import java.util.Locale

/**
 * Pads top content against both mobile status bars (`WindowInsets.statusBars`) and
 * Googlebook OS freeform desktop window caption bars (`WindowInsets.captionBar`) so top-right
 * controls never collide with system minimize/maximize/close window buttons.
 */
@Composable
fun Modifier.luminaTopWindowInsets(): Modifier =
    this.windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.captionBar))

/**
 * Pads HUD chrome horizontally against camera punch-holes/notches (`WindowInsets.displayCutout`)
 * and side system navigation bars in Horizontal / Landscape mode while allowing the underlying
 * video surface to span 100% symmetrically edge-to-edge.
 */
@Composable
fun Modifier.luminaHorizontalSafeInsets(): Modifier =
    this.windowInsetsPadding(
        WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    )

/**
 * Lumina's signature 6-facet **Smoked-Crystal Play Prism** brand emblem.
 *
 * Uses an app-emblem squircle (`RoundedCornerShape(size * 0.28f)`) with subtle OLED obsidian glass
 * framing rather than `CircleShape` so it is never mistaken for an interactive toolbar button.
 */
@Composable
fun LuminaBrandLogo(
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    showContainer: Boolean = true
) {
    val emblemShape = RoundedCornerShape(size * 0.28f)
    val containerModifier = if (showContainer) {
        modifier
            .size(size)
            .clip(emblemShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF0E1420), Color(0xFF05070C))
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.16f),
                        LuminaCyan.copy(alpha = 0.10f)
                    )
                ),
                shape = emblemShape
            )
            .padding(size * 0.16f)
    } else {
        modifier.size(size)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = containerModifier
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height

            // Outer play prism vertices (A = top-left, B = right apex, C = bottom-left)
            val ax = w * 0.20f
            val ay = h * 0.11f
            val bx = w * 0.88f
            val by = h * 0.50f
            val cx = w * 0.20f
            val cy = h * 0.89f

            // Inner beveled table vertices (a, b, c) + central optical refraction apex (o)
            val iax = w * 0.32f
            val iay = h * 0.28f
            val ibx = w * 0.69f
            val iby = h * 0.50f
            val icx = w * 0.32f
            val icy = h * 0.72f
            val ox = w * 0.46f
            val oy = h * 0.50f

            fun poly(vararg pts: Pair<Float, Float>): Path = Path().apply {
                moveTo(pts[0].first, pts[0].second)
                for (i in 1 until pts.size) {
                    lineTo(pts[i].first, pts[i].second)
                }
                close()
            }

            // 1. Outer top sunlit Moonlight Platinum bevel (A -> B -> b -> a)
            drawPath(
                path = poly(ax to ay, bx to by, ibx to iby, iax to iay),
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFFF8FAFC), Color(0xFFCBD5E1)),
                    start = Offset(ax, ay),
                    end = Offset(bx, by)
                )
            )

            // 2. Outer bottom Glacier Ice refractive bevel (C -> c -> b -> B)
            drawPath(
                path = poly(cx to cy, icx to icy, ibx to iby, bx to by),
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFFBAE6FD), Color(0xFF38BDF8)),
                    start = Offset(cx, cy),
                    end = Offset(bx, by)
                )
            )

            // 3. Outer left Brushed Slate Silver bevel (A -> a -> c -> C)
            drawPath(
                path = poly(ax to ay, iax to iay, icx to icy, cx to cy),
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFFCBD5E1), Color(0xFF64748B)),
                    startY = ay,
                    endY = cy
                )
            )

            // 4. Inner upper Smoked Crystal facet (a -> b -> o)
            drawPath(
                path = poly(iax to iay, ibx to iby, ox to oy),
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF475569), Color(0xFF94A3B8)),
                    start = Offset(iax, iay),
                    end = Offset(ibx, iby)
                )
            )

            // 5. Inner lower Ice Crystal facet (o -> b -> c)
            drawPath(
                path = poly(ox to oy, ibx to iby, icx to icy),
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFFE0F2FE), Color(0xFF7DD3FC)),
                    start = Offset(ox, oy),
                    end = Offset(icx, icy)
                )
            )

            // 6. Inner left Deep Obsidian Crystal facet (a -> o -> c)
            drawPath(
                path = poly(iax to iay, ox to oy, icx to icy),
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF334155), Color(0xFF1E293B)),
                    startY = iay,
                    endY = icy
                )
            )

            // Crisp optical specular ridge lines
            val ridgeStroke = (w * 0.032f).coerceAtLeast(1f)
            val ridgeColor = Color.White.copy(alpha = 0.55f)
            drawLine(ridgeColor, Offset(ax, ay), Offset(iax, iay), strokeWidth = ridgeStroke, cap = StrokeCap.Round)
            drawLine(ridgeColor, Offset(bx, by), Offset(ibx, iby), strokeWidth = ridgeStroke, cap = StrokeCap.Round)
            drawLine(ridgeColor, Offset(cx, cy), Offset(icx, icy), strokeWidth = ridgeStroke, cap = StrokeCap.Round)
            drawPath(
                path = poly(iax to iay, ibx to iby, icx to icy),
                color = Color.White.copy(alpha = 0.38f),
                style = Stroke(width = ridgeStroke * 0.85f, join = StrokeJoin.Round)
            )
            drawPath(
                path = poly(ax to ay, bx to by, cx to cy),
                color = Color.White.copy(alpha = 0.70f),
                style = Stroke(width = ridgeStroke, join = StrokeJoin.Round)
            )
        }
    }
}

/**
 * Computes the next shrunk font size (in `sp`) when a single-line label overflows its container width,
 * stopping cleanly at [minFontSizeSp] so localized labels like `"Einstellungen"` or `"Téléchargements"`
 * always fit on one unbroken line without mid-word character splits.
 */
internal fun computeNextAutoFitFontSizeSp(
    currentSizeSp: Float,
    minFontSizeSp: Float = 8.5f,
    stepRatio: Float = 0.91f
): Float? {
    if (currentSizeSp <= minFontSizeSp + 0.05f) return null
    return (currentSizeSp * stepRatio).coerceAtLeast(minFontSizeSp)
}

/**
 * Single-line auto-fitting text label that shrinks its font size smoothly down to [minFontSizeSp]
 * whenever localized strings (such as `"Einstellungen"` or `"Téléchargements"`) exceed narrow mobile
 * navigation cells or header slots — preventing mid-word character wrapping (`"Einstellunge\nn"`)
 * and avoiding text clipping.
 */
@Composable
fun LuminaSingleLineFitText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelMedium,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    minFontSizeSp: Float = 8.5f
) {
    val baseSizeSp = if (style.fontSize.isSpecified) style.fontSize.value else 12f
    var currentFontSizeSp by remember(text, baseSizeSp) { mutableFloatStateOf(baseSizeSp) }
    var readyToDraw by remember(text, baseSizeSp) { mutableStateOf(false) }

    Text(
        text = text,
        style = style.copy(
            fontSize = currentFontSizeSp.sp,
            letterSpacing = if (currentFontSizeSp < baseSizeSp) 0.sp else style.letterSpacing
        ),
        fontWeight = fontWeight,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layoutResult ->
            val nextSize = if (layoutResult.hasVisualOverflow) {
                computeNextAutoFitFontSizeSp(currentFontSizeSp, minFontSizeSp)
            } else {
                null
            }
            if (nextSize != null) {
                currentFontSizeSp = nextSize
            } else {
                readyToDraw = true
            }
        },
        modifier = modifier.drawWithContent {
            if (readyToDraw) {
                drawContent()
            }
        }
    )
}

/**
 * High-contrast smoked obsidian surface with subtle specular border for crisp readability.
 */
@Composable
fun LuminaGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    strongOpacity: Boolean = false,
    accentGlow: Color? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val baseTop = if (strongOpacity) LuminaGlassFillStrong else LuminaGlassFill
    val baseBottom = if (strongOpacity) Color(0xF706080E) else Color(0xEB080B12)
    val borderBrush = Brush.verticalGradient(
        colors = listOf(
            Color(0x3DFFFFFF),
            LuminaGlassBorder
        )
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(baseTop, baseBottom)
                )
            )
            .then(
                if (accentGlow != null) {
                    Modifier.background(
                        Brush.radialGradient(
                            colors = listOf(accentGlow.copy(alpha = 0.10f), Color.Transparent)
                        )
                    )
                } else {
                    Modifier
                }
            )
            .border(BorderStroke(1.dp, borderBrush), shape)
            .padding(contentPadding),
        content = content
    )
}

/**
 * Material 3 Expressive glass icon button:
 * - Dynamically morphs corner geometry from a full circle (`50%`) to an expressive squircle
 *   on press or active state using spatial springs (`LuminaExpressiveMotion.spatialDpSpring`).
 * - Uses a rich dark smoked obsidian backing so icons stay unmistakably legible over bright HDR video.
 */
@Composable
fun LuminaGlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    iconSize: Dp = 20.dp,
    isActive: Boolean = false,
    morphWhenActive: Boolean = false,
    tint: Color = if (isActive) LuminaOledBlack else LuminaTextPrimary
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val targetCornerRadius = when {
        isPressed -> size * 0.26f
        isActive && morphWhenActive -> size * 0.30f
        isActive -> size * 0.36f
        else -> size * 0.50f
    }

    val cornerRadius by animateDpAsState(
        targetValue = targetCornerRadius,
        animationSpec = LuminaExpressiveMotion.spatialDpSpring,
        label = "expressiveIconCorner"
    )

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1.0f,
        animationSpec = LuminaExpressiveMotion.spatialFloatSpring,
        label = "expressiveIconScale"
    )

    val background by animateColorAsState(
        targetValue = when {
            isActive -> LuminaPlatinum
            isHovered || isFocused -> Color(0xEB1E293B)
            else -> Color(0xD9131A28)
        },
        animationSpec = LuminaExpressiveMotion.effectsColorSpring,
        label = "glassIconBg"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isActive -> LuminaPlatinum
            isHovered || isFocused -> LuminaGlassBorderHighlight
            else -> LuminaGlassBorder
        },
        animationSpec = LuminaExpressiveMotion.effectsColorSpring,
        label = "glassIconBorder"
    )

    val morphShape = RoundedCornerShape(cornerRadius)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(morphShape)
            .background(background)
            .border(1.dp, borderColor, morphShape)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * Material 3 Expressive smoked-obsidian pill button with spring-driven corner morphing:
 * capsule (`20.dp`) at rest -> expressive squircle (`12.dp`) when selected, `9.dp` when pressed.
 */
@Composable
fun LuminaGlassPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    isActive: Boolean = false,
    accentColor: Color = LuminaPlatinum
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val cornerRadius by animateDpAsState(
        targetValue = when {
            isPressed -> 9.dp
            isActive -> 12.dp
            else -> 20.dp
        },
        animationSpec = LuminaExpressiveMotion.spatialDpSpring,
        label = "expressivePillCorner"
    )

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1.0f,
        animationSpec = LuminaExpressiveMotion.spatialFloatSpring,
        label = "expressivePillScale"
    )

    val bg by animateColorAsState(
        targetValue = when {
            isActive -> Color(0xED18263A)
            isHovered || isFocused -> Color(0xEB1E293B)
            else -> Color(0xD9131A28)
        },
        animationSpec = LuminaExpressiveMotion.effectsColorSpring,
        label = "pillBg"
    )

    val border by animateColorAsState(
        targetValue = when {
            isActive || isFocused -> accentColor.copy(alpha = 0.72f)
            isHovered -> Color(0x52FFFFFF)
            else -> LuminaGlassBorder
        },
        animationSpec = LuminaExpressiveMotion.effectsColorSpring,
        label = "pillBorder"
    )

    val morphShape = RoundedCornerShape(cornerRadius)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(morphShape)
            .background(bg)
            .border(1.dp, border, morphShape)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 13.dp, vertical = 7.dp)
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = if (isActive) accentColor else LuminaTextPrimary,
                modifier = Modifier.size(15.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            color = if (isActive) accentColor else LuminaTextPrimary
        )
    }
}

/**
 * Material 3 Expressive Connected Segmented Button Group (`ButtonGroup` pattern):
 * Unselected adjacent segments share tight `6.dp` inner corners while the outer ends form a `20.dp`
 * capsule; selecting or pressing a segment morphs its corners into a standalone pill via spatial springs.
 */
@Composable
fun <T> LuminaExpressiveSegmentedGroup(
    options: List<T>,
    selectedOption: T,
    labelProvider: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = LuminaPlatinum
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier.horizontalScroll(rememberScrollState())
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selectedOption
            val isFirst = index == 0
            val isLast = index == options.lastIndex
            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            val isHovered by interactionSource.collectIsHoveredAsState()

            val startRadius by animateDpAsState(
                targetValue = when {
                    isPressed -> 10.dp
                    isSelected -> 18.dp
                    isFirst -> 18.dp
                    else -> 6.dp
                },
                animationSpec = LuminaExpressiveMotion.spatialDpSpring,
                label = "segStartRadius"
            )
            val endRadius by animateDpAsState(
                targetValue = when {
                    isPressed -> 10.dp
                    isSelected -> 18.dp
                    isLast -> 18.dp
                    else -> 6.dp
                },
                animationSpec = LuminaExpressiveMotion.spatialDpSpring,
                label = "segEndRadius"
            )

            val bg by animateColorAsState(
                targetValue = when {
                    isSelected -> Color(0xED1A283C)
                    isHovered -> Color(0xE01E293B)
                    else -> Color(0xD6121826)
                },
                animationSpec = LuminaExpressiveMotion.effectsColorSpring,
                label = "segBg"
            )

            val border by animateColorAsState(
                targetValue = if (isSelected) accentColor.copy(alpha = 0.72f) else LuminaGlassBorder,
                animationSpec = LuminaExpressiveMotion.effectsColorSpring,
                label = "segBorder"
            )

            val segShape = RoundedCornerShape(
                topStart = startRadius,
                bottomStart = startRadius,
                topEnd = endRadius,
                bottomEnd = endRadius
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(segShape)
                    .background(bg)
                    .border(1.dp, border, segShape)
                    .hoverable(interactionSource)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) }
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = labelProvider(option),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) accentColor else LuminaTextSecondary
                )
            }
        }
    }
}

/**
 * High-contrast platinum-and-ice optical micro-badge for HDR10, HDR10+, HLG, Dolby Vision, and AV1/FLAC.
 */
@Composable
fun HdrBadgeChip(
    badgeText: String,
    modifier: Modifier = Modifier
) {
    val upper = badgeText.uppercase(Locale.ROOT)
    val tint = when {
        upper.contains("HLG") -> LuminaTeal
        upper.contains("HDR") || upper.contains("DOLBY") || upper.contains("DV") -> LuminaCyan
        else -> LuminaPlatinum
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(Color(0xF0070A10))
            .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(7.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = badgeText.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
            fontWeight = FontWeight.SemiBold,
            color = tint
        )
    }
}

/**
 * Formats millisecond durations into `HH:MM:SS` or `MM:SS` tabular strings.
 */
fun formatDurationMs(durationMs: Long): String {
    if (durationMs <= 0L) return "00:00"
    val totalSeconds = durationMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/**
 * Smoked-Crystal Googlebook OS & Desktop Drag-and-Drop hover target overlay.
 */
@Composable
fun LuminaDragDropTargetOverlay(
    visible: Boolean,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn(LuminaExpressiveMotion.effectsFast()),
        exit = androidx.compose.animation.fadeOut(LuminaExpressiveMotion.effectsFast()),
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(LuminaOledBlack.copy(alpha = 0.78f))
                .padding(28.dp)
                .border(
                    width = 2.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(LuminaPlatinum, LuminaCyan)
                    ),
                    shape = RoundedCornerShape(28.dp)
                )
        ) {
            LuminaGlassSurface(
                strongOpacity = true,
                accentGlow = LuminaCyan,
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 26.dp)
            ) {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LuminaBrandLogo(size = 52.dp)
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = LuminaPlatinum
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LuminaTextSecondary
                    )
                }
            }
        }
    }
}


