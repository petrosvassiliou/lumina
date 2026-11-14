package app.lumina.player.data.network

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import androidx.core.content.getSystemService
import app.lumina.player.di.ApplicationScope
import app.lumina.player.di.MainDispatcher
import app.lumina.player.player.engine.LuminaPlayerEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaDiscoverer
import org.videolan.libvlc.interfaces.IMedia

/**
 * Represents a discovered local network media server or share (UPnP/DLNA, SMB/DSM, mDNS).
 */
data class LanDiscoveredEndpoint(
    val uri: String,
    val title: String,
    val protocolBadge: String,
    val serviceName: String,
    val isDirectory: Boolean
)

/**
 * Breadcrumb segment while recursively browsing an SMB / UPnP / DLNA / FTP directory tree.
 */
data class LanRemoteBreadcrumb(
    val uri: String,
    val label: String,
    val protocolBadge: String
)

/**
 * Remote folder or media item returned when browsing inside a LAN share.
 */
data class LanRemoteEntry(
    val uri: String,
    val name: String,
    val isDirectory: Boolean,
    val protocolBadge: String,
    val durationMs: Long = 0L,
    val hdrBadge: String? = null
)

sealed interface LanBrowseResult {
    data class Success(val entries: List<LanRemoteEntry>) : LanBrowseResult
    data class AuthRequired(val serverUri: String, val hostLabel: String) : LanBrowseResult
    data class Error(val message: String) : LanBrowseResult
}

/**
 * Automatic LAN Media Server Discoverer & Recursive Directory Browser powered by libVLC 4.0.
 *
 * - Discovers UPnP/DLNA (`upnp`), SMB/CIFS (`dsm`), and Bonjour/ZeroConf (`microdns`) servers
 *   on the local Wi-Fi / Ethernet network while holding a reference-safe `WifiManager.MulticastLock`.
 * - Supports interactive recursive folder browsing (`browseRemoteDirectory`) across SMB, UPnP/DLNA,
 *   and FTP shares with automatic encrypted credential injection from [LuminaCredentialVault].
 */
