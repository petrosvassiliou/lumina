package app.lumina.player.player.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import app.lumina.player.MainActivity
import app.lumina.player.R
import app.lumina.player.player.engine.LuminaPlayerEngine
import app.lumina.player.player.model.LuminaPlayerState
import app.lumina.player.player.model.PlaybackStatus
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Official Foreground Media Playback & System `MediaSession` Service for Lumina.
 *
 * - Satisfies Android 14/15 (`targetSdk = 35`) & Google Play Console `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
 *   requirements cleanly.
 * - Exposes lock-screen scrubber, Quick Settings media card, Bluetooth headset buttons,
 *   and Googlebook OS hardware media keys (`Play/Pause`, `Next`, `Previous`, `Seek`).
 * - Keeps lossless audio & PiP / floating mini-player sessions alive without OS background kills.
 */
@AndroidEntryPoint
class LuminaPlaybackService : Service() {

    @Inject
    lateinit var playerEngine: LuminaPlayerEngine

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null
    private var isInForeground = false

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        initializeMediaSession()
        observeEngineState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> playerEngine.togglePlayPause()
            ACTION_PREVIOUS -> playerEngine.playPrevious()
            ACTION_NEXT -> playerEngine.playNext()
            ACTION_REWIND -> playerEngine.seekRelative(-10_000L)
            ACTION_FORWARD -> playerEngine.seekRelative(10_000L)
            ACTION_STOP -> {
                playerEngine.stop()
                stopForegroundCleanly()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val currentSnapshot = playerEngine.state.value
        if (currentSnapshot.currentMedia != null && currentSnapshot.status != PlaybackStatus.IDLE) {
            promoteOrUpdateNotification(currentSnapshot)
        } else if (intent?.action == ACTION_SYNC) {
            // Started right as queue opens; post lightweight placeholder if needed, then observe
            promoteOrUpdateNotification(currentSnapshot)
            if (currentSnapshot.currentMedia == null) {
                stopForegroundCleanly()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        val snapshot = playerEngine.state.value
        if (!snapshot.isPlaying) {
            stopForegroundCleanly()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        runCatching {
            mediaSession?.isActive = false
            mediaSession?.release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun initializeMediaSession() {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val session = MediaSession(this, "LuminaMediaSession").apply {
            setSessionActivity(openAppIntent)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    playerEngine.play()
                }

                override fun onPause() {
                    playerEngine.pause()
                }

                override fun onStop() {
                    playerEngine.stop()
                    stopForegroundCleanly()
                    stopSelf()
                }

                override fun onSkipToNext() {
                    playerEngine.playNext()
                }

                override fun onSkipToPrevious() {
                    playerEngine.playPrevious()
                }

                override fun onFastForward() {
                    playerEngine.seekRelative(10_000L)
                }

                override fun onRewind() {
                    playerEngine.seekRelative(-10_000L)
                }

                override fun onSeekTo(pos: Long) {
                    playerEngine.seekTo(pos, fast = false)
                }

                override fun onSetPlaybackSpeed(speed: Float) {
                    playerEngine.setPlaybackSpeed(speed)
                }
            })
            isActive = true
        }
        mediaSession = session
    }

    private fun observeEngineState() {
        serviceScope.launch {
            playerEngine.state.collectLatest { state ->
                updateMediaSessionState(state)
                if (state.currentMedia == null || state.status == PlaybackStatus.IDLE) {
                    if (isInForeground) {
                        stopForegroundCleanly()
                        stopSelf()
                    }
                } else {
                    promoteOrUpdateNotification(state)
                }
            }
        }
    }

    private fun updateMediaSessionState(state: LuminaPlayerState) {
        val session = mediaSession ?: return
        val media = state.currentMedia

        if (media != null) {
            val subtitleParts = buildList {
                add(state.hdrStatus.sourceFormat.displayBadge)
                state.videoCodecLabel?.let { add(it) }
            }.joinToString(" • ")

            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, media.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, media.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, subtitleParts)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.app_name))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs.coerceAtLeast(0L))
                .build()
            session.setMetadata(metadata)
        }

        val pbStateCode = when (state.status) {
            PlaybackStatus.OPENING, PlaybackStatus.BUFFERING -> PlaybackState.STATE_BUFFERING
            PlaybackStatus.PLAYING -> PlaybackState.STATE_PLAYING
            PlaybackStatus.PAUSED -> PlaybackState.STATE_PAUSED
            PlaybackStatus.ENDED -> PlaybackState.STATE_STOPPED
            PlaybackStatus.ERROR -> PlaybackState.STATE_ERROR
            PlaybackStatus.IDLE -> PlaybackState.STATE_NONE
        }

        val availableActions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_REWIND or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SET_PLAYBACK_SPEED

        val playbackState = PlaybackState.Builder()
            .setActions(availableActions)
            .setState(
                pbStateCode,
                state.currentTimeMs.coerceAtLeast(0L),
                if (state.isPlaying) state.playbackSpeed else 0f
            )
            .build()

        session.setPlaybackState(playbackState)
        session.isActive = media != null && state.status != PlaybackStatus.IDLE
    }

    private fun promoteOrUpdateNotification(state: LuminaPlayerState) {
        val notification = buildMediaNotification(state)
        val manager = getSystemService(NotificationManager::class.java)

        if (state.isPlaying && !isInForeground) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                isInForeground = true
            }
        } else if (isInForeground) {
            manager?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCleanly() {
        if (isInForeground) {
            runCatching {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            isInForeground = false
        }
    }

    private fun buildMediaNotification(state: LuminaPlayerState): Notification {
        val title = state.currentMedia?.title ?: getString(R.string.app_name)
        val subtitle = buildList {
            add(state.hdrStatus.sourceFormat.displayBadge)
            state.videoCodecLabel?.let { add(it) }
        }.joinToString(" • ")

        val contentIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_media_prev),
            getString(R.string.player_previous),
            serviceActionPendingIntent(ACTION_PREVIOUS, 11)
        ).build()

        val playPauseAction = Notification.Action.Builder(
            Icon.createWithResource(
                this,
                if (state.isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play
            ),
            getString(R.string.player_play_pause),
            serviceActionPendingIntent(ACTION_PLAY_PAUSE, 12)
        ).build()

        val nextAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_media_next),
            getString(R.string.player_next),
            serviceActionPendingIntent(ACTION_NEXT, 13)
        ).build()

        val mediaStyle = Notification.MediaStyle()
            .setMediaSession(mediaSession?.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(contentIntent)
            .setDeleteIntent(serviceActionPendingIntent(ACTION_STOP, 14))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(state.isPlaying)
            .setOnlyAlertOnce(true)
            .setStyle(mediaStyle)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .build()
    }

    private fun serviceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, LuminaPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.app_full_title),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.app_tagline)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "lumina_media_playback"
        private const val NOTIFICATION_ID = 4401

        const val ACTION_SYNC = "app.lumina.player.action.SYNC"
        const val ACTION_PLAY_PAUSE = "app.lumina.player.action.PLAY_PAUSE"
        const val ACTION_PREVIOUS = "app.lumina.player.action.PREVIOUS"
        const val ACTION_NEXT = "app.lumina.player.action.NEXT"
        const val ACTION_REWIND = "app.lumina.player.action.REWIND"
        const val ACTION_FORWARD = "app.lumina.player.action.FORWARD"
        const val ACTION_STOP = "app.lumina.player.action.STOP"

        fun syncPlaybackForegroundState(context: Context, isPlaying: Boolean) {
            val intent = Intent(context, LuminaPlaybackService::class.java).setAction(ACTION_SYNC)
            runCatching {
                if (isPlaying) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
