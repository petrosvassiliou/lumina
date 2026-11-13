package app.lumina.player

import app.lumina.player.player.engine.LuminaPlayerEngine
import app.lumina.player.player.model.ABRepeatState
import app.lumina.player.player.model.PlaybackStatus
import app.lumina.player.update.LuminaUpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive JVM Unit Test Suite verifying Lumina's playback state machine,
 * post-EOF timeline scrubbing & resume at any timestamp (`0`, sub-second, mid-stream, tail),
 * libVLC 4.0 initial seek gating (`PLAYING` + `isSeekable`), pre-seek `TimeChanged` suppression,
 * `LengthChanged` duration/progress integrity, A-B Repeat manual-seek interaction,
 * blank-video watchdog buffering/seek guards, and error recovery.
 */
class LuminaPlaybackRobustnessTest {

    @Test
    fun restartRequiredStatus_coversEndedIdleAndErrorOnly() {
        assertTrue(LuminaPlayerEngine.isRestartRequiredStatus(PlaybackStatus.ENDED))
        assertTrue(LuminaPlayerEngine.isRestartRequiredStatus(PlaybackStatus.IDLE))
        assertTrue(LuminaPlayerEngine.isRestartRequiredStatus(PlaybackStatus.ERROR))

        assertFalse(LuminaPlayerEngine.isRestartRequiredStatus(PlaybackStatus.PLAYING))
        assertFalse(LuminaPlayerEngine.isRestartRequiredStatus(PlaybackStatus.PAUSED))
        assertFalse(LuminaPlayerEngine.isRestartRequiredStatus(PlaybackStatus.OPENING))
        assertFalse(LuminaPlayerEngine.isRestartRequiredStatus(PlaybackStatus.BUFFERING))
    }

    @Test
    fun postEndRestartPosition_handlesZeroSubSecondMidStreamTailAndErrorResume() {
        val durationMs = 120_000L

        // 1. Replay at EOF without scrubbing -> 0:00
        assertEquals(
            0L,
            LuminaPlayerEngine.resolvePostEndRestartPositionMs(
                requestedSeekMs = null,
                durationMs = durationMs
            )
        )

        // 2. Scrub explicitly to 0:00 -> 0:00
        assertEquals(
            0L,
            LuminaPlayerEngine.resolvePostEndRestartPositionMs(
                requestedSeekMs = 0L,
                durationMs = durationMs
            )
        )

        // 3. Scrub to sub-second (400ms), 1s, 18.4s, 50% (60s), and 95% (114s) -> exact target preserved
        for (target in listOf(400L, 1_000L, 18_400L, 60_000L, 114_000L)) {
            assertEquals(
                target,
                LuminaPlayerEngine.resolvePostEndRestartPositionMs(
                    requestedSeekMs = target,
                    durationMs = durationMs
                )
            )
        }

        // 4. Scrub to extreme tail within 500ms of EOF -> wraps cleanly to 0:00 so it never re-hits EOF
        assertEquals(
            0L,
            LuminaPlayerEngine.resolvePostEndRestartPositionMs(
                requestedSeekMs = 119_700L,
                durationMs = durationMs
            )
        )

        // 5. Recovery from PlaybackStatus.ERROR without scrubbing resumes at last error timestamp
        assertEquals(
            42_500L,
            LuminaPlayerEngine.resolvePostEndRestartPositionMs(
                requestedSeekMs = null,
                durationMs = durationMs,
                fallbackErrorPositionMs = 42_500L
            )
        )

        // 6. Recovery from PlaybackStatus.ERROR after scrubbing honors user's scrubbed position
        assertEquals(
            15_000L,
            LuminaPlayerEngine.resolvePostEndRestartPositionMs(
                requestedSeekMs = 15_000L,
                durationMs = durationMs,
                fallbackErrorPositionMs = 42_500L
            )
        )
    }

