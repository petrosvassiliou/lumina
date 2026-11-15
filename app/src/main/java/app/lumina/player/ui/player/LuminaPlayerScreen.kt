package app.lumina.player.ui.player

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.StayCurrentLandscape
import androidx.compose.material.icons.outlined.StayCurrentPortrait
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumina.player.R
import app.lumina.player.data.download.AdmDownloadPlanner
import app.lumina.player.data.local.entity.VideoBookmarkEntity
import app.lumina.player.data.preferences.SubtitleBackgroundStyle
import app.lumina.player.data.preferences.SubtitleFontScale
import app.lumina.player.data.preferences.UserPreferences
import app.lumina.player.player.model.HdrOutputMode
import app.lumina.player.player.model.LuminaPlayerState
import app.lumina.player.player.model.PlaybackStatus
import app.lumina.player.player.model.PlayerOrientationMode
import app.lumina.player.player.model.SleepTimerMode
import app.lumina.player.player.ui.LuminaVideoSurface
import app.lumina.player.player.ui.clampVideoPanOffset
import app.lumina.player.player.ui.clampVideoZoomScale
import app.lumina.player.ui.adaptive.LuminaAdaptiveEnvironment
import app.lumina.player.ui.adaptive.LuminaLayoutMode
import app.lumina.player.ui.components.HdrBadgeChip
import app.lumina.player.ui.components.LuminaDragDropTargetOverlay
import app.lumina.player.ui.components.LuminaGlassIconButton
import app.lumina.player.ui.components.LuminaGlassPillButton
import app.lumina.player.ui.components.LuminaGlassSurface
import app.lumina.player.ui.components.LuminaSingleLineFitText
import app.lumina.player.ui.components.formatDurationMs
import app.lumina.player.ui.components.luminaHorizontalSafeInsets
import app.lumina.player.ui.components.luminaTopWindowInsets
import app.lumina.player.ui.theme.LuminaCyan
import app.lumina.player.ui.theme.LuminaDanger
import app.lumina.player.ui.theme.LuminaExpressiveMotion
import app.lumina.player.ui.theme.LuminaGlassBorder
import app.lumina.player.ui.theme.LuminaObsidianElevated
import app.lumina.player.ui.theme.LuminaOledBlack
import app.lumina.player.ui.theme.LuminaPlatinum
import app.lumina.player.ui.theme.LuminaTextPrimary
import app.lumina.player.ui.theme.LuminaTextSecondary
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private enum class ActivePlayerSheet {
    NONE,
    TRACKS_AND_SYNC,
    EQUALIZER,
    SPEED_AND_LOOP,
    SLEEP_TIMER,
    CHAPTERS,
    BOOKMARKS,
    QUEUE,
    HDR_INSPECTOR
}

private enum class GestureOverlayKind {
    NONE,
    BRIGHTNESS,
    VOLUME,
    SCRUB,
    ZOOM,
    STATUS_TOAST
}

