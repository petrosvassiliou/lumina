package app.lumina.player.update

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import app.lumina.player.data.preferences.LuminaPreferences
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine

enum class UpdateCheckStatus {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    DOWNLOADING,
    READY_TO_INSTALL,
    STORE_UNAVAILABLE
}

/**
 * Immutable UI state for Google Play Official In-App Updates (Automatic & Manual).
 */
data class LuminaUpdateState(
    val status: UpdateCheckStatus = UpdateCheckStatus.IDLE,
    val isChecking: Boolean = false,
    val updateAvailable: Boolean = false,
    val isImmediateRequired: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgressPercent: Int = 0,
    val isReadyToInstall: Boolean = false,
    val installedVersionName: String = "1.0.2",
    val availableVersionCode: Int? = null,
    val lastCheckedEpochMs: Long = 0L,
    val isManualCheckResultVisible: Boolean = false
)

/**
 * 100% Google Play Protect compliant Automatic & Manual update manager built strictly on the official
 * Google Play In-App Updates API (`com.google.android.play:app-update-ktx`).
 *
 * - Never downloads arbitrary native binaries or side-loaded APKs (`REQUEST_INSTALL_PACKAGES` is absent).
 * - **Automatic Mode:** Checks Google Play on launch & foreground resume (respecting user toggle and
 *   a 6-hour background throttle), triggering **Immediate** updates for critical security patches
 *   (`updatePriority >= 4` or `staleness >= 14 days`) and **Flexible** background downloads otherwise.
 * - **Manual Mode:** Triggered from Settings ("Check for Updates Now"), immediately querying Google Play
 *   and surfacing clear Play Protect verification feedback (`UP_TO_DATE`, `DOWNLOADING`, `READY_TO_INSTALL`,
 *   or `STORE_UNAVAILABLE` on non-Play/offline devices).
 */
