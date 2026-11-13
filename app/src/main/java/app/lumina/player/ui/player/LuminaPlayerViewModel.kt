package app.lumina.player.ui.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lumina.player.data.preferences.LuminaPreferences
import app.lumina.player.data.preferences.SubtitleBackgroundStyle
import app.lumina.player.data.preferences.SubtitleFontScale
import app.lumina.player.data.preferences.UserPreferences
import app.lumina.player.data.repository.MediaRepository
import app.lumina.player.player.engine.LuminaPlayerEngine
import app.lumina.player.player.model.AspectRatioMode
import app.lumina.player.player.model.LuminaPlayerState
import app.lumina.player.player.model.MediaQueueItem
import app.lumina.player.player.model.SleepTimerMode
import app.lumina.player.ui.library.toQueueItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Bridges Compose player UI (Phone, Foldable Tabletop, and Googlebook OS Desktop)
 * with the singleton `LuminaPlayerEngine`.
 */
@HiltViewModel
class LuminaPlayerViewModel @Inject constructor(
    val playerEngine: LuminaPlayerEngine,
    private val mediaRepository: MediaRepository,
    private val preferences: LuminaPreferences
) : ViewModel() {

    val playerState: StateFlow<LuminaPlayerState> = playerEngine.state

    val userPreferences: StateFlow<UserPreferences> = preferences.userPreferencesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferences())

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
}

