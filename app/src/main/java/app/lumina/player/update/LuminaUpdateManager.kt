package app.lumina.player.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import app.lumina.player.BuildConfig
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
import java.io.IOException
import java.net.URL
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class UpdateCheckStatus {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    UPDATE_AVAILABLE,
    DOWNLOADING,
    READY_TO_INSTALL,
    STORE_UNAVAILABLE
}

enum class UpdateSource {
    GITHUB_RELEASES,
    GOOGLE_PLAY
}

data class GithubReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

data class MatchedApkSelection(
    val asset: GithubReleaseAsset,
    val matchedAbi: String
)

private data class GithubReleaseSnapshot(
    val tagName: String,
    val htmlUrl: String,
    val isDraft: Boolean,
    val isPrerelease: Boolean,
    val assets: List<GithubReleaseAsset>
)

/**
 * Immutable UI state for Dual-Channel Updates (GitHub Releases APK + Google Play In-App Updates).
 */
data class LuminaUpdateState(
    val status: UpdateCheckStatus = UpdateCheckStatus.IDLE,
    val source: UpdateSource = UpdateSource.GITHUB_RELEASES,
    val isChecking: Boolean = false,
    val updateAvailable: Boolean = false,
    val isImmediateRequired: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgressPercent: Int = 0,
    val isReadyToInstall: Boolean = false,
    val installedVersionName: String = BuildConfig.VERSION_NAME,
    val availableVersionCode: Int? = null,
    val availableVersionName: String? = null,
    val downloadUrl: String? = null,
    val releasePageUrl: String? = null,
    val matchedAbiLabel: String? = null,
    val assetSizeLabel: String? = null,
    val isBannerDismissed: Boolean = false,
    val lastCheckedEpochMs: Long = 0L,
    val isManualCheckResultVisible: Boolean = false
)

