package app.lumina.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Rational
import android.view.DragAndDropPermissions
import android.view.DragEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.os.asOutcomeReceiver
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.lumina.player.data.download.AdmDownloadPlanner
import app.lumina.player.data.playlist.M3uPlaylistCodec
import app.lumina.player.data.preferences.LuminaPreferences
import app.lumina.player.data.preferences.UserPreferences
import app.lumina.player.data.repository.MediaRepository
import app.lumina.player.player.engine.LuminaPlayerEngine
import app.lumina.player.player.model.MediaQueueItem
import app.lumina.player.player.model.PlaybackStatus
import app.lumina.player.player.model.PlayerOrientationMode
import app.lumina.player.player.ui.PipSourceBounds
import app.lumina.player.ui.adaptive.rememberLuminaAdaptiveEnvironment
import app.lumina.player.ui.library.LuminaLibraryScreen
import app.lumina.player.ui.library.LuminaLibraryViewModel
import app.lumina.player.ui.player.LuminaPlayerScreen
import app.lumina.player.ui.player.LuminaPlayerViewModel
import app.lumina.player.ui.theme.LuminaTheme
import app.lumina.player.update.LuminaUpdateManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

private enum class LuminaRootDestination {
    LIBRARY,
    PLAYER
}

/**
 * Single Adaptive Host Activity for Lumina – HDR Media Player.
 *
 * Supports:
 * - Zero-flash OLED startup via `installSplashScreen()`
 * - Seamless Continuity across Foldable postures (Flat <-> Lumina Tabletop Mode)
 * - Googlebook OS freeform multi-window resizing, cross-app Drag & Drop, native desktop <-> device
 *   fullscreen transitions (`Activity.requestFullscreenMode`), and Always-on-Top floating mini-player mode
 * - Android Picture-in-Picture (PiP) with dynamic video aspect ratio, `sourceRectHint` seamless resize & `RemoteAction` controls
 * - Background Audio Continuation with Library Now Playing Mini-Bar
 * - Per-app multi-language switching via `AppCompatActivity` + `locales_config.xml`
 * - Official Google Play Protect compliant Automatic & Manual In-App Updates (`LuminaUpdateManager`)
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var playerEngine: LuminaPlayerEngine

    @Inject
    lateinit var mediaRepository: MediaRepository

    @Inject
    lateinit var updateManager: LuminaUpdateManager

    @Inject
    lateinit var preferences: LuminaPreferences

    private var updateFlowLauncher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var activeDragPermission: DragAndDropPermissions? = null

    private val pendingIntentDestination = MutableStateFlow<LuminaRootDestination?>(null)
    private val pendingSharedDownloadUrl = MutableStateFlow<String?>(null)
    private var currentActiveDestination: LuminaRootDestination = LuminaRootDestination.LIBRARY
    private var isSystemPipState by mutableStateOf(false)
    private var isFullscreenState by mutableStateOf(false)
    private var wantsImmersiveFullscreen: Boolean = false
    private var lastFullscreenRequestElapsedMs: Long = 0L
    private var isGooglebookMiniState by mutableStateOf(false)
    private var isDragHoveringState by mutableStateOf(false)
    private var playerOrientationMode by mutableStateOf(PlayerOrientationMode.AUTO)
    private var autoRotatedByFullscreen: Boolean = false
    private var isLandscapeWindowState by mutableStateOf(false)
    private var isDesktopOrGooglebookHost: Boolean = false
    private var isTabletopPostureActive: Boolean = false
    private var activeSeekSeconds: Int = 10
    private var activeAutoEnterPipOnBackground: Boolean = true
    private var latestPipSourceBounds by mutableStateOf<PipSourceBounds?>(null)

    private val surfaceSettleRunnable = Runnable {
        playerEngine.onSurfaceBoundsChanged()
    }

    private val pipControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_PIP_CONTROL) return
            val stepMs = activeSeekSeconds.coerceAtLeast(5) * 1_000L
            when (intent.getIntExtra(EXTRA_PIP_ACTION, 0)) {
                PIP_ACTION_REWIND -> playerEngine.seekRelative(-stepMs)
                PIP_ACTION_PLAY_PAUSE -> playerEngine.togglePlayPause()
                PIP_ACTION_FORWARD -> playerEngine.seekRelative(stepMs)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(playerEngine)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        isLandscapeWindowState =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        configureSymmetricalDisplayCutout()

        window.decorView.setOnDragListener { _, event ->
            handleWindowDragEvent(event)
        }

        runCatching {
            ContextCompat.registerReceiver(
                this,
                pipControlReceiver,
                IntentFilter(ACTION_PIP_CONTROL),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        updateFlowLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { /* Play Core InstallStateUpdatedListener handles completion & cancellation */ }

        // Trigger Play Protect compliant Automatic In-App Update check if enabled & due
        lifecycleScope.launch {
            updateManager.checkAutomaticUpdatesIfDue(updateFlowLauncher)
        }

        if (savedInstanceState == null) {
            handleIncomingMediaIntent(intent)
        }

        setContent {
            LuminaTheme {
                val adaptiveEnv = rememberLuminaAdaptiveEnvironment(activity = this@MainActivity)
                val updateState by updateManager.updateState.collectAsStateWithLifecycle()
                val engineState by playerEngine.state.collectAsStateWithLifecycle()
                val userPrefs by preferences.userPreferencesFlow.collectAsStateWithLifecycle(
                    initialValue = UserPreferences()
                )
                val intentDestination by pendingIntentDestination.collectAsStateWithLifecycle()
                val sharedDownloadUrl by pendingSharedDownloadUrl.collectAsStateWithLifecycle()

                isDesktopOrGooglebookHost = adaptiveEnv.isGooglebookOrDesktop
                isTabletopPostureActive = adaptiveEnv.isTabletopMode

                var activeDestination by rememberSaveable {
                    mutableStateOf(
                        if (playerEngine.state.value.currentMedia != null &&
                            playerEngine.state.value.status != PlaybackStatus.IDLE
                        ) {
                            LuminaRootDestination.PLAYER
                        } else {
                            LuminaRootDestination.LIBRARY
                        }
                    )
                }

                LaunchedEffect(userPrefs.doubleTapSeekSeconds) {
                    activeSeekSeconds = userPrefs.doubleTapSeekSeconds.coerceAtLeast(5)
                }

                LaunchedEffect(userPrefs.autoEnterPipOnBackground) {
                    activeAutoEnterPipOnBackground = userPrefs.autoEnterPipOnBackground
                }

                LaunchedEffect(intentDestination) {
                    val requested = intentDestination ?: return@LaunchedEffect
                    if (requested == LuminaRootDestination.LIBRARY &&
                        activeDestination == LuminaRootDestination.PLAYER
                    ) {
                        if (isFullscreenState) {
                            setImmersiveFullscreen(false)
                        }
                        applyPlayerOrientationMode(PlayerOrientationMode.AUTO)
                        resetWindowBrightnessOverride()
                        isGooglebookMiniState = false
                        playerEngine.setPipOrMiniPlayerMode(false)
                        if (userPrefs.continueAudioInBackground && playerEngine.state.value.isPlaying) {
                            playerEngine.enterBackgroundAudioMode()
                        } else {
                            playerEngine.stop()
                        }
                    }
                    activeDestination = requested
                    pendingIntentDestination.value = null
                }

                LaunchedEffect(activeDestination) {
                    currentActiveDestination = activeDestination
                    if (activeDestination == LuminaRootDestination.LIBRARY) {
                        latestPipSourceBounds = null
                        if (playerOrientationMode != PlayerOrientationMode.AUTO) {
                            applyPlayerOrientationMode(PlayerOrientationMode.AUTO)
                        }
                    }
                }

                LaunchedEffect(adaptiveEnv.isTabletopMode) {
                    if (adaptiveEnv.isTabletopMode && autoRotatedByFullscreen) {
                        applyPlayerOrientationMode(PlayerOrientationMode.AUTO)
                    }
                    scheduleVideoSurfaceSettle()
                }

                // Exit floating mini-player automatically if disabled in Settings
                LaunchedEffect(userPrefs.googlebookAlwaysOnTopMiniPlayer) {
                    if (!userPrefs.googlebookAlwaysOnTopMiniPlayer && isGooglebookMiniState) {
                        isGooglebookMiniState = false
                        playerEngine.setPipOrMiniPlayerMode(false)
                        scheduleVideoSurfaceSettle()
                    }
                }

                // Keep PiP aspect ratio, sourceRectHint, RemoteAction buttons & Android 12+ auto-PiP synchronized
                LaunchedEffect(
                    activeDestination,
                    isSystemPipState,
                    engineState.isPlaying,
                    engineState.videoWidth,
                    engineState.videoHeight,
                    userPrefs.autoEnterPipOnBackground,
                    userPrefs.doubleTapSeekSeconds,
                    adaptiveEnv.isGooglebookOrDesktop,
                    latestPipSourceBounds
                ) {
                    val shouldAutoPip = activeDestination == LuminaRootDestination.PLAYER &&
                        engineState.isPlaying &&
                        userPrefs.autoEnterPipOnBackground &&
                        !adaptiveEnv.isGooglebookOrDesktop

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || isSystemPipState) {
                        runCatching {
                            setPictureInPictureParams(
                                buildPipParams(
                                    videoWidth = engineState.videoWidth,
                                    videoHeight = engineState.videoHeight,
                                    isPlaying = engineState.isPlaying,
                                    autoEnter = shouldAutoPip,
                                    sourceBounds = latestPipSourceBounds
                                )
                            )
                        }
                    }
                }

                // Direct root composition (without Crossfade alpha offscreen layer) ensures
                // LuminaPlayerScreen's hardware SurfaceView attaches on frame 1 and punches
                // cleanly through to the hardware overlay plane on Android & Googlebook OS.
                when (activeDestination) {
                    LuminaRootDestination.LIBRARY -> {
                        val libraryViewModel: LuminaLibraryViewModel = hiltViewModel()
                        LuminaLibraryScreen(
                            viewModel = libraryViewModel,
                            adaptiveEnv = adaptiveEnv,
                            updateState = updateState,
                            isDragHovering = isDragHoveringState,
                            sharedDownloadUrl = sharedDownloadUrl,
                            onConsumeSharedDownloadUrl = {
                                pendingSharedDownloadUrl.value = null
                            },
                            onReturnToPlayer = {
                                activeDestination = LuminaRootDestination.PLAYER
                                scheduleVideoSurfaceSettle()
                            },
                            onPlayQueue = { items, startIndex, smbUser, smbPassword ->
                                activeDestination = LuminaRootDestination.PLAYER
                                playerEngine.loadQueue(
                                    items = items,
                                    startIndex = startIndex,
                                    startImmediately = true,
                                    smbUsername = smbUser,
                                    smbPassword = smbPassword
                                )
                            },
                            onCheckForUpdates = {
                                lifecycleScope.launch {
                                    updateManager.checkForUpdatesManually(
                                        updateLauncher = updateFlowLauncher
                                    )
                                }
                            },
                            onCompleteFlexibleUpdate = {
                                updateManager.completeFlexibleUpdate()
                            },
                            onDismissUpdateBanner = {
                                updateManager.dismissUpdateBanner()
                            }
                        )
                    }

                    LuminaRootDestination.PLAYER -> {
                        val playerViewModel: LuminaPlayerViewModel = hiltViewModel()
                        LuminaPlayerScreen(
                            activity = this@MainActivity,
                            viewModel = playerViewModel,
                            adaptiveEnv = adaptiveEnv,
                            isSystemPipMode = isSystemPipState,
                            isFullscreen = isFullscreenState,
                            isGooglebookFloatingMini = isGooglebookMiniState,
                            isDragHovering = isDragHoveringState,
                            orientationMode = playerOrientationMode,
                            onCycleOrientation = {
                                cyclePlayerOrientationMode()
                            },
                            onToggleFullscreen = {
                                setImmersiveFullscreen(!isFullscreenState)
                            },
                            onEnterPip = {
                                enterLuminaPictureInPicture()
                            },
                            onToggleGooglebookMiniPlayer = {
                                if (userPrefs.googlebookAlwaysOnTopMiniPlayer) {
                                    val nextMini = !isGooglebookMiniState
                                    if (nextMini && isFullscreenState) {
                                        setImmersiveFullscreen(false)
                                    }
                                    isGooglebookMiniState = nextMini
                                    playerEngine.setPipOrMiniPlayerMode(
                                        inPipOrMiniPlayer = nextMini || isSystemPipState,
                                        isHostStopped = false
                                    )
                                    scheduleVideoSurfaceSettle()
                                }
                            },
                            onMinimizeToBackgroundAudio = {
                                if (isFullscreenState) {
                                    setImmersiveFullscreen(false)
                                }
                                applyPlayerOrientationMode(PlayerOrientationMode.AUTO)
                                resetWindowBrightnessOverride()
                                isGooglebookMiniState = false
                                playerEngine.enterBackgroundAudioMode()
                                activeDestination = LuminaRootDestination.LIBRARY
                            },
                            onVideoBoundsInWindowChanged = { bounds ->
                                if (!bounds.isApproximatelyEqual(latestPipSourceBounds)) {
                                    latestPipSourceBounds = bounds
                                }
                            },
                            onNavigateBack = {
                                if (isFullscreenState) {
                                    setImmersiveFullscreen(false)
                                }
                                applyPlayerOrientationMode(PlayerOrientationMode.AUTO)
                                resetWindowBrightnessOverride()
                                isGooglebookMiniState = false
                                if (userPrefs.continueAudioInBackground && playerEngine.state.value.isPlaying) {
                                    playerEngine.enterBackgroundAudioMode()
                                } else {
                                    playerEngine.stop()
                                }
                                activeDestination = LuminaRootDestination.LIBRARY
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { window?.decorView?.removeCallbacks(surfaceSettleRunnable) }
        if (::playerEngine.isInitialized) {
            runCatching { lifecycle.removeObserver(playerEngine) }
        }
        runCatching { activeDragPermission?.release() }
        activeDragPermission = null
        runCatching { unregisterReceiver(pipControlReceiver) }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        isLandscapeWindowState = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (wantsImmersiveFullscreen && !isInMultiWindowMode) {
            applySystemBarsImmersive(true)
        }
        scheduleVideoSurfaceSettle()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingMediaIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        scheduleVideoSurfaceSettle()
        lifecycleScope.launch {
            updateManager.resumePendingUpdatesIfNeeded(this@MainActivity, updateFlowLauncher)
            updateManager.checkAutomaticUpdatesIfDue(updateFlowLauncher)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isInPictureInPictureMode &&
            !isDesktopOrGooglebookHost &&
            currentActiveDestination == LuminaRootDestination.PLAYER &&
            playerEngine.state.value.isPlaying &&
            activeAutoEnterPipOnBackground
        ) {
            enterLuminaPictureInPicture()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val hostStopped = !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        isSystemPipState = isInPictureInPictureMode
        playerEngine.setPipOrMiniPlayerMode(
            inPipOrMiniPlayer = isInPictureInPictureMode || isGooglebookMiniState,
            isHostStopped = hostStopped
        )
        scheduleVideoSurfaceSettle()
    }

    override fun onMultiWindowModeChanged(
        isInMultiWindowMode: Boolean,
        newConfig: Configuration
    ) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (!isInMultiWindowMode && wantsImmersiveFullscreen) {
            // Googlebook OS window just transitioned from desktop freeform -> device fullscreen
            isFullscreenState = true
            window.decorView.post {
                if (wantsImmersiveFullscreen) {
                    applySystemBarsImmersive(true)
                }
            }
        } else if (
            isInMultiWindowMode &&
            isFullscreenState &&
            (SystemClock.elapsedRealtime() - lastFullscreenRequestElapsedMs > 600L)
        ) {
            // User restored freeform desktop window externally via OS shortcut or window handle
            wantsImmersiveFullscreen = false
            isFullscreenState = false
            applySystemBarsImmersive(false)
        }
        scheduleVideoSurfaceSettle()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && wantsImmersiveFullscreen && !isInMultiWindowMode) {
            applySystemBarsImmersive(true)
        }
    }

    private fun resetWindowBrightnessOverride() {
        runCatching {
            val lp = window.attributes
            if (lp.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = lp
            }
        }
    }

    private fun resolveDisplayNameForUri(uri: Uri): String {
        val scheme = uri.scheme?.lowercase()
        val queried = if (scheme == "content") {
            runCatching {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else {
                        null
                    }
                }
            }.getOrNull()
        } else {
            null
        }
        return queried?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: uri.toString()
    }

    private fun handleWindowDragEvent(event: DragEvent?): Boolean {
        if (event == null) return false
        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DRAG_ENTERED -> {
                isDragHoveringState = true
                true
            }
            DragEvent.ACTION_DRAG_LOCATION -> true
            DragEvent.ACTION_DRAG_EXITED -> {
                isDragHoveringState = false
                true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                isDragHoveringState = false
                true
            }
            DragEvent.ACTION_DROP -> {
                isDragHoveringState = false
                val newPermission = runCatching { requestDragAndDropPermissions(event) }.getOrNull()
                if (newPermission != null) {
                    runCatching { activeDragPermission?.release() }
                    activeDragPermission = newPermission
                }

                val clip = event.clipData ?: return false
                val subtitleUris = mutableListOf<Uri>()
                val playlistUris = mutableListOf<Uri>()
                val mediaQueueItems = mutableListOf<MediaQueueItem>()

                for (i in 0 until clip.itemCount) {
                    val clipItem = clip.getItemAt(i) ?: continue
                    val candidateUri = clipItem.uri ?: clipItem.text?.toString()?.trim()
                        ?.takeIf {
                            it.startsWith("http://", ignoreCase = true) ||
                                it.startsWith("https://", ignoreCase = true) ||
                                it.startsWith("smb://", ignoreCase = true) ||
                                it.startsWith("rtsp://", ignoreCase = true) ||
                                it.startsWith("ftp://", ignoreCase = true)
                        }
                        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                        ?: continue

                    val resolvedName = resolveDisplayNameForUri(candidateUri)
                    val resolvedMime = runCatching { contentResolver.getType(candidateUri) }.getOrNull()
                    val scheme = candidateUri.scheme?.lowercase()
                    val isNetwork = scheme in setOf("http", "https", "rtsp", "rtmp", "mms", "smb", "ftp", "upnp")

                    when {
                        M3uPlaylistCodec.isSubtitleFileName(resolvedName, resolvedMime) -> {
                            subtitleUris.add(candidateUri)
                        }
                        M3uPlaylistCodec.isM3uPlaylistFile(
                            fileNameOrUri = resolvedName,
                            mimeType = resolvedMime,
                            isRemoteHlsStream = isNetwork && resolvedName.endsWith(".m3u8", ignoreCase = true)
                        ) -> {
                            playlistUris.add(candidateUri)
                        }
                        else -> {
                            val cleanTitle = resolvedName.substringBeforeLast('.', resolvedName).ifBlank { resolvedName }
                            mediaQueueItems.add(
                                MediaQueueItem(
                                    uri = candidateUri,
                                    title = cleanTitle,
                                    mimeType = resolvedMime,
                                    isNetworkStream = isNetwork
                                )
                            )
                        }
                    }
                }

                when {
                    mediaQueueItems.isNotEmpty() -> {
                        pendingIntentDestination.value = LuminaRootDestination.PLAYER
                        playerEngine.loadQueue(
                            items = mediaQueueItems,
                            startIndex = 0,
                            startImmediately = true,
                            initialExternalSubtitleUri = subtitleUris.firstOrNull()
                        )
                        true
                    }
                    playlistUris.isNotEmpty() -> {
                        val firstPlaylistUri = playlistUris.first()
                        lifecycleScope.launch {
                            val resolvedQueue = mediaRepository.importAndResolveM3uQueue(firstPlaylistUri)
                            if (resolvedQueue.isNotEmpty()) {
                                pendingIntentDestination.value = LuminaRootDestination.PLAYER
                                playerEngine.loadQueue(
                                    items = resolvedQueue,
                                    startIndex = 0,
                                    startImmediately = true
                                )
                            } else {
                                val fallbackScheme = firstPlaylistUri.scheme?.lowercase()
                                val isNet = fallbackScheme in setOf("http", "https", "rtsp", "rtmp", "mms", "smb", "ftp", "upnp")
                                if (isNet) {
                                    val fallbackTitle = resolveDisplayNameForUri(firstPlaylistUri)
                                    pendingIntentDestination.value = LuminaRootDestination.PLAYER
                                    playerEngine.loadSingleMedia(
                                        item = MediaQueueItem(
                                            uri = firstPlaylistUri,
                                            title = fallbackTitle.substringBeforeLast('.', fallbackTitle).ifBlank { fallbackTitle },
                                            isNetworkStream = true
                                        ),
                                        startImmediately = true
                                    )
                                }
                            }
                        }
                        true
                    }
                    subtitleUris.isNotEmpty() &&
                        playerEngine.state.value.currentMedia != null &&
                        playerEngine.state.value.status != PlaybackStatus.IDLE -> {
                        pendingIntentDestination.value = LuminaRootDestination.PLAYER
                        playerEngine.loadExternalSubtitle(subtitleUris.first(), autoSelect = true)
                        true
                    }
                    else -> false
                }
            }
            else -> false
        }
    }

    private fun handleIncomingMediaIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            val extractedUrl = AdmDownloadPlanner.extractFirstStreamUrlFromSharedText(sharedText)
            if (!extractedUrl.isNullOrBlank()) {
                pendingSharedDownloadUrl.value = extractedUrl
                pendingIntentDestination.value = LuminaRootDestination.LIBRARY
            }
            return
        }

        if (intent?.action != Intent.ACTION_VIEW) return
        val dataUri = intent.data ?: return

        val scheme = dataUri.scheme?.lowercase()
        val isNetwork = scheme in setOf("http", "https", "rtsp", "rtmp", "mms", "smb", "ftp")
        val rawTitle = resolveDisplayNameForUri(dataUri)
        val cleanTitle = rawTitle.substringBeforeLast('.', rawTitle).ifBlank { rawTitle }
        val isAppleHlsMime = intent.type?.equals("application/vnd.apple.mpegurl", ignoreCase = true) == true

        if (
            M3uPlaylistCodec.isM3uPlaylistFile(
                fileNameOrUri = rawTitle,
                mimeType = intent.type,
                isRemoteHlsStream = isNetwork && (rawTitle.endsWith(".m3u8", ignoreCase = true) || isAppleHlsMime)
            )
        ) {
            val capturedType = intent.type
            lifecycleScope.launch {
                val resolvedQueue = mediaRepository.importAndResolveM3uQueue(dataUri)
                if (resolvedQueue.isNotEmpty()) {
                    pendingIntentDestination.value = LuminaRootDestination.PLAYER
                    playerEngine.loadQueue(
                        items = resolvedQueue,
                        startIndex = 0,
                        startImmediately = true
                    )
                } else {
                    // Fallback directly to libVLC stream playback (e.g. extensionless application/x-mpegurl HLS stream)
                    pendingIntentDestination.value = LuminaRootDestination.PLAYER
                    playerEngine.loadSingleMedia(
                        item = MediaQueueItem(
                            uri = dataUri,
                            title = cleanTitle,
                            mimeType = capturedType,
                            isNetworkStream = isNetwork
                        ),
                        startImmediately = true
                    )
                }
            }
            return
        }

        pendingIntentDestination.value = LuminaRootDestination.PLAYER
        playerEngine.loadSingleMedia(
            item = MediaQueueItem(
                uri = dataUri,
                title = cleanTitle,
                mimeType = intent.type,
                isNetworkStream = isNetwork
            ),
            startImmediately = true
        )
    }

    private fun enterLuminaPictureInPicture() {
        if (isInPictureInPictureMode) return
        val snapshot = playerEngine.state.value
        val shouldAutoEnter = activeAutoEnterPipOnBackground &&
            currentActiveDestination == LuminaRootDestination.PLAYER &&
            snapshot.isPlaying &&
            !isDesktopOrGooglebookHost
        val params = buildPipParams(
            videoWidth = snapshot.videoWidth,
            videoHeight = snapshot.videoHeight,
            isPlaying = snapshot.isPlaying,
            autoEnter = shouldAutoEnter,
            sourceBounds = latestPipSourceBounds
        )
        // Pre-signal PiP continuity BEFORE requesting WindowManager transition so any synchronous
        // lifecycle pause/stop callback keeps the hardware video track and SurfaceView active.
        playerEngine.setPipOrMiniPlayerMode(inPipOrMiniPlayer = true, isHostStopped = false)
        val entered = runCatching {
            enterPictureInPictureMode(params)
        }.getOrDefault(false)
        if (entered || isInPictureInPictureMode) {
            isSystemPipState = true
            scheduleVideoSurfaceSettle()
        } else {
            playerEngine.setPipOrMiniPlayerMode(
                inPipOrMiniPlayer = isGooglebookMiniState,
                isHostStopped = !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            )
        }
    }

    private fun buildPipParams(
        videoWidth: Int,
        videoHeight: Int,
        isPlaying: Boolean,
        autoEnter: Boolean,
        sourceBounds: PipSourceBounds? = latestPipSourceBounds
    ): PictureInPictureParams {
        val (ratioNum, ratioDen) = computeClampedPipAspectRatio(videoWidth, videoHeight)
        val ratio = Rational(ratioNum, ratioDen)
        val decor = window?.decorView
        val sanitizedBounds = sanitizePipSourceBounds(
            bounds = sourceBounds,
            windowWidth = decor?.width ?: 0,
            windowHeight = decor?.height ?: 0,
            aspectRatioNum = ratioNum,
            aspectRatioDen = ratioDen
        )

        val rewindAction = RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_media_rewind),
            getString(R.string.player_seek_back),
            getString(R.string.player_seek_back),
            buildPipControlPendingIntent(PIP_ACTION_REWIND)
        )

        val playPauseAction = RemoteAction(
            Icon.createWithResource(
                this,
                if (isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play
            ),
            getString(R.string.player_play_pause),
            getString(R.string.player_play_pause),
            buildPipControlPendingIntent(PIP_ACTION_PLAY_PAUSE)
        )

        val forwardAction = RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_media_forward),
            getString(R.string.player_seek_forward),
            getString(R.string.player_seek_forward),
            buildPipControlPendingIntent(PIP_ACTION_FORWARD)
        )

        return PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .setActions(listOf(rewindAction, playPauseAction, forwardAction))
            .apply {
                if (sanitizedBounds != null) {
                    setSourceRectHint(sanitizedBounds.toAndroidRect())
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setSeamlessResizeEnabled(sanitizedBounds != null)
                    setAutoEnterEnabled(autoEnter)
                }
            }
            .build()
    }

    private fun buildPipControlPendingIntent(actionCode: Int): PendingIntent {
        val intent = Intent(ACTION_PIP_CONTROL)
            .setPackage(packageName)
            .putExtra(EXTRA_PIP_ACTION, actionCode)
        return PendingIntent.getBroadcast(
            this,
            200 + actionCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun configureSymmetricalDisplayCutout() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val lp = window.attributes
                lp.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                window.attributes = lp
            }
        }
    }

    private fun scheduleVideoSurfaceSettle() {
        val decor = window?.decorView ?: return
        decor.removeCallbacks(surfaceSettleRunnable)
        playerEngine.onSurfaceBoundsChanged()
        decor.post(surfaceSettleRunnable)
        decor.postDelayed(surfaceSettleRunnable, 60L)
        decor.postDelayed(surfaceSettleRunnable, 160L)
        decor.postDelayed(surfaceSettleRunnable, 320L)
        decor.postDelayed(surfaceSettleRunnable, 520L)
    }

    private fun cyclePlayerOrientationMode(): PlayerOrientationMode {
        val nextMode = playerOrientationMode.next()
        applyPlayerOrientationMode(nextMode, fromAutoFullscreen = false)
        return nextMode
    }

    private fun applyPlayerOrientationMode(
        mode: PlayerOrientationMode,
        fromAutoFullscreen: Boolean = false
    ) {
        playerOrientationMode = mode
        autoRotatedByFullscreen = fromAutoFullscreen && mode == PlayerOrientationMode.LANDSCAPE
        val targetActivityOrientation = when (mode) {
            PlayerOrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            PlayerOrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            PlayerOrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        runCatching {
            if (requestedOrientation != targetActivityOrientation) {
                requestedOrientation = targetActivityOrientation
            }
        }
        scheduleVideoSurfaceSettle()
    }

    private fun setImmersiveFullscreen(enable: Boolean) {
        lastFullscreenRequestElapsedMs = SystemClock.elapsedRealtime()
        if (enable) {
            if (isGooglebookMiniState) {
                isGooglebookMiniState = false
                playerEngine.setPipOrMiniPlayerMode(
                    inPipOrMiniPlayer = isSystemPipState,
                    isHostStopped = false
                )
            }
            wantsImmersiveFullscreen = true
            isFullscreenState = true

            val snapshot = playerEngine.state.value
            if (shouldAutoRotateToLandscapeOnFullscreen(
                    enteringFullscreen = true,
                    currentOrientationMode = playerOrientationMode,
                    isCurrentlyLandscape = isLandscapeWindowState,
                    isGooglebookOrDesktop = isDesktopOrGooglebookHost,
                    isTabletopMode = isTabletopPostureActive,
                    isInMultiWindowMode = isInMultiWindowMode,
                    videoWidth = snapshot.videoWidth,
                    videoHeight = snapshot.videoHeight
                )
            ) {
                applyPlayerOrientationMode(
                    mode = PlayerOrientationMode.LANDSCAPE,
                    fromAutoFullscreen = true
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                // If already outside freeform desktop windowing, hide system bars immediately too
                if (!isInMultiWindowMode) {
                    applySystemBarsImmersive(true)
                }
                lifecycleScope.launch {
                    val result = requestPlatformFullscreenMode(enter = true)
                    if (!wantsImmersiveFullscreen) return@launch
                    if (result.isSuccess || !isInMultiWindowMode) {
                        isFullscreenState = true
                        applySystemBarsImmersive(true)
                        window.decorView.postDelayed(
                            {
                                if (wantsImmersiveFullscreen && !isInMultiWindowMode) {
                                    applySystemBarsImmersive(true)
                                }
                                scheduleVideoSurfaceSettle()
                            },
                            160L
                        )
                    } else {
                        wantsImmersiveFullscreen = false
                        isFullscreenState = false
                    }
                }
            } else {
                applySystemBarsImmersive(true)
            }
            scheduleVideoSurfaceSettle()
        } else {
            wantsImmersiveFullscreen = false
            isFullscreenState = false
            if (autoRotatedByFullscreen) {
                applyPlayerOrientationMode(PlayerOrientationMode.AUTO)
            }
            applySystemBarsImmersive(false)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                lifecycleScope.launch {
                    requestPlatformFullscreenMode(enter = false)
                    if (!wantsImmersiveFullscreen) {
                        applySystemBarsImmersive(false)
                    }
                    scheduleVideoSurfaceSettle()
                }
            }
            scheduleVideoSurfaceSettle()
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private suspend fun requestPlatformFullscreenMode(enter: Boolean): Result<Unit> = runCatching {
        val requestCode = if (enter) {
            FULLSCREEN_MODE_REQUEST_ENTER
        } else {
            FULLSCREEN_MODE_REQUEST_EXIT
        }
        val outcome = suspendCancellableCoroutine { continuation ->
            requestFullscreenMode(requestCode, continuation.asOutcomeReceiver())
        }
        @Suppress("UNUSED_VARIABLE")
        val ignored = outcome
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarsImmersive(enable: Boolean) {
        val isArcContainer = packageManager.hasSystemFeature("org.chromium.arc") ||
            packageManager.hasSystemFeature("org.chromium.arc.device_management")
        val useLegacyWindowFlags =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM || isArcContainer

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (enable) {
            if (useLegacyWindowFlags) {
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
            }
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            if (useLegacyWindowFlags) {
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    )
            }
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        private const val ACTION_PIP_CONTROL = "app.lumina.player.action.PIP_CONTROL"
        private const val EXTRA_PIP_ACTION = "extra_pip_action"
        private const val PIP_ACTION_REWIND = 1
        private const val PIP_ACTION_PLAY_PAUSE = 2
        private const val PIP_ACTION_FORWARD = 3

        internal fun computeClampedPipAspectRatio(
            videoWidth: Int,
            videoHeight: Int
        ): Pair<Int, Int> {
            return if (videoWidth > 0 && videoHeight > 0) {
                val rawRatio = videoWidth.toFloat() / videoHeight.toFloat()
                val clamped = rawRatio.coerceIn(0.42f, 2.38f)
                (clamped * 1000f).roundToInt().coerceIn(420, 2380) to 1000
            } else {
                16 to 9
            }
        }

        internal fun sanitizePipSourceBounds(
            bounds: PipSourceBounds?,
            windowWidth: Int,
            windowHeight: Int,
            aspectRatioNum: Int = 0,
            aspectRatioDen: Int = 0
        ): PipSourceBounds? {
            val raw = bounds ?: return null
            if (!raw.isValid) return null
            val clamped = if (windowWidth > 0 && windowHeight > 0) {
                PipSourceBounds(
                    left = raw.left.coerceIn(0, windowWidth),
                    top = raw.top.coerceIn(0, windowHeight),
                    right = raw.right.coerceIn(0, windowWidth),
                    bottom = raw.bottom.coerceIn(0, windowHeight)
                )
            } else {
                raw
            }
            if (!clamped.isValid) return null

            if (aspectRatioNum <= 0 || aspectRatioDen <= 0) {
                return clamped
            }

            val cw = clamped.width
            val ch = clamped.height
            val targetRatio = aspectRatioNum.toDouble() / aspectRatioDen.toDouble()
            val containerRatio = cw.toDouble() / ch.toDouble()
            val fittedW: Int
            val fittedH: Int
            if (containerRatio > targetRatio) {
                fittedH = ch
                fittedW = (ch * targetRatio).roundToInt().coerceIn(1, cw)
            } else {
                fittedW = cw
                fittedH = (cw / targetRatio).roundToInt().coerceIn(1, ch)
            }
            val fittedLeft = clamped.left + (cw - fittedW) / 2
            val fittedTop = clamped.top + (ch - fittedH) / 2
            return PipSourceBounds(
                left = fittedLeft,
                top = fittedTop,
                right = fittedLeft + fittedW,
                bottom = fittedTop + fittedH
            ).takeIf { it.isValid }
        }

        internal fun shouldAutoRotateToLandscapeOnFullscreen(
            enteringFullscreen: Boolean,
            currentOrientationMode: PlayerOrientationMode,
            isCurrentlyLandscape: Boolean,
            isGooglebookOrDesktop: Boolean,
            isTabletopMode: Boolean,
            isInMultiWindowMode: Boolean,
            videoWidth: Int,
            videoHeight: Int
        ): Boolean {
            if (!enteringFullscreen) return false
            if (currentOrientationMode != PlayerOrientationMode.AUTO) return false
            if (isCurrentlyLandscape) return false
            if (isGooglebookOrDesktop || isTabletopMode || isInMultiWindowMode) return false
            return videoWidth > 0 && videoHeight > 0 && videoWidth > videoHeight
        }
    }
}