@Singleton
class LuminaLanDiscoveryManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val playerEngine: LuminaPlayerEngine,
    private val credentialVault: LuminaCredentialVault,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope
) {

    private val _discoveredEndpoints = MutableStateFlow<List<LanDiscoveredEndpoint>>(emptyList())
    val discoveredEndpoints: StateFlow<List<LanDiscoveredEndpoint>> = _discoveredEndpoints.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val activeDiscoverers = mutableListOf<Pair<String, MediaDiscoverer>>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var pollJob: Job? = null

    fun startDiscovery() {
        appScope.launch(mainDispatcher) {
            if (_isDiscovering.value) return@launch
            _isDiscovering.value = true
            acquireMulticastLock()

            val libVlc = runCatching { playerEngine.requireLibVlc() }.getOrNull()
            if (libVlc == null) {
                _isDiscovering.value = false
                releaseMulticastLock()
                return@launch
            }

            // Category 0 corresponds to libVLC MediaDiscoverer.Description.Category.Lan
            val dynamicNames = runCatching {
                MediaDiscoverer.list(libVlc, 0)
                    ?.mapNotNull { it.name }
                    .orEmpty()
            }.getOrDefault(emptyList())

            val serviceNames = (PREFERRED_LAN_SERVICES + dynamicNames).distinct()

            serviceNames.forEach { serviceName ->
                runCatching {
                    val discoverer = MediaDiscoverer(libVlc, serviceName)
                    if (discoverer.start()) {
                        activeDiscoverers.add(serviceName to discoverer)
                    } else {
                        discoverer.release()
                    }
                }
            }

            rebuildEndpointsSnapshot()
            pollJob?.cancel()
            pollJob = appScope.launch(mainDispatcher) {
                while (isActive && _isDiscovering.value) {
                    delay(1_500L)
                    rebuildEndpointsSnapshot()
                }
            }
        }
    }

    fun refreshDiscovery() {
        appScope.launch(mainDispatcher) {
            stopDiscoveryInternal()
            startDiscovery()
        }
    }

    fun stopDiscovery() {
        appScope.launch(mainDispatcher) {
            stopDiscoveryInternal()
        }
    }

    /**
     * Asynchronously parses a remote SMB, UPnP/DLNA, or FTP directory via libVLC 4.0 `IMedia.parseAsync`,
     * injecting stored or explicit credentials and returning sorted subfolders + playable streams.
     */
    suspend fun browseRemoteDirectory(
        directoryUri: String,
        explicitCredentials: NetworkCredentials? = null
    ): LanBrowseResult = withContext(mainDispatcher) {
        val libVlc = runCatching { playerEngine.requireLibVlc() }.getOrNull()
            ?: return@withContext LanBrowseResult.Error("libVLC unavailable")

        val parsedUri = runCatching { Uri.parse(directoryUri) }.getOrNull()
            ?: return@withContext LanBrowseResult.Error("Invalid network URI")
        val scheme = parsedUri.scheme?.lowercase(Locale.ROOT).orEmpty()
        val requiresAuthSupport = scheme == "smb" || scheme == "ftp" || scheme == "ftps"
        val effectiveCreds = explicitCredentials ?: credentialVault.getCredentialsFor(directoryUri)

        val media = runCatching { Media(libVlc, parsedUri) }.getOrNull()
            ?: return@withContext LanBrowseResult.Error("Unable to open remote share")

        try {
            runCatching {
                // Prevent native interactive dialog hangs; surface auth prompt cleanly in Compose UI
                media.addOption(":no-interact")
                if (scheme == "smb") {
                    if (!effectiveCreds?.username.isNullOrBlank()) {
                        media.addOption(":smb-user=${effectiveCreds?.username}")
                    }
                    if (!effectiveCreds?.password.isNullOrBlank()) {
                        media.addOption(":smb-pwd=${effectiveCreds?.password}")
                    }
                    if (!effectiveCreds?.domain.isNullOrBlank()) {
                        media.addOption(":smb-domain=${effectiveCreds?.domain}")
                    }
                } else if (scheme == "ftp" || scheme == "ftps") {
                    if (!effectiveCreds?.username.isNullOrBlank()) {
                        media.addOption(":ftp-user=${effectiveCreds?.username}")
                    }
                    if (!effectiveCreds?.password.isNullOrBlank()) {
                        media.addOption(":ftp-pwd=${effectiveCreds?.password}")
                    }
                }
            }

            val parsedDeferred = CompletableDeferred<Boolean>()
            media.setEventListener { event ->
                if (event.type == IMedia.Event.ParsedChanged) {
                    parsedDeferred.complete(true)
                }
            }

            val started = runCatching {
                media.parseAsync(IMedia.Parse.ParseNetwork or IMedia.Parse.FetchNetwork)
            }.getOrDefault(false)

            if (!started) {
                if (requiresAuthSupport && effectiveCreds == null) {
                    val hostLabel = parsedUri.host ?: directoryUri
                    return@withContext LanBrowseResult.AuthRequired(directoryUri, hostLabel)
                }
                return@withContext LanBrowseResult.Error("Could not query remote folder")
            }

            val completed = withTimeoutOrNull(REMOTE_BROWSE_TIMEOUT_MS) {
                parsedDeferred.await()
            } ?: false

            media.setEventListener(null)

            val subList = runCatching { media.subItems() }.getOrNull()
            if (subList == null) {
                if (requiresAuthSupport) {
                    val hostLabel = parsedUri.host ?: directoryUri
                    return@withContext LanBrowseResult.AuthRequired(directoryUri, hostLabel)
                }
                return@withContext if (!completed) {
                    LanBrowseResult.Error("Timed out reading network folder")
                } else {
                    LanBrowseResult.Success(emptyList())
                }
            }

            val entries = mutableListOf<LanRemoteEntry>()
            try {
                val count = runCatching { subList.count }.getOrDefault(0)
                if (count == 0 && requiresAuthSupport && (!completed || effectiveCreds == null)) {
                    // Empty SMB root or unauthenticated share very commonly indicates guest login denial
                    val hostLabel = parsedUri.host ?: directoryUri
                    if (effectiveCreds == null) {
                        return@withContext LanBrowseResult.AuthRequired(directoryUri, hostLabel)
                    }
                }

                for (i in 0 until count) {
                    val child = runCatching { subList.getMediaAt(i) }.getOrNull() ?: continue
                    try {
                        val childUri = child.uri?.toString()?.takeIf { it.isNotBlank() } ?: continue
                        val rawName = child.getMeta(IMedia.Meta.Title)
                            ?.takeIf { it.isNotBlank() }
                            ?: child.uri?.lastPathSegment?.let { seg ->
                                runCatching { java.net.URLDecoder.decode(seg, "UTF-8") }.getOrDefault(seg)
                            }?.takeIf { it.isNotBlank() }
                            ?: childUri

                        if (rawName.startsWith(".")) continue

                        val isDir = child.type == IMedia.Type.Directory || isLikelyRemoteDirectory(childUri, rawName, child.type)
                        if (!isDir && !isSupportedRemotePlayableFile(rawName, childUri)) continue

                        val childScheme = child.uri?.scheme?.uppercase(Locale.ROOT) ?: scheme.uppercase(Locale.ROOT)
                        val durMs = runCatching { child.duration.coerceAtLeast(0L) }.getOrDefault(0L)

                        entries.add(
                            LanRemoteEntry(
                                uri = childUri,
                                name = rawName,
                                isDirectory = isDir,
                                protocolBadge = childScheme.ifBlank { "LAN" },
                                durationMs = durMs,
                                hdrBadge = if (!isDir) inferRemoteHdrBadge(rawName) else null
                            )
                        )
                    } finally {
                        runCatching { child.release() }
                    }
                }
            } finally {
                runCatching { subList.release() }
            }

            val sorted = entries.sortedWith(
                compareByDescending<LanRemoteEntry> { it.isDirectory }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )
            LanBrowseResult.Success(sorted)
        } finally {
            runCatching {
                media.setEventListener(null)
                media.release()
            }
        }
    }

    private fun stopDiscoveryInternal() {
        pollJob?.cancel()
        pollJob = null
        activeDiscoverers.forEach { (_, discoverer) ->
            runCatching {
                discoverer.stop()
                discoverer.mediaList.release()
                discoverer.release()
            }
        }
        activeDiscoverers.clear()
        releaseMulticastLock()
        _isDiscovering.value = false
    }

    private fun rebuildEndpointsSnapshot() {
        val collected = LinkedHashMap<String, LanDiscoveredEndpoint>()
        activeDiscoverers.forEach { (serviceName, discoverer) ->
            val list = runCatching { discoverer.mediaList }.getOrNull() ?: return@forEach
            val count = runCatching { list.count }.getOrDefault(0)
            for (i in 0 until count) {
                val media = runCatching { list.getMediaAt(i) }.getOrNull() ?: continue
                try {
                    val uriStr = media.uri?.toString()?.takeIf { it.isNotBlank() } ?: continue
                    val rawTitle = media.getMeta(IMedia.Meta.Title)
                        ?.takeIf { it.isNotBlank() }
                        ?: media.uri?.lastPathSegment?.takeIf { it.isNotBlank() }
                        ?: media.uri?.host
                        ?: uriStr

                    val scheme = media.uri?.scheme?.uppercase(Locale.ROOT)
                    val protocolBadge = when {
                        !scheme.isNullOrBlank() -> scheme
                        serviceName.contains("upnp", ignoreCase = true) -> "UPnP/DLNA"
                        serviceName.contains("dsm", ignoreCase = true) ||
                            serviceName.contains("smb", ignoreCase = true) -> "SMB"
                        else -> "LAN"
                    }

                    val isDir = media.type == IMedia.Type.Directory
                    collected[uriStr] = LanDiscoveredEndpoint(
                        uri = uriStr,
                        title = rawTitle,
                        protocolBadge = protocolBadge,
                        serviceName = serviceName,
                        isDirectory = isDir
                    )
                } finally {
                    runCatching { media.release() }
                }
            }
        }
        _discoveredEndpoints.value = collected.values.sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    private fun acquireMulticastLock() {
        runCatching {
            if (multicastLock == null) {
                val wifiManager = appContext.applicationContext.getSystemService<WifiManager>()
                multicastLock = wifiManager?.createMulticastLock("lumina:lan_discovery")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
            }
        }
    }

    private fun releaseMulticastLock() {
        runCatching {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        }
    }

    companion object {
        private val PREFERRED_LAN_SERVICES = listOf("upnp", "dsm", "microdns")
        private const val REMOTE_BROWSE_TIMEOUT_MS = 9_000L

        private val PLAYABLE_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "mov", "m4v", "avi", "ts", "m2ts", "mts", "flv", "wmv", "3gp", "ogv",
            "flac", "wav", "opus", "ogg", "mp3", "m4a", "aac", "alac", "ape", "wv", "dsf", "dff", "m3u8"
        )

        internal fun isLikelyRemoteDirectory(uriStr: String, name: String, mediaType: Int): Boolean {
            if (mediaType == IMedia.Type.Directory || mediaType == IMedia.Type.Node) return true
            if (uriStr.endsWith("/")) return true
            if (uriStr.contains("ObjectID=", ignoreCase = true) && !name.contains('.')) return true
            return false
        }

        internal fun isSupportedRemotePlayableFile(name: String, uriStr: String): Boolean {
            val extFromName = name.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
            if (extFromName in PLAYABLE_EXTENSIONS) return true
            val cleanUri = uriStr.substringBefore('?').substringBefore('#')
            val extFromUri = cleanUri.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
            if (extFromUri in PLAYABLE_EXTENSIONS) return true
            // UPnP/DLNA media servers frequently serve HTTP stream URLs without file extensions (e.g. /MediaItems/42)
            if (uriStr.startsWith("http://", ignoreCase = true) || uriStr.startsWith("https://", ignoreCase = true) ||
                uriStr.startsWith("rtsp://", ignoreCase = true)
            ) {
                return true
            }
            return false
        }

        internal fun inferRemoteHdrBadge(name: String): String? {
            val lower = name.lowercase(Locale.ROOT)
            return when {
                lower.contains("dovi") || lower.contains("dolby.vision") || lower.contains("dolby vision") || lower.contains(".dv.") -> "Dolby Vision"
                lower.contains("hdr10+") || lower.contains("hdr10plus") -> "HDR10+"
                lower.contains("hdr10") || lower.contains(".hdr.") || lower.contains("bt2020") -> "HDR10"
                lower.contains(".hlg.") || lower.contains(" hlg") -> "HLG"
                else -> null
            }
        }
    }
}