@Singleton
class LuminaUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: LuminaPreferences
) {
    private val appUpdateManager: AppUpdateManager? = runCatching {
        AppUpdateManagerFactory.create(context)
    }.getOrNull()

    private val installedVersion: String = resolveInstalledVersionName(context)

    private val _updateState = MutableStateFlow(
        LuminaUpdateState(installedVersionName = installedVersion)
    )
    val updateState: StateFlow<LuminaUpdateState> = _updateState.asStateFlow()

    private val installStateListener = InstallStateUpdatedListener { state ->
        when (state.installStatus()) {
            InstallStatus.DOWNLOADING -> {
                val total = state.totalBytesToDownload()
                val downloaded = state.bytesDownloaded()
                val percent = if (total > 0L) {
                    ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                } else {
                    0
                }
                _updateState.update {
                    it.copy(
                        status = UpdateCheckStatus.DOWNLOADING,
                        isChecking = false,
                        isDownloading = true,
                        downloadProgressPercent = percent,
                        isReadyToInstall = false
                    )
                }
            }

            InstallStatus.DOWNLOADED -> {
                _updateState.update {
                    it.copy(
                        status = UpdateCheckStatus.READY_TO_INSTALL,
                        isChecking = false,
                        isDownloading = false,
                        downloadProgressPercent = 100,
                        isReadyToInstall = true
                    )
                }
            }

            InstallStatus.INSTALLED -> {
                _updateState.update {
                    it.copy(
                        status = UpdateCheckStatus.UP_TO_DATE,
                        isDownloading = false,
                        isReadyToInstall = false
                    )
                }
            }

            InstallStatus.CANCELED,
            InstallStatus.FAILED -> {
                _updateState.update {
                    it.copy(
                        status = UpdateCheckStatus.IDLE,
                        isDownloading = false,
                        isReadyToInstall = false
                    )
                }
            }

            else -> Unit
        }
    }

    init {
        runCatching {
            appUpdateManager?.registerListener(installStateListener)
        }
    }

    /**
     * Automatic background Play Store update check invoked on app launch and foreground resume.
     * Respects `UserPreferences.autoCheckUpdatesEnabled` and throttles network checks to once every 6 hours.
     */
    suspend fun checkAutomaticUpdatesIfDue(
        updateLauncher: ActivityResultLauncher<IntentSenderRequest>?
    ) {
        val prefs = preferences.userPreferencesFlow.first()
        if (!prefs.autoCheckUpdatesEnabled) return

        val now = System.currentTimeMillis()
        val elapsed = now - prefs.lastAutoUpdateCheckEpochMs
        if (prefs.lastAutoUpdateCheckEpochMs > 0L && elapsed < AUTO_CHECK_INTERVAL_MS) {
            _updateState.update { it.copy(lastCheckedEpochMs = prefs.lastAutoUpdateCheckEpochMs) }
            return
        }

        performPlayStoreCheck(
            updateLauncher = updateLauncher,
            isManualUserAction = false
        )
    }

    /**
     * Manual user-initiated Play Store update check from Settings.
     * Bypasses background throttling and always reports explicit Play Protect verification status.
     */
    suspend fun checkForUpdatesManually(
        updateLauncher: ActivityResultLauncher<IntentSenderRequest>?
    ) {
        performPlayStoreCheck(
            updateLauncher = updateLauncher,
            isManualUserAction = true
        )
    }

    /**
     * Unified entry point retained for compatibility with callers specifying `forceUserFeedback`.
     */
    suspend fun checkForUpdates(
        updateLauncher: ActivityResultLauncher<IntentSenderRequest>?,
        forceUserFeedback: Boolean = false
    ) {
        if (forceUserFeedback) {
            checkForUpdatesManually(updateLauncher)
        } else {
            checkAutomaticUpdatesIfDue(updateLauncher)
        }
    }

    private suspend fun performPlayStoreCheck(
        updateLauncher: ActivityResultLauncher<IntentSenderRequest>?,
        isManualUserAction: Boolean
    ) {
        if (_updateState.value.isChecking) return

        _updateState.update {
            it.copy(
                status = UpdateCheckStatus.CHECKING,
                isChecking = true,
                isManualCheckResultVisible = isManualUserAction
            )
        }

        val checkedAt = System.currentTimeMillis()
        preferences.setLastAutoUpdateCheckEpochMs(checkedAt)

        runCatching {
            awaitAppUpdateInfo()
        }.fold(
            onSuccess = { info ->
                handleAppUpdateInfo(
                    info = info,
                    updateLauncher = updateLauncher,
                    checkedAtEpochMs = checkedAt,
                    isManualUserAction = isManualUserAction
                )
            },
            onFailure = {
                // Gracefully handle offline / non-Play environments without crashing
                _updateState.update {
                    it.copy(
                        status = if (isManualUserAction) {
                            UpdateCheckStatus.STORE_UNAVAILABLE
                        } else {
                            UpdateCheckStatus.IDLE
                        },
                        isChecking = false,
                        updateAvailable = false,
                        lastCheckedEpochMs = checkedAt,
                        isManualCheckResultVisible = isManualUserAction
                    )
                }
            }
        )
    }

    private suspend fun awaitAppUpdateInfo(): AppUpdateInfo {
        val manager = appUpdateManager
            ?: throw IllegalStateException("Google Play In-App Updates service unavailable")
        return suspendCancellableCoroutine { cont ->
            manager.appUpdateInfo
                .addOnSuccessListener { info ->
                    if (cont.isActive) cont.resume(info)
                }
                .addOnFailureListener { err ->
                    if (cont.isActive) cont.resumeWithException(err)
                }
        }
    }

    /**
     * Called in `Activity.onResume` to resume stalled Immediate updates, surface already-downloaded
     * Flexible updates waiting for restart, and trigger due automatic checks.
     */
    suspend fun resumePendingUpdatesIfNeeded(
        activity: Activity,
        updateLauncher: ActivityResultLauncher<IntentSenderRequest>?
    ) {
        val manager = appUpdateManager ?: return
        runCatching {
            val info = awaitAppUpdateInfo()
            if (info.installStatus() == InstallStatus.DOWNLOADED) {
                _updateState.update {
                    it.copy(
                        status = UpdateCheckStatus.READY_TO_INSTALL,
                        isDownloading = false,
                        downloadProgressPercent = 100,
                        isReadyToInstall = true
                    )
                }
            } else if (
                info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS &&
                info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
            ) {
                if (updateLauncher != null) {
                    manager.startUpdateFlowForResult(
                        info,
                        updateLauncher,
                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    manager.startUpdateFlowForResult(
                        info,
                        AppUpdateType.IMMEDIATE,
                        activity,
                        REQUEST_CODE_IMMEDIATE_FALLBACK
                    )
                }
            }
        }
    }

    /**
     * Completes a Flexible update after the user taps "Restart & Update" on the glassmorphic banner.
     */
    fun completeFlexibleUpdate() {
        runCatching { appUpdateManager?.completeUpdate() }
    }

    fun dismissUpdatePrompt() {
        _updateState.update { it.copy(isManualCheckResultVisible = false) }
    }

    fun unregister() {
        runCatching { appUpdateManager?.unregisterListener(installStateListener) }
    }

    private fun handleAppUpdateInfo(
        info: AppUpdateInfo,
        updateLauncher: ActivityResultLauncher<IntentSenderRequest>?,
        checkedAtEpochMs: Long,
        isManualUserAction: Boolean
    ) {
        if (info.installStatus() == InstallStatus.DOWNLOADED) {
            _updateState.update {
                it.copy(
                    status = UpdateCheckStatus.READY_TO_INSTALL,
                    isChecking = false,
                    updateAvailable = true,
                    isReadyToInstall = true,
                    downloadProgressPercent = 100,
                    availableVersionCode = info.availableVersionCode(),
                    lastCheckedEpochMs = checkedAtEpochMs,
                    isManualCheckResultVisible = true
                )
            }
            return
        }

        val isAvailable = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
        if (!isAvailable) {
            _updateState.update {
                it.copy(
                    status = UpdateCheckStatus.UP_TO_DATE,
                    isChecking = false,
                    updateAvailable = false,
                    lastCheckedEpochMs = checkedAtEpochMs,
                    isManualCheckResultVisible = isManualUserAction
                )
            }
            return
        }

        val stalenessDays = info.clientVersionStalenessDays() ?: 0
        val priority = info.updatePriority()
        val shouldUseImmediate =
            (priority >= CRITICAL_UPDATE_PRIORITY || stalenessDays >= CRITICAL_STALENESS_DAYS) &&
                info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)

        _updateState.update {
            it.copy(
                status = UpdateCheckStatus.UPDATE_AVAILABLE,
                isChecking = false,
                updateAvailable = true,
                isImmediateRequired = shouldUseImmediate,
                availableVersionCode = info.availableVersionCode(),
                lastCheckedEpochMs = checkedAtEpochMs,
                isManualCheckResultVisible = true
            )
        }

        val manager = appUpdateManager ?: return
        if (updateLauncher == null) return

        runCatching {
            if (shouldUseImmediate) {
                manager.startUpdateFlowForResult(
                    info,
                    updateLauncher,
                    AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                )
            } else if (info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                manager.startUpdateFlowForResult(
                    info,
                    updateLauncher,
                    AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()
                )
            }
        }
    }

    private fun resolveInstalledVersionName(context: Context): String = runCatching {
        val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        pkgInfo.versionName ?: "1.0.2"
    }.getOrDefault("1.0.2")

    companion object {
        private const val AUTO_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L // 6 hours
        private const val CRITICAL_UPDATE_PRIORITY = 4
        private const val CRITICAL_STALENESS_DAYS = 14
        private const val REQUEST_CODE_IMMEDIATE_FALLBACK = 9410
    }
}