    @Test
    fun resolveInitialSeekTargetMs_prioritizesExplicitScrubOverFallbackAndAutoResume() {
        // Explicit 0:00 scrub after EOF needs no post-open seek (starts naturally at 0:00)
        assertNull(
            LuminaPlayerEngine.resolveInitialSeekTargetMs(
                explicitStartTimeMs = 0L,
                fallbackResumeTimeMs = null,
                skipAutoResume = true,
                autoResumeEnabled = true,
                savedCompleted = false,
                savedPositionMs = 50_000L,
                knownDurationMs = 120_000L
            )
        )

        // Explicit positive scrub (including sub-second 350ms or mid-timeline 37_200ms) is preserved exactly
        assertEquals(
            350L,
            LuminaPlayerEngine.resolveInitialSeekTargetMs(
                explicitStartTimeMs = 350L,
                fallbackResumeTimeMs = null,
                skipAutoResume = true,
                autoResumeEnabled = true,
                savedCompleted = true,
                savedPositionMs = 0L,
                knownDurationMs = 120_000L
            )
        )
        assertEquals(
            37_200L,
            LuminaPlayerEngine.resolveInitialSeekTargetMs(
                explicitStartTimeMs = 37_200L,
                fallbackResumeTimeMs = 10_000L,
                skipAutoResume = true,
                autoResumeEnabled = true,
                savedCompleted = true,
                savedPositionMs = 0L,
                knownDurationMs = 120_000L
            )
        )

        // Decoder step-down fallback preserves playback position > 250ms
        assertEquals(
            24_800L,
            LuminaPlayerEngine.resolveInitialSeekTargetMs(
                explicitStartTimeMs = null,
                fallbackResumeTimeMs = 24_800L,
                skipAutoResume = false,
                autoResumeEnabled = false,
                savedCompleted = false,
                savedPositionMs = 0L,
                knownDurationMs = 120_000L
            )
        )

        // Saved Room auto-resume applies only when enabled, not completed, > 3s, and > 5s from EOF
        assertEquals(
            48_000L,
            LuminaPlayerEngine.resolveInitialSeekTargetMs(
                explicitStartTimeMs = null,
                fallbackResumeTimeMs = null,
                skipAutoResume = false,
                autoResumeEnabled = true,
                savedCompleted = false,
                savedPositionMs = 48_000L,
                knownDurationMs = 120_000L
            )
        )
        assertNull(
            LuminaPlayerEngine.resolveInitialSeekTargetMs(
                explicitStartTimeMs = null,
                fallbackResumeTimeMs = null,
                skipAutoResume = false,
                autoResumeEnabled = true,
                savedCompleted = true,
                savedPositionMs = 48_000L,
                knownDurationMs = 120_000L
            )
        )
        assertNull(
            LuminaPlayerEngine.resolveInitialSeekTargetMs(
                explicitStartTimeMs = null,
                fallbackResumeTimeMs = null,
                skipAutoResume = false,
                autoResumeEnabled = true,
                savedCompleted = false,
                savedPositionMs = 117_000L,
                knownDurationMs = 120_000L
            )
        )
    }

    @Test
    fun canApplyInitialSeekNow_requiresBothPlayingStatusAndSeekableCapability() {
        // Must NEVER fire prematurely during OPENING or BUFFERING or when seekable == false
        assertFalse(LuminaPlayerEngine.canApplyInitialSeekNow(PlaybackStatus.OPENING, stateSeekable = false, nativeSeekable = false))
        assertFalse(LuminaPlayerEngine.canApplyInitialSeekNow(PlaybackStatus.OPENING, stateSeekable = true, nativeSeekable = true))
        assertFalse(LuminaPlayerEngine.canApplyInitialSeekNow(PlaybackStatus.BUFFERING, stateSeekable = true, nativeSeekable = true))
        assertFalse(LuminaPlayerEngine.canApplyInitialSeekNow(PlaybackStatus.PLAYING, stateSeekable = false, nativeSeekable = false))

        // Fires cleanly once PLAYING and either SeekableChanged(true) or player.isSeekable is true
        assertTrue(LuminaPlayerEngine.canApplyInitialSeekNow(PlaybackStatus.PLAYING, stateSeekable = true, nativeSeekable = false))
        assertTrue(LuminaPlayerEngine.canApplyInitialSeekNow(PlaybackStatus.PLAYING, stateSeekable = false, nativeSeekable = true))
        assertTrue(LuminaPlayerEngine.canApplyInitialSeekNow(PlaybackStatus.PLAYING, stateSeekable = true, nativeSeekable = true))
    }

