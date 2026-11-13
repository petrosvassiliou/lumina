package app.lumina.player.player.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.lumina.player.data.local.dao.MediaDao
import app.lumina.player.data.local.dao.PlaybackStateDao
import app.lumina.player.data.local.entity.PlaybackStateEntity
import app.lumina.player.data.preferences.LuminaPreferences
import app.lumina.player.data.preferences.UserPreferences
import app.lumina.player.di.ApplicationScope
import app.lumina.player.di.IoDispatcher
import app.lumina.player.di.MainDispatcher
import app.lumina.player.player.hdr.LuminaHdrController
import app.lumina.player.player.model.ABRepeatState
import app.lumina.player.player.model.AspectRatioMode
import app.lumina.player.player.model.AudioTrackInfo
import app.lumina.player.player.model.ChapterInfo
import app.lumina.player.player.model.EqualizerBand
import app.lumina.player.player.model.EqualizerState
import app.lumina.player.player.model.HdrFormat
import app.lumina.player.player.model.LuminaPlayerState
import app.lumina.player.player.model.MediaQueueItem
import app.lumina.player.player.model.PlaybackStatus
import app.lumina.player.player.model.SleepTimerMode
import app.lumina.player.player.model.SleepTimerState
import app.lumina.player.player.model.SubtitleTrackInfo
import app.lumina.player.player.service.LuminaPlaybackService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IVLCVout
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Core Universal HDR Media Engine powered by libVLC 4.0+.
 *
 * Designed as a lifecycle-safe singleton so playback continues uninterrupted across:
 * - Foldable posture transitions (Flat <-> Half-Opened Lumina Tabletop Mode)
 * - Googlebook OS freeform window resizing, snapping, and Always-on-Top mini-player transitions
 * - Mobile Picture-in-Picture (PiP) mode & Foreground `MediaSession` background audio playback
 */
