package app.lumina.player.ui.player

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lumina.player.data.local.entity.VideoBookmarkEntity
import app.lumina.player.data.preferences.LuminaPreferences
import app.lumina.player.data.preferences.SubtitleBackgroundStyle
import app.lumina.player.data.preferences.SubtitleFontScale
import app.lumina.player.data.preferences.UserPreferences
import app.lumina.player.data.repository.LuminaThumbnailRepository
import app.lumina.player.data.repository.MediaRepository
import app.lumina.player.player.engine.LuminaPlayerEngine
import app.lumina.player.player.model.AspectRatioMode
import app.lumina.player.player.model.LuminaPlayerState
import app.lumina.player.player.model.MediaQueueItem
import app.lumina.player.player.model.PlaybackStatus
import app.lumina.player.player.model.SleepTimerMode
import app.lumina.player.ui.library.toQueueItem
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Bridges Compose player UI (Phone, Foldable Tabletop, and Googlebook OS Desktop)
 * with the singleton `LuminaPlayerEngine`, per-video timestamp bookmarks, custom playlists,
 * and live timeline scrub frame extraction.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LuminaPlayerViewModel @Inject constructor(
    val playerEngine: LuminaPlayerEngine,
    private val mediaRepository: MediaRepository,
    private val thumbnailRepository: LuminaThumbnailRepository,
    private val preferences: LuminaPreferences
) : ViewModel() {

    val playerState: StateFlow<LuminaPlayerState> = playerEngine.state

    val userPreferences: StateFlow<UserPreferences> = preferences.userPreferencesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferences())

    val currentMediaBookmarks: StateFlow<List<VideoBookmarkEntity>> = playerState
        .map { it.currentMedia?.uri?.toString() }
        .distinctUntilChanged()
        .flatMapLatest { uriStr ->
            if (uriStr.isNullOrBlank()) {
                flowOf(emptyList())
            } else {
                mediaRepository.observeBookmarks(uriStr)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private val scrubPreviewTargetMsFlow = MutableStateFlow<Long?>(null)

    val scrubPreviewBitmap: StateFlow<Bitmap?> = scrubPreviewTargetMsFlow
        .debounce { target -> if (target == null) 0L else 55L }
        .mapLatest { targetMs ->
            if (targetMs == null) return@mapLatest null
            val media = playerState.value.currentMedia ?: return@mapLatest null
            thumbnailRepository.getScrubPreviewFrame(
                uri = media.uri,
                targetTimeMs = targetMs,
                isNetworkStream = media.isNetworkStream
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    fun requestScrubPreview(targetTimeMs: Long) {
        scrubPreviewTargetMsFlow.value = targetTimeMs.coerceAtLeast(0L)
    }

    fun clearScrubPreview() {
        scrubPreviewTargetMsFlow.value = null
    }

    fun addBookmarkAtCurrentPosition(customLabel: String? = null) {
        val snapshot = playerState.value
        val mediaUri = snapshot.currentMedia?.uri?.toString() ?: return
        val posMs = snapshot.currentTimeMs.coerceAtLeast(0L)
        val formattedTime = formatBookmarkTimecode(posMs)
        val resolvedLabel = customLabel?.trim()?.takeIf { it.isNotEmpty() } ?: "Bookmark @ $formattedTime"
        viewModelScope.launch {
            mediaRepository.addBookmark(
                mediaUri = mediaUri,
                positionMs = posMs,
                label = resolvedLabel
            )
        }
    }

    fun jumpToBookmark(bookmark: VideoBookmarkEntity) {
        val status = playerState.value.status
        playerEngine.seekTo(bookmark.positionMs, fast = false)
        if (status == PlaybackStatus.ENDED || status == PlaybackStatus.IDLE || status == PlaybackStatus.ERROR) {
            playerEngine.play()
        }
    }

    fun deleteBookmark(bookmarkId: Long) {
        viewModelScope.launch {
            mediaRepository.deleteBookmark(bookmarkId)
        }
    }

    fun saveCurrentQueueAsPlaylist(playlistName: String, onSaved: (() -> Unit)? = null) {
        val currentQueue = playerState.value.queue
        if (currentQueue.isEmpty()) return
        viewModelScope.launch {
            mediaRepository.addQueueToNewPlaylist(playlistName, currentQueue)
            onSaved?.invoke()
        }
    }

    fun enterBackgroundAudioMode() {
        playerEngine.enterBackgroundAudioMode()
    }

    fun playQueue(
        items: List<MediaQueueItem>,
        startIndex: Int = 0,
        smbUser: String? = null,
        smbPassword: String? = null
    ) {
        playerEngine.loadQueue(
            items = items,
            startIndex = startIndex,
            startImmediately = true,
            smbUsername = smbUser,
            smbPassword = smbPassword
        )
    }

    fun openLocalVideoUri(uri: Uri) {
        viewModelScope.launch {
            val queueItem = runCatching {
                mediaRepository.registerPickedUri(uri).toQueueItem()
            }.getOrElse {
                val fallbackTitle = uri.lastPathSegment
                    ?.substringAfterLast('/')
                    ?.substringAfterLast(':')
                    ?.takeIf { it.isNotBlank() }
                    ?: "Local Video"
                MediaQueueItem(
                    uri = uri,
                    title = fallbackTitle,
                    mimeType = "video/*",
                    isNetworkStream = false
                )
            }
            playerEngine.loadSingleMedia(
                item = queueItem,
                startImmediately = true
            )
        }
    }

    fun retryCurrentMedia() = playerEngine.retryCurrentMedia()

    fun setHdrPassthrough(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setHdrPassthroughEnabled(enabled)
        }
    }

    fun setSubtitleFontScale(scale: SubtitleFontScale) {
        viewModelScope.launch {
            preferences.setSubtitleFontScale(scale)
        }
    }

    fun setSubtitleBackgroundStyle(style: SubtitleBackgroundStyle) {
        viewModelScope.launch {
            preferences.setSubtitleBackgroundStyle(style)
        }
    }

    fun togglePlayPause() = playerEngine.togglePlayPause()
    fun playNext() = playerEngine.playNext()
    fun playPrevious() = playerEngine.playPrevious()
    fun playQueueIndex(index: Int) = playerEngine.playQueueIndex(index)
    fun seekTo(positionMs: Long, fast: Boolean = true) = playerEngine.seekTo(positionMs, fast)
    fun seekRelative(deltaMs: Long) = playerEngine.seekRelative(deltaMs)
    fun setPlaybackSpeed(speed: Float) = playerEngine.setPlaybackSpeed(speed)
    fun setVolume(volumePercent: Int) = playerEngine.setVolume(volumePercent)
    fun adjustVolumeBy(delta: Int) = playerEngine.adjustVolumeBy(delta)
    fun toggleMute() = playerEngine.toggleMute()
    fun cycleAspectRatio(): AspectRatioMode = playerEngine.cycleAspectRatio()
    fun setAspectRatio(mode: AspectRatioMode) = playerEngine.setAspectRatioMode(mode)
    fun stepABRepeat() = playerEngine.stepABRepeat()
    fun clearABRepeat() = playerEngine.clearABRepeat()

    fun setTouchLocked(locked: Boolean) = playerEngine.setTouchLocked(locked)
    fun toggleTouchLock(): Boolean = playerEngine.toggleTouchLock()
    fun setSleepTimer(mode: SleepTimerMode) = playerEngine.setSleepTimer(mode)
    fun cancelSleepTimer() = playerEngine.cancelSleepTimer()
    fun selectChapter(chapterIndex: Int) = playerEngine.selectChapter(chapterIndex)
    fun nextChapter() = playerEngine.nextChapter()
    fun previousChapter() = playerEngine.previousChapter()

    fun selectAudioTrack(id: String) = playerEngine.selectAudioTrack(id)
    fun cycleAudioTrack() = playerEngine.cycleAudioTrack()
    fun selectSubtitleTrack(id: String) = playerEngine.selectSubtitleTrack(id)
    fun disableSubtitles() = playerEngine.disableSubtitles()
    fun cycleSubtitleTrack() = playerEngine.cycleSubtitleTrack()
    fun loadExternalSubtitle(uri: Uri) = playerEngine.loadExternalSubtitle(uri, autoSelect = true)
    fun setAudioSyncOffsetMs(offsetMs: Long) = playerEngine.setAudioSyncOffsetMs(offsetMs)
    fun setSubtitleSyncOffsetMs(offsetMs: Long) = playerEngine.setSubtitleSyncOffsetMs(offsetMs)

    fun setEqualizerEnabled(enabled: Boolean) = playerEngine.setEqualizerEnabled(enabled)
    fun selectEqualizerPreset(index: Int) = playerEngine.selectEqualizerPreset(index)
    fun setEqualizerPreAmp(preAmpDb: Float) = playerEngine.setEqualizerPreAmp(preAmpDb)
    fun setEqualizerBandGain(bandIndex: Int, gainDb: Float) = playerEngine.setEqualizerBandGain(bandIndex, gainDb)
    fun resetEqualizerFlat() = playerEngine.resetEqualizerFlat()

    fun stopAndSave() = playerEngine.stop()

    private fun formatBookmarkTimecode(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L)) / 1_000L
        val hours = totalSec / 3_600L
        val minutes = (totalSec % 3_600L) / 60L
        val seconds = totalSec % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