    @Test
    fun preSeekTimeChangedSuppression_blocksStaleZeroTicksAndAllowsLandedTicksThrough() {
        // 1. Before initial seek is applied, suppress demuxer 0:00 ticks when a positive seek is pending
        assertTrue(
            LuminaPlayerEngine.shouldSuppressPreSeekTimeChanged(
                hasAppliedInitialSeek = false,
                pendingTargetMs = 25_000L,
                guardTargetMs = null,
                nowWallMs = 1_000L,
                guardDeadlineWallMs = 0L,
                reportedTimeMs = 120L
            )
        )

        // 2. When playing from 0:00 (no pending seek), never suppress 0:00 ticks
        assertFalse(
            LuminaPlayerEngine.shouldSuppressPreSeekTimeChanged(
                hasAppliedInitialSeek = true,
                pendingTargetMs = null,
                guardTargetMs = null,
                nowWallMs = 1_000L,
                guardDeadlineWallMs = 0L,
                reportedTimeMs = 80L
            )
        )

        // 3. Immediately after player.setTime(25_000L, false), suppress in-flight pre-seek 0..800ms tick
        assertTrue(
            LuminaPlayerEngine.shouldSuppressPreSeekTimeChanged(
                hasAppliedInitialSeek = true,
                pendingTargetMs = null,
                guardTargetMs = 25_000L,
                nowWallMs = 1_200L,
                guardDeadlineWallMs = 2_800L,
                reportedTimeMs = 250L
            )
        )

        // 4. Once demuxer lands at ~25_000ms, allow ticks immediately and mark seek landed
        assertFalse(
            LuminaPlayerEngine.shouldSuppressPreSeekTimeChanged(
                hasAppliedInitialSeek = true,
                pendingTargetMs = null,
                guardTargetMs = 25_000L,
                nowWallMs = 1_300L,
                guardDeadlineWallMs = 2_800L,
                reportedTimeMs = 24_920L
            )
        )
        assertTrue(LuminaPlayerEngine.hasInitialSeekLanded(guardTargetMs = 25_000L, reportedTimeMs = 24_920L))
        assertFalse(LuminaPlayerEngine.hasInitialSeekLanded(guardTargetMs = 25_000L, reportedTimeMs = 150L))

        // 5. Once guard window expires, never suppress even if container jumped to an earlier keyframe
        assertFalse(
            LuminaPlayerEngine.shouldSuppressPreSeekTimeChanged(
                hasAppliedInitialSeek = true,
                pendingTargetMs = null,
                guardTargetMs = 25_000L,
                nowWallMs = 3_000L,
                guardDeadlineWallMs = 2_800L,
                reportedTimeMs = 250L
            )
        )
    }

    @Test
    fun lengthChangedResolution_preservesKnownDurationAgainstTransientZeroAndComputesExactProgress() {
        // Transient LengthChanged(0) during Opening must NEVER wipe out known 60s duration or mid-timeline scrub progress
        val (keptDuration, keptProgress) = LuminaPlayerEngine.resolveUpdatedDurationAndProgress(
            reportedLengthMs = 0L,
            previousDurationMs = 60_000L,
            currentTimeMs = 30_000L
        )
        assertEquals(60_000L, keptDuration)
        assertEquals(0.5f, keptProgress, 0.0001f)

        // Real LengthChanged(90_000L) on SAF/network stream with unknown initial duration updates both duration & progress
        val (updatedDuration, updatedProgress) = LuminaPlayerEngine.resolveUpdatedDurationAndProgress(
            reportedLengthMs = 90_000L,
            previousDurationMs = 0L,
            currentTimeMs = 22_500L
        )
        assertEquals(90_000L, updatedDuration)
        assertEquals(0.25f, updatedProgress, 0.0001f)
    }