/**
 * Adaptive Cinema Player Screen for Lumina.
 *
 * Dynamically adapts across:
 * 1. **Compact Phones:** Edge-to-edge OLED video, pinch-to-zoom & pan, brightness/volume/scrub swipe gestures,
 *    live scrub frame preview bubble, bookmarks, background audio continuation, double-tap seek, PiP.
 * 2. **Foldables (Lumina Tabletop Mode):** Clean HDR video above the physical hinge, glass control deck & queue below.
 * 3. **Googlebook OS (Desktop):** Custom caption bar, cross-app drag-and-drop, Ctrl+Wheel & +/-/0 zoom,
 *    mouse hover HUD & timeline frame preview, right-click context menu, floating Always-on-Top mini-player,
 *    and full hardware keyboard shortcuts (`Space`, `←/→`, `↑/↓`, `+/-`, `0`, `B`, `F`, `M`, `C`, `V`, `A`, `L`, `[/]`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LuminaPlayerScreen(
    activity: Activity,
    viewModel: LuminaPlayerViewModel,
    adaptiveEnv: LuminaAdaptiveEnvironment,
    isSystemPipMode: Boolean,
    isFullscreen: Boolean,
    isGooglebookFloatingMini: Boolean,
    isDragHovering: Boolean = false,
    orientationMode: PlayerOrientationMode = PlayerOrientationMode.AUTO,
    onCycleOrientation: () -> PlayerOrientationMode = { PlayerOrientationMode.AUTO },
    onToggleFullscreen: () -> Unit,
    onEnterPip: () -> Unit,
    onToggleGooglebookMiniPlayer: () -> Unit,
    onMinimizeToBackgroundAudio: () -> Unit = {},
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val prefs by viewModel.userPreferences.collectAsStateWithLifecycle()
    val bookmarks by viewModel.currentMediaBookmarks.collectAsStateWithLifecycle()
    val scrubPreviewBitmap by viewModel.scrubPreviewBitmap.collectAsStateWithLifecycle()

    var hudVisible by remember { mutableStateOf(true) }
    var hudInteractionTick by remember { mutableIntStateOf(0) }
    var activeSheet by rememberSaveable { mutableStateOf(ActivePlayerSheet.NONE) }
    var showSaveQueueDialog by rememberSaveable { mutableStateOf(false) }

    // Interactive Hardware Surface Zoom & Pan State (1.0x .. 4.0x)
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }
    var viewportWidthPx by remember { mutableFloatStateOf(1920f) }
    var viewportHeightPx by remember { mutableFloatStateOf(1080f) }

    LaunchedEffect(playerState.currentMedia?.uri, isSystemPipMode, isGooglebookFloatingMini) {
        zoomScale = 1f
        panOffsetX = 0f
        panOffsetY = 0f
    }

    // Transient Gesture / Keyboard Shortcut Pill Overlay
    var overlayKind by remember { mutableStateOf(GestureOverlayKind.NONE) }
    var overlayText by remember { mutableStateOf("") }
    var overlayTick by remember { mutableIntStateOf(0) }

    // Desktop Right-Click Context Menu
    var desktopMenuExpanded by remember { mutableStateOf(false) }
    var desktopMenuOffset by remember { mutableStateOf(DpOffset.Zero) }

    // Window Brightness state (0.02f .. 1.0f)
    var windowBrightness by remember {
        val current = activity.window.attributes.screenBrightness
        mutableFloatStateOf(if (current in 0.01f..1.0f) current else 0.65f)
    }

    val focusRequester = remember { FocusRequester() }

    // Local Video File Browser Launcher (MKV, MP4, MOV, AVI, WebM, TS)
    val localVideoMimeTypes = remember {
        arrayOf("video/*", "application/x-matroska", "application/octet-stream")
    }
    val localVideoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.openLocalVideoUri(uri)
        }
    }

    // External Subtitle File Picker (.srt, .ass, .ssa, .vtt)
    val subtitlePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.loadExternalSubtitle(uri)
        }
    }

    val touchLockedStatusMsg = stringResource(R.string.player_touch_locked_toast)
    val touchUnlockedStatusMsg = stringResource(R.string.player_touch_unlocked_toast)
    val bookmarkAddedToastMsg = stringResource(R.string.bookmarks_added_toast)
    val playlistSavedToastMsg = stringResource(R.string.playlists_saved_toast)
    val admAddedToastMsg = stringResource(R.string.adm_added_toast)
    val orientationAutoMsg = stringResource(R.string.orientation_auto)
    val orientationLandscapeMsg = stringResource(R.string.orientation_landscape)
    val orientationPortraitMsg = stringResource(R.string.orientation_portrait)
    val canAdmDownloadCurrent = remember(playerState.currentMedia?.uri) {
        AdmDownloadPlanner.isDownloadableStreamProtocol(playerState.currentMedia?.uri?.toString())
    }

    fun showTransientStatus(kind: GestureOverlayKind, message: String) {
        overlayKind = kind
        overlayText = message
        overlayTick++
    }

    fun orientationLabelFor(mode: PlayerOrientationMode): String = when (mode) {
        PlayerOrientationMode.AUTO -> orientationAutoMsg
        PlayerOrientationMode.LANDSCAPE -> orientationLandscapeMsg
        PlayerOrientationMode.PORTRAIT -> orientationPortraitMsg
    }

    fun triggerOrientationCycle() {
        val nextMode = onCycleOrientation()
        showTransientStatus(GestureOverlayKind.STATUS_TOAST, orientationLabelFor(nextMode))
    }

    fun triggerCurrentStreamAdmDownload() {
        if (canAdmDownloadCurrent && viewModel.downloadCurrentPlayingStream()) {
            showTransientStatus(GestureOverlayKind.STATUS_TOAST, admAddedToastMsg)
        }
    }

    fun wakeHud() {
        hudVisible = true
        hudInteractionTick++
    }

    fun applyZoomAndPan(targetScale: Float, deltaPanX: Float = 0f, deltaPanY: Float = 0f) {
        val clamped = clampVideoZoomScale(targetScale)
        zoomScale = clamped
        panOffsetX = clampVideoPanOffset(panOffsetX + deltaPanX, clamped, viewportWidthPx)
        panOffsetY = clampVideoPanOffset(panOffsetY + deltaPanY, clamped, viewportHeightPx)
    }

    fun resetZoomAndPan() {
        zoomScale = 1f
        panOffsetX = 0f
        panOffsetY = 0f
    }

    BackHandler {
        when {
            playerState.isTouchLocked -> {
                wakeHud()
                showTransientStatus(GestureOverlayKind.STATUS_TOAST, touchLockedStatusMsg)
            }
            activeSheet != ActivePlayerSheet.NONE -> activeSheet = ActivePlayerSheet.NONE
            isFullscreen -> onToggleFullscreen()
            else -> onNavigateBack()
        }
    }

    // Request keyboard focus automatically for Googlebook OS hardware shortcuts
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    // Auto-hide glass HUD after 3.5 seconds during active video playback, and wake when ended
    LaunchedEffect(playerState.status) {
        if (playerState.status == PlaybackStatus.ENDED) {
            wakeHud()
        }
    }

    LaunchedEffect(hudVisible, hudInteractionTick, playerState.isPlaying, activeSheet, playerState.isTouchLocked) {
        if (hudVisible && (playerState.isPlaying || playerState.isTouchLocked) && activeSheet == ActivePlayerSheet.NONE) {
            delay(if (playerState.isTouchLocked) 2_600L else 3_500L)
            hudVisible = false
        }
    }

    // Auto-dismiss transient gesture/shortcut toast pill
    LaunchedEffect(overlayTick) {
        if (overlayKind != GestureOverlayKind.NONE) {
            delay(1_100L)
            overlayKind = GestureOverlayKind.NONE
        }
    }

    fun togglePopover(target: ActivePlayerSheet) {
        if (playerState.isTouchLocked) return
        wakeHud()
        activeSheet = if (activeSheet == target) ActivePlayerSheet.NONE else target
    }

    // 1. Mobile Picture-in-Picture Chromeless View
    if (isSystemPipMode) {
        Box(modifier = Modifier.fillMaxSize().background(LuminaOledBlack)) {
            LuminaVideoSurface(
                playerEngine = viewModel.playerEngine,
                isPlaying = playerState.isPlaying,
                modifier = Modifier.fillMaxSize()
            )
        }
        return
    }

    // Root Keyboard Shortcut Container (Active across Desktop, Foldable, and External Keyboards)
    val seekStepMs = (prefs.doubleTapSeekSeconds.coerceAtLeast(5)) * 1_000L

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LuminaOledBlack)
            .onSizeChanged { sz ->
                if (sz.width > 0) viewportWidthPx = sz.width.toFloat()
                if (sz.height > 0) viewportHeightPx = sz.height.toFloat()
            }
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.key == Key.Spacebar || keyEvent.key == Key.MediaPlayPause) {
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        wakeHud()
                        if (playerState.isTouchLocked) {
                            showTransientStatus(GestureOverlayKind.STATUS_TOAST, touchLockedStatusMsg)
                        } else {
                            viewModel.togglePlayPause()
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                wakeHud()
                if (keyEvent.key == Key.L) {
                    activeSheet = ActivePlayerSheet.NONE
                    val locked = viewModel.toggleTouchLock()
                    showTransientStatus(
                        GestureOverlayKind.STATUS_TOAST,
                        if (locked) touchLockedStatusMsg else touchUnlockedStatusMsg
                    )
                    return@onPreviewKeyEvent true
                }
                if (playerState.isTouchLocked) {
                    showTransientStatus(GestureOverlayKind.STATUS_TOAST, touchLockedStatusMsg)
                    return@onPreviewKeyEvent true
                }
                when (keyEvent.key) {
                    Key.DirectionLeft -> {
                        val delta = if (keyEvent.isShiftPressed) -30_000L else -seekStepMs
                        viewModel.seekRelative(delta)
                        showTransientStatus(GestureOverlayKind.SCRUB, "${delta / 1000}s")
                        true
                    }
                    Key.DirectionRight -> {
                        val delta = if (keyEvent.isShiftPressed) 30_000L else seekStepMs
                        viewModel.seekRelative(delta)
                        showTransientStatus(GestureOverlayKind.SCRUB, "+${delta / 1000}s")
                        true
                    }
                    Key.DirectionUp -> {
                        viewModel.adjustVolumeBy(5)
                        showTransientStatus(
                            GestureOverlayKind.VOLUME,
                            "${(playerState.volume + 5).coerceAtMost(150)}%"
                        )
                        true
                    }
                    Key.DirectionDown -> {
                        viewModel.adjustVolumeBy(-5)
                        showTransientStatus(
                            GestureOverlayKind.VOLUME,
                            "${(playerState.volume - 5).coerceAtLeast(0)}%"
                        )
                        true
                    }
                    Key.Equals, Key.Plus, Key.NumPadAdd -> {
                        applyZoomAndPan(zoomScale + 0.25f)
                        showTransientStatus(
                            GestureOverlayKind.ZOOM,
                            String.format(Locale.US, "%.2f×", zoomScale)
                        )
                        true
                    }
                    Key.Minus, Key.NumPadSubtract -> {
                        applyZoomAndPan(zoomScale - 0.25f)
                        showTransientStatus(
                            GestureOverlayKind.ZOOM,
                            String.format(Locale.US, "%.2f×", zoomScale)
                        )
                        true
                    }
                    Key.Zero, Key.NumPad0 -> {
                        resetZoomAndPan()
                        showTransientStatus(GestureOverlayKind.ZOOM, "1.00×")
                        true
                    }
                    Key.B -> {
                        viewModel.addBookmarkAtCurrentPosition()
                        showTransientStatus(GestureOverlayKind.STATUS_TOAST, bookmarkAddedToastMsg)
                        true
                    }
                    Key.D -> {
                        if (canAdmDownloadCurrent) {
                            triggerCurrentStreamAdmDownload()
                            true
                        } else {
                            false
                        }
                    }
                    Key.F, Key.F11 -> {
                        onToggleFullscreen()
                        true
                    }
                    Key.R -> {
                        if (!adaptiveEnv.isGooglebookOrDesktop) {
                            triggerOrientationCycle()
                            true
                        } else {
                            false
                        }
                    }
                    Key.Escape -> {
                        when {
                            activeSheet != ActivePlayerSheet.NONE -> activeSheet = ActivePlayerSheet.NONE
                            zoomScale > 1.01f -> resetZoomAndPan()
                            isFullscreen -> onToggleFullscreen()
                            else -> onNavigateBack()
                        }
                        true
                    }
                    Key.M -> {
                        viewModel.toggleMute()
                        showTransientStatus(
                            GestureOverlayKind.VOLUME,
                            if (!playerState.isMuted) "Muted" else "${playerState.volume}%"
                        )
                        true
                    }
                    Key.C -> {
                        val nextMode = viewModel.cycleAspectRatio()
                        showTransientStatus(GestureOverlayKind.STATUS_TOAST, nextMode.label)
                        true
                    }
                    Key.V -> {
                        viewModel.cycleSubtitleTrack()
                        showTransientStatus(GestureOverlayKind.STATUS_TOAST, "Subtitles")
                        true
                    }
                    Key.A -> {
                        viewModel.cycleAudioTrack()
                        showTransientStatus(GestureOverlayKind.STATUS_TOAST, "Audio Track")
                        true
                    }
                    Key.LeftBracket -> {
                        viewModel.previousChapter()
                        true
                    }
                    Key.RightBracket -> {
                        viewModel.nextChapter()
                        true
                    }
                    Key.O -> {
                        localVideoPickerLauncher.launch(localVideoMimeTypes)
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(focusRequester)
            .focusable()
    ) {
        when {
            // 2. Googlebook OS Floating Always-on-Top Mini-Player Mode
            isGooglebookFloatingMini -> {
                GooglebookFloatingMiniPlayer(
                    playerState = playerState,
                    viewModel = viewModel,
                    onExpandToFullPlayer = onToggleGooglebookMiniPlayer,
                    onClosePlayer = {
                        viewModel.stopAndSave()
                        onNavigateBack()
                    }
                )
            }

            // 3. Foldable Tabletop Mode (Horizontal Hinge Half-Opened)
            adaptiveEnv.isTabletopMode -> {
                FoldableTabletopPlayerLayout(
                    adaptiveEnv = adaptiveEnv,
                    playerState = playerState,
                    bookmarks = bookmarks,
                    scrubPreviewBitmap = scrubPreviewBitmap,
                    viewModel = viewModel,
                    hudVisible = hudVisible,
                    isFullscreen = isFullscreen,
                    canAdmDownloadCurrent = canAdmDownloadCurrent,
                    onAdmDownloadStream = ::triggerCurrentStreamAdmDownload,
                    onToggleHud = {
                        if (activeSheet != ActivePlayerSheet.NONE) {
                            activeSheet = ActivePlayerSheet.NONE
                        } else {
                            hudVisible = !hudVisible
                        }
                    },
                    onOpenSheet = ::togglePopover,
                    onOpenLocalVideo = { localVideoPickerLauncher.launch(localVideoMimeTypes) },
                    onSaveQueueAsPlaylist = { showSaveQueueDialog = true },
                    onMinimizeToBackgroundAudio = onMinimizeToBackgroundAudio,
                    onToggleFullscreen = onToggleFullscreen,
                    onNavigateBack = onNavigateBack
                )
            }

            // 4. Phone & Googlebook OS Full Cinema Player
            else -> {
                StandardOrDesktopCinemaPlayerLayout(
                    adaptiveEnv = adaptiveEnv,
                    playerState = playerState,
                    bookmarks = bookmarks,
                    scrubPreviewBitmap = scrubPreviewBitmap,
                    viewModel = viewModel,
                    hudVisible = hudVisible,
                    isFullscreen = isFullscreen,
                    orientationMode = orientationMode,
                    onCycleOrientation = ::triggerOrientationCycle,
                    allowGooglebookMini = prefs.googlebookAlwaysOnTopMiniPlayer,
                    canAdmDownloadCurrent = canAdmDownloadCurrent,
                    onAdmDownloadStream = ::triggerCurrentStreamAdmDownload,
                    seekStepMs = seekStepMs,
                    zoomScale = zoomScale,
                    panOffsetX = panOffsetX,
                    panOffsetY = panOffsetY,
                    onZoomAndPanChange = ::applyZoomAndPan,
                    onResetZoom = ::resetZoomAndPan,
                    windowBrightness = windowBrightness,
                    onBrightnessChange = { updated ->
                        windowBrightness = updated
                        val lp = activity.window.attributes
                        lp.screenBrightness = updated
                        activity.window.attributes = lp
                    },
                    onToggleHud = {
                        if (activeSheet != ActivePlayerSheet.NONE) {
                            activeSheet = ActivePlayerSheet.NONE
                        } else {
                            hudVisible = !hudVisible
                            if (hudVisible) hudInteractionTick++
                        }
                    },
                    onWakeHud = ::wakeHud,
                    onGestureFeedback = ::showTransientStatus,
                    onRightClickMenu = { offset ->
                        desktopMenuOffset = offset
                        desktopMenuExpanded = true
                    },
                    onOpenSheet = ::togglePopover,
                    onOpenLocalVideo = { localVideoPickerLauncher.launch(localVideoMimeTypes) },
                    onMinimizeToBackgroundAudio = onMinimizeToBackgroundAudio,
                    onToggleFullscreen = onToggleFullscreen,
                    onEnterPip = onEnterPip,
                    onToggleGooglebookMini = onToggleGooglebookMiniPlayer,
                    onNavigateBack = onNavigateBack
                )
            }
        }

        // Center Gesture / Shortcut Glass Feedback Pill
        AnimatedVisibility(
            visible = overlayKind != GestureOverlayKind.NONE,
            enter = fadeIn(LuminaExpressiveMotion.effectsFast()),
            exit = fadeOut(LuminaExpressiveMotion.effectsStandard()),
            modifier = Modifier.align(Alignment.Center)
        ) {
            LuminaGlassSurface(
                strongOpacity = true,
                accentGlow = LuminaPlatinum,
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val icon = when (overlayKind) {
                        GestureOverlayKind.BRIGHTNESS -> Icons.Outlined.BrightnessMedium
                        GestureOverlayKind.VOLUME -> if (playerState.isMuted) {
                            Icons.AutoMirrored.Outlined.VolumeOff
                        } else {
                            Icons.AutoMirrored.Outlined.VolumeUp
                        }
                        GestureOverlayKind.SCRUB -> Icons.Outlined.Forward10
                        GestureOverlayKind.ZOOM -> Icons.Outlined.ZoomIn
                        else -> Icons.Outlined.Tune
                    }
                    Icon(icon, contentDescription = null, tint = LuminaPlatinum)
                    Text(
                        text = overlayText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = LuminaTextPrimary
                    )
                }
            }
        }

        // Buffering Spinner
        if (playerState.status == PlaybackStatus.OPENING || playerState.status == PlaybackStatus.BUFFERING) {
            CircularProgressIndicator(
                color = LuminaPlatinum,
                modifier = Modifier
                    .size(52.dp)
                    .align(Alignment.Center)
            )
        }

        // Playback Error Recovery Card (Material 3 Expressive)
        AnimatedVisibility(
            visible = playerState.status == PlaybackStatus.ERROR,
            enter = fadeIn(LuminaExpressiveMotion.effectsFast()) + scaleIn(
                animationSpec = LuminaExpressiveMotion.spatialStandard(),
                initialScale = 0.92f
            ),
            exit = fadeOut(LuminaExpressiveMotion.effectsFast()),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp)
        ) {
            LuminaGlassSurface(
                strongOpacity = true,
                accentGlow = LuminaDanger,
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 18.dp),
                modifier = Modifier.widthIn(max = 420.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = LuminaDanger
                        )
                        Text(
                            text = stringResource(R.string.player_error_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = LuminaTextPrimary
                        )
                    }
                    if (!playerState.errorMessage.isNullOrBlank()) {
                        Text(
                            text = playerState.errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LuminaTextSecondary
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LuminaGlassPillButton(
                            label = stringResource(R.string.player_retry_action),
                            leadingIcon = Icons.Outlined.Refresh,
                            isActive = true,
                            onClick = viewModel::retryCurrentMedia
                        )
                        LuminaGlassPillButton(
                            label = stringResource(R.string.player_open_local_file),
                            leadingIcon = Icons.Outlined.FolderOpen,
                            accentColor = LuminaCyan,
                            onClick = { localVideoPickerLauncher.launch(localVideoMimeTypes) }
                        )
                        LuminaGlassPillButton(
                            label = stringResource(R.string.player_back_to_library),
                            leadingIcon = Icons.AutoMirrored.Outlined.ArrowBack,
                            onClick = {
                                viewModel.stopAndSave()
                                onNavigateBack()
                            }
                        )
                    }
                }
            }
        }

        // Googlebook OS Right-Click Context Menu on Player Surface
        DropdownMenu(
            expanded = desktopMenuExpanded,
            offset = desktopMenuOffset,
            shape = RoundedCornerShape(18.dp),
            containerColor = Color(0xF70C101A),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.24f)),
            onDismissRequest = { desktopMenuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.player_play_pause)) },
                leadingIcon = {
                    Icon(
                        if (playerState.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = null
                    )
                },
                onClick = {
                    desktopMenuExpanded = false
                    viewModel.togglePlayPause()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.bookmarks_add_current)) },
                leadingIcon = { Icon(Icons.Outlined.BookmarkAdd, contentDescription = null) },
                onClick = {
                    desktopMenuExpanded = false
                    viewModel.addBookmarkAtCurrentPosition()
                    showTransientStatus(GestureOverlayKind.STATUS_TOAST, bookmarkAddedToastMsg)
                }
            )
            if (canAdmDownloadCurrent) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.adm_download_stream_action)) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.ArrowDownward,
                            contentDescription = null,
                            tint = LuminaCyan
                        )
                    },
                    onClick = {
                        desktopMenuExpanded = false
                        triggerCurrentStreamAdmDownload()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("${stringResource(R.string.player_aspect_ratio)} (${playerState.aspectRatioMode.label})") },
                leadingIcon = { Icon(Icons.Outlined.AspectRatio, contentDescription = null) },
                onClick = {
                    desktopMenuExpanded = false
                    viewModel.cycleAspectRatio()
                }
            )
            if (zoomScale > 1.01f) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                R.string.player_reset_zoom,
                                String.format(Locale.US, "%.1f×", zoomScale)
                            )
                        )
                    },
                    leadingIcon = { Icon(Icons.Outlined.ZoomIn, contentDescription = null) },
                    onClick = {
                        desktopMenuExpanded = false
                        resetZoomAndPan()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.player_audio_subtitles)) },
                leadingIcon = { Icon(Icons.Outlined.Subtitles, contentDescription = null) },
                onClick = {
                    desktopMenuExpanded = false
                    togglePopover(ActivePlayerSheet.TRACKS_AND_SYNC)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.player_equalizer)) },
                leadingIcon = { Icon(Icons.Outlined.Equalizer, contentDescription = null) },
                onClick = {
                    desktopMenuExpanded = false
                    togglePopover(ActivePlayerSheet.EQUALIZER)
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.player_background_audio)) },
                leadingIcon = { Icon(Icons.Outlined.Headphones, contentDescription = null) },
                onClick = {
                    desktopMenuExpanded = false
                    onMinimizeToBackgroundAudio()
                }
            )
            if (adaptiveEnv.layoutMode == LuminaLayoutMode.GOOGLEBOOK_DESKTOP && prefs.googlebookAlwaysOnTopMiniPlayer) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.player_mini_player)) },
                    leadingIcon = { Icon(Icons.Outlined.OpenInNew, contentDescription = null) },
                    onClick = {
                        desktopMenuExpanded = false
                        onToggleGooglebookMiniPlayer()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.player_open_local_file)) },
                leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                onClick = {
                    desktopMenuExpanded = false
                    localVideoPickerLauncher.launch(localVideoMimeTypes)
                }
            )
            if (!adaptiveEnv.isGooglebookOrDesktop) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "${stringResource(R.string.player_rotate_screen)} (${orientationLabelFor(orientationMode)})"
                        )
                    },
                    leadingIcon = {
                        Icon(
                            when (orientationMode) {
                                PlayerOrientationMode.AUTO -> Icons.Outlined.ScreenRotation
                                PlayerOrientationMode.LANDSCAPE -> Icons.Outlined.StayCurrentLandscape
                                PlayerOrientationMode.PORTRAIT -> Icons.Outlined.StayCurrentPortrait
                            },
                            contentDescription = null
                        )
                    },
                    onClick = {
                        desktopMenuExpanded = false
                        triggerOrientationCycle()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.player_fullscreen)) },
                leadingIcon = {
                    Icon(
                        if (isFullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                        contentDescription = null
                    )
                },
                onClick = {
                    desktopMenuExpanded = false
                    onToggleFullscreen()
                }
            )
        }

        // High-Contrast Floating Cinema Unlock Pill (visible when Touch Lock is active and user taps screen)
        AnimatedVisibility(
            visible = playerState.isTouchLocked && hudVisible && !isGooglebookFloatingMini,
            enter = fadeIn(LuminaExpressiveMotion.effectsFast()) + scaleIn(
                animationSpec = LuminaExpressiveMotion.spatialFast(),
                initialScale = 0.92f
            ),
            exit = fadeOut(LuminaExpressiveMotion.effectsFast()),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .luminaTopWindowInsets()
                .luminaHorizontalSafeInsets()
                .padding(top = 18.dp)
        ) {
            val pillShape = RoundedCornerShape(999.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .clip(pillShape)
                    .background(Color(0xF20B0F18))
                    .border(1.5.dp, LuminaCyan.copy(alpha = 0.72f), pillShape)
                    .clickable {
                        viewModel.setTouchLocked(false)
                        wakeHud()
                    }
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = LuminaCyan,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.player_unlock_controls),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = LuminaTextPrimary
                )
            }
        }

        // Non-Intrusive Floating Translucent HUD Popovers
        val isCompactPortraitHud = !adaptiveEnv.isTabletopMode &&
            !adaptiveEnv.isGooglebookOrDesktop &&
            !adaptiveEnv.isLandscape &&
            adaptiveEnv.screenWidthDp in 1..579
        val bottomPopoverClearance = when {
            adaptiveEnv.isTabletopMode -> 16.dp
            isCompactPortraitHud -> 172.dp
            else -> 128.dp
        }
        val popoversAllowed = !isGooglebookFloatingMini && !playerState.isTouchLocked

        AnimatedVisibility(
            visible = activeSheet == ActivePlayerSheet.TRACKS_AND_SYNC && popoversAllowed,
            enter = fadeIn(LuminaExpressiveMotion.effectsFast()) + scaleIn(
                animationSpec = LuminaExpressiveMotion.spatialFast(),
                initialScale = 0.94f
            ),
            exit = fadeOut(LuminaExpressiveMotion.effectsFast()) + scaleOut(
                animationSpec = LuminaExpressiveMotion.effectsFast(),
                targetScale = 0.96f
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .luminaHorizontalSafeInsets()
                .padding(start = 16.dp, end = 16.dp, bottom = bottomPopoverClearance)
        ) {
            AudioAndSubtitlePopover(
                playerState = playerState,
                prefs = prefs,
                viewModel = viewModel,
                onLoadExternalSubtitle = { subtitlePickerLauncher.launch(arrayOf("*/*")) },
                onDismiss = { activeSheet = ActivePlayerSheet.NONE }
            )
        }

        AnimatedVisibility(
            visible = activeSheet == ActivePlayerSheet.EQUALIZER && popoversAllowed,
            enter = fadeIn(LuminaExpressiveMotion.effectsFast()) + scaleIn(
                animationSpec = LuminaExpressiveMotion.spatialFast(),
                initialScale = 0.94f
            ),
            exit = fadeOut(LuminaExpressiveMotion.effectsFast()) + scaleOut(
                animationSpec = LuminaExpressiveMotion.effectsFast(),
                targetScale = 0.96f
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .luminaHorizontalSafeInsets()
                .padding(start = 16.dp, end = 16.dp, bottom = bottomPopoverClearance)
        ) {
            TenBandEqualizerPopover(
                playerState = playerState,
                viewModel = viewModel,
                onDismiss = { activeSheet = ActivePlayerSheet.NONE }
            )
        }

        AnimatedVisibility(
            visible = activeSheet == ActivePlayerSheet.SPEED_AND_LOOP && popoversAllowed,
            enter = fadeIn(LuminaExpressiveMotion.effectsFast()) + scaleIn(
                animationSpec = LuminaExpressiveMotion.spatialFast(),
                initialScale = 0.94f
            ),
            exit = fadeOut(LuminaExpressiveMotion.effectsFast()) + scaleOut(
                animationSpec = LuminaExpressiveMotion.effectsFast(),
                targetScale = 0.96f
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .luminaHorizontalSafeInsets()
                .padding(start = 16.dp, end = 16.dp, bottom = bottomPopoverClearance)
        ) {
            SpeedAndABRepeatPopover(
                playerState = playerState,
                viewModel = viewModel,
                onDismiss = { activeSheet = ActivePlayerSheet.NONE }
            )
        }

        AnimatedVisibility(
            visible = activeSheet == ActivePlayerSheet.SLEEP_TIMER && popoversAllowed,
            enter = fadeIn(LuminaExpressiveMotion.effectsFast()) + scaleIn(
                animationSpec = LuminaExpressiveMotion.spatialFast(),
                initialScale = 0.94f
            ),
            exit = fadeOut(LuminaExpressiveMotion.effectsFast()) + scaleOut(
                animationSpec = LuminaExpressiveMotion.effectsFast(),
                targetScale = 0.96f
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .luminaHorizontalSafeInsets()
                .padding(start = 16.dp, end = 16.dp, bottom = bottomPopoverClearance)
        ) {
            SleepTimerPopover(
                playerState = playerState,
                viewModel = viewModel,
                onDismiss = { activeSheet = ActivePlayerSheet.NONE }
            )
        }

        AnimatedVisibility(
            visible = activeSheet == ActivePlayerSheet.CHAPTERS && popoversAllowed,
            enter = fadeIn(LuminaExpressiveMotion.effectsFast()) + scaleIn(
                animationSpec = LuminaExpressiveMotion.spatialFast(),
                initialScale = 0.94f
            ),
            exit = fadeOut(LuminaExpressiveMotion.effectsFast()) + scaleOut(
                animationSpec = LuminaExpressiveMotion.effectsFast(),
                targetScale = 0.96f
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .luminaHorizontalSafeInsets()
                .padding(start = 16.dp, end = 16.dp, bottom = bottomPopoverClearance)
        ) {
            ChaptersPopover(
                playerState = playerState,
                viewModel = viewModel,
                onDismiss = { activeSheet = ActivePlayerSheet.NONE }
            )
        }

        AnimatedVisibility(
            visible = activeSheet == ActivePlayerSheet.BOOKMARKS && popoversAllowed,
            enter = fadeIn(LuminaExpressiveMotion.effectsFast()) + scaleIn(
                animationSpec = LuminaExpressiveMotion.spatialFast(),
                initialScale = 0.94f
            ),
            exit = fadeOut(LuminaExpressiveMotion.effectsFast()) + scaleOut(
                animationSpec = LuminaExpressiveMotion.effectsFast(),
                targetScale = 0.96f
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .luminaHorizontalSafeInsets()
                .padding(start = 16.dp, end = 16.dp, bottom = bottomPopoverClearance)
        ) {
            BookmarksPopover(
                bookmarks = bookmarks,
                currentTimeMs = playerState.currentTimeMs,
                viewModel = viewModel,
                onDismiss = { activeSheet = ActivePlayerSheet.NONE }
            )
        }

        AnimatedVisibility(
            visible = activeSheet == ActivePlayerSheet.QUEUE && popoversAllowed,
            enter = fadeIn(LuminaExpressiveMotion.effectsFast()) + scaleIn(
                animationSpec = LuminaExpressiveMotion.spatialFast(),
                initialScale = 0.94f
            ),
            exit = fadeOut(LuminaExpressiveMotion.effectsFast()) + scaleOut(
                animationSpec = LuminaExpressiveMotion.effectsFast(),
                targetScale = 0.96f
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .luminaHorizontalSafeInsets()
                .padding(start = 16.dp, end = 16.dp, bottom = bottomPopoverClearance)
        ) {
            PlaybackQueuePopover(
                playerState = playerState,
                viewModel = viewModel,
                onSaveAsPlaylist = {
                    activeSheet = ActivePlayerSheet.NONE
                    showSaveQueueDialog = true
                },
                onDismiss = { activeSheet = ActivePlayerSheet.NONE }
            )
        }

        AnimatedVisibility(
            visible = activeSheet == ActivePlayerSheet.HDR_INSPECTOR && popoversAllowed,
            enter = fadeIn(LuminaExpressiveMotion.effectsFast()) + scaleIn(
                animationSpec = LuminaExpressiveMotion.spatialFast(),
                initialScale = 0.94f
            ),
            exit = fadeOut(LuminaExpressiveMotion.effectsFast()) + scaleOut(
                animationSpec = LuminaExpressiveMotion.effectsFast(),
                targetScale = 0.96f
            ),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .luminaTopWindowInsets()
                .luminaHorizontalSafeInsets()
                .padding(
                    top = if (isCompactPortraitHud) 122.dp else 74.dp,
                    start = 16.dp,
                    end = 16.dp
                )
        ) {
            HdrPipelineInspectorPopover(
                playerState = playerState,
                hdrPassthroughEnabled = prefs.hdrPassthroughEnabled,
                onToggleHdrPassthrough = viewModel::setHdrPassthrough,
                onDismiss = { activeSheet = ActivePlayerSheet.NONE }
            )
        }

        // Cross-App Googlebook OS Drag-and-Drop Visual Target
        LuminaDragDropTargetOverlay(
            visible = isDragHovering,
            title = stringResource(R.string.drop_overlay_title),
            subtitle = stringResource(R.string.drop_overlay_subtitle)
        )
    }

    if (showSaveQueueDialog) {
        SaveQueueAsPlaylistDialog(
            defaultName = playerState.currentMedia?.title ?: "Lumina Queue",
            onDismiss = { showSaveQueueDialog = false },
            onConfirm = { name ->
                showSaveQueueDialog = false
                viewModel.saveCurrentQueueAsPlaylist(name) {
                    showTransientStatus(GestureOverlayKind.STATUS_TOAST, playlistSavedToastMsg)
                }
            }
        )
    }
}

// ============================================================================
// 1. Standard Phone & Googlebook OS Desktop Full Cinema Layout
// ============================================================================

@Composable
private fun StandardOrDesktopCinemaPlayerLayout(
    adaptiveEnv: LuminaAdaptiveEnvironment,
    playerState: LuminaPlayerState,
    bookmarks: List<VideoBookmarkEntity>,
    scrubPreviewBitmap: Bitmap?,
    viewModel: LuminaPlayerViewModel,
    hudVisible: Boolean,
    isFullscreen: Boolean,
    orientationMode: PlayerOrientationMode = PlayerOrientationMode.AUTO,
    onCycleOrientation: () -> Unit = {},
    allowGooglebookMini: Boolean,
    canAdmDownloadCurrent: Boolean = false,
    onAdmDownloadStream: () -> Unit = {},
    seekStepMs: Long,
    zoomScale: Float,
    panOffsetX: Float,
    panOffsetY: Float,
    onZoomAndPanChange: (Float, Float, Float) -> Unit,
    onResetZoom: () -> Unit,
    windowBrightness: Float,
    onBrightnessChange: (Float) -> Unit,
    onToggleHud: () -> Unit,
    onWakeHud: () -> Unit,
    onGestureFeedback: (GestureOverlayKind, String) -> Unit,
    onRightClickMenu: (DpOffset) -> Unit,
    onOpenSheet: (ActivePlayerSheet) -> Unit,
    onOpenLocalVideo: () -> Unit,
    onMinimizeToBackgroundAudio: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onEnterPip: () -> Unit,
    onToggleGooglebookMini: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val isDesktop = adaptiveEnv.layoutMode == LuminaLayoutMode.GOOGLEBOOK_DESKTOP
    val isWideHud = isDesktop || adaptiveEnv.screenWidthDp >= 580 || adaptiveEnv.isLandscape
    val latestPlayerState by rememberUpdatedState(playerState)
    val latestZoomScale by rememberUpdatedState(zoomScale)
    val latestOnZoomAndPanChange by rememberUpdatedState(onZoomAndPanChange)
    val latestBrightness by rememberUpdatedState(windowBrightness)
    val latestOnBrightnessChange by rememberUpdatedState(onBrightnessChange)
    val latestOnGestureFeedback by rememberUpdatedState(onGestureFeedback)
    val latestOnWakeHud by rememberUpdatedState(onWakeHud)
    val latestOnToggleHud by rememberUpdatedState(onToggleHud)
    val latestOnToggleFullscreen by rememberUpdatedState(onToggleFullscreen)
    val latestOnRightClickMenu by rememberUpdatedState(onRightClickMenu)

    var scrubAnchorMs by remember { mutableLongStateOf(0L) }
    var lastScrubTargetMs by remember { mutableLongStateOf(0L) }
    var brightnessAnchor by remember { mutableFloatStateOf(0.5f) }
    var volumeAnchor by remember { mutableFloatStateOf(100f) }
    var accumulatedDragX by remember { mutableFloatStateOf(0f) }
    var accumulatedDragY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Desktop mouse hover wake, Ctrl+Wheel zoom, scroll-wheel volume & right-click menu
            .pointerInput(isDesktop, playerState.isTouchLocked) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (latestPlayerState.isTouchLocked) {
                            if (event.type == PointerEventType.Move && isDesktop) latestOnWakeHud()
                            continue
                        }
                        when (event.type) {
                            PointerEventType.Move -> {
                                if (isDesktop) latestOnWakeHud()
                            }
                            PointerEventType.Scroll -> {
                                val scrollDelta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                if (abs(scrollDelta) > 0.05f) {
                                    if (event.keyboardModifiers.isCtrlPressed) {
                                        val step = if (scrollDelta < 0f) 0.15f else -0.15f
                                        val nextZoom = clampVideoZoomScale(latestZoomScale + step)
                                        latestOnZoomAndPanChange(nextZoom, 0f, 0f)
                                        latestOnGestureFeedback(
                                            GestureOverlayKind.ZOOM,
                                            String.format(Locale.US, "%.1f×", nextZoom)
                                        )
                                    } else {
                                        val volStep = if (scrollDelta < 0f) 4 else -4
                                        val targetVol = (latestPlayerState.volume + volStep).coerceIn(0, 150)
                                        viewModel.setVolume(targetVol)
                                        latestOnGestureFeedback(
                                            GestureOverlayKind.VOLUME,
                                            "$targetVol%"
                                        )
                                    }
                                }
                            }
                            PointerEventType.Press -> {
                                if (event.buttons.isSecondaryPressed) {
                                    val pos = event.changes.firstOrNull()?.position ?: Offset.Zero
                                    event.changes.forEach { it.consume() }
                                    latestOnRightClickMenu(DpOffset(pos.x.toDp(), pos.y.toDp()))
                                }
                            }
                        }
                    }
                }
            }
            // Touch single-tap HUD, double-tap left/right seek, and desktop center double-click fullscreen
            .pointerInput(isDesktop, seekStepMs, playerState.isTouchLocked) {
                detectTapGestures(
                    onTap = { latestOnToggleHud() },
                    onDoubleTap = { tapOffset ->
                        if (latestPlayerState.isTouchLocked) {
                            latestOnWakeHud()
                            return@detectTapGestures
                        }
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        when {
                            tapOffset.x < width * 0.36f -> {
                                viewModel.seekRelative(-seekStepMs)
                                latestOnGestureFeedback(GestureOverlayKind.SCRUB, "-${seekStepMs / 1000}s")
                            }
                            tapOffset.x > width * 0.64f -> {
                                viewModel.seekRelative(seekStepMs)
                                latestOnGestureFeedback(GestureOverlayKind.SCRUB, "+${seekStepMs / 1000}s")
                            }
                            isDesktop -> latestOnToggleFullscreen()
                            else -> viewModel.togglePlayPause()
                        }
                    }
                )
            }
            // Two-finger pinch-to-zoom & pan across touchscreens and multi-touch Googlebook trackpads
            .pointerInput(isDesktop, playerState.isTouchLocked) {
                if (playerState.isTouchLocked) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var multiTouchActive = false
                    do {
                        val event = awaitPointerEvent()
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount >= 2) {
                            multiTouchActive = true
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (abs(zoomChange - 1f) > 0.004f || panChange.getDistance() > 0.5f) {
                                val nextScale = clampVideoZoomScale(latestZoomScale * zoomChange)
                                latestOnZoomAndPanChange(nextScale, panChange.x, panChange.y)
                                if (abs(zoomChange - 1f) > 0.01f) {
                                    latestOnGestureFeedback(
                                        GestureOverlayKind.ZOOM,
                                        String.format(Locale.US, "%.1f×", nextScale)
                                    )
                                }
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (multiTouchActive) {
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            // 1-finger pan when zoomed in, or Touch swipe gestures (Brightness / Volume / Scrub) at 1.0x
            .pointerInput(isDesktop, playerState.isTouchLocked) {
                if (playerState.isTouchLocked) return@pointerInput
                var gestureMode = 0 // 0 = undecided, 1 = scrub, 2 = brightness, 3 = volume, 4 = pan zoomed video
                detectDragGestures(
                    onDragStart = { startOffset ->
                        if (latestZoomScale > 1.01f) {
                            gestureMode = 4
                            return@detectDragGestures
                        }
                        if (isDesktop) {
                            gestureMode = 0
                            return@detectDragGestures
                        }
                        gestureMode = 0
                        scrubAnchorMs = latestPlayerState.currentTimeMs
                        lastScrubTargetMs = scrubAnchorMs
                        brightnessAnchor = latestBrightness
                        volumeAnchor = latestPlayerState.volume.toFloat()
                        accumulatedDragX = 0f
                        accumulatedDragY = 0f
                        val isLeftSide = startOffset.x < size.width * 0.5f
                        gestureMode = if (isLeftSide) -2 else -3
                    },
                    onDragEnd = {
                        if (gestureMode == 1) {
                            viewModel.seekTo(lastScrubTargetMs, fast = false)
                        }
                        gestureMode = 0
                    },
                    onDragCancel = {
                        gestureMode = 0
                    },
                    onDrag = { change, dragAmount ->
                        if (gestureMode == 4) {
                            change.consume()
                            latestOnZoomAndPanChange(latestZoomScale, dragAmount.x, dragAmount.y)
                            return@detectDragGestures
                        }
                        if (isDesktop || gestureMode == 0) return@detectDragGestures
                        change.consume()
                        val absX = abs(dragAmount.x)
                        val absY = abs(dragAmount.y)
                        if (gestureMode < 0) {
                            gestureMode = if (absX > absY * 1.35f) {
                                1
                            } else if (gestureMode == -2) {
                                2
                            } else {
                                3
                            }
                        }

                        when (gestureMode) {
                            1 -> {
                                accumulatedDragX += dragAmount.x
                                val liveDuration = latestPlayerState.durationMs
                                val scrubSpan = liveDuration.coerceAtLeast(60_000L).coerceAtMost(180_000L)
                                val deltaMs = ((accumulatedDragX / size.width.coerceAtLeast(1)) * scrubSpan).toLong()
                                val target = if (liveDuration > 0L) {
                                    (scrubAnchorMs + deltaMs).coerceIn(0L, liveDuration)
                                } else {
                                    (scrubAnchorMs + deltaMs).coerceAtLeast(0L)
                                }
                                lastScrubTargetMs = target
                                viewModel.seekTo(target, fast = true)
                                val sign = if (deltaMs >= 0) "+" else "-"
                                latestOnGestureFeedback(
                                    GestureOverlayKind.SCRUB,
                                    "${formatDurationMs(target)} ($sign${abs(deltaMs) / 1000}s)"
                                )
                            }
                            2 -> {
                                accumulatedDragY += dragAmount.y
                                val delta = -accumulatedDragY / size.height.coerceAtLeast(1).toFloat()
                                val updated = (brightnessAnchor + delta * 1.25f).coerceIn(0.02f, 1.0f)
                                latestOnBrightnessChange(updated)
                                latestOnGestureFeedback(
                                    GestureOverlayKind.BRIGHTNESS,
                                    "${(updated * 100).roundToInt()}%"
                                )
                            }
                            3 -> {
                                accumulatedDragY += dragAmount.y
                                val deltaRatio = -accumulatedDragY / size.height.coerceAtLeast(1).toFloat()
                                val updatedVol = (volumeAnchor + deltaRatio * 150f).roundToInt().coerceIn(0, 150)
                                if (updatedVol != latestPlayerState.volume) {
                                    viewModel.setVolume(updatedVol)
                                }
                                latestOnGestureFeedback(
                                    GestureOverlayKind.VOLUME,
                                    "$updatedVol%"
                                )
                            }
                        }
                    }
                )
            }
    ) {
        // Hardware HDR SurfaceView with Interactive Zoom & Pan
        LuminaVideoSurface(
            playerEngine = viewModel.playerEngine,
            isPlaying = playerState.isPlaying,
            zoomScale = zoomScale,
            panOffsetX = panOffsetX,
            panOffsetY = panOffsetY,
            modifier = Modifier.fillMaxSize()
        )

        // High-Contrast Smoked-Crystal Cinema HUD Overlay (suppressed while Touch Lock is active)
        AnimatedVisibility(
            visible = hudVisible && !playerState.isTouchLocked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to LuminaOledBlack.copy(alpha = 0.86f),
                                0.26f to Color.Transparent,
                                0.68f to Color.Transparent,
                                1.0f to LuminaOledBlack.copy(alpha = 0.92f)
                            )
                        )
                    )
            ) {
                // Top Glass Caption / Header Bar
                LuminaGlassSurface(
                    shape = RoundedCornerShape(20.dp),
                    strongOpacity = true,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .luminaTopWindowInsets()
                        .luminaHorizontalSafeInsets()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .fillMaxWidth()
                ) {
                    val metaLine = buildList {
                        if (playerState.videoWidth > 0 && playerState.videoHeight > 0) {
                            add("${playerState.videoWidth}×${playerState.videoHeight}")
                        }
                        playerState.videoCodecLabel?.let { add(it) }
                        if (playerState.frameRate > 0f) {
                            val fps = playerState.frameRate
                            val fpsText = if (abs(fps - fps.roundToInt()) < 0.03f) {
                                "${fps.roundToInt()} fps"
                            } else {
                                String.format(Locale.US, "%.2f fps", fps)
                            }
                            add(fpsText)
                        }
                    }.joinToString(" • ")

                    val orientationButtonIcon = when (orientationMode) {
                        PlayerOrientationMode.AUTO -> Icons.Outlined.ScreenRotation
                        PlayerOrientationMode.LANDSCAPE -> Icons.Outlined.StayCurrentLandscape
                        PlayerOrientationMode.PORTRAIT -> Icons.Outlined.StayCurrentPortrait
                    }

                    if (isWideHud) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            LuminaGlassIconButton(
                                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.player_back_to_library),
                                onClick = onNavigateBack
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = playerState.currentMedia?.title ?: stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    color = LuminaTextPrimary
                                )
                                if (metaLine.isNotEmpty()) {
                                    Text(
                                        text = metaLine,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        color = LuminaTextSecondary
                                    )
                                }
                            }

                            if (zoomScale > 1.01f) {
                                LuminaGlassPillButton(
                                    label = stringResource(
                                        R.string.player_reset_zoom,
                                        String.format(Locale.US, "%.1f×", zoomScale)
                                    ),
                                    leadingIcon = Icons.Outlined.ZoomIn,
                                    accentColor = LuminaCyan,
                                    isActive = true,
                                    onClick = onResetZoom
                                )
                            }

                            HdrBadgeChip(
                                badgeText = playerState.hdrStatus.sourceFormat.displayBadge,
                                modifier = Modifier.clickable {
                                    onOpenSheet(ActivePlayerSheet.HDR_INSPECTOR)
                                }
                            )

                            if (canAdmDownloadCurrent) {
                                LuminaGlassIconButton(
                                    icon = Icons.Outlined.ArrowDownward,
                                    contentDescription = stringResource(R.string.adm_download_stream_action),
                                    tint = LuminaCyan,
                                    onClick = onAdmDownloadStream
                                )
                            }

                            LuminaGlassIconButton(
                                icon = Icons.Outlined.Headphones,
                                contentDescription = stringResource(R.string.player_background_audio),
                                onClick = onMinimizeToBackgroundAudio
                            )

                            LuminaGlassIconButton(
                                icon = Icons.Outlined.LockOpen,
                                contentDescription = stringResource(R.string.player_touch_lock),
                                onClick = {
                                    viewModel.setTouchLocked(true)
                                    onWakeHud()
                                }
                            )

                            LuminaGlassIconButton(
                                icon = Icons.Outlined.FolderOpen,
                                contentDescription = stringResource(R.string.player_open_local_file),
                                onClick = onOpenLocalVideo
                            )

                            if (isDesktop && allowGooglebookMini) {
                                LuminaGlassIconButton(
                                    icon = Icons.Outlined.OpenInNew,
                                    contentDescription = stringResource(R.string.player_mini_player),
                                    onClick = onToggleGooglebookMini
                                )
                            }

                            LuminaGlassIconButton(
                                icon = Icons.Outlined.PictureInPictureAlt,
                                contentDescription = stringResource(R.string.player_pip),
                                onClick = onEnterPip
                            )

                            if (!isDesktop) {
                                LuminaGlassIconButton(
                                    icon = orientationButtonIcon,
                                    contentDescription = stringResource(R.string.player_rotate_screen),
                                    isActive = orientationMode != PlayerOrientationMode.AUTO,
                                    tint = if (orientationMode != PlayerOrientationMode.AUTO) LuminaCyan else LuminaTextPrimary,
                                    onClick = onCycleOrientation
                                )
                            }

                            LuminaGlassIconButton(
                                icon = if (isFullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                                contentDescription = stringResource(R.string.player_fullscreen),
                                isActive = isFullscreen,
                                onClick = onToggleFullscreen
                            )
                        }
                    } else {
                        // Compact Portrait Android Phone Header:
                        // Row 1 gives Title & concise stream telemetry full uncrushed width;
                        // Row 2 hosts compact 38dp quick-action buttons cleanly.
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                LuminaGlassIconButton(
                                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = stringResource(R.string.player_back_to_library),
                                    size = 38.dp,
                                    iconSize = 19.dp,
                                    onClick = onNavigateBack
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = playerState.currentMedia?.title ?: stringResource(R.string.app_name),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        color = LuminaTextPrimary
                                    )
                                    if (metaLine.isNotEmpty()) {
                                        Text(
                                            text = metaLine,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis,
                                            color = LuminaTextSecondary
                                        )
                                    }
                                }

                                HdrBadgeChip(
                                    badgeText = playerState.hdrStatus.sourceFormat.displayBadge,
                                    modifier = Modifier.clickable {
                                        onOpenSheet(ActivePlayerSheet.HDR_INSPECTOR)
                                    }
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                            ) {
                                if (zoomScale > 1.01f) {
                                    LuminaGlassPillButton(
                                        label = stringResource(
                                            R.string.player_reset_zoom,
                                            String.format(Locale.US, "%.1f×", zoomScale)
                                        ),
                                        leadingIcon = Icons.Outlined.ZoomIn,
                                        accentColor = LuminaCyan,
                                        isActive = true,
                                        onClick = onResetZoom
                                    )
                                }

                                if (!isDesktop) {
                                    LuminaGlassIconButton(
                                        icon = orientationButtonIcon,
                                        contentDescription = stringResource(R.string.player_rotate_screen),
                                        size = 38.dp,
                                        iconSize = 19.dp,
                                        isActive = orientationMode != PlayerOrientationMode.AUTO,
                                        tint = if (orientationMode != PlayerOrientationMode.AUTO) LuminaCyan else LuminaTextPrimary,
                                        onClick = onCycleOrientation
                                    )
                                }

                                LuminaGlassIconButton(
                                    icon = if (isFullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                                    contentDescription = stringResource(R.string.player_fullscreen),
                                    size = 38.dp,
                                    iconSize = 19.dp,
                                    isActive = isFullscreen,
                                    onClick = onToggleFullscreen
                                )

                                LuminaGlassIconButton(
                                    icon = Icons.Outlined.PictureInPictureAlt,
                                    contentDescription = stringResource(R.string.player_pip),
                                    size = 38.dp,
                                    iconSize = 19.dp,
                                    onClick = onEnterPip
                                )

                                LuminaGlassIconButton(
                                    icon = Icons.Outlined.LockOpen,
                                    contentDescription = stringResource(R.string.player_touch_lock),
                                    size = 38.dp,
                                    iconSize = 19.dp,
                                    onClick = {
                                        viewModel.setTouchLocked(true)
                                        onWakeHud()
                                    }
                                )

                                LuminaGlassIconButton(
                                    icon = Icons.Outlined.Headphones,
                                    contentDescription = stringResource(R.string.player_background_audio),
                                    size = 38.dp,
                                    iconSize = 19.dp,
                                    onClick = onMinimizeToBackgroundAudio
                                )

                                LuminaGlassIconButton(
                                    icon = Icons.Outlined.FolderOpen,
                                    contentDescription = stringResource(R.string.player_open_local_file),
                                    size = 38.dp,
                                    iconSize = 19.dp,
                                    onClick = onOpenLocalVideo
                                )

                                if (canAdmDownloadCurrent) {
                                    LuminaGlassIconButton(
                                        icon = Icons.Outlined.ArrowDownward,
                                        contentDescription = stringResource(R.string.adm_download_stream_action),
                                        size = 38.dp,
                                        iconSize = 19.dp,
                                        tint = LuminaCyan,
                                        onClick = onAdmDownloadStream
                                    )
                                }
                            }
                        }
                    }
                }

                // Bottom Glassmorphic Scrubber & Transport Controls Deck
                LuminaGlassSurface(
                    shape = RoundedCornerShape(24.dp),
                    strongOpacity = true,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .luminaHorizontalSafeInsets()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .fillMaxWidth()
                ) {
                    PlayerBottomControlsContent(
                        playerState = playerState,
                        bookmarks = bookmarks,
                        scrubPreviewBitmap = scrubPreviewBitmap,
                        isDesktop = isDesktop,
                        isWideHud = isWideHud,
                        showOrientationControl = !isDesktop,
                        orientationMode = orientationMode,
                        onCycleOrientation = onCycleOrientation,
                        canAdmDownloadCurrent = canAdmDownloadCurrent,
                        onAdmDownloadStream = onAdmDownloadStream,
                        onEnterPip = if (!isDesktop) onEnterPip else null,
                        onOpenLocalVideo = onOpenLocalVideo,
                        viewModel = viewModel,
                        onOpenSheet = onOpenSheet,
                        onMinimizeToBackgroundAudio = onMinimizeToBackgroundAudio
                    )
                }
            }
        }
    }
}

