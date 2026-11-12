package app.lumina.player.data.network

import android.content.Context
import android.net.wifi.WifiManager
import androidx.core.content.getSystemService
import app.lumina.player.di.ApplicationScope
import app.lumina.player.di.MainDispatcher
import app.lumina.player.player.engine.LuminaPlayerEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 * Automatic LAN Media Server Discoverer powered by libVLC 4.0 `MediaDiscoverer`.
 *
 * Discovers UPnP/DLNA (`upnp`), SMB/CIFS (`dsm`), and Bonjour/ZeroConf (`microdns`) servers
 * on the local Wi-Fi / Ethernet network while holding a reference-safe `WifiManager.MulticastLock`.
 */
@Singleton
class LuminaLanDiscoveryManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val playerEngine: LuminaPlayerEngine,
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
    }
}