    @Test
    fun abRepeatManualSeek_keepsLoopInsideWindowAndReleasesLoopWhenUserScrubsOutside() {
        val activeLoop = ABRepeatState(pointAMs = 10_000L, pointBMs = 20_000L)
        assertTrue(activeLoop.isActive)

        // Seeking inside [A, B] keeps the A-B repeat loop active
        assertFalse(LuminaPlayerEngine.shouldClearAbRepeatOnManualSeek(activeLoop, targetSeekMs = 14_000L))
        assertFalse(LuminaPlayerEngine.shouldClearAbRepeatOnManualSeek(activeLoop, targetSeekMs = 10_000L))

        // Explicitly scrubbing past Point B or well before Point A clears A-B repeat instead of trapping the user
        assertTrue(LuminaPlayerEngine.shouldClearAbRepeatOnManualSeek(activeLoop, targetSeekMs = 35_000L))
        assertTrue(LuminaPlayerEngine.shouldClearAbRepeatOnManualSeek(activeLoop, targetSeekMs = 2_000L))
    }

    @Test
    fun blankVideoWatchdog_neverFalseTriggersDuringBufferingOrFreshSeekAndCatchesBrokenHardwareVout() {
        // 1. Must NOT trigger while stream is still buffering a 4K/NAS seek
        assertFalse(
            LuminaPlayerEngine.shouldTriggerBlankVideoWatchdog(
                isPlaying = true,
                status = PlaybackStatus.PLAYING,
                bufferingPercent = 65f,
                activeVoutCount = 0,
                decodedVideoFrames = 0L,
                displayedPictures = 0L,
                elapsedSincePlayOrSeekWallMs = 2_000L
            )
        )

        // 2. Must NOT trigger immediately after an initial/manual seek (< 1100ms elapsed)
        assertFalse(
            LuminaPlayerEngine.shouldTriggerBlankVideoWatchdog(
                isPlaying = true,
                status = PlaybackStatus.PLAYING,
                bufferingPercent = 100f,
                activeVoutCount = 1,
                decodedVideoFrames = 0L,
                displayedPictures = 0L,
                elapsedSincePlayOrSeekWallMs = 450L
            )
        )

        // 3. Triggers fallback when unbuffered playback has elapsed >= 1.1s with zero vout or zero decoded frames
        assertTrue(
            LuminaPlayerEngine.shouldTriggerBlankVideoWatchdog(
                isPlaying = true,
                status = PlaybackStatus.PLAYING,
                bufferingPercent = 100f,
                activeVoutCount = 0,
                decodedVideoFrames = 12L,
                displayedPictures = 0L,
                elapsedSincePlayOrSeekWallMs = 1_500L
            )
        )
        assertTrue(
            LuminaPlayerEngine.shouldTriggerBlankVideoWatchdog(
                isPlaying = true,
                status = PlaybackStatus.PLAYING,
                bufferingPercent = 100f,
                activeVoutCount = 1,
                decodedVideoFrames = 0L,
                displayedPictures = 0L,
                elapsedSincePlayOrSeekWallMs = 1_500L
            )
        )

        // 4. Healthy hardware playback never triggers fallback
        assertFalse(
            LuminaPlayerEngine.shouldTriggerBlankVideoWatchdog(
                isPlaying = true,
                status = PlaybackStatus.PLAYING,
                bufferingPercent = 100f,
                activeVoutCount = 1,
                decodedVideoFrames = 48L,
                displayedPictures = 45L,
                elapsedSincePlayOrSeekWallMs = 1_600L
            )
        )
    }

    @Test
    fun versionComparison_recognizesV1_0_10UpgradeOverV1_0_9() {
        assertTrue(LuminaUpdateManager.isRemoteVersionNewer("1.0.9", "v1.0.10"))
        assertFalse(LuminaUpdateManager.isRemoteVersionNewer("1.0.10", "v1.0.10"))
    }
}