@Singleton
class LuminaPlayerEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val hdrController: LuminaHdrController,
    private val playbackStateDao: PlaybackStateDao,
    private val mediaDao: MediaDao,
    private val preferences: LuminaPreferences,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) : DefaultLifecycleObserver {

    private val _state = MutableStateFlow(LuminaPlayerState())
    val state: StateFlow<LuminaPlayerState> = _state.asStateFlow()

    private val userPreferences: StateFlow<UserPreferences> = preferences.userPreferencesFlow
        .stateIn(appScope, SharingStarted.Eagerly, UserPreferences())

    private val audioManager: AudioManager? = appContext.getSystemService()
    private var audioFocusRequest: AudioFocusRequest? = null
    private var resumeOnAudioFocusGain: Boolean = false
    private var isAudioDucked: Boolean = false
    private var isNoisyReceiverRegistered: Boolean = false
    private var sleepTimerJob: Job? = null

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        appScope.launch(mainDispatcher) {
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    resumeOnAudioFocusGain = false
                    restoreDuckedVolumeIfNeeded()
                    pause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    resumeOnAudioFocusGain = mediaPlayer?.isPlaying == true
                    restoreDuckedVolumeIfNeeded()
                    pause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    if (mediaPlayer?.isPlaying == true && !_state.value.isMuted) {
                        isAudioDucked = true
                        mediaPlayer?.volume = (_state.value.volume * 0.30f).toInt().coerceAtLeast(10)
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    restoreDuckedVolumeIfNeeded()
                    if (resumeOnAudioFocusGain) {
                        resumeOnAudioFocusGain = false
                        play()
                    }
                }
            }
        }
    }

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                resumeOnAudioFocusGain = false
                pause()
            }
        }
    }

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var activeEqualizer: MediaPlayer.Equalizer? = null
    private val fdLock = Any()
    private var activeFileDescriptor: ParcelFileDescriptor? = null
    private var attachedLayoutRef: WeakReference<VLCVideoLayout>? = null
    private val surfaceSignalLock = Any()
    @Volatile
    private var areNativeSurfacesCreated: Boolean = false
    private var surfaceReadyDeferred: CompletableDeferred<Unit> = CompletableDeferred()
    @Volatile
    private var activeVoutCount: Int = 0
    private var sessionDecoderOverride: ResolvedVlcDecoderMode? = null
    private var activeResolvedDecoderMode: ResolvedVlcDecoderMode =
        ResolvedVlcDecoderMode.HARDWARE_DIRECT_RENDERING
    private var pendingFallbackResumeTimeMs: Long? = null
    private var activeSmbUsername: String? = null
    private var activeSmbPassword: String? = null
    private var activeSmbDomain: String? = null
    private var openMediaJob: Job? = null
    private var blankVoutWatchdogJob: Job? = null
    private val playbackSessionId = AtomicLong(0L)
    @Volatile
    private var isOpeningMedia: Boolean = false
    private var preMuteVolume: Int = 100
    private var pendingResumeState: PlaybackStateEntity? = null
    private var hasAppliedInitialResumeSeek: Boolean = false
    private var lastPersistedTimestampMs: Long = 0L
    private val isReleased = AtomicBoolean(false)
    private val isArcEnvironment: Boolean by lazy { LuminaDecoderPolicy.isArcContainer(appContext) }

    private val vlcVoutCallback = object : IVLCVout.Callback {
        override fun onSurfacesCreated(vlcVout: IVLCVout?) {
            markNativeSurfacesCreated(true)
            appScope.launch(mainDispatcher) {
                runCatching {
                    mediaPlayer?.setVideoTrackEnabled(true)
                    onSurfaceBoundsChanged()
                }
            }
        }

        override fun onSurfacesDestroyed(vlcVout: IVLCVout?) {
            markNativeSurfacesCreated(false)
        }
    }

    init {
        seedInitialEqualizerState(userPreferences.value)
        observePreferenceUpdates()
        // Warm up native LibVLC asynchronously after Activity launch frame completes
        appScope.launch(mainDispatcher) {
            delay(120L)
            initializeEngineIfNeeded()
        }
    }

    private fun markNativeSurfacesCreated(created: Boolean) {
        synchronized(surfaceSignalLock) {
            areNativeSurfacesCreated = created
            if (created) {
                if (!surfaceReadyDeferred.isCompleted) {
                    surfaceReadyDeferred.complete(Unit)
                }
            } else if (surfaceReadyDeferred.isCompleted) {
                surfaceReadyDeferred = CompletableDeferred()
            }
        }
    }

    private suspend fun awaitVideoSurfaceReadyIfNeeded(item: MediaQueueItem) {
        if (isLikelyAudioOnlyMedia(item)) return
        val alreadyReady = areNativeSurfacesCreated &&
            runCatching { mediaPlayer?.vlcVout?.areViewsAttached() == true }.getOrDefault(false)
        if (alreadyReady) return

        val deferred = synchronized(surfaceSignalLock) { surfaceReadyDeferred }
        withTimeoutOrNull(SURFACE_READY_WAIT_TIMEOUT_MS) {
            deferred.await()
        }
    }

    private fun isLikelyAudioOnlyMedia(item: MediaQueueItem): Boolean {
        if (item.mimeType?.startsWith("audio/", ignoreCase = true) == true) return true
        val nameLower = (item.title.ifBlank { item.uri.lastPathSegment.orEmpty() }).lowercase(Locale.ROOT)
        val ext = nameLower.substringAfterLast('.', "")
        return ext in AUDIO_ONLY_EXTENSIONS
    }

    /**
     * Returns the initialized `LibVLC` instance for auxiliary subsystems such as `LuminaLanDiscoveryManager`.
     */
    @Synchronized
    fun requireLibVlc(): LibVLC {
        initializeEngineIfNeeded()
        return checkNotNull(libVlc) { "LibVLC native engine unavailable on this device" }
    }

    @Synchronized
    private fun initializeEngineIfNeeded(): Boolean {
        if (libVlc != null && mediaPlayer != null && !isReleased.get()) return true
        isReleased.set(false)

        val prefs = userPreferences.value
        val fullArgs = buildLibVlcOptions(prefs)
        val audioOutArg = resolveAudioOutputOption()

        // Multi-tier fault-tolerant LibVLC initialization so an unsupported option or OEM quirk can NEVER crash startup
        val instanceResult = runCatching {
            LibVLC(appContext, ArrayList(fullArgs))
        }.recoverCatching {
            LibVLC(appContext, arrayListOf(audioOutArg))
        }.recoverCatching {
            LibVLC(appContext)
        }

        val instance = instanceResult.getOrElse { throwable ->
            _state.update {
                it.copy(
                    status = PlaybackStatus.ERROR,
                    isPlaying = false,
                    errorMessage = throwable.localizedMessage
                        ?: "Unable to initialize native media engine for this device architecture."
                )
            }
            return false
        }

        val player = runCatching {
            MediaPlayer(instance).also { mp ->
                mp.setEventListener(vlcEventListener)
                runCatching { mp.vlcVout.addCallback(vlcVoutCallback) }
            }
        }.getOrElse { throwable ->
            runCatching { instance.release() }
            _state.update {
                it.copy(
                    status = PlaybackStatus.ERROR,
                    isPlaying = false,
                    errorMessage = throwable.localizedMessage ?: "Unable to create native MediaPlayer."
                )
            }
            return false
        }

        libVlc = instance
        mediaPlayer = player

        runCatching { initializeEqualizerState(prefs) }
        return true
    }

    private fun resolveAudioOutputOption(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            "--aout=aaudio"
        } else {
            "--aout=android_audiotrack"
        }
    }

    private fun buildLibVlcOptions(prefs: UserPreferences): List<String> {
        val audioOutputArg = resolveAudioOutputOption()
        val networkCache = prefs.networkCachingMs.coerceIn(300, 10_000)

        return buildList {
            add(audioOutputArg)
            add("--audio-time-stretch")
            add("--avcodec-skiploopfilter=0")
            add("--stats")
            add("--network-caching=$networkCache")
            add("--file-caching=1000")
            add("--live-caching=$networkCache")
            add("--subsdec-encoding=UTF-8")
            add("--sub-autodetect-file")
            add("--sub-text-scale=${prefs.subtitleFontScale.scalePercent}")
            add("--freetype-background-opacity=${prefs.subtitleBackgroundStyle.vlcOpacity}")
            add("--keystore=file_crypt,none")
            addAll(hdrController.buildLibVlcHdrArguments(prefs.sdrToneMappingAlgorithm))
        }
    }

    private fun observePreferenceUpdates() {
        appScope.launch(mainDispatcher) {
            userPreferences.collect { prefs ->
                val currentLayout = attachedLayoutRef?.get()
                if (currentLayout != null) {
                    val updatedHdr = hdrController.applyHdrPipelineToSurface(
                        videoLayout = currentLayout,
                        sourceFormat = _state.value.hdrStatus.sourceFormat,
                        hdrPassthroughEnabled = prefs.hdrPassthroughEnabled,
                        toneMappingAlgorithm = prefs.sdrToneMappingAlgorithm
                    )
                    _state.update { it.copy(hdrStatus = updatedHdr) }
                }
            }
        }
    }

    // =========================================================================
    // Surface Attachment & Multi-Window / Foldable Continuity
    // =========================================================================

    /**
     * Attaches a `VLCVideoLayout` backed by hardware `SurfaceView`s (video + subtitle overlay)
     * and activates the True HDR pipeline on the surface & window.
     */
    fun attachVideoSurface(videoLayout: VLCVideoLayout) {
        if (!videoLayout.isAttachedToWindow) return
        if (!initializeEngineIfNeeded()) return
        val player = mediaPlayer ?: return

        val previousLayout = attachedLayoutRef?.get()
        if (previousLayout === videoLayout) {
            val voutAttached = runCatching { player.vlcVout.areViewsAttached() }.getOrDefault(true)
            if (voutAttached) {
                syncSurfaceCreatedStateFromLayout(videoLayout)
                onSurfaceBoundsChanged(videoLayout)
                return
            }
        }

        if (previousLayout != null && previousLayout !== videoLayout) {
            markNativeSurfacesCreated(false)
            runCatching { player.detachViews() }
        }

        // textureView = false forces dedicated hardware SurfaceView required for HDR10 / HLG / Dolby Vision
        runCatching {
            player.attachViews(
                videoLayout,
                /* dm = */ null,
                /* subtitles = */ true,
                /* textureView = */ false
            )
            attachedLayoutRef = WeakReference(videoLayout)
            runCatching { player.setVideoTrackEnabled(true) }
            syncSurfaceCreatedStateFromLayout(videoLayout)

            applyScaleAndAspectRatio(_state.value.aspectRatioMode)
            refreshHdrSurfacePipeline()
        }
    }

    private fun syncSurfaceCreatedStateFromLayout(videoLayout: VLCVideoLayout) {
        val surfaceValid = runCatching {
            with(hdrController) { videoLayout.findPrimaryVideoSurfaceView() }
                ?.holder
                ?.surface
                ?.isValid == true
        }.getOrDefault(false)
        if (surfaceValid) {
            markNativeSurfacesCreated(true)
        }
    }

    /**
     * Safely detaches the `VLCVideoLayout` only if it is still the active bound surface.
     * Prevents race conditions during Compose transitions between Phone, Tabletop, and Desktop layouts.
     */
    fun detachVideoSurface(videoLayout: VLCVideoLayout) {
        val activeLayout = attachedLayoutRef?.get()
        if (activeLayout === videoLayout) {
            markNativeSurfacesCreated(false)
            runCatching { mediaPlayer?.detachViews() }
            hdrController.resetWindowHdrMode(videoLayout.context)
            attachedLayoutRef = null
        }
    }

    /**
     * Invoked when the window or foldable pane resizes (e.g., Googlebook OS freeform window drag
     * or Foldable Tabletop hinge transition). Updates surface geometry seamlessly without pausing.
     */
    fun onSurfaceBoundsChanged(videoLayout: VLCVideoLayout? = attachedLayoutRef?.get()) {
        val player = mediaPlayer ?: return
        if (videoLayout != null && videoLayout.isAttachedToWindow && attachedLayoutRef?.get() === videoLayout) {
            runCatching {
                player.updateVideoSurfaces()
                applyScaleAndAspectRatio(_state.value.aspectRatioMode)
            }
            refreshHdrSurfacePipeline()
        }
    }

    fun setPipOrMiniPlayerMode(inPipOrMiniPlayer: Boolean) {
        _state.update { it.copy(isInPipOrMiniPlayer = inPipOrMiniPlayer) }
        onSurfaceBoundsChanged()
    }

    // =========================================================================
    // Media & Queue Loading (Local Scoped Storage + SMB/FTP/UPnP/HLS/RTSP)
    // =========================================================================

    fun loadSingleMedia(
        item: MediaQueueItem,
        startImmediately: Boolean = true,
        smbUsername: String? = null,
        smbPassword: String? = null,
        smbDomain: String? = null
    ) {
        loadQueue(
            items = listOf(item),
            startIndex = 0,
            startImmediately = startImmediately,
            smbUsername = smbUsername,
            smbPassword = smbPassword,
            smbDomain = smbDomain
        )
    }

    fun loadQueue(
        items: List<MediaQueueItem>,
        startIndex: Int = 0,
        startImmediately: Boolean = true,
        smbUsername: String? = null,
        smbPassword: String? = null,
        smbDomain: String? = null
    ) {
        if (items.isEmpty()) return
        val boundedIndex = startIndex.coerceIn(0, items.lastIndex)
        sessionDecoderOverride = null
        pendingFallbackResumeTimeMs = null
        activeSmbUsername = smbUsername
        activeSmbPassword = smbPassword
        activeSmbDomain = smbDomain
        _state.update {
            it.copy(
                queue = items,
                currentQueueIndex = boundedIndex,
                errorMessage = null
            )
        }
        openQueueIndexInternal(
            index = boundedIndex,
            startImmediately = startImmediately,
            smbUsername = smbUsername,
            smbPassword = smbPassword,
            smbDomain = smbDomain
        )
    }

    fun playQueueIndex(index: Int) {
        val queue = _state.value.queue
        if (index !in queue.indices) return
        persistCurrentPlaybackStateAsync()
        sessionDecoderOverride = null
        pendingFallbackResumeTimeMs = null
        openQueueIndexInternal(
            index = index,
            startImmediately = true,
            smbUsername = activeSmbUsername,
            smbPassword = activeSmbPassword,
            smbDomain = activeSmbDomain
        )
    }

    fun retryCurrentMedia() {
        val currentIdx = _state.value.currentQueueIndex
        if (currentIdx in _state.value.queue.indices) {
            sessionDecoderOverride = null
            pendingFallbackResumeTimeMs = null
            openQueueIndexInternal(
                index = currentIdx,
                startImmediately = true,
                smbUsername = activeSmbUsername,
                smbPassword = activeSmbPassword,
                smbDomain = activeSmbDomain
            )
        }
    }

    private fun triggerDecoderFallbackRetry(
        targetMode: ResolvedVlcDecoderMode,
        resumeTimeMs: Long
    ) {
        val currentIdx = _state.value.currentQueueIndex
        if (currentIdx !in _state.value.queue.indices) return
        sessionDecoderOverride = targetMode
        pendingFallbackResumeTimeMs = resumeTimeMs.takeIf { it > 250L }
        openQueueIndexInternal(
            index = currentIdx,
            startImmediately = true,
            smbUsername = activeSmbUsername,
            smbPassword = activeSmbPassword,
            smbDomain = activeSmbDomain,
            preserveFallbackState = true
        )
    }

    fun playNext(): Boolean {
        val nextIndex = _state.value.currentQueueIndex + 1
        if (nextIndex in _state.value.queue.indices) {
            playQueueIndex(nextIndex)
            return true
        }
        return false
    }

    fun playPrevious(): Boolean {
        if (_state.value.currentTimeMs > 4_000L) {
            seekTo(0L, fast = false)
            return true
        }
        val prevIndex = _state.value.currentQueueIndex - 1
        if (prevIndex in _state.value.queue.indices) {
            playQueueIndex(prevIndex)
            return true
        }
        return false
    }

    private data class ConfiguredVlcMediaBundle(
        val media: Media,
        val resolvedDecoderMode: ResolvedVlcDecoderMode,
        val probedInfo: ProbedVideoStreamInfo?
    )

    private fun openQueueIndexInternal(
        index: Int,
        startImmediately: Boolean,
        smbUsername: String? = null,
        smbPassword: String? = null,
        smbDomain: String? = null,
        preserveFallbackState: Boolean = false
    ) {
        if (!initializeEngineIfNeeded()) return
        val vlc = libVlc ?: return
        val player = mediaPlayer ?: return
        val item = _state.value.queue.getOrNull(index) ?: return

        if (!preserveFallbackState) {
            sessionDecoderOverride = null
            pendingFallbackResumeTimeMs = null
        }

        val sessionId = playbackSessionId.incrementAndGet()
        isOpeningMedia = true
        activeVoutCount = 0
        blankVoutWatchdogJob?.cancel()
        openMediaJob?.cancel()

        openMediaJob = appScope.launch(mainDispatcher) {
            // Stop previous playback first, THEN close previous ParcelFileDescriptor so libVLC never reads a closed fd
            runCatching { player.stop() }
            closeActiveFileDescriptor()

            if (sessionId != playbackSessionId.get()) return@launch

            val prefs = userPreferences.value
            val savedState = runCatching {
                withContext(ioDispatcher) {
                    mediaDao.markPlayed(item.uri.toString())
                    playbackStateDao.getPlaybackState(item.uri.toString())
                }
            }.getOrNull()

            if (sessionId != playbackSessionId.get()) return@launch

            pendingResumeState = savedState
            hasAppliedInitialResumeSeek = false

            val restoredSpeed = (savedState?.playbackSpeed ?: prefs.defaultPlaybackSpeed).coerceIn(0.25f, 4.0f)
            val restoredAspect = AspectRatioMode.fromNameOrDefault(savedState?.aspectRatioMode)
            val initialHdrFormat = item.hdrHint ?: HdrFormat.SDR

            _state.update { prev ->
                prev.copy(
                    currentQueueIndex = index,
                    status = PlaybackStatus.OPENING,
                    isPlaying = false,
                    bufferingPercent = 0f,
                    currentTimeMs = pendingFallbackResumeTimeMs ?: 0L,
                    durationMs = item.durationMs.coerceAtLeast(0L),
                    progress = 0f,
                    playbackSpeed = restoredSpeed,
                    aspectRatioMode = restoredAspect,
                    audioTracks = emptyList(),
                    subtitleTracks = emptyList(),
                    chapters = emptyList(),
                    currentChapterIndex = -1,
                    audioDelayMs = savedState?.audioDelayMs ?: 0L,
                    subtitleDelayMs = savedState?.subtitleDelayMs ?: 0L,
                    externalSubtitleUri = savedState?.externalSubtitleUri?.let(Uri::parse),
                    abRepeat = if (preserveFallbackState) prev.abRepeat else ABRepeatState(),
                    errorMessage = null,
                    hdrStatus = hdrController.applyHdrPipelineToSurface(
                        videoLayout = attachedLayoutRef?.get(),
                        sourceFormat = initialHdrFormat,
                        hdrPassthroughEnabled = prefs.hdrPassthroughEnabled,
                        toneMappingAlgorithm = prefs.sdrToneMappingAlgorithm
                    )
                )
            }

            val currentOverride = sessionDecoderOverride
            val mediaResult = withContext(ioDispatcher) {
                createConfiguredVlcMedia(
                    vlc = vlc,
                    uri = item.uri,
                    hdrHint = item.hdrHint,
                    prefs = prefs,
                    sessionOverride = currentOverride,
                    smbUsername = smbUsername,
                    smbPassword = smbPassword,
                    smbDomain = smbDomain
                )
            }

            if (sessionId != playbackSessionId.get()) {
                mediaResult.getOrNull()?.let { runCatching { it.media.release() } }
                return@launch
            }

            // Ensure VLCVideoLayout's hardware SurfaceView has attached and created its ANativeWindow
            // BEFORE MediaPlayer.play() spawns libVLC 4.0's AImageReader / ASurfaceControl video output!
            if (startImmediately) {
                awaitVideoSurfaceReadyIfNeeded(item)
                if (sessionId != playbackSessionId.get()) {
                    mediaResult.getOrNull()?.let { runCatching { it.media.release() } }
                    return@launch
                }
            }

            mediaResult.fold(
                onSuccess = { bundle ->
                    activeResolvedDecoderMode = bundle.resolvedDecoderMode
                    val probed = bundle.probedInfo
                    if (probed?.isHdrStream == true && initialHdrFormat == HdrFormat.SDR) {
                        val preflightHdr = when {
                            probed.mimeType.equals(ProbedVideoStreamInfo.MIME_DOLBY_VISION, ignoreCase = true) ->
                                HdrFormat.DOLBY_VISION
                            probed.colorTransfer == ProbedVideoStreamInfo.COLOR_TRANSFER_HLG ->
                                HdrFormat.HLG
                            else -> HdrFormat.HDR10
                        }
                        val updatedHdr = hdrController.applyHdrPipelineToSurface(
                            videoLayout = attachedLayoutRef?.get(),
                            sourceFormat = preflightHdr,
                            hdrPassthroughEnabled = prefs.hdrPassthroughEnabled,
                            toneMappingAlgorithm = prefs.sdrToneMappingAlgorithm
                        )
                        _state.update { it.copy(hdrStatus = updatedHdr) }
                    }
                    val media = bundle.media
                    val playResult = runCatching {
                        try {
                            player.media = media
                        } finally {
                            runCatching { media.release() }
                        }

                        if (attachedLayoutRef?.get()?.isAttachedToWindow == true) {
                            runCatching { player.setVideoTrackEnabled(true) }
                        }
                        runCatching { player.rate = restoredSpeed }
                        applyScaleAndAspectRatio(restoredAspect)

                        // Re-attach saved external subtitle file if present
                        savedState?.externalSubtitleUri?.let { subUriStr ->
                            loadExternalSubtitle(Uri.parse(subUriStr), autoSelect = true)
                        }

                        if (startImmediately) {
                            requestAudioFocusAndRegisterNoisyReceiver()
                            LuminaPlaybackService.syncPlaybackForegroundState(appContext, isPlaying = true)
                            player.play()
                        } else {
                            isOpeningMedia = false
                        }
                    }

                    playResult.onFailure { throwable ->
                        isOpeningMedia = false
                        _state.update {
                            it.copy(
                                status = PlaybackStatus.ERROR,
                                isPlaying = false,
                                errorMessage = throwable.localizedMessage ?: "Unable to start media playback"
                            )
                        }
                    }
                },
                onFailure = { throwable ->
                    isOpeningMedia = false
                    _state.update {
                        it.copy(
                            status = PlaybackStatus.ERROR,
                            isPlaying = false,
                            errorMessage = throwable.localizedMessage ?: "Unable to open media resource"
                        )
                    }
                }
            )
        }
    }

    private fun createConfiguredVlcMedia(
        vlc: LibVLC,
        uri: Uri,
        hdrHint: HdrFormat?,
        prefs: UserPreferences,
        sessionOverride: ResolvedVlcDecoderMode?,
        smbUsername: String?,
        smbPassword: String?,
        smbDomain: String?
    ): Result<ConfiguredVlcMediaBundle> = runCatching {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val probedInfo = if (sessionOverride == null) {
            LuminaDecoderPolicy.probeLocalVideoStream(appContext, uri)
        } else {
            null
        }

        val resolvedDecoderMode = LuminaDecoderPolicy.resolveDecoderMode(
            userPreference = prefs.hardwareAccelerationMode,
            sessionFallbackOverride = sessionOverride,
            probedInfo = probedInfo,
            hdrHint = hdrHint,
            isArcOrDesktopContainer = isArcEnvironment
        )

        val media: Media = if (scheme == "content") {
            val openedViaFd = runCatching {
                appContext.contentResolver.openFileDescriptor(uri, "r")?.let { pfd ->
                    replaceActiveFileDescriptor(pfd)
                    Media(vlc, pfd.fileDescriptor)
                }
            }.getOrNull()
            openedViaFd ?: Media(vlc, uri)
        } else {
            Media(vlc, uri)
        }

        // Configure Hardware / OpenGL / avcodec decoding based on verified device & stream capability
        runCatching {
            LuminaDecoderPolicy.applyToMedia(media, resolvedDecoderMode)

            // Subtitle font scale & background opacity styling
            media.addOption(":sub-text-scale=${prefs.subtitleFontScale.scalePercent}")
            media.addOption(":freetype-background-opacity=${prefs.subtitleBackgroundStyle.vlcOpacity}")

            // Network streaming options (SMB/NAS, FTP, RTSP, HTTP/HLS)
            if (scheme in setOf("smb", "ftp", "rtsp", "http", "https", "mms", "upnp")) {
                media.addOption(":network-caching=${prefs.networkCachingMs.coerceIn(300, 10_000)}")
                if (scheme == "rtsp") {
                    media.addOption(":rtsp-tcp")
                }
                if (scheme == "smb") {
                    if (!smbUsername.isNullOrBlank()) media.addOption(":smb-user=$smbUsername")
                    if (!smbPassword.isNullOrBlank()) media.addOption(":smb-pwd=$smbPassword")
                    if (!smbDomain.isNullOrBlank()) media.addOption(":smb-domain=$smbDomain")
                }
            }
        }

        ConfiguredVlcMediaBundle(
            media = media,
            resolvedDecoderMode = resolvedDecoderMode,
            probedInfo = probedInfo
        )
    }

    // =========================================================================
    // Playback Transport, Audio Focus, Speed, Volume, A-B Repeat & Aspect Ratio
    // =========================================================================

    private fun requestAudioFocusAndRegisterNoisyReceiver(): Boolean {
        if (!isNoisyReceiverRegistered) {
            runCatching {
                ContextCompat.registerReceiver(
                    appContext,
                    becomingNoisyReceiver,
                    IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                isNoisyReceiverRegistered = true
            }
        }

        val manager = audioManager ?: return true
        val req = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()
            .also { audioFocusRequest = it }

        val res = runCatching { manager.requestAudioFocus(req) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        return res != AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }

    private fun abandonAudioFocusAndNoisyReceiver() {
        resumeOnAudioFocusGain = false
        restoreDuckedVolumeIfNeeded()
        if (isNoisyReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(becomingNoisyReceiver) }
            isNoisyReceiverRegistered = false
        }
        audioFocusRequest?.let { req ->
            runCatching { audioManager?.abandonAudioFocusRequest(req) }
        }
    }

    private fun restoreDuckedVolumeIfNeeded() {
        if (isAudioDucked) {
            isAudioDucked = false
            if (!_state.value.isMuted) {
                runCatching { mediaPlayer?.volume = _state.value.volume }
            }
        }
    }

    fun play() {
        val player = mediaPlayer ?: return
        if (!requestAudioFocusAndRegisterNoisyReceiver()) return
        if (_state.value.status == PlaybackStatus.ENDED && _state.value.currentQueueIndex >= 0) {
            openQueueIndexInternal(_state.value.currentQueueIndex, startImmediately = true)
        } else {
            LuminaPlaybackService.syncPlaybackForegroundState(appContext, isPlaying = true)
            runCatching { player.play() }
        }
    }

    fun pause() {
        val player = mediaPlayer ?: return
        if (runCatching { player.isPlaying }.getOrDefault(false)) {
            runCatching { player.pause() }
            persistCurrentPlaybackStateAsync()
        }
    }

    fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (runCatching { player.isPlaying }.getOrDefault(false)) {
            resumeOnAudioFocusGain = false
            pause()
        } else {
            play()
        }
    }

    fun stop() {
        playbackSessionId.incrementAndGet()
        isOpeningMedia = false
        openMediaJob?.cancel()
        openMediaJob = null
        persistCurrentPlaybackStateAsync()
        abandonAudioFocusAndNoisyReceiver()
        runCatching { mediaPlayer?.stop() }
        closeActiveFileDescriptor()
        _state.update {
            it.copy(
                status = PlaybackStatus.IDLE,
                isPlaying = false,
                currentTimeMs = 0L,
                progress = 0f
            )
        }
        LuminaPlaybackService.syncPlaybackForegroundState(appContext, isPlaying = false)
    }

    fun seekTo(positionMs: Long, fast: Boolean = true) {
        val player = mediaPlayer ?: return
        val duration = _state.value.durationMs
        val targetMs = if (duration > 0L) {
            positionMs.coerceIn(0L, duration)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        runCatching { player.setTime(targetMs, fast) }
        val progress = if (duration > 0L) (targetMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
        _state.update {
            it.copy(
                currentTimeMs = targetMs,
                progress = progress
            )
        }
    }

    fun seekRelative(deltaMs: Long) {
        seekTo(_state.value.currentTimeMs + deltaMs, fast = true)
    }

    /**
     * Adjusts playback rate cleanly between 0.25x and 4.0x with pitch-corrected time-stretch.
     */
    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4.0f)
        runCatching { mediaPlayer?.rate = clamped }
        _state.update { it.copy(playbackSpeed = clamped) }
        persistCurrentPlaybackStateAsync()
    }

    /**
     * Adjusts player volume from 0 to 150 (allowing up to 150% software amplification on quiet streams).
     */
    fun setVolume(volumePercent: Int) {
        val clamped = volumePercent.coerceIn(0, 150)
        runCatching { mediaPlayer?.volume = clamped }
        if (clamped > 0) {
            preMuteVolume = clamped
        }
        _state.update {
            it.copy(
                volume = clamped,
                isMuted = clamped == 0
            )
        }
    }

    fun adjustVolumeBy(delta: Int) {
        setVolume(_state.value.volume + delta)
    }

    fun toggleMute() {
        if (_state.value.isMuted || _state.value.volume == 0) {
            val restored = preMuteVolume.takeIf { it > 0 } ?: 100
            setVolume(restored)
        } else {
            preMuteVolume = _state.value.volume
            runCatching { mediaPlayer?.volume = 0 }
            _state.update { it.copy(volume = 0, isMuted = true) }
        }
    }

    fun setAspectRatioMode(mode: AspectRatioMode) {
        applyScaleAndAspectRatio(mode)
        _state.update { it.copy(aspectRatioMode = mode) }
        persistCurrentPlaybackStateAsync()
    }

    fun cycleAspectRatio(): AspectRatioMode {
        val nextMode = _state.value.aspectRatioMode.next()
        setAspectRatioMode(nextMode)
        return nextMode
    }

    private fun applyScaleAndAspectRatio(mode: AspectRatioMode) {
        val player = mediaPlayer ?: return
        runCatching {
            player.aspectRatio = mode.customAspectRatio
            player.videoScale = mode.scaleType
            player.updateVideoSurfaces()
        }
    }

    /**
     * Cycles A-B Repeat state:
     * 1st call -> Marks Point A at current timestamp
     * 2nd call -> Marks Point B at current timestamp (activates loop)
     * 3rd call -> Clears A-B Repeat
     */
    fun stepABRepeat() {
        val now = _state.value.currentTimeMs
        val current = _state.value.abRepeat
        val next = when {
            current.pointAMs == null -> ABRepeatState(pointAMs = now, pointBMs = null)
            current.pointBMs == null && now > current.pointAMs + 500L ->
                ABRepeatState(pointAMs = current.pointAMs, pointBMs = now)
            else -> ABRepeatState()
        }
        _state.update { it.copy(abRepeat = next) }
    }

    fun clearABRepeat() {
        _state.update { it.copy(abRepeat = ABRepeatState()) }
    }

    // =========================================================================
    // Cinema Touch Lock, Sleep Timer & Embedded Chapter Navigation
    // =========================================================================

    fun setTouchLocked(locked: Boolean) {
        _state.update { it.copy(isTouchLocked = locked) }
    }

    fun toggleTouchLock(): Boolean {
        val next = !_state.value.isTouchLocked
        _state.update { it.copy(isTouchLocked = next) }
        return next
    }

    fun setSleepTimer(mode: SleepTimerMode) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null

        when (mode) {
            SleepTimerMode.OFF -> {
                _state.update { it.copy(sleepTimer = SleepTimerState()) }
            }
            SleepTimerMode.END_OF_MEDIA -> {
                val snap = _state.value
                val rem = (snap.durationMs - snap.currentTimeMs).coerceAtLeast(0L)
                _state.update {
                    it.copy(
                        sleepTimer = SleepTimerState(
                            mode = SleepTimerMode.END_OF_MEDIA,
                            remainingMs = rem
                        )
                    )
                }
            }
            else -> {
                val totalMs = mode.durationMinutes * 60_000L
                val deadlineMs = System.currentTimeMillis() + totalMs
                _state.update {
                    it.copy(
                        sleepTimer = SleepTimerState(
                            mode = mode,
                            remainingMs = totalMs
                        )
                    )
                }
                sleepTimerJob = appScope.launch(mainDispatcher) {
                    while (true) {
                        val remaining = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
                        _state.update {
                            it.copy(
                                sleepTimer = SleepTimerState(
                                    mode = mode,
                                    remainingMs = remaining
                                )
                            )
                        }
                        if (remaining <= 0L) {
                            resumeOnAudioFocusGain = false
                            pause()
                            _state.update { it.copy(sleepTimer = SleepTimerState()) }
                            break
                        }
                        delay(1_000L)
                    }
                }
            }
        }
    }

    fun cancelSleepTimer() {
        setSleepTimer(SleepTimerMode.OFF)
    }

    fun selectChapter(chapterIndex: Int) {
        val player = mediaPlayer ?: return
        val chapters = _state.value.chapters
        if (chapterIndex !in chapters.indices) return
        runCatching {
            player.chapter = chapterIndex
        }
        val targetOffset = chapters[chapterIndex].timeOffsetMs
        _state.update {
            it.copy(
                currentChapterIndex = chapterIndex,
                currentTimeMs = targetOffset
            )
        }
    }

    fun nextChapter() {
        val chapters = _state.value.chapters
        if (chapters.isEmpty()) return
        val nextIdx = (_state.value.currentChapterIndex + 1).coerceAtMost(chapters.lastIndex)
        selectChapter(nextIdx)
    }

    fun previousChapter() {
        val chapters = _state.value.chapters
        if (chapters.isEmpty()) return
        val curIdx = _state.value.currentChapterIndex.coerceAtLeast(0)
        val curChapter = chapters.getOrNull(curIdx)
        val targetIdx = if (curChapter != null && (_state.value.currentTimeMs - curChapter.timeOffsetMs) > 3_500L) {
            curIdx
        } else {
            (curIdx - 1).coerceAtLeast(0)
        }
        selectChapter(targetIdx)
    }

    // =========================================================================
    // Multi-Track Audio, Subtitles, External Subtitles & Sync Offset (ms)
    // =========================================================================

    fun selectAudioTrack(trackId: String) {
        val player = mediaPlayer ?: return
        if (runCatching { player.selectTrack(trackId) }.getOrDefault(false)) {
            refreshTracksAndStreamInfo()
            persistCurrentPlaybackStateAsync()
        }
    }

    fun cycleAudioTrack() {
        val tracks = _state.value.audioTracks
        if (tracks.size <= 1) return
        val currentIdx = tracks.indexOfFirst { it.isSelected }.coerceAtLeast(0)
        val nextTrack = tracks[(currentIdx + 1) % tracks.size]
        selectAudioTrack(nextTrack.id)
    }

    fun selectSubtitleTrack(trackId: String) {
        val player = mediaPlayer ?: return
        if (runCatching { player.selectTrack(trackId) }.getOrDefault(false)) {
            _state.update { it.copy(subtitlesEnabled = true) }
            refreshTracksAndStreamInfo()
            persistCurrentPlaybackStateAsync()
        }
    }

    fun disableSubtitles() {
        val player = mediaPlayer ?: return
        runCatching { player.unselectTrackType(IMedia.Track.Type.Text) }
        _state.update { state ->
            state.copy(
                subtitlesEnabled = false,
                subtitleTracks = state.subtitleTracks.map { it.copy(isSelected = false) }
            )
        }
        persistCurrentPlaybackStateAsync()
    }

    fun cycleSubtitleTrack() {
        val tracks = _state.value.subtitleTracks
        if (tracks.isEmpty()) return
        val selectedIndex = tracks.indexOfFirst { it.isSelected }
        if (selectedIndex == -1) {
            selectSubtitleTrack(tracks.first().id)
        } else if (selectedIndex == tracks.lastIndex) {
            disableSubtitles()
        } else {
            selectSubtitleTrack(tracks[selectedIndex + 1].id)
        }
    }

    /**
     * Loads an external subtitle file (.srt, .ass, .ssa, .vtt) from Scoped Storage (`content://`)
     * or direct file/network URI. Automatically stages `content://` streams to app cache so native
     * libVLC demuxers have full random-access read capability while remaining 100% Scoped Storage compliant.
     */
    fun loadExternalSubtitle(subtitleUri: Uri, autoSelect: Boolean = true) {
        val player = mediaPlayer ?: return
        appScope.launch(mainDispatcher) {
            val resolvedUri = withContext(ioDispatcher) {
                stageExternalSubtitleIfNeeded(subtitleUri)
            }
            if (resolvedUri != null) {
                val added = runCatching { player.addSlave(IMedia.Slave.Type.Subtitle, resolvedUri, autoSelect) }.getOrDefault(false)
                if (added) {
                    _state.update {
                        it.copy(
                            externalSubtitleUri = subtitleUri,
                            subtitlesEnabled = autoSelect || it.subtitlesEnabled
                        )
                    }
                    persistCurrentPlaybackStateAsync()
                }
            }
        }
    }

    private fun stageExternalSubtitleIfNeeded(uri: Uri): Uri? = runCatching {
        if (uri.scheme?.lowercase(Locale.ROOT) != "content") {
            return@runCatching uri
        }
        val docName = DocumentFile.fromSingleUri(appContext, uri)?.name
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "external_sub.srt"
        val ext = docName.substringAfterLast('.', "srt").lowercase(Locale.ROOT)
        val safeExtension = if (ext in setOf("srt", "ass", "ssa", "vtt", "sub")) ext else "srt"

        val subtitleCacheDir = File(appContext.cacheDir, "lumina_subtitles").apply { mkdirs() }
        val targetFile = File(subtitleCacheDir, "sub_${uri.toString().hashCode().toUInt()}.$safeExtension")

        appContext.contentResolver.openInputStream(uri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return@runCatching null

        Uri.fromFile(targetFile)
    }.getOrNull()

    /**
     * Sets on-the-fly audio sync offset in milliseconds (libVLC uses microseconds internally).
     */
    fun setAudioSyncOffsetMs(offsetMs: Long) {
        val clampedMs = offsetMs.coerceIn(-10_000L, 10_000L)
        val applied = runCatching { mediaPlayer?.setAudioDelay(clampedMs * 1_000L) }.getOrNull() ?: false
        if (applied || mediaPlayer != null) {
            _state.update { it.copy(audioDelayMs = clampedMs) }
            persistCurrentPlaybackStateAsync()
        }
    }

    /**
     * Sets on-the-fly subtitle (SPU) sync offset in milliseconds (libVLC uses microseconds internally).
     */
    fun setSubtitleSyncOffsetMs(offsetMs: Long) {
        val clampedMs = offsetMs.coerceIn(-30_000L, 30_000L)
        val applied = runCatching { mediaPlayer?.setSpuDelay(clampedMs * 1_000L) }.getOrNull() ?: false
        if (applied || mediaPlayer != null) {
            _state.update { it.copy(subtitleDelayMs = clampedMs) }
            persistCurrentPlaybackStateAsync()
        }
    }

    // =========================================================================
    // 10-Band Hardware/DSP Audio Equalizer with Presets
    // =========================================================================

    private fun seedInitialEqualizerState(prefs: UserPreferences) {
        val savedGains = prefs.parsedEqualizerBands()
        val bands = DEFAULT_EQ_FREQUENCIES.mapIndexed { index, freq ->
            EqualizerBand(
                index = index,
                frequencyHz = freq,
                gainDb = savedGains.getOrElse(index) { 0f }
            )
        }
        val initialEq = EqualizerState(
            enabled = prefs.equalizerEnabled,
            preAmpDb = prefs.equalizerPreAmpDb,
            presets = DEFAULT_EQ_PRESETS,
            selectedPresetIndex = prefs.equalizerPresetIndex.coerceIn(0, DEFAULT_EQ_PRESETS.lastIndex),
            bands = bands
        )
        _state.update { it.copy(equalizer = initialEq) }
    }

    private fun initializeEqualizerState(prefs: UserPreferences) {
        val presetCount = runCatching { MediaPlayer.Equalizer.getPresetCount() }.getOrDefault(0)
        val presets = if (presetCount > 0) {
            (0 until presetCount).map { index ->
                runCatching { MediaPlayer.Equalizer.getPresetName(index) }.getOrNull()
                    ?: "Preset ${index + 1}"
            }
        } else {
            DEFAULT_EQ_PRESETS
        }

        val bandCount = runCatching { MediaPlayer.Equalizer.getBandCount() }
            .getOrDefault(10)
            .takeIf { it > 0 } ?: 10
        val savedGains = prefs.parsedEqualizerBands()
        val bands = (0 until bandCount).map { index ->
            val freq = runCatching { MediaPlayer.Equalizer.getBandFrequency(index) }
                .getOrDefault(DEFAULT_EQ_FREQUENCIES.getOrElse(index) { 1000f })
            EqualizerBand(
                index = index,
                frequencyHz = freq,
                gainDb = savedGains.getOrElse(index) { 0f }
            )
        }

        val eqState = EqualizerState(
            enabled = prefs.equalizerEnabled,
            preAmpDb = prefs.equalizerPreAmpDb,
            presets = presets,
            selectedPresetIndex = prefs.equalizerPresetIndex.coerceIn(0, (presets.size - 1).coerceAtLeast(0)),
            bands = bands
        )
        _state.update { it.copy(equalizer = eqState) }
        applyEqualizerToPlayer(eqState, persist = false)
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        initializeEngineIfNeeded()
        val updated = _state.value.equalizer.copy(enabled = enabled)
        _state.update { it.copy(equalizer = updated) }
        applyEqualizerToPlayer(updated, persist = true)
    }

    fun selectEqualizerPreset(presetIndex: Int) {
        initializeEngineIfNeeded()
        val current = _state.value.equalizer
        if (presetIndex !in current.presets.indices) return

        val presetEq = runCatching { MediaPlayer.Equalizer.createFromPreset(presetIndex) }.getOrNull() ?: return
        val updatedBands = current.bands.map { band ->
            val amp = runCatching { presetEq.getAmp(band.index) }.getOrDefault(0f)
            band.copy(gainDb = amp.coerceIn(-20f, 20f))
        }
        val preAmp = runCatching { presetEq.preAmp }.getOrDefault(0f)
        val updated = current.copy(
            enabled = true,
            preAmpDb = preAmp.coerceIn(-20f, 20f),
            selectedPresetIndex = presetIndex,
            bands = updatedBands
        )
        _state.update { it.copy(equalizer = updated) }
        applyEqualizerToPlayer(updated, persist = true)
    }

    fun setEqualizerPreAmp(preAmpDb: Float) {
        initializeEngineIfNeeded()
        val clamped = preAmpDb.coerceIn(-20f, 20f)
        val updated = _state.value.equalizer.copy(enabled = true, preAmpDb = clamped)
        _state.update { it.copy(equalizer = updated) }
        applyEqualizerToPlayer(updated, persist = true)
    }

    fun setEqualizerBandGain(bandIndex: Int, gainDb: Float) {
        initializeEngineIfNeeded()
        val clamped = gainDb.coerceIn(-20f, 20f)
        val current = _state.value.equalizer
        val updatedBands = current.bands.map { band ->
            if (band.index == bandIndex) band.copy(gainDb = clamped) else band
        }
        val updated = current.copy(enabled = true, bands = updatedBands)
        _state.update { it.copy(equalizer = updated) }
        applyEqualizerToPlayer(updated, persist = true)
    }

    fun resetEqualizerFlat() {
        initializeEngineIfNeeded()
        val current = _state.value.equalizer
        val flatBands = current.bands.map { it.copy(gainDb = 0f) }
        val updated = current.copy(preAmpDb = 0f, selectedPresetIndex = 0, bands = flatBands)
        _state.update { it.copy(equalizer = updated) }
        applyEqualizerToPlayer(updated, persist = true)
    }

    private fun applyEqualizerToPlayer(eqState: EqualizerState, persist: Boolean) {
        val player = mediaPlayer
        if (player != null) {
            runCatching {
                if (!eqState.enabled) {
                    player.setEqualizer(null)
                    activeEqualizer = null
                } else {
                    val vlcEqualizer = MediaPlayer.Equalizer.create()
                    vlcEqualizer.preAmp = eqState.preAmpDb
                    eqState.bands.forEach { band ->
                        vlcEqualizer.setAmp(band.index, band.gainDb)
                    }
                    activeEqualizer = vlcEqualizer
                    player.setEqualizer(vlcEqualizer)
                }
            }
        }

        if (persist) {
            appScope.launch(ioDispatcher) {
                preferences.setEqualizerConfiguration(
                    enabled = eqState.enabled,
                    presetIndex = eqState.selectedPresetIndex,
                    preAmpDb = eqState.preAmpDb,
                    bandGainsDb = eqState.bands.map { it.gainDb }
                )
            }
        }
    }

    // =========================================================================
    // libVLC Event Processing, Track Inspection & HDR Pipeline Activation
    // =========================================================================

    private val vlcEventListener = MediaPlayer.EventListener { event ->
        // Immediately snapshot primitive fields before libVLC recycles the native AbstractVLCEvent,
        // and never allow any uncaught exception to cross the JNI callback boundary.
        runCatching {
            val eventType = event.type
            val bufferingValue = if (eventType == MediaPlayer.Event.Buffering) event.buffering else 0f
            val timeChangedValue = if (eventType == MediaPlayer.Event.TimeChanged) event.timeChanged else 0L
            val lengthChangedValue = if (eventType == MediaPlayer.Event.LengthChanged) event.lengthChanged else 0L
            val seekableValue = if (eventType == MediaPlayer.Event.SeekableChanged) event.seekable else false
            val voutCountValue = if (eventType == MediaPlayer.Event.Vout) event.voutCount else 0

            appScope.launch(mainDispatcher) {
                runCatching {
                    handleVlcEventOnMain(
                        eventType = eventType,
                        bufferingValue = bufferingValue,
                        timeChangedValue = timeChangedValue,
                        lengthChangedValue = lengthChangedValue,
                        seekableValue = seekableValue,
                        voutCountValue = voutCountValue
                    )
                }
            }
        }
    }

    private fun handleVlcEventOnMain(
        eventType: Int,
        bufferingValue: Float,
        timeChangedValue: Long,
        lengthChangedValue: Long,
        seekableValue: Boolean,
        voutCountValue: Int
    ) {
        when (eventType) {
            MediaPlayer.Event.Opening -> {
                _state.update { it.copy(status = PlaybackStatus.OPENING, errorMessage = null) }
            }

            MediaPlayer.Event.Buffering -> {
                val pct = bufferingValue.coerceIn(0f, 100f)
                _state.update { prev ->
                    prev.copy(
                        bufferingPercent = pct,
                        status = if (pct < 100f && !prev.isPlaying) PlaybackStatus.BUFFERING else prev.status
                    )
                }
            }

            MediaPlayer.Event.Playing -> {
                isOpeningMedia = false
                _state.update {
                    it.copy(
                        status = PlaybackStatus.PLAYING,
                        isPlaying = true,
                        bufferingPercent = 100f,
                        errorMessage = null
                    )
                }
                LuminaPlaybackService.syncPlaybackForegroundState(appContext, isPlaying = true)
                if (attachedLayoutRef?.get()?.isAttachedToWindow == true) {
                    runCatching {
                        mediaPlayer?.setVideoTrackEnabled(true)
                        mediaPlayer?.updateVideoSurfaces()
                    }
                }
                refreshTracksAndStreamInfo()
                applyPendingResumeIfNeeded()
                applyEqualizerToPlayer(_state.value.equalizer, persist = false)
                scheduleBlankVideoWatchdog(playbackSessionId.get())
            }

            MediaPlayer.Event.Paused -> {
                isOpeningMedia = false
                blankVoutWatchdogJob?.cancel()
                _state.update {
                    it.copy(
                        status = PlaybackStatus.PAUSED,
                        isPlaying = false
                    )
                }
                LuminaPlaybackService.syncPlaybackForegroundState(appContext, isPlaying = false)
            }

            MediaPlayer.Event.Stopped -> {
                // Ignore stale async Stopped events emitted by player.stop() while opening a new video!
                if (isOpeningMedia) return
                blankVoutWatchdogJob?.cancel()
                _state.update {
                    it.copy(
                        status = PlaybackStatus.IDLE,
                        isPlaying = false
                    )
                }
                LuminaPlaybackService.syncPlaybackForegroundState(appContext, isPlaying = false)
            }

            MediaPlayer.Event.EndReached -> {
                isOpeningMedia = false
                blankVoutWatchdogJob?.cancel()
                markCurrentMediaCompletedAsync()
                val stopAtEnd = _state.value.sleepTimer.mode == SleepTimerMode.END_OF_MEDIA
                val advanced = if (stopAtEnd) false else playNext()
                if (!advanced) {
                    abandonAudioFocusAndNoisyReceiver()
                    _state.update {
                        it.copy(
                            status = PlaybackStatus.ENDED,
                            isPlaying = false,
                            progress = 1f,
                            currentTimeMs = it.durationMs,
                            sleepTimer = if (stopAtEnd) SleepTimerState() else it.sleepTimer
                        )
                    }
                    LuminaPlaybackService.syncPlaybackForegroundState(appContext, isPlaying = false)
                }
            }

            MediaPlayer.Event.EncounteredError -> {
                blankVoutWatchdogJob?.cancel()
                if (activeResolvedDecoderMode != ResolvedVlcDecoderMode.SOFTWARE_AVCODEC &&
                    _state.value.currentQueueIndex in _state.value.queue.indices
                ) {
                    triggerDecoderFallbackRetry(
                        targetMode = ResolvedVlcDecoderMode.SOFTWARE_AVCODEC,
                        resumeTimeMs = _state.value.currentTimeMs
                    )
                    return
                }

                isOpeningMedia = false
                abandonAudioFocusAndNoisyReceiver()
                _state.update {
                    it.copy(
                        status = PlaybackStatus.ERROR,
                        isPlaying = false,
                        errorMessage = "Playback error: unsupported stream or unreachable network source."
                    )
                }
                LuminaPlaybackService.syncPlaybackForegroundState(appContext, isPlaying = false)
            }

            MediaPlayer.Event.TimeChanged -> {
                val nowMs = timeChangedValue.coerceAtLeast(0L)
                val duration = _state.value.durationMs
                val progress = if (duration > 0L) {
                    (nowMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }

                // Enforce A-B Repeat loop seamlessly
                val ab = _state.value.abRepeat
                if (ab.isActive && ab.pointAMs != null && ab.pointBMs != null && nowMs >= ab.pointBMs) {
                    runCatching { mediaPlayer?.setTime(ab.pointAMs, true) }
                    return
                }

                _state.update { prev ->
                    val activeChapIdx = if (prev.chapters.isNotEmpty()) {
                        prev.chapters.indexOfLast { it.timeOffsetMs <= nowMs }.coerceAtLeast(0)
                    } else {
                        -1
                    }
                    val updatedSleep = if (prev.sleepTimer.mode == SleepTimerMode.END_OF_MEDIA && duration > 0L) {
                        prev.sleepTimer.copy(remainingMs = (duration - nowMs).coerceAtLeast(0L))
                    } else {
                        prev.sleepTimer
                    }
                    prev.copy(
                        currentTimeMs = nowMs,
                        progress = progress,
                        currentChapterIndex = activeChapIdx,
                        sleepTimer = updatedSleep
                    )
                }

                // Periodically checkpoint playback resume position every 5 seconds
                val wallNow = System.currentTimeMillis()
                if (wallNow - lastPersistedTimestampMs >= 5_000L) {
                    lastPersistedTimestampMs = wallNow
                    persistCurrentPlaybackStateAsync()
                }
            }

            MediaPlayer.Event.LengthChanged -> {
                val len = lengthChangedValue.coerceAtLeast(0L)
                _state.update { it.copy(durationMs = len) }
            }

            MediaPlayer.Event.SeekableChanged -> {
                _state.update { it.copy(isSeekable = seekableValue) }
                applyPendingResumeIfNeeded()
            }

            MediaPlayer.Event.Vout -> {
                activeVoutCount = voutCountValue
                if (voutCountValue > 0) {
                    runCatching { mediaPlayer?.updateVideoSurfaces() }
                    applyScaleAndAspectRatio(_state.value.aspectRatioMode)
                }
                refreshTracksAndStreamInfo()
            }

            MediaPlayer.Event.ESAdded,
            MediaPlayer.Event.ESSelected,
            MediaPlayer.Event.ESDeleted -> {
                refreshTracksAndStreamInfo()
            }
        }
    }

    /**
     * Monitors active video sessions after playback starts: if a video track exists and the
     * UI surface is attached, yet hardware `MediaCodec` produced zero video outputs (`voutCount == 0`)
     * or zero decoded/displayed frames, seamlessly steps down to OpenGL ES (`:no-mediacodec-dr`)
     * and then multi-threaded software `avcodec` (`:no-hw-dec`) at the current timestamp.
     */
    private fun scheduleBlankVideoWatchdog(expectedSessionId: Long) {
        blankVoutWatchdogJob?.cancel()
        blankVoutWatchdogJob = appScope.launch(mainDispatcher) {
            delay(BLANK_VOUT_WATCHDOG_DELAY_MS)
            if (expectedSessionId != playbackSessionId.get() || isReleased.get()) return@launch
            val player = mediaPlayer ?: return@launch
            if (!_state.value.isPlaying) return@launch
            val layout = attachedLayoutRef?.get()
            if (layout == null || !layout.isAttachedToWindow) return@launch

            val videoTracks = runCatching { player.getTracks(IMedia.Track.Type.Video) }.getOrNull().orEmpty()
            if (videoTracks.isEmpty()) return@launch

            val nextTier = LuminaDecoderPolicy.nextFallbackTier(activeResolvedDecoderMode) ?: return@launch

            val stats = runCatching {
                player.media?.let { activeMedia ->
                    try {
                        activeMedia.stats
                    } finally {
                        runCatching { activeMedia.release() }
                    }
                }
            }.getOrNull()

            val noVoutCreated = activeVoutCount <= 0
            val zeroFramesDecoded = stats != null &&
                stats.decodedVideo == 0 &&
                stats.displayedPictures == 0 &&
                _state.value.currentTimeMs >= 900L

            if (noVoutCreated || zeroFramesDecoded) {
                triggerDecoderFallbackRetry(
                    targetMode = nextTier,
                    resumeTimeMs = _state.value.currentTimeMs
                )
            }
        }
    }

    private fun applyPendingResumeIfNeeded() {
        if (hasAppliedInitialResumeSeek) return
        val player = mediaPlayer ?: return
        val fallbackSeekMs = pendingFallbackResumeTimeMs
        val saved = pendingResumeState
        if (fallbackSeekMs == null && saved == null) {
            hasAppliedInitialResumeSeek = true
            return
        }

        val prefs = userPreferences.value
        hasAppliedInitialResumeSeek = true
        pendingFallbackResumeTimeMs = null

        runCatching {
            if (saved != null) {
                // Restore saved audio/subtitle tracks & sync offsets
                if (saved.audioDelayMs != 0L) {
                    player.setAudioDelay(saved.audioDelayMs * 1_000L)
                }
                if (saved.subtitleDelayMs != 0L) {
                    player.setSpuDelay(saved.subtitleDelayMs * 1_000L)
                }
                saved.selectedAudioTrackId?.let { player.selectTrack(it) }
                saved.selectedSubtitleTrackId?.let { subId ->
                    if (subId == SUBTITLE_DISABLED_SENTINEL) {
                        player.unselectTrackType(IMedia.Track.Type.Text)
                    } else {
                        player.selectTrack(subId)
                    }
                }
            }

            if (fallbackSeekMs != null && fallbackSeekMs > 250L) {
                player.setTime(fallbackSeekMs, true)
            } else if (saved != null && prefs.autoResumePlayback && !saved.completed && saved.positionMs > 3_000L) {
                // Restore saved playback position if auto-resume is enabled and media wasn't completed
                val length = player.length.takeIf { it > 0L } ?: saved.durationMs
                if (length <= 0L || saved.positionMs < (length - 5_000L)) {
                    player.setTime(saved.positionMs, false)
                }
            }
        }
        refreshTracksAndStreamInfo()
    }

    private fun refreshTracksAndStreamInfo() {
        val player = mediaPlayer ?: return

        // 1. Audio Tracks
        val rawAudioTracks = runCatching { player.getTracks(IMedia.Track.Type.Audio) }.getOrNull().orEmpty()
        val mappedAudio = rawAudioTracks.mapIndexed { index, track ->
            val audioTrack = track as? IMedia.AudioTrack
            val label = buildTrackLabel(
                rawName = track.name,
                description = track.description,
                language = track.language,
                codec = track.codec,
                fallbackPrefix = "Audio Track ${index + 1}"
            )
            AudioTrackInfo(
                id = track.id,
                title = label,
                language = track.language,
                codec = track.codec?.uppercase(Locale.ROOT),
                channels = audioTrack?.channels ?: 2,
                sampleRateHz = audioTrack?.rate ?: 48_000,
                bitrateKbps = (track.bitrate / 1000).coerceAtLeast(0),
                isSelected = track.selected
            )
        }

        // 2. Subtitle Tracks
        val rawSubTracks = runCatching { player.getTracks(IMedia.Track.Type.Text) }.getOrNull().orEmpty()
        val mappedSubs = rawSubTracks.mapIndexed { index, track ->
            val label = buildTrackLabel(
                rawName = track.name,
                description = track.description,
                language = track.language,
                codec = track.codec,
                fallbackPrefix = "Subtitle ${index + 1}"
            )
            SubtitleTrackInfo(
                id = track.id,
                title = label,
                language = track.language,
                codec = track.codec?.uppercase(Locale.ROOT),
                isExternal = track.id.contains("slave", ignoreCase = true),
                isSelected = track.selected
            )
        }

        // 3. Embedded MKV / MP4 Chapters (strictly honest — empty when none are present)
        val rawChapters = runCatching { player.getChapters(-1) }.getOrNull()
        val mappedChapters = rawChapters?.mapIndexed { index, ch ->
            ChapterInfo(
                index = index,
                title = ch.name?.takeIf { it.isNotBlank() } ?: "Chapter ${index + 1}",
                timeOffsetMs = ch.timeOffset.coerceAtLeast(0L),
                durationMs = ch.duration.coerceAtLeast(0L)
            )
        }.orEmpty()
        val currentChap = if (mappedChapters.isNotEmpty()) {
            runCatching { player.chapter }.getOrDefault(0).coerceIn(0, mappedChapters.lastIndex)
        } else {
            -1
        }

        // 4. Video Track & True HDR Pipeline Detection
        val rawVideoTracks = runCatching { player.getTracks(IMedia.Track.Type.Video) }.getOrNull().orEmpty()
        val activeVideoTrack = (rawVideoTracks.firstOrNull { it.selected } ?: rawVideoTracks.firstOrNull())
            as? IMedia.VideoTrack

        val currentItem = _state.value.currentMedia
        val detectedHdrFormat = hdrController.detectStreamHdrFormat(
            videoTrack = activeVideoTrack,
            mediaTitleOrUri = currentItem?.title ?: currentItem?.uri?.toString(),
            fallbackHint = currentItem?.hdrHint
        )

        val prefs = userPreferences.value
        val updatedHdrStatus = hdrController.applyHdrPipelineToSurface(
            videoLayout = attachedLayoutRef?.get(),
            sourceFormat = detectedHdrFormat,
            hdrPassthroughEnabled = prefs.hdrPassthroughEnabled,
            toneMappingAlgorithm = prefs.sdrToneMappingAlgorithm
        )

        val fps = if (activeVideoTrack != null && activeVideoTrack.frameRateDen > 0) {
            activeVideoTrack.frameRateNum.toFloat() / activeVideoTrack.frameRateDen.toFloat()
        } else {
            0f
        }

        val codecLabel = activeVideoTrack?.codec?.uppercase(Locale.ROOT)

        _state.update { prev ->
            prev.copy(
                audioTracks = mappedAudio,
                subtitleTracks = mappedSubs,
                subtitlesEnabled = mappedSubs.any { it.isSelected },
                chapters = mappedChapters,
                currentChapterIndex = currentChap,
                videoWidth = activeVideoTrack?.width ?: prev.videoWidth,
                videoHeight = activeVideoTrack?.height ?: prev.videoHeight,
                videoCodecLabel = codecLabel ?: prev.videoCodecLabel,
                frameRate = if (fps > 0f) fps else prev.frameRate,
                hdrStatus = updatedHdrStatus
            )
        }

        // Persist newly discovered HDR badge & codec into the media library for fast badges
        if (currentItem != null && (detectedHdrFormat.isHdr || codecLabel != null)) {
            appScope.launch(ioDispatcher) {
                runCatching {
                    mediaDao.updateDetectedStreamInfo(
                        uri = currentItem.uri.toString(),
                        hdrBadge = detectedHdrFormat.takeIf { it.isHdr }?.displayBadge,
                        videoCodec = codecLabel
                    )
                }
            }
        }
    }

    private fun refreshHdrSurfacePipeline() {
        val prefs = userPreferences.value
        val updatedHdrStatus = hdrController.applyHdrPipelineToSurface(
            videoLayout = attachedLayoutRef?.get(),
            sourceFormat = _state.value.hdrStatus.sourceFormat,
            hdrPassthroughEnabled = prefs.hdrPassthroughEnabled,
            toneMappingAlgorithm = prefs.sdrToneMappingAlgorithm
        )
        _state.update { it.copy(hdrStatus = updatedHdrStatus) }
    }

    private fun buildTrackLabel(
        rawName: String?,
        description: String?,
        language: String?,
        codec: String?,
        fallbackPrefix: String
    ): String {
        val base = rawName?.takeIf { it.isNotBlank() }
            ?: description?.takeIf { it.isNotBlank() }
            ?: language?.takeIf { it.isNotBlank() }?.let { Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }
            ?: fallbackPrefix

        val codecSuffix = codec?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)
        return if (codecSuffix != null && !base.contains(codecSuffix, ignoreCase = true)) {
            "$base ($codecSuffix)"
        } else {
            base
        }
    }

    // =========================================================================
    // Room Resume Persistence & Lifecycle Safety
    // =========================================================================

    fun persistCurrentPlaybackStateAsync() {
        val snapshot = _state.value
        val media = snapshot.currentMedia ?: return
        if (snapshot.durationMs <= 0L && snapshot.currentTimeMs <= 0L) return

        val selectedAudioId = snapshot.audioTracks.firstOrNull { it.isSelected }?.id
        val selectedSubId = when {
            !snapshot.subtitlesEnabled && snapshot.subtitleTracks.isNotEmpty() -> SUBTITLE_DISABLED_SENTINEL
            else -> snapshot.subtitleTracks.firstOrNull { it.isSelected }?.id
        }
        val completed = snapshot.durationMs > 0L && snapshot.currentTimeMs >= (snapshot.durationMs * 0.96f).toLong()

        val entity = PlaybackStateEntity(
            mediaUri = media.uri.toString(),
            positionMs = snapshot.currentTimeMs,
            durationMs = snapshot.durationMs,
            playbackSpeed = snapshot.playbackSpeed,
            selectedAudioTrackId = selectedAudioId,
            selectedSubtitleTrackId = selectedSubId,
            externalSubtitleUri = snapshot.externalSubtitleUri?.toString(),
            audioDelayMs = snapshot.audioDelayMs,
            subtitleDelayMs = snapshot.subtitleDelayMs,
            aspectRatioMode = snapshot.aspectRatioMode.name,
            completed = completed,
            updatedAt = System.currentTimeMillis()
        )

        appScope.launch(ioDispatcher) {
            runCatching { playbackStateDao.upsertPlaybackState(entity) }
        }
    }

    private fun markCurrentMediaCompletedAsync() {
        val snapshot = _state.value
        val media = snapshot.currentMedia ?: return
        appScope.launch(ioDispatcher) {
            runCatching {
                playbackStateDao.upsertPlaybackState(
                    PlaybackStateEntity(
                        mediaUri = media.uri.toString(),
                        positionMs = 0L,
                        durationMs = snapshot.durationMs,
                        playbackSpeed = snapshot.playbackSpeed,
                        aspectRatioMode = snapshot.aspectRatioMode.name,
                        completed = true,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    /**
     * Lifecycle hook:
     * - On Googlebook OS multi-window and PiP, `onPause` is called when another freeform window
     *   gains focus while Lumina remains visible, so we never pause in `onPause`.
     * - On `onStop`, lossless/background audio-only sessions continue playing uninterrupted via
     *   `LuminaPlaybackService`, while video sessions pause unless in PiP / Always-on-Top Mini-Player.
     */
    override fun onStop(owner: LifecycleOwner) {
        persistCurrentPlaybackStateAsync()
        val snapshot = _state.value
        val isAudioOnlySession = snapshot.videoWidth == 0 &&
            snapshot.videoHeight == 0 &&
            snapshot.currentMedia?.mimeType?.startsWith("audio/", ignoreCase = true) == true

        if (!snapshot.isInPipOrMiniPlayer && !isAudioOnlySession && runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false)) {
            runCatching { mediaPlayer?.pause() }
        }
    }

    /**
     * Full native engine teardown when the application process or explicit session releases resources.
     */
    @Synchronized
    fun release() {
        if (isReleased.getAndSet(true)) return
        playbackSessionId.incrementAndGet()
        isOpeningMedia = false
        markNativeSurfacesCreated(false)
        blankVoutWatchdogJob?.cancel()
        blankVoutWatchdogJob = null
        openMediaJob?.cancel()
        openMediaJob = null
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        persistCurrentPlaybackStateAsync()
        abandonAudioFocusAndNoisyReceiver()

        val player = mediaPlayer
        val vlc = libVlc
        mediaPlayer = null
        libVlc = null
        activeEqualizer = null
        attachedLayoutRef?.get()?.let { hdrController.resetWindowHdrMode(it.context) }
        attachedLayoutRef = null

        runCatching {
            player?.setEventListener(null)
            player?.vlcVout?.removeCallback(vlcVoutCallback)
            player?.stop()
            player?.detachViews()
            player?.release()
        }
        closeActiveFileDescriptor()
        runCatching { vlc?.release() }
        LuminaPlaybackService.syncPlaybackForegroundState(appContext, isPlaying = false)
    }

    private fun replaceActiveFileDescriptor(newPfd: ParcelFileDescriptor) {
        val oldPfd = synchronized(fdLock) {
            val prev = activeFileDescriptor
            activeFileDescriptor = newPfd
            prev
        }
        if (oldPfd != null && oldPfd !== newPfd) {
            runCatching { oldPfd.close() }
        }
    }

    private fun closeActiveFileDescriptor() {
        val oldPfd = synchronized(fdLock) {
            val prev = activeFileDescriptor
            activeFileDescriptor = null
            prev
        }
        runCatching { oldPfd?.close() }
    }

    companion object {
        private const val SUBTITLE_DISABLED_SENTINEL = "__LUMINA_SUBTITLES_DISABLED__"
        private const val SURFACE_READY_WAIT_TIMEOUT_MS = 1_200L
        private const val BLANK_VOUT_WATCHDOG_DELAY_MS = 1_400L
        private val AUDIO_ONLY_EXTENSIONS = setOf(
            "mp3", "flac", "wav", "ogg", "opus", "m4a", "aac", "alac", "wma", "aiff", "ape"
        )
        private val DEFAULT_EQ_FREQUENCIES = listOf(
            31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
        )
        private val DEFAULT_EQ_PRESETS = listOf(
            "Flat", "Classical", "Club", "Dance", "Full Bass", "Full Bass & Treble",
            "Full Treble", "Headphones", "Large Hall", "Live", "Party", "Pop",
            "Reggae", "Rock", "Ska", "Soft", "Soft Rock", "Techno"
        )
    }
}
