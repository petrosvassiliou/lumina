package app.lumina.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Rational
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.lumina.player.data.preferences.LuminaPreferences
import app.lumina.player.data.preferences.UserPreferences
import app.lumina.player.player.engine.LuminaPlayerEngine
import app.lumina.player.player.model.MediaQueueItem
import app.lumina.player.ui.adaptive.rememberLuminaAdaptiveEnvironment
import app.lumina.player.ui.library.LuminaLibraryScreen
import app.lumina.player.ui.library.LuminaLibraryViewModel
import app.lumina.player.ui.player.LuminaPlayerScreen
import app.lumina.player.ui.player.LuminaPlayerViewModel
import app.lumina.player.ui.theme.LuminaTheme
import app.lumina.player.update.LuminaUpdateManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
 * - Googlebook OS freeform multi-window resizing, native desktop <-> device fullscreen
 *   transitions (`Activity.requestFullscreenMode`), and Always-on-Top floating mini-player mode
 * - Android Picture-in-Picture (PiP) with dynamic video aspect ratio & `RemoteAction` controls
 * - Per-app multi-language switching via `AppCompatActivity` + `locales_config.xml`
 * - Official Google Play Protect compliant Automatic & Manual In-App Updates (`LuminaUpdateManager`)
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var playerEngine: LuminaPlayerEngine

    @Inject
    lateinit var updateManager: LuminaUpdateManager

    @Inject
    lateinit var preferences: LuminaPreferences

    private var updateFlowLauncher: ActivityResultLauncher<IntentSenderRequest>? = null

    private val pendingIntentDestination = MutableStateFlow<LuminaRootDestination?>(null)
    private var currentActiveDestination: LuminaRootDestination = LuminaRootDestination.LIBRARY
    private var isSystemPipState by mutableStateOf(false)
    private var isFullscreenState by mutableStateOf(false)
    private var wantsImmersiveFullscreen: Boolean = false
    private var lastFullscreenRequestElapsedMs: Long = 0L
    private var isGooglebookMiniState by mutableStateOf(false)
    private var activeSeekSeconds: Int = 10

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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

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

                var activeDestination by rememberSaveable {
                    mutableStateOf(
                        if (playerEngine.state.value.currentMedia != null) {
                            LuminaRootDestination.PLAYER
                        } else {
                            LuminaRootDestination.LIBRARY
                        }
                    )
                }

                LaunchedEffect(userPrefs.doubleTapSeekSeconds) {
                    activeSeekSeconds = userPrefs.doubleTapSeekSeconds.coerceAtLeast(5)
                }

                LaunchedEffect(intentDestination) {
                    val requested = intentDestination ?: return@LaunchedEffect
                    activeDestination = requested
                    pendingIntentDestination.value = null
                }

                LaunchedEffect(activeDestination) {
                    currentActiveDestination = activeDestination
                }

                // Exit floating mini-player automatically if disabled in Settings
                LaunchedEffect(userPrefs.googlebookAlwaysOnTopMiniPlayer) {
                    if (!userPrefs.googlebookAlwaysOnTopMiniPlayer && isGooglebookMiniState) {
                        isGooglebookMiniState = false
                        playerEngine.setPipOrMiniPlayerMode(false)
                    }
                }

                // Keep PiP aspect ratio, RemoteAction buttons & Android 12+ auto-PiP synchronized
                LaunchedEffect(
                    activeDestination,
                    isSystemPipState,
                    engineState.isPlaying,
                    engineState.videoWidth,
                    engineState.videoHeight,
                    userPrefs.autoEnterPipOnBackground,
                    userPrefs.doubleTapSeekSeconds,
                    adaptiveEnv.isGooglebookOrDesktop
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
                                    autoEnter = shouldAutoPip
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
                                    playerEngine.setPipOrMiniPlayerMode(nextMini)
                                }
                            },
                            onNavigateBack = {
                                if (isFullscreenState) {
                                    setImmersiveFullscreen(false)
                                }
                                runCatching {
                                    val lp = window.attributes
                                    if (lp.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                                        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                                        window.attributes = lp
                                    }
                                }
                                isGooglebookMiniState = false
                                playerEngine.stop()
                                activeDestination = LuminaRootDestination.LIBRARY
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipControlReceiver) }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingMediaIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            updateManager.resumePendingUpdatesIfNeeded(this@MainActivity, updateFlowLauncher)
            updateManager.checkAutomaticUpdatesIfDue(updateFlowLauncher)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            currentActiveDestination == LuminaRootDestination.PLAYER &&
            playerEngine.state.value.isPlaying
        ) {
            lifecycleScope.launch {
                val prefs = preferences.userPreferencesFlow.first()
                if (prefs.autoEnterPipOnBackground) {
                    enterLuminaPictureInPicture()
                }
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isSystemPipState = isInPictureInPictureMode
        playerEngine.setPipOrMiniPlayerMode(isInPictureInPictureMode || isGooglebookMiniState)
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
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && wantsImmersiveFullscreen && !isInMultiWindowMode) {
            applySystemBarsImmersive(true)
        }
    }

    private fun handleIncomingMediaIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val dataUri = intent.data ?: return

        val scheme = dataUri.scheme?.lowercase()
        val isNetwork = scheme in setOf("http", "https", "rtsp", "rtmp", "mms", "smb", "ftp")
        val resolvedDisplayName = if (scheme == "content") {
            runCatching {
                contentResolver.query(
                    dataUri,
                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else {
                        null
                    }
                }
            }.getOrNull()
        } else {
            null
        }
        val rawTitle = resolvedDisplayName?.takeIf { it.isNotBlank() }
            ?: dataUri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?: dataUri.toString()
        val cleanTitle = rawTitle.substringBeforeLast('.', rawTitle).ifBlank { rawTitle }

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
        val snapshot = playerEngine.state.value
        val params = buildPipParams(
            videoWidth = snapshot.videoWidth,
            videoHeight = snapshot.videoHeight,
            isPlaying = snapshot.isPlaying,
            autoEnter = false
        )
        runCatching {
            enterPictureInPictureMode(params)
        }
    }

    private fun buildPipParams(
        videoWidth: Int,
        videoHeight: Int,
        isPlaying: Boolean,
        autoEnter: Boolean
    ): PictureInPictureParams {
        val ratio = if (videoWidth > 0 && videoHeight > 0) {
            val rawRatio = videoWidth.toFloat() / videoHeight.toFloat()
            val clamped = rawRatio.coerceIn(0.42f, 2.38f)
            Rational((clamped * 1000).toInt(), 1000)
        } else {
            Rational(16, 9)
        }

        val rewindAction = RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_media_rewind),
            getString(R.string.player_previous),
            getString(R.string.player_previous),
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
            getString(R.string.player_next),
            getString(R.string.player_next),
            buildPipControlPendingIntent(PIP_ACTION_FORWARD)
        )

        return PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .setActions(listOf(rewindAction, playPauseAction, forwardAction))
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setSeamlessResizeEnabled(true)
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

    private fun setImmersiveFullscreen(enable: Boolean) {
        lastFullscreenRequestElapsedMs = SystemClock.elapsedRealtime()
        if (enable) {
            if (isGooglebookMiniState) {
                isGooglebookMiniState = false
                playerEngine.setPipOrMiniPlayerMode(false)
            }
            wantsImmersiveFullscreen = true
            isFullscreenState = true

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
        } else {
            wantsImmersiveFullscreen = false
            isFullscreenState = false
            applySystemBarsImmersive(false)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                lifecycleScope.launch {
                    requestPlatformFullscreenMode(enter = false)
                    if (!wantsImmersiveFullscreen) {
                        applySystemBarsImmersive(false)
                    }
                }
            }
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
    }
}