/**
 * 100% Google Play Protect compliant Dual-Channel Automatic & Manual Update Manager:
 *
 * 1. **GitHub Releases Direct Channel (`petrosvassiliou/lumina`):**
 *    Queries `https://api.github.com/repos/petrosvassiliou/lumina/releases/latest` over HTTPS,
 *    compares semantic versions against `installedVersionName`, selects the exact per-ABI signed
 *    production `.apk` (`arm64-v8a`, `x86_64`, `armeabi-v7a`, `x86`, or `universal`) matching
 *    `Build.SUPPORTED_ABIS`, and launches the HTTPS `.apk` download via Android's trusted browser
 *    (`Intent.ACTION_VIEW`) — requiring **zero `REQUEST_INSTALL_PACKAGES` permission** so Google
 *    Play Protect never flags Lumina as an unknown package installer.
 * 2. **Google Play In-App Updates Channel (`com.google.android.play:app-update-ktx`):**
 *    Used automatically when Lumina is installed directly from Google Play (`com.android.vending`),
 *    with seamless fallback to GitHub Releases whenever Google Play is unavailable or has no update.
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
                        source = UpdateSource.GOOGLE_PLAY,
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
                        source = UpdateSource.GOOGLE_PLAY,
                        isChecking = false,
                        isDownloading = false,
                        downloadProgressPercent = 100,
                        isReadyToInstall = true,
                        isBannerDismissed = false
                    )
                }
            }

            InstallStatus.INSTALLED -> {
                _updateState.update {
                    it.copy(
                        status = UpdateCheckStatus.UP_TO_DATE,
                        isDownloading = false,
                        isReadyToInstall = false,
                        updateAvailable = false
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
        if (isInstalledFromGooglePlay(context)) {
            runCatching {
                appUpdateManager?.registerListener(installStateListener)
            }
        }
    }

    /**
     * Automatic background update check invoked on app launch and foreground resume.
     * Respects `UserPreferences.autoCheckUpdatesEnabled` and throttles successful checks to 15 minutes.
     */
    suspend fun checkAutomaticUpdatesIfDue(
        updateLauncher: ActivityResultLauncher<IntentSenderRequest>?
    ) {
        val prefs = preferences.userPreferencesFlow.first()
        if (!prefs.autoCheckUpdatesEnabled) return

        val now = System.currentTimeMillis()
        val elapsed = now - prefs.lastAutoUpdateCheckEpochMs
        if (prefs.lastAutoUpdateCheckEpochMs > 0L && elapsed in 0 until AUTO_CHECK_INTERVAL_MS) {
            _updateState.update { it.copy(lastCheckedEpochMs = prefs.lastAutoUpdateCheckEpochMs) }
            return
        }

        performUpdateCheck(
            updateLauncher = updateLauncher,
            isManualUserAction = false
        )
    }

    /**
     * Manual user-initiated update check from Settings.
     * Bypasses background throttling and always displays explicit status feedback.
     */
    suspend fun checkForUpdatesManually(
        updateLauncher: ActivityResultLauncher<IntentSenderRequest>?
    ) {
        performUpdateCheck(
            updateLauncher = updateLauncher,
            isManualUserAction = true
        )
    }

    /**
     * Unified entry point retained for callers specifying `forceUserFeedback`.
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

    private suspend fun performUpdateCheck(
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

        // 1. Try Google Play Core first ONLY if Lumina was installed from Google Play Store
        if (isInstalledFromGooglePlay(context)) {
            val playHandled = runCatching { awaitAppUpdateInfo() }
                .map { info ->
                    val hasPlayUpdate = info.installStatus() == InstallStatus.DOWNLOADED ||
                        info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                    if (hasPlayUpdate) {
                        preferences.setLastAutoUpdateCheckEpochMs(checkedAt)
                        handleAppUpdateInfo(
                            info = info,
                            updateLauncher = updateLauncher,
                            checkedAtEpochMs = checkedAt,
                            isManualUserAction = isManualUserAction
                        )
                        true
                    } else {
                        false
                    }
                }
                .getOrDefault(false)

            if (playHandled) return
        }

        // 2. Primary path for GitHub APK installations (& automatic fallback when not on Play Store)
        runCatching {
            fetchLatestGithubRelease()
        }.fold(
            onSuccess = { release ->
                preferences.setLastAutoUpdateCheckEpochMs(checkedAt)
                handleGithubReleaseInfo(
                    release = release,
                    checkedAtEpochMs = checkedAt,
                    isManualUserAction = isManualUserAction
                )
            },
            onFailure = {
                // Do NOT advance lastAutoUpdateCheckEpochMs on network error so the next launch retries
                _updateState.update {
                    it.copy(
                        status = if (isManualUserAction) {
                            UpdateCheckStatus.STORE_UNAVAILABLE
                        } else {
                            UpdateCheckStatus.IDLE
                        },
                        isChecking = false,
                        lastCheckedEpochMs = checkedAt,
                        isManualCheckResultVisible = isManualUserAction
                    )
                }
            }
        )
    }

    private suspend fun fetchLatestGithubRelease(): GithubReleaseSnapshot = withContext(Dispatchers.IO) {
        val connection = (URL(GITHUB_LATEST_RELEASE_API_URL).openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = NETWORK_TIMEOUT_MS
            readTimeout = NETWORK_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Lumina-Android/$installedVersion")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("GitHub Releases API returned HTTP $code")
            }

            val responseBody = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(responseBody)
            val tagName = json.optString("tag_name", "").trim()
            if (tagName.isEmpty()) {
                throw IOException("GitHub Releases payload missing tag_name")
            }

            val htmlUrl = json.optString("html_url", GITHUB_LATEST_RELEASE_PAGE_URL)
                .ifBlank { GITHUB_LATEST_RELEASE_PAGE_URL }
            val isDraft = json.optBoolean("draft", false)
            val isPrerelease = json.optBoolean("prerelease", false)

            val assetsArray = json.optJSONArray("assets")
            val parsedAssets = buildList {
                if (assetsArray != null) {
                    for (i in 0 until assetsArray.length()) {
                        val assetObj = assetsArray.optJSONObject(i) ?: continue
                        val name = assetObj.optString("name", "").trim()
                        val downloadUrl = assetObj.optString("browser_download_url", "").trim()
                        val sizeBytes = assetObj.optLong("size", 0L)
                        if (name.isNotEmpty() && downloadUrl.isNotEmpty()) {
                            add(
                                GithubReleaseAsset(
                                    name = name,
                                    downloadUrl = downloadUrl,
                                    sizeBytes = sizeBytes
                                )
                            )
                        }
                    }
                }
            }

            GithubReleaseSnapshot(
                tagName = tagName,
                htmlUrl = htmlUrl,
                isDraft = isDraft,
                isPrerelease = isPrerelease,
                assets = parsedAssets
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun handleGithubReleaseInfo(
        release: GithubReleaseSnapshot,
        checkedAtEpochMs: Long,
        isManualUserAction: Boolean
    ) {
        val cleanRemoteVersion = normalizeVersionString(release.tagName)
        val isNewer = !release.isDraft &&
            !release.isPrerelease &&
            isRemoteVersionNewer(
                installedVersion = installedVersion,
                remoteTagOrVersion = release.tagName
            )

        if (!isNewer) {
            _updateState.update {
                it.copy(
                    status = UpdateCheckStatus.UP_TO_DATE,
                    source = UpdateSource.GITHUB_RELEASES,
                    isChecking = false,
                    updateAvailable = false,
                    availableVersionName = cleanRemoteVersion.ifEmpty { null },
                    releasePageUrl = release.htmlUrl,
                    lastCheckedEpochMs = checkedAtEpochMs,
                    isManualCheckResultVisible = isManualUserAction
                )
            }
            return
        }

        val supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val matchedApk = selectBestApkAsset(
            assets = release.assets,
            supportedAbis = supportedAbis
        )
        val targetDownloadUrl = matchedApk?.asset?.downloadUrl ?: release.htmlUrl
        val formattedSize = matchedApk?.asset?.sizeBytes
            ?.takeIf { it > 0L }
            ?.let(::formatMegabytes)

        _updateState.update { prev ->
            val keepDismissed = prev.availableVersionName == cleanRemoteVersion && prev.isBannerDismissed && !isManualUserAction
            prev.copy(
                status = UpdateCheckStatus.UPDATE_AVAILABLE,
                source = UpdateSource.GITHUB_RELEASES,
                isChecking = false,
                updateAvailable = true,
                isImmediateRequired = false,
                isDownloading = false,
                isReadyToInstall = false,
                availableVersionName = cleanRemoteVersion,
                downloadUrl = targetDownloadUrl,
                releasePageUrl = release.htmlUrl,
                matchedAbiLabel = matchedApk?.matchedAbi,
                assetSizeLabel = formattedSize,
                isBannerDismissed = keepDismissed,
                lastCheckedEpochMs = checkedAtEpochMs,
                isManualCheckResultVisible = true
            )
        }
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
     * Called in `Activity.onResume` to resume pending Google Play updates when installed via Play Store.
     */
    suspend fun resumePendingUpdatesIfNeeded(
        activity: Activity,
        updateLauncher: ActivityResultLauncher<IntentSenderRequest>?
    ) {
        if (!isInstalledFromGooglePlay(context)) return
        val manager = appUpdateManager ?: return
        runCatching {
            val info = awaitAppUpdateInfo()
            if (info.installStatus() == InstallStatus.DOWNLOADED) {
                _updateState.update {
                    it.copy(
                        status = UpdateCheckStatus.READY_TO_INSTALL,
                        source = UpdateSource.GOOGLE_PLAY,
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
     * Executes the primary update action when the user taps the Update Banner or Settings Update pill:
     * - **Google Play Channel:** Completes the already-downloaded Flexible update (`completeUpdate()`).
     * - **GitHub Releases Channel:** Opens the exact ABI-matched signed `.apk` HTTPS URL in the user's
     *   browser so Android downloads and installs the update in-place with 100% Play Protect compliance.
     */
    fun completeFlexibleUpdate() {
        val snapshot = _updateState.value
        if (snapshot.source == UpdateSource.GOOGLE_PLAY && snapshot.isReadyToInstall) {
            runCatching { appUpdateManager?.completeUpdate() }
            return
        }

        val targetUrl = snapshot.downloadUrl
            ?: snapshot.releasePageUrl
            ?: GITHUB_LATEST_RELEASE_PAGE_URL

        val launched = runCatching {
            val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(viewIntent)
            true
        }.getOrDefault(false)

        if (!launched && targetUrl != GITHUB_LATEST_RELEASE_PAGE_URL) {
            runCatching {
                val fallbackIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(GITHUB_LATEST_RELEASE_PAGE_URL)
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            }
        }
    }

    fun dismissUpdateBanner() {
        _updateState.update { it.copy(isBannerDismissed = true) }
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
                    source = UpdateSource.GOOGLE_PLAY,
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
                    source = UpdateSource.GOOGLE_PLAY,
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
                source = UpdateSource.GOOGLE_PLAY,
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

    private fun isInstalledFromGooglePlay(context: Context): Boolean = runCatching {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
        installer == GOOGLE_PLAY_INSTALLER
    }.getOrDefault(false)

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
        pkgInfo.versionName ?: BuildConfig.VERSION_NAME
    }.getOrDefault(BuildConfig.VERSION_NAME)

    companion object {
        private const val GITHUB_LATEST_RELEASE_API_URL =
            "https://api.github.com/repos/petrosvassiliou/lumina/releases/latest"
        const val GITHUB_LATEST_RELEASE_PAGE_URL =
            "https://github.com/petrosvassiliou/lumina/releases/latest"
        private const val GOOGLE_PLAY_INSTALLER = "com.android.vending"
        private const val NETWORK_TIMEOUT_MS = 8_000
        private const val AUTO_CHECK_INTERVAL_MS = 15L * 60L * 1000L // 15 minutes
        private const val CRITICAL_UPDATE_PRIORITY = 4
        private const val CRITICAL_STALENESS_DAYS = 14
        private const val REQUEST_CODE_IMMEDIATE_FALLBACK = 9410

        internal fun normalizeVersionString(raw: String): String =
            raw.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('-')
                .substringBefore('+')
                .trim()

        internal fun isRemoteVersionNewer(
            installedVersion: String,
            remoteTagOrVersion: String
        ): Boolean {
            val installedClean = normalizeVersionString(installedVersion)
            val remoteClean = normalizeVersionString(remoteTagOrVersion)
            if (remoteClean.isEmpty() || installedClean.isEmpty()) return false

            val installedParts = installedClean.split('.').map { it.toIntOrNull() ?: 0 }
            val remoteParts = remoteClean.split('.').map { it.toIntOrNull() ?: 0 }
            val maxSegments = maxOf(installedParts.size, remoteParts.size)

            for (i in 0 until maxSegments) {
                val installedSeg = installedParts.getOrElse(i) { 0 }
                val remoteSeg = remoteParts.getOrElse(i) { 0 }
                if (remoteSeg != installedSeg) {
                    return remoteSeg > installedSeg
                }
            }
            return false
        }

        internal fun selectBestApkAsset(
            assets: List<GithubReleaseAsset>,
            supportedAbis: List<String>
        ): MatchedApkSelection? {
            val apkAssets = assets.filter {
                it.name.endsWith(".apk", ignoreCase = true) &&
                    it.downloadUrl.startsWith("https://", ignoreCase = true)
            }
            if (apkAssets.isEmpty()) return null

            for (abi in supportedAbis) {
                val normalizedAbi = abi.trim().lowercase(Locale.ROOT)
                if (normalizedAbi.isEmpty()) continue
                val exactMatch = apkAssets.firstOrNull { asset ->
                    val lower = asset.name.lowercase(Locale.ROOT)
                    lower.contains("-$normalizedAbi.apk") ||
                        lower.contains("-$normalizedAbi-") ||
                        lower.contains("_$normalizedAbi.")
                }
                if (exactMatch != null) {
                    return MatchedApkSelection(asset = exactMatch, matchedAbi = abi.trim())
                }
            }

            val universalMatch = apkAssets.firstOrNull { asset ->
                asset.name.lowercase(Locale.ROOT).contains("universal")
            }
            if (universalMatch != null) {
                return MatchedApkSelection(asset = universalMatch, matchedAbi = "universal")
            }

            return MatchedApkSelection(asset = apkAssets.first(), matchedAbi = "apk")
        }

        internal fun formatMegabytes(bytes: Long): String {
            val mb = bytes.toDouble() / (1024.0 * 1024.0)
            return String.format(Locale.US, "%.1f MB", mb)
        }
    }
}