// ============================================================================
// 2. Foldable Tabletop Mode Layout (Horizontal Hinge Half-Opened)
// ============================================================================

@Composable
private fun FoldableTabletopPlayerLayout(
    adaptiveEnv: LuminaAdaptiveEnvironment,
    playerState: LuminaPlayerState,
    bookmarks: List<VideoBookmarkEntity>,
    scrubPreviewBitmap: Bitmap?,
    viewModel: LuminaPlayerViewModel,
    hudVisible: Boolean,
    isFullscreen: Boolean,
    canAdmDownloadCurrent: Boolean = false,
    onAdmDownloadStream: () -> Unit = {},
    onToggleHud: () -> Unit,
    onOpenSheet: (ActivePlayerSheet) -> Unit,
    onOpenLocalVideo: () -> Unit,
    onSaveQueueAsPlaylist: () -> Unit,
    onMinimizeToBackgroundAudio: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onNavigateBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LuminaOledBlack)
    ) {
        // TOP HALF (Above Physical Hinge): Pure, Unobstructed HDR Video Surface
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (adaptiveEnv.hingeTopDp > 80.dp) {
                        Modifier.height(adaptiveEnv.hingeTopDp)
                    } else {
                        Modifier.weight(1f)
                    }
                )
                .clickable(onClick = onToggleHud)
        ) {
            LuminaVideoSurface(
                playerEngine = viewModel.playerEngine,
                isPlaying = playerState.isPlaying,
                modifier = Modifier.fillMaxSize()
            )

            androidx.compose.animation.AnimatedVisibility(
                visible = hudVisible && !playerState.isTouchLocked,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .luminaTopWindowInsets()
                    .padding(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LuminaGlassIconButton(
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.player_back_to_library),
                        onClick = onNavigateBack
                    )
                    if (canAdmDownloadCurrent) {
                        LuminaGlassIconButton(
                            icon = Icons.Outlined.ArrowDownward,
                            contentDescription = stringResource(R.string.adm_download_stream_action),
                            tint = LuminaCyan,
                            onClick = onAdmDownloadStream
                        )
                    }
                    LuminaGlassIconButton(
                        icon = Icons.Outlined.Headphones,
                        contentDescription = stringResource(R.string.player_background_audio),
                        onClick = onMinimizeToBackgroundAudio
                    )
                    LuminaGlassIconButton(
                        icon = Icons.Outlined.LockOpen,
                        contentDescription = stringResource(R.string.player_touch_lock),
                        onClick = { viewModel.setTouchLocked(true) }
                    )
                    LuminaGlassIconButton(
                        icon = Icons.Outlined.FolderOpen,
                        contentDescription = stringResource(R.string.player_open_local_file),
                        onClick = onOpenLocalVideo
                    )
                    LuminaGlassIconButton(
                        icon = if (isFullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                        contentDescription = stringResource(R.string.player_fullscreen),
                        isActive = isFullscreen,
                        onClick = onToggleFullscreen
                    )
                    HdrBadgeChip(
                        badgeText = playerState.hdrStatus.sourceFormat.displayBadge,
                        modifier = Modifier.clickable {
                            onOpenSheet(ActivePlayerSheet.HDR_INSPECTOR)
                        }
                    )
                }
            }
        }

        // HINGE CREASE SEPARATOR
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(adaptiveEnv.hingeHeightDp.coerceAtLeast(4.dp))
                .background(LuminaOledBlack)
        )

        // BOTTOM HALF (Below Physical Hinge): Glassmorphic Control Deck & Live Queue
        LuminaGlassSurface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            strongOpacity = true,
            accentGlow = LuminaPlatinum,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .navigationBarsPadding()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = playerState.currentMedia?.title ?: stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = LuminaTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(R.string.player_tabletop_mode_active),
                        style = MaterialTheme.typography.labelSmall,
                        color = LuminaCyan
                    )
                }

                PlayerBottomControlsContent(
                    playerState = playerState,
                    bookmarks = bookmarks,
                    scrubPreviewBitmap = scrubPreviewBitmap,
                    isDesktop = false,
                    isWideHud = true,
                    viewModel = viewModel,
                    onOpenSheet = onOpenSheet,
                    onMinimizeToBackgroundAudio = onMinimizeToBackgroundAudio
                )

                // Interactive Playlist Queue on Tabletop Bottom Half
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.player_queue_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = LuminaPlatinum
                    )
                    if (playerState.queue.isNotEmpty()) {
                        LuminaGlassPillButton(
                            label = stringResource(R.string.playlists_save_queue),
                            leadingIcon = Icons.Outlined.SaveAlt,
                            accentColor = LuminaCyan,
                            onClick = onSaveQueueAsPlaylist
                        )
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    itemsIndexed(playerState.queue) { index, item ->
                        val isCurrent = index == playerState.currentQueueIndex
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isCurrent) Color(0xF01B2A40) else Color(0xD4141C2B)
                                )
                                .border(
                                    1.dp,
                                    if (isCurrent) LuminaCyan.copy(alpha = 0.68f) else LuminaGlassBorder,
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { viewModel.playQueueIndex(index) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = if (isCurrent && playerState.isPlaying) {
                                    Icons.Outlined.Equalizer
                                } else {
                                    Icons.Outlined.PlayArrow
                                },
                                contentDescription = null,
                                tint = if (isCurrent) LuminaPlatinum else LuminaTextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isCurrent) LuminaPlatinum else LuminaTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (item.durationMs > 0L) {
                                Text(
                                    text = formatDurationMs(item.durationMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LuminaTextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 3. Googlebook OS Draggable Floating Always-on-Top Mini-Player
// ============================================================================

@Composable
private fun GooglebookFloatingMiniPlayer(
    playerState: LuminaPlayerState,
    viewModel: LuminaPlayerViewModel,
    onExpandToFullPlayer: () -> Unit,
    onClosePlayer: () -> Unit
) {
    var offsetX by remember { mutableFloatStateOf(24f) }
    var offsetY by remember { mutableFloatStateOf(24f) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
        LuminaGlassSurface(
            shape = RoundedCornerShape(20.dp),
            strongOpacity = true,
            accentGlow = LuminaCyan,
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .width(380.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                }
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                ) {
                    LuminaVideoSurface(
                        playerEngine = viewModel.playerEngine,
                        isPlaying = playerState.isPlaying,
                        modifier = Modifier.fillMaxSize()
                    )
                    HdrBadgeChip(
                        badgeText = playerState.hdrStatus.sourceFormat.displayBadge,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = playerState.currentMedia?.title ?: stringResource(R.string.app_name),
                        style = MaterialTheme.typography.labelLarge,
                        color = LuminaTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LuminaGlassIconButton(
                            icon = if (playerState.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = stringResource(R.string.player_play_pause),
                            size = 34.dp,
                            iconSize = 18.dp,
                            isActive = true,
                            morphWhenActive = playerState.isPlaying,
                            onClick = viewModel::togglePlayPause
                        )
                        LuminaGlassIconButton(
                            icon = Icons.Outlined.Fullscreen,
                            contentDescription = stringResource(R.string.player_fullscreen),
                            size = 34.dp,
                            iconSize = 18.dp,
                            onClick = onExpandToFullPlayer
                        )
                        LuminaGlassIconButton(
                            icon = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_close),
                            size = 34.dp,
                            iconSize = 18.dp,
                            onClick = onClosePlayer
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// Shared Glass Scrubber, Scrub Frame Preview Bubble, Transport & Feature Deck
// ============================================================================

@Composable
private fun PlayerBottomControlsContent(
    playerState: LuminaPlayerState,
    bookmarks: List<VideoBookmarkEntity>,
    scrubPreviewBitmap: Bitmap?,
    isDesktop: Boolean,
    isWideHud: Boolean = isDesktop,
    showOrientationControl: Boolean = false,
    orientationMode: PlayerOrientationMode = PlayerOrientationMode.AUTO,
    onCycleOrientation: () -> Unit = {},
    canAdmDownloadCurrent: Boolean = false,
    onAdmDownloadStream: (() -> Unit)? = null,
    onEnterPip: (() -> Unit)? = null,
    onOpenLocalVideo: (() -> Unit)? = null,
    viewModel: LuminaPlayerViewModel,
    onOpenSheet: (ActivePlayerSheet) -> Unit,
    onMinimizeToBackgroundAudio: () -> Unit = {}
) {
    var userDraggingSlider by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var hoverFraction by remember { mutableStateOf<Float?>(null) }
    var sliderWidthPx by remember { mutableIntStateOf(0) }
    val latestDurationMs by rememberUpdatedState(playerState.durationMs)
    val latestDragFraction by rememberUpdatedState(dragFraction)
    val density = LocalDensity.current

    LaunchedEffect(playerState.currentMedia?.uri) {
        userDraggingSlider = false
        hoverFraction = null
        viewModel.clearScrubPreview()
    }

    val activePreviewFraction: Float? = if (userDraggingSlider) dragFraction else hoverFraction
    val previewTimeMs: Long? = if (activePreviewFraction != null && playerState.durationMs > 0L) {
        (activePreviewFraction.coerceIn(0f, 1f) * playerState.durationMs).toLong()
    } else {
        null
    }

    LaunchedEffect(previewTimeMs, playerState.currentMedia?.uri) {
        if (previewTimeMs != null && playerState.durationMs > 0L) {
            viewModel.requestScrubPreview(previewTimeMs)
        } else {
            viewModel.clearScrubPreview()
        }
    }

    val displayedProgress = if (userDraggingSlider) dragFraction else playerState.progress
    val displayedTimeMs = if (userDraggingSlider && latestDurationMs > 0L) {
        (dragFraction.coerceIn(0f, 1f) * latestDurationMs).toLong()
    } else {
        playerState.currentTimeMs
    }

    val previewAnnotation = remember(previewTimeMs, bookmarks, playerState.chapters) {
        val target = previewTimeMs ?: return@remember null
        val nearbyBookmark = bookmarks.minByOrNull { abs(it.positionMs - target) }
            ?.takeIf { abs(it.positionMs - target) <= 4_500L }
        if (nearbyBookmark != null) {
            nearbyBookmark.label
        } else {
            playerState.chapters.lastOrNull { it.timeOffsetMs <= target }?.title
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Live 16:9 Scrub / Hover Frame Preview Bubble
        AnimatedVisibility(
            visible = activePreviewFraction != null && previewTimeMs != null && playerState.durationMs > 0L,
            enter = fadeIn(LuminaExpressiveMotion.effectsFast()) + scaleIn(
                animationSpec = LuminaExpressiveMotion.spatialFast(),
                initialScale = 0.92f
            ),
            exit = fadeOut(LuminaExpressiveMotion.effectsFast())
        ) {
            val bubbleWidthDp = 164.dp
            val bubbleWidthPx = with(density) { bubbleWidthDp.roundToPx() }
            val fraction = (activePreviewFraction ?: 0f).coerceIn(0f, 1f)
            val xOffsetPx = if (sliderWidthPx > bubbleWidthPx) {
                ((fraction * sliderWidthPx) - bubbleWidthPx / 2f)
                    .roundToInt()
                    .coerceIn(0, (sliderWidthPx - bubbleWidthPx).coerceAtLeast(0))
            } else {
                0
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 44.dp)
            ) {
                val bubbleShape = RoundedCornerShape(14.dp)
                Column(
                    modifier = Modifier
                        .offset { IntOffset(xOffsetPx, 0) }
                        .width(bubbleWidthDp)
                        .clip(bubbleShape)
                        .background(Color(0xE60B0F18))
                        .border(1.dp, LuminaCyan.copy(alpha = 0.45f), bubbleShape)
                ) {
                    if (scrubPreviewBitmap != null && !scrubPreviewBitmap.isRecycled) {
                        val imageBitmap = remember(scrubPreviewBitmap) { scrubPreviewBitmap.asImageBitmap() }
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = formatDurationMs(previewTimeMs ?: 0L),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = LuminaCyan
                        )
                        if (!previewAnnotation.isNullOrBlank()) {
                            Text(
                                text = previewAnnotation,
                                style = MaterialTheme.typography.labelSmall,
                                color = LuminaTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // Timeline Scrubber + Bookmark Tick Markers + Tabular Timecodes
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = formatDurationMs(displayedTimeMs),
                style = MaterialTheme.typography.labelLarge,
                color = LuminaPlatinum
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .onSizeChanged { sliderWidthPx = it.width }
                    .pointerInput(isDesktop) {
                        if (!isDesktop) return@pointerInput
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                when (event.type) {
                                    PointerEventType.Move, PointerEventType.Enter -> {
                                        val posX = event.changes.firstOrNull()?.position?.x
                                        if (posX != null && size.width > 0 && latestDurationMs > 0L) {
                                            hoverFraction = (posX / size.width.toFloat()).coerceIn(0f, 1f)
                                        }
                                    }
                                    PointerEventType.Exit -> {
                                        hoverFraction = null
                                    }
                                }
                            }
                        }
                    }
            ) {
                Slider(
                    value = displayedProgress.coerceIn(0f, 1f),
                    onValueChange = { fraction ->
                        val clampedFraction = fraction.coerceIn(0f, 1f)
                        userDraggingSlider = true
                        dragFraction = clampedFraction
                        val dur = latestDurationMs
                        if (dur > 0L) {
                            viewModel.seekTo((clampedFraction * dur).toLong(), fast = true)
                        }
                    },
                    onValueChangeFinished = {
                        val dur = latestDurationMs
                        if (dur > 0L) {
                            viewModel.seekTo((latestDragFraction.coerceIn(0f, 1f) * dur).toLong(), fast = false)
                        }
                        userDraggingSlider = false
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = LuminaPlatinum,
                        activeTrackColor = LuminaPlatinum,
                        inactiveTrackColor = Color.White.copy(alpha = 0.22f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                )

                if (bookmarks.isNotEmpty() && playerState.durationMs > 0L) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(26.dp)
                            .padding(horizontal = 10.dp)
                    ) {
                        val dur = playerState.durationMs.toFloat().coerceAtLeast(1f)
                        bookmarks.forEach { bm ->
                            val frac = (bm.positionMs.toFloat() / dur).coerceIn(0f, 1f)
                            val x = frac * size.width
                            drawCircle(
                                color = LuminaCyan,
                                radius = 3.5.dp.toPx(),
                                center = Offset(x, size.height / 2f)
                            )
                        }
                    }
                }
            }

            Text(
                text = formatDurationMs(playerState.durationMs),
                style = MaterialTheme.typography.labelLarge,
                color = LuminaTextSecondary
            )
        }

        val transportControls: @Composable () -> Unit = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LuminaGlassIconButton(
                    icon = Icons.Outlined.SkipPrevious,
                    contentDescription = stringResource(R.string.player_previous),
                    size = 40.dp,
                    onClick = { viewModel.playPrevious() }
                )
                LuminaGlassIconButton(
                    icon = Icons.Outlined.Replay10,
                    contentDescription = stringResource(R.string.player_seek_back),
                    size = 40.dp,
                    onClick = { viewModel.seekRelative(-10_000L) }
                )
                LuminaGlassIconButton(
                    icon = if (playerState.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(R.string.player_play_pause),
                    size = 52.dp,
                    iconSize = 28.dp,
                    isActive = true,
                    morphWhenActive = playerState.isPlaying,
                    onClick = viewModel::togglePlayPause
                )
                LuminaGlassIconButton(
                    icon = Icons.Outlined.Forward10,
                    contentDescription = stringResource(R.string.player_seek_forward),
                    size = 40.dp,
                    onClick = { viewModel.seekRelative(10_000L) }
                )
                LuminaGlassIconButton(
                    icon = Icons.Outlined.SkipNext,
                    contentDescription = stringResource(R.string.player_next),
                    size = 40.dp,
                    onClick = { viewModel.playNext() }
                )

                if (isDesktop) {
                    Spacer(Modifier.width(8.dp))
                    LuminaGlassIconButton(
                        icon = if (playerState.isMuted) {
                            Icons.AutoMirrored.Outlined.VolumeOff
                        } else {
                            Icons.AutoMirrored.Outlined.VolumeUp
                        },
                        contentDescription = stringResource(R.string.player_volume),
                        size = 38.dp,
                        onClick = viewModel::toggleMute
                    )
                    Slider(
                        value = playerState.volume / 150f,
                        onValueChange = { viewModel.setVolume((it * 150f).roundToInt()) },
                        colors = SliderDefaults.colors(
                            thumbColor = LuminaCyan,
                            activeTrackColor = LuminaCyan
                        ),
                        modifier = Modifier.width(110.dp)
                    )
                    Text(
                        text = "${playerState.volume}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = LuminaCyan
                    )
                }
            }
        }

        val featureActionPills: @Composable (Modifier) -> Unit = { rowModifier ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = rowModifier.horizontalScroll(rememberScrollState())
            ) {
                if (showOrientationControl) {
                    val orientationPillLabel = when (orientationMode) {
                        PlayerOrientationMode.AUTO -> stringResource(R.string.orientation_auto)
                        PlayerOrientationMode.LANDSCAPE -> stringResource(R.string.orientation_landscape)
                        PlayerOrientationMode.PORTRAIT -> stringResource(R.string.orientation_portrait)
                    }
                    val orientationPillIcon = when (orientationMode) {
                        PlayerOrientationMode.AUTO -> Icons.Outlined.ScreenRotation
                        PlayerOrientationMode.LANDSCAPE -> Icons.Outlined.StayCurrentLandscape
                        PlayerOrientationMode.PORTRAIT -> Icons.Outlined.StayCurrentPortrait
                    }
                    LuminaGlassPillButton(
                        label = orientationPillLabel,
                        leadingIcon = orientationPillIcon,
                        isActive = orientationMode != PlayerOrientationMode.AUTO,
                        accentColor = LuminaCyan,
                        onClick = onCycleOrientation
                    )
                }

                LuminaGlassPillButton(
                    label = playerState.aspectRatioMode.label,
                    leadingIcon = Icons.Outlined.AspectRatio,
                    onClick = { viewModel.cycleAspectRatio() }
                )

                LuminaGlassPillButton(
                    label = stringResource(R.string.player_audio_subtitles),
                    leadingIcon = Icons.Outlined.ClosedCaption,
                    isActive = playerState.subtitlesEnabled,
                    onClick = { onOpenSheet(ActivePlayerSheet.TRACKS_AND_SYNC) }
                )

                val bookmarksLabel = if (bookmarks.isNotEmpty()) {
                    "${stringResource(R.string.player_bookmarks)} (${bookmarks.size})"
                } else {
                    stringResource(R.string.player_bookmarks)
                }
                LuminaGlassPillButton(
                    label = bookmarksLabel,
                    leadingIcon = if (bookmarks.isNotEmpty()) Icons.Outlined.Bookmarks else Icons.Outlined.BookmarkBorder,
                    isActive = bookmarks.isNotEmpty(),
                    accentColor = LuminaCyan,
                    onClick = { onOpenSheet(ActivePlayerSheet.BOOKMARKS) }
                )

                if (playerState.queue.isNotEmpty()) {
                    LuminaGlassPillButton(
                        label = "${stringResource(R.string.player_queue_title)} (${playerState.queue.size})",
                        leadingIcon = Icons.Outlined.LibraryMusic,
                        isActive = playerState.queue.size > 1,
                        accentColor = LuminaPlatinum,
                        onClick = { onOpenSheet(ActivePlayerSheet.QUEUE) }
                    )
                }

                val chapterPillLabel = if (playerState.chapters.isNotEmpty() && playerState.currentChapterIndex >= 0) {
                    "${stringResource(R.string.player_chapters)} (${playerState.currentChapterIndex + 1}/${playerState.chapters.size})"
                } else {
                    stringResource(R.string.player_chapters)
                }
                LuminaGlassPillButton(
                    label = chapterPillLabel,
                    leadingIcon = Icons.Outlined.BookmarkBorder,
                    isActive = playerState.chapters.isNotEmpty(),
                    accentColor = LuminaPlatinum,
                    onClick = { onOpenSheet(ActivePlayerSheet.CHAPTERS) }
                )

                val sleepLabel = when {
                    !playerState.sleepTimer.isActive -> stringResource(R.string.player_sleep_timer)
                    playerState.sleepTimer.mode == SleepTimerMode.END_OF_MEDIA && playerState.sleepTimer.remainingMs <= 0L ->
                        stringResource(R.string.sleep_timer_end_short)
                    else -> formatDurationMs(playerState.sleepTimer.remainingMs)
                }
                LuminaGlassPillButton(
                    label = sleepLabel,
                    leadingIcon = Icons.Outlined.Bedtime,
                    isActive = playerState.sleepTimer.isActive,
                    accentColor = LuminaCyan,
                    onClick = { onOpenSheet(ActivePlayerSheet.SLEEP_TIMER) }
                )

                LuminaGlassPillButton(
                    label = "${playerState.playbackSpeed}x",
                    leadingIcon = Icons.Outlined.Speed,
                    isActive = playerState.playbackSpeed != 1.0f,
                    onClick = { onOpenSheet(ActivePlayerSheet.SPEED_AND_LOOP) }
                )

                val abLabel = when {
                    playerState.abRepeat.isActive -> "A-B ON"
                    playerState.abRepeat.pointAMs != null -> "A-"
                    else -> "A-B"
                }
                LuminaGlassPillButton(
                    label = abLabel,
                    leadingIcon = Icons.Outlined.Repeat,
                    isActive = playerState.abRepeat.pointAMs != null,
                    accentColor = LuminaCyan,
                    onClick = viewModel::stepABRepeat
                )

                LuminaGlassPillButton(
                    label = "EQ",
                    leadingIcon = Icons.Outlined.Equalizer,
                    isActive = playerState.equalizer.enabled,
                    accentColor = LuminaCyan,
                    onClick = { onOpenSheet(ActivePlayerSheet.EQUALIZER) }
                )

                if (!isWideHud && canAdmDownloadCurrent && onAdmDownloadStream != null) {
                    LuminaGlassPillButton(
                        label = stringResource(R.string.adm_download_stream_action),
                        leadingIcon = Icons.Outlined.ArrowDownward,
                        accentColor = LuminaCyan,
                        onClick = onAdmDownloadStream
                    )
                }

                if (!isWideHud && onEnterPip != null) {
                    LuminaGlassPillButton(
                        label = stringResource(R.string.player_pip),
                        leadingIcon = Icons.Outlined.PictureInPictureAlt,
                        accentColor = LuminaPlatinum,
                        onClick = onEnterPip
                    )
                }

                if (!isWideHud && onOpenLocalVideo != null) {
                    LuminaGlassPillButton(
                        label = stringResource(R.string.player_open_local_file),
                        leadingIcon = Icons.Outlined.FolderOpen,
                        accentColor = LuminaCyan,
                        onClick = onOpenLocalVideo
                    )
                }

                LuminaGlassPillButton(
                    label = stringResource(R.string.player_background_audio),
                    leadingIcon = Icons.Outlined.Headphones,
                    accentColor = LuminaCyan,
                    onClick = onMinimizeToBackgroundAudio
                )
            }
        }

        // Primary Transport & Feature Deck (Single sleek row in Horizontal/Landscape & Desktop; centered 2-row stack in compact Portrait)
        if (isWideHud) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                transportControls()
                Spacer(Modifier.width(12.dp))
                featureActionPills(Modifier.weight(1f, fill = false))
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                transportControls()
                featureActionPills(Modifier.fillMaxWidth())
            }
        }
    }
}

// ============================================================================
// Floating Translucent HUD Popovers: Tracks + Sync (ms), 10-Band EQ, Speed, HDR
// ============================================================================

/**
 * High-contrast smoked-obsidian floating HUD popover card (`95%–98%` alpha) that sits cleanly
 * above bright or dark video frames without sacrificing readability.
 */
@Composable
private fun LuminaFloatingHudPopover(
    title: String,
    maxWidth: androidx.compose.ui.unit.Dp = 360.dp,
    headerTrailing: (@Composable () -> Unit)? = null,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .heightIn(max = 368.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xF20D121D), // 95% deep smoked obsidian top
                        Color(0xFA080B12)  // 98% OLED obsidian base for effortless readability over bright video
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.28f),
                        Color.White.copy(alpha = 0.14f)
                    )
                ),
                shape = shape
            )
            // Consume taps inside the popover so they don't toggle the underlying HUD
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = LuminaTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    headerTrailing?.invoke()
                    LuminaGlassIconButton(
                        icon = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.action_close),
                        size = 30.dp,
                        onClick = onDismiss
                    )
                }
            }
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioAndSubtitlePopover(
    playerState: LuminaPlayerState,
    prefs: UserPreferences,
    viewModel: LuminaPlayerViewModel,
    onLoadExternalSubtitle: () -> Unit,
    onDismiss: () -> Unit
) {
    LuminaFloatingHudPopover(
        title = stringResource(R.string.player_audio_subtitles),
        maxWidth = 368.dp,
        onDismiss = onDismiss
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Audio Tracks & Sync Offset (ms)
            item {
                Text(
                    text = stringResource(R.string.tracks_audio_header),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = LuminaPlatinum
                )
            }

            itemsIndexed(playerState.audioTracks) { _, track ->
                TrackSelectionRow(
                    title = track.title,
                    subtitle = "${track.channels}ch • ${track.sampleRateHz / 1000}kHz",
                    selected = track.isSelected,
                    onClick = { viewModel.selectAudioTrack(track.id) }
                )
            }

            item {
                SyncOffsetStepperRow(
                    label = stringResource(R.string.tracks_audio_sync, playerState.audioDelayMs),
                    onMinus50Ms = { viewModel.setAudioSyncOffsetMs(playerState.audioDelayMs - 50L) },
                    onPlus50Ms = { viewModel.setAudioSyncOffsetMs(playerState.audioDelayMs + 50L) },
                    onReset = { viewModel.setAudioSyncOffsetMs(0L) }
                )
            }

            // Subtitles, External Subtitle Loader & Sync Offset (ms)
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.tracks_subtitle_header),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = LuminaPlatinum
                    )
                    LuminaGlassPillButton(
                        label = stringResource(R.string.tracks_load_external_sub),
                        leadingIcon = Icons.Outlined.Subtitles,
                        accentColor = LuminaCyan,
                        onClick = onLoadExternalSubtitle,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                TrackSelectionRow(
                    title = stringResource(R.string.tracks_subtitles_off),
                    subtitle = null,
                    selected = !playerState.subtitlesEnabled,
                    onClick = viewModel::disableSubtitles
                )
            }

            itemsIndexed(playerState.subtitleTracks) { _, sub ->
                TrackSelectionRow(
                    title = sub.title,
                    subtitle = if (sub.isExternal) "External Subtitle" else sub.codec,
                    selected = sub.isSelected && playerState.subtitlesEnabled,
                    onClick = { viewModel.selectSubtitleTrack(sub.id) }
                )
            }

            item {
                SyncOffsetStepperRow(
                    label = stringResource(R.string.tracks_subtitle_sync, playerState.subtitleDelayMs),
                    onMinus50Ms = { viewModel.setSubtitleSyncOffsetMs(playerState.subtitleDelayMs - 50L) },
                    onPlus50Ms = { viewModel.setSubtitleSyncOffsetMs(playerState.subtitleDelayMs + 50L) },
                    onReset = { viewModel.setSubtitleSyncOffsetMs(0L) }
                )
            }

            // Subtitle Font Scale & Background Opacity Styling
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_subtitle_size),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = LuminaTextPrimary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SubtitleFontScale.entries.forEach { scale ->
                            LuminaGlassPillButton(
                                label = scale.displayLabel,
                                isActive = prefs.subtitleFontScale == scale,
                                onClick = { viewModel.setSubtitleFontScale(scale) }
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.settings_subtitle_background),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = LuminaTextPrimary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SubtitleBackgroundStyle.entries.forEach { bgStyle ->
                            val bgLabel = when (bgStyle) {
                                SubtitleBackgroundStyle.NONE -> stringResource(R.string.subtitle_bg_clear)
                                SubtitleBackgroundStyle.TRANSLUCENT -> stringResource(R.string.subtitle_bg_glass)
                                SubtitleBackgroundStyle.SOLID -> stringResource(R.string.subtitle_bg_solid)
                            }
                            LuminaGlassPillButton(
                                label = bgLabel,
                                isActive = prefs.subtitleBackgroundStyle == bgStyle,
                                accentColor = LuminaCyan,
                                onClick = { viewModel.setSubtitleBackgroundStyle(bgStyle) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackSelectionRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0xF01B2A40) else Color(0xD4141C2B))
            .border(
                1.dp,
                if (selected) LuminaCyan.copy(alpha = 0.68f) else LuminaGlassBorder,
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = LuminaTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = LuminaTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (selected) {
            Icon(Icons.Outlined.Check, contentDescription = null, tint = LuminaCyan, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun SyncOffsetStepperRow(
    label: String,
    onMinus50Ms: () -> Unit,
    onPlus50Ms: () -> Unit,
    onReset: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        LuminaSingleLineFitText(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = LuminaTextPrimary,
            minFontSizeSp = 10f,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LuminaGlassIconButton(
                icon = Icons.Outlined.Remove,
                contentDescription = "-50 ms",
                size = 30.dp,
                onClick = onMinus50Ms
            )
            LuminaGlassIconButton(
                icon = Icons.Outlined.Add,
                contentDescription = "+50 ms",
                size = 30.dp,
                onClick = onPlus50Ms
            )
            LuminaGlassPillButton(
                label = "0 ms",
                onClick = onReset
            )
        }
    }
}

@Composable
private fun TenBandEqualizerPopover(
    playerState: LuminaPlayerState,
    viewModel: LuminaPlayerViewModel,
    onDismiss: () -> Unit
) {
    val eq = playerState.equalizer

    LuminaFloatingHudPopover(
        title = stringResource(R.string.eq_title),
        maxWidth = 396.dp,
        headerTrailing = {
            LuminaGlassPillButton(
                label = stringResource(R.string.action_reset),
                onClick = viewModel::resetEqualizerFlat
            )
            Switch(
                checked = eq.enabled,
                onCheckedChange = viewModel::setEqualizerEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = LuminaOledBlack,
                    checkedTrackColor = LuminaPlatinum
                )
            )
        },
        onDismiss = onDismiss
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            // Presets Row
            if (eq.presets.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    eq.presets.forEachIndexed { idx, presetName ->
                        LuminaGlassPillButton(
                            label = presetName,
                            isActive = eq.enabled && eq.selectedPresetIndex == idx,
                            accentColor = LuminaPlatinum,
                            onClick = { viewModel.selectEqualizerPreset(idx) }
                        )
                    }
                }
            }

            // Compact Pre-Amp Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.eq_preamp, eq.preAmpDb),
                    style = MaterialTheme.typography.labelMedium,
                    color = LuminaTextSecondary
                )
                Slider(
                    value = eq.preAmpDb,
                    valueRange = -20f..20f,
                    onValueChange = viewModel::setEqualizerPreAmp,
                    colors = SliderDefaults.colors(thumbColor = LuminaPlatinum, activeTrackColor = LuminaPlatinum),
                    modifier = Modifier.weight(1f)
                )
            }

            // Compact 10-Band Vertical Sliders
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 2.dp)
            ) {
                eq.bands.forEach { band ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.width(36.dp)
                    ) {
                        Text(
                            text = String.format(Locale.US, "%+.0f", band.gainDb),
                            style = MaterialTheme.typography.labelSmall,
                            color = LuminaPlatinum
                        )
                        VerticalEqualizerSlider(
                            value = band.gainDb,
                            onValueChange = { gain ->
                                viewModel.setEqualizerBandGain(band.index, gain)
                            },
                            modifier = Modifier.height(96.dp)
                        )
                        Text(
                            text = band.formattedFrequency,
                            style = MaterialTheme.typography.labelSmall,
                            color = LuminaTextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VerticalEqualizerSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Slider(
        value = value,
        valueRange = -20f..20f,
        onValueChange = onValueChange,
        colors = SliderDefaults.colors(
            thumbColor = LuminaPlatinum,
            activeTrackColor = LuminaCyan,
            inactiveTrackColor = Color.White.copy(alpha = 0.16f)
        ),
        modifier = modifier
            .graphicsLayer {
                rotationZ = 270f
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints(
                        minWidth = constraints.minHeight,
                        maxWidth = constraints.maxHeight,
                        minHeight = constraints.minWidth,
                        maxHeight = constraints.maxWidth
                    )
                )
                layout(placeable.height, placeable.width) {
                    placeable.place(-placeable.width, 0)
                }
            }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedAndABRepeatPopover(
    playerState: LuminaPlayerState,
    viewModel: LuminaPlayerViewModel,
    onDismiss: () -> Unit
) {
    val speedPresets = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 4.0f)

    LuminaFloatingHudPopover(
        title = "${stringResource(R.string.player_speed)} • ${String.format(Locale.US, "%.2fx", playerState.playbackSpeed)}",
        maxWidth = 330.dp,
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Slider(
                value = playerState.playbackSpeed,
                valueRange = 0.25f..4.0f,
                onValueChange = { raw ->
                    val stepped = (raw * 20f).roundToInt() / 20f
                    viewModel.setPlaybackSpeed(stepped)
                },
                colors = SliderDefaults.colors(thumbColor = LuminaPlatinum, activeTrackColor = LuminaPlatinum)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                speedPresets.forEach { preset ->
                    LuminaGlassPillButton(
                        label = "${preset}x",
                        isActive = abs(playerState.playbackSpeed - preset) < 0.03f,
                        onClick = { viewModel.setPlaybackSpeed(preset) }
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.player_ab_repeat),
                    style = MaterialTheme.typography.labelMedium,
                    color = LuminaTextPrimary,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LuminaGlassPillButton(
                        label = if (playerState.abRepeat.pointAMs == null) "Set A" else "Step A-B",
                        accentColor = LuminaCyan,
                        isActive = playerState.abRepeat.pointAMs != null,
                        onClick = viewModel::stepABRepeat
                    )
                    if (playerState.abRepeat.pointAMs != null) {
                        LuminaGlassPillButton(
                            label = stringResource(R.string.action_reset),
                            onClick = viewModel::clearABRepeat
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HdrPipelineInspectorPopover(
    playerState: LuminaPlayerState,
    hdrPassthroughEnabled: Boolean,
    onToggleHdrPassthrough: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val hdr = playerState.hdrStatus
    val modeLabel = when (hdr.outputMode) {
        HdrOutputMode.HARDWARE_HDR_PASSTHROUGH -> stringResource(R.string.hdr_passthrough_active)
        HdrOutputMode.LIBVLC_DYNAMIC_TONE_MAPPING -> stringResource(
            R.string.hdr_tonemapping_active,
            hdr.activeToneMapper ?: "BT.2390"
        )
        HdrOutputMode.STANDARD_SDR -> stringResource(R.string.hdr_standard_sdr)
    }

    LuminaFloatingHudPopover(
        title = stringResource(R.string.player_hdr_inspector),
        maxWidth = 352.dp,
        headerTrailing = { HdrBadgeChip(hdr.sourceFormat.displayBadge) },
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = modeLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = LuminaCyan
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.settings_hdr_passthrough),
                    style = MaterialTheme.typography.labelMedium,
                    color = LuminaTextPrimary,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                Switch(
                    checked = hdrPassthroughEnabled,
                    onCheckedChange = onToggleHdrPassthrough,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = LuminaOledBlack,
                        checkedTrackColor = LuminaPlatinum
                    )
                )
            }
            Text(
                text = stringResource(R.string.hdr_color_space, hdr.activeColorSpaceName),
                style = MaterialTheme.typography.labelMedium,
                color = LuminaTextPrimary
            )
            if (hdr.displayMaxLuminanceNits > 0f) {
                Text(
                    text = stringResource(
                        R.string.hdr_peak_luminance,
                        hdr.displayMaxLuminanceNits.roundToInt()
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = LuminaTextSecondary
                )
            }
            val supportedPanelCaps = buildList {
                if (hdr.displaySupportsDolbyVision) add("Dolby Vision")
                if (hdr.displaySupportsHdr10Plus) add("HDR10+")
                if (hdr.displaySupportsHdr10) add("HDR10")
                if (hdr.displaySupportsHlg) add("HLG")
                if (isEmpty()) add("SDR Panel (Tone-Mapping Fallback Ready)")
            }.joinToString(" • ")

            Text(
                text = "Display EOTF: $supportedPanelCaps",
                style = MaterialTheme.typography.labelSmall,
                color = LuminaTextSecondary
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepTimerPopover(
    playerState: LuminaPlayerState,
    viewModel: LuminaPlayerViewModel,
    onDismiss: () -> Unit
) {
    val activeTimer = playerState.sleepTimer
    LuminaFloatingHudPopover(
        title = stringResource(R.string.player_sleep_timer),
        maxWidth = 340.dp,
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (activeTimer.isActive) {
                Text(
                    text = stringResource(
                        R.string.sleep_timer_remaining,
                        formatDurationMs(activeTimer.remainingMs)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = LuminaCyan
                )
            } else {
                Text(
                    text = stringResource(R.string.sleep_timer_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = LuminaTextSecondary
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SleepTimerMode.entries.forEach { mode ->
                    val optionLabel = when (mode) {
                        SleepTimerMode.OFF -> stringResource(R.string.sleep_timer_off)
                        SleepTimerMode.MIN_15 -> "15 min"
                        SleepTimerMode.MIN_30 -> "30 min"
                        SleepTimerMode.MIN_45 -> "45 min"
                        SleepTimerMode.MIN_60 -> "60 min"
                        SleepTimerMode.END_OF_MEDIA -> stringResource(R.string.sleep_timer_end_of_media)
                    }
                    LuminaGlassPillButton(
                        label = optionLabel,
                        isActive = activeTimer.mode == mode,
                        accentColor = if (mode == SleepTimerMode.OFF) LuminaPlatinum else LuminaCyan,
                        onClick = { viewModel.setSleepTimer(mode) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChaptersPopover(
    playerState: LuminaPlayerState,
    viewModel: LuminaPlayerViewModel,
    onDismiss: () -> Unit
) {
    LuminaFloatingHudPopover(
        title = stringResource(R.string.player_chapters),
        maxWidth = 360.dp,
        onDismiss = onDismiss
    ) {
        if (playerState.chapters.isEmpty()) {
            Text(
                text = stringResource(R.string.chapters_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                color = LuminaTextSecondary
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(playerState.chapters) { idx, chapter ->
                    TrackSelectionRow(
                        title = chapter.title,
                        subtitle = formatDurationMs(chapter.timeOffsetMs),
                        selected = idx == playerState.currentChapterIndex,
                        onClick = { viewModel.selectChapter(idx) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BookmarksPopover(
    bookmarks: List<VideoBookmarkEntity>,
    currentTimeMs: Long,
    viewModel: LuminaPlayerViewModel,
    onDismiss: () -> Unit
) {
    var noteText by rememberSaveable { mutableStateOf("") }

    LuminaFloatingHudPopover(
        title = stringResource(R.string.player_bookmarks),
        maxWidth = 376.dp,
        headerTrailing = {
            LuminaGlassPillButton(
                label = formatDurationMs(currentTimeMs),
                leadingIcon = Icons.Outlined.BookmarkAdd,
                accentColor = LuminaCyan,
                isActive = true,
                onClick = {
                    viewModel.addBookmarkAtCurrentPosition(noteText)
                    noteText = ""
                }
            )
        },
        onDismiss = onDismiss
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                placeholder = {
                    Text(
                        text = stringResource(R.string.bookmarks_label_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = LuminaTextSecondary
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            if (bookmarks.isEmpty()) {
                Text(
                    text = stringResource(R.string.bookmarks_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LuminaTextSecondary
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(bookmarks, key = { _, bm -> bm.id }) { _, bm ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xD4141C2B))
                                .border(1.dp, LuminaGlassBorder, RoundedCornerShape(12.dp))
                                .clickable { viewModel.jumpToBookmark(bm) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Bookmark,
                                contentDescription = null,
                                tint = LuminaCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = bm.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LuminaTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = formatDurationMs(bm.positionMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LuminaCyan
                                )
                            }
                            LuminaGlassIconButton(
                                icon = Icons.Outlined.DeleteOutline,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = LuminaDanger,
                                size = 30.dp,
                                iconSize = 16.dp,
                                onClick = { viewModel.deleteBookmark(bm.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackQueuePopover(
    playerState: LuminaPlayerState,
    viewModel: LuminaPlayerViewModel,
    onSaveAsPlaylist: () -> Unit,
    onDismiss: () -> Unit
) {
    LuminaFloatingHudPopover(
        title = stringResource(R.string.player_queue_title),
        maxWidth = 384.dp,
        headerTrailing = {
            if (playerState.queue.isNotEmpty()) {
                LuminaGlassPillButton(
                    label = stringResource(R.string.playlists_save_queue),
                    leadingIcon = Icons.Outlined.SaveAlt,
                    accentColor = LuminaCyan,
                    onClick = onSaveAsPlaylist
                )
            }
        },
        onDismiss = onDismiss
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(playerState.queue) { idx, item ->
                TrackSelectionRow(
                    title = item.title,
                    subtitle = if (item.durationMs > 0L) formatDurationMs(item.durationMs) else item.subtitle,
                    selected = idx == playerState.currentQueueIndex,
                    onClick = { viewModel.playQueueIndex(idx) }
                )
            }
        }
    }
}

@Composable
private fun SaveQueueAsPlaylistDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var playlistName by rememberSaveable(defaultName) { mutableStateOf(defaultName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LuminaObsidianElevated,
        title = {
            Text(
                text = stringResource(R.string.playlists_save_queue),
                color = LuminaTextPrimary
            )
        },
        text = {
            OutlinedTextField(
                value = playlistName,
                onValueChange = { playlistName = it },
                label = { Text(stringResource(R.string.playlists_name_label)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = playlistName.trim().isNotEmpty(),
                onClick = { onConfirm(playlistName.trim()) }
            ) {
                Text(stringResource(R.string.playlists_save_queue), color = LuminaCyan)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = LuminaTextSecondary)
            }
        }
    )
}


