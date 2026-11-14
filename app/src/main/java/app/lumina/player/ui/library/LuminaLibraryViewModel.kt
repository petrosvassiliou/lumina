package app.lumina.player.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lumina.player.data.local.dao.PlaybackStateDao
import app.lumina.player.data.local.entity.MediaItemEntity
import app.lumina.player.data.local.entity.PlaybackStateEntity
import app.lumina.player.data.local.entity.PlaylistEntryEntity
import app.lumina.player.data.local.entity.PlaylistSummaryRow
import app.lumina.player.data.network.LanBrowseResult
import app.lumina.player.data.network.LanDiscoveredEndpoint
import app.lumina.player.data.network.LanRemoteBreadcrumb
import app.lumina.player.data.network.LanRemoteEntry
import app.lumina.player.data.network.LuminaCredentialVault
import app.lumina.player.data.network.LuminaLanDiscoveryManager
import app.lumina.player.data.network.NetworkCredentials
import app.lumina.player.data.preferences.HardwareAccelerationMode
import app.lumina.player.data.preferences.LuminaPreferences
import app.lumina.player.data.preferences.SubtitleBackgroundStyle
import app.lumina.player.data.preferences.SubtitleFontScale
import app.lumina.player.data.preferences.ToneMappingAlgorithm
import app.lumina.player.data.preferences.UserPreferences
import app.lumina.player.data.repository.LocalFileEntry
import app.lumina.player.data.repository.MediaRepository
import app.lumina.player.data.repository.SafPinnedFolderSummary
import app.lumina.player.player.engine.LuminaPlayerEngine
import app.lumina.player.player.model.HdrFormat
import app.lumina.player.player.model.LuminaPlayerState
import app.lumina.player.player.model.MediaQueueItem
import app.lumina.player.util.LuminaLocaleManager
import app.lumina.player.util.SupportedLuminaLocale
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibraryTab {
    VIDEOS,
    FOLDERS,
    PLAYLISTS,
    AUDIO,
    NETWORK,
    SETTINGS
}

enum class FolderSortOption {
    NAME,
    SIZE,
    DATE_MODIFIED
}

enum class FolderHdrFilter {
    ALL,
    HDR_ANY,
    DOLBY_VISION,
    HDR10_PLUS,
    HDR10,
    HLG,
    SDR;

    fun matches(hdrBadge: String?): Boolean = when (this) {
        ALL -> true
        HDR_ANY -> !hdrBadge.isNullOrBlank()
        DOLBY_VISION -> hdrBadge.equals("Dolby Vision", ignoreCase = true)
        HDR10_PLUS -> hdrBadge.equals("HDR10+", ignoreCase = true)
        HDR10 -> hdrBadge.equals("HDR10", ignoreCase = true)
        HLG -> hdrBadge.equals("HLG", ignoreCase = true)
        SDR -> hdrBadge.isNullOrBlank()
    }
}

sealed interface FolderBreadcrumb {
    val label: String

    data class DeviceBucket(override val label: String) : FolderBreadcrumb

    data class SafDirectory(
        override val label: String,
        val treeRootUri: Uri,
        val directoryUri: Uri
    ) : FolderBreadcrumb
}

data class DeviceVideoFolderGroup(
    val folderName: String,
    val videos: List<MediaItemEntity>
)

data class LanAuthPromptTarget(
    val serverUri: String,
    val hostLabel: String,
    val pendingOpenLabel: String? = null,
    val pendingProtocolBadge: String = "SMB"
)

data class LibraryUiState(
    val selectedTab: LibraryTab = LibraryTab.VIDEOS,
    val searchQuery: String = "",
    val isScanning: Boolean = false,
    val videos: List<MediaItemEntity> = emptyList(),
    val audioItems: List<MediaItemEntity> = emptyList(),
    val networkStreams: List<MediaItemEntity> = emptyList(),
    val discoveredLanEndpoints: List<LanDiscoveredEndpoint> = emptyList(),
    val isDiscoveringLan: Boolean = false,
    val lanBreadcrumbs: List<LanRemoteBreadcrumb> = emptyList(),
    val lanRemoteEntries: List<LanRemoteEntry> = emptyList(),
    val isLoadingLanFolder: Boolean = false,
    val lanAuthPrompt: LanAuthPromptTarget? = null,
    val lanBrowseError: String? = null,
    val savedCredentialServerKeys: Set<String> = emptySet(),
    val playlists: List<PlaylistSummaryRow> = emptyList(),
    val selectedPlaylistId: Long? = null,
    val activePlaylistEntries: List<PlaylistEntryEntity> = emptyList(),
    val continueWatching: List<MediaItemEntity> = emptyList(),
    val resumeProgressByUri: Map<String, PlaybackStateEntity> = emptyMap(),
    val preferences: UserPreferences = UserPreferences(),
    val pinnedSafFolders: List<SafPinnedFolderSummary> = emptyList(),
    val deviceVideoFolders: List<DeviceVideoFolderGroup> = emptyList(),
    val folderBreadcrumbs: List<FolderBreadcrumb> = emptyList(),
    val activeFolderEntries: List<LocalFileEntry> = emptyList(),
    val isLoadingFolder: Boolean = false,
    val folderSortOption: FolderSortOption = FolderSortOption.NAME,
    val folderSortAscending: Boolean = true,
    val folderHdrFilter: FolderHdrFilter = FolderHdrFilter.ALL
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LuminaLibraryViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val playbackStateDao: PlaybackStateDao,
    private val preferences: LuminaPreferences,
    private val lanDiscoveryManager: LuminaLanDiscoveryManager,
    private val credentialVault: LuminaCredentialVault,
    private val playerEngine: LuminaPlayerEngine
) : ViewModel() {

    val activeEngineState: StateFlow<LuminaPlayerState> = playerEngine.state

    private val selectedTabFlow = MutableStateFlow(LibraryTab.VIDEOS)
    private val searchQueryFlow = MutableStateFlow("")
    private val isScanningFlow = MutableStateFlow(false)

    private val folderBreadcrumbsFlow = MutableStateFlow<List<FolderBreadcrumb>>(emptyList())
    private val activeSafEntriesFlow = MutableStateFlow<List<LocalFileEntry>>(emptyList())
    private val isLoadingFolderFlow = MutableStateFlow(false)

    private val folderSortOptionFlow = MutableStateFlow(FolderSortOption.NAME)
    private val folderSortAscendingFlow = MutableStateFlow(true)
    private val folderHdrFilterFlow = MutableStateFlow(FolderHdrFilter.ALL)

    // Interactive LAN / SMB / UPnP Folder Browser State
    private val lanBreadcrumbsFlow = MutableStateFlow<List<LanRemoteBreadcrumb>>(emptyList())
    private val lanRemoteEntriesFlow = MutableStateFlow<List<LanRemoteEntry>>(emptyList())
    private val isLoadingLanFolderFlow = MutableStateFlow(false)
    private val lanAuthPromptFlow = MutableStateFlow<LanAuthPromptTarget?>(null)
    private val lanBrowseErrorFlow = MutableStateFlow<String?>(null)

    // Playlists State
    private val selectedPlaylistIdFlow = MutableStateFlow<Long?>(null)

    private val activePlaylistEntriesFlow = selectedPlaylistIdFlow.flatMapLatest { playlistId ->
        if (playlistId == null) {
            flowOf(emptyList())
        } else {
            mediaRepository.observePlaylistEntries(playlistId)
        }
    }

    private val filteredMediaFlow = searchQueryFlow.flatMapLatest { query ->
        if (query.isBlank()) {
            mediaRepository.localMediaFlow
        } else {
            mediaRepository.searchMedia(query.trim())
        }
    }

    private val pinnedSafFoldersFlow = preferences.userPreferencesFlow
        .mapLatest { prefs ->
            prefs.pinnedFolderTreeUris.mapNotNull { uriStr ->
                mediaRepository.resolvePinnedSafFolderSummary(uriStr)
            }.sortedBy { it.displayName.lowercase() }
        }

    private data class FolderSortFilterConfig(
        val sortOption: FolderSortOption,
        val ascending: Boolean,
        val hdrFilter: FolderHdrFilter
    )

    private val folderSortFilterFlow = combine(
        folderSortOptionFlow,
        folderSortAscendingFlow,
        folderHdrFilterFlow
    ) { sortOpt, asc, hdr ->
        FolderSortFilterConfig(sortOpt, asc, hdr)
    }

    private data class BrowserNavigationSnapshot(
        val pinnedFolders: List<SafPinnedFolderSummary>,
        val breadcrumbs: List<FolderBreadcrumb>,
        val safEntries: List<LocalFileEntry>,
        val isLoading: Boolean,
        val sortFilter: FolderSortFilterConfig
    )

    private val browserNavFlow = combine(
        pinnedSafFoldersFlow,
        folderBreadcrumbsFlow,
        activeSafEntriesFlow,
        isLoadingFolderFlow,
        folderSortFilterFlow
    ) { pinned, crumbs, safEntries, loading, sortFilter ->
        BrowserNavigationSnapshot(pinned, crumbs, safEntries, loading, sortFilter)
    }

    private data class LanBrowserSnapshot(
        val endpoints: List<LanDiscoveredEndpoint>,
        val isDiscovering: Boolean,
        val breadcrumbs: List<LanRemoteBreadcrumb>,
        val entries: List<LanRemoteEntry>,
        val isLoadingFolder: Boolean,
        val authPrompt: LanAuthPromptTarget?,
        val browseError: String?,
        val savedCredKeys: Set<String>
    )

    private val lanBrowserSnapshotFlow = combine(
        combine(
            lanDiscoveryManager.discoveredEndpoints,
            lanDiscoveryManager.isDiscovering,
            lanBreadcrumbsFlow,
            lanRemoteEntriesFlow
        ) { endpoints, discovering, crumbs, entries ->
            Quadruple(endpoints, discovering, crumbs, entries)
        },
        combine(
            isLoadingLanFolderFlow,
            lanAuthPromptFlow,
            lanBrowseErrorFlow,
            credentialVault.savedServerKeys
        ) { loading, prompt, err, keys ->
            Quadruple(loading, prompt, err, keys)
        }
    ) { partA, partB ->
        LanBrowserSnapshot(
            endpoints = partA.first,
            isDiscovering = partA.second,
            breadcrumbs = partA.third,
            entries = partA.fourth,
            isLoadingFolder = partB.first,
            authPrompt = partB.second,
            browseError = partB.third,
            savedCredKeys = partB.fourth
        )
    }

    private data class PlaylistSnapshot(
        val playlists: List<PlaylistSummaryRow>,
        val selectedId: Long?,
        val entries: List<PlaylistEntryEntity>
    )

    private val playlistSnapshotFlow = combine(
        mediaRepository.playlistSummariesFlow,
        selectedPlaylistIdFlow,
        activePlaylistEntriesFlow
    ) { lists, selectedId, entries ->
        PlaylistSnapshot(lists, selectedId, entries)
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        selectedTabFlow,
        searchQueryFlow,
        isScanningFlow,
        filteredMediaFlow,
        combine(
            mediaRepository.networkStreamsFlow,
            mediaRepository.continueWatchingFlow,
            mediaRepository.playbackStatesFlow,
            preferences.userPreferencesFlow,
            combine(browserNavFlow, lanBrowserSnapshotFlow, playlistSnapshotFlow) { nav, lan, pls ->
                Triple(nav, lan, pls)
            }
        ) { net, cont, states, prefs, subState ->
            Quintuple(net, cont, states, prefs, subState)
        }
    ) { tab, query, scanning, localItems, extra ->
        val videos = localItems.filter { !it.mimeType.startsWith("audio/", ignoreCase = true) }
        val audio = localItems.filter { it.mimeType.startsWith("audio/", ignoreCase = true) }
        val resumeMap = extra.third.associateBy { it.mediaUri }
        val (browserNav, lanSnap, playlistSnap) = extra.fifth
        val sortFilter = browserNav.sortFilter

        val filteredVideosForFolders = videos.filter { sortFilter.hdrFilter.matches(it.hdrBadge) }

        val groupedFolders = filteredVideosForFolders
            .groupBy { it.folderName.ifBlank { "Videos" } }
            .entries
            .map { (folder, items) ->
                DeviceVideoFolderGroup(
                    folderName = folder,
                    videos = sortMediaEntities(items, sortFilter.sortOption, sortFilter.ascending)
                )
            }
            .let { groups ->
                val baseSorted = when (sortFilter.sortOption) {
                    FolderSortOption.NAME -> groups.sortedBy { it.folderName.lowercase() }
                    FolderSortOption.SIZE -> groups.sortedBy { grp -> grp.videos.sumOf { it.sizeBytes } }
                    FolderSortOption.DATE_MODIFIED -> groups.sortedBy { grp ->
                        grp.videos.maxOfOrNull { it.dateAddedEpochSec } ?: 0L
                    }
                }
                if (sortFilter.ascending) baseSorted else baseSorted.reversed()
            }

        val sortedPinnedSaf = if (sortFilter.ascending) {
            browserNav.pinnedFolders.sortedBy { it.displayName.lowercase() }
        } else {
            browserNav.pinnedFolders.sortedByDescending { it.displayName.lowercase() }
        }

        val rawEntries = when (val activeCrumb = browserNav.breadcrumbs.lastOrNull()) {
            null -> emptyList()
            is FolderBreadcrumb.DeviceBucket -> {
                val matching = videos.filter {
                    it.folderName.equals(activeCrumb.label, ignoreCase = true)
                }
                matching.map { entity ->
                    LocalFileEntry(
                        uri = Uri.parse(entity.uri),
                        name = entity.title,
                        isDirectory = false,
                        sizeBytes = entity.sizeBytes,
                        mimeType = entity.mimeType,
                        hdrBadge = entity.hdrBadge,
                        videoCodec = entity.videoCodec,
                        lastModifiedMs = entity.dateAddedEpochSec * 1000L
                    )
                }
            }
            is FolderBreadcrumb.SafDirectory -> {
                val trimmed = query.trim()
                if (trimmed.isEmpty()) {
                    browserNav.safEntries
                } else {
                    browserNav.safEntries.filter { entry ->
                        entry.name.contains(trimmed, ignoreCase = true) ||
                            (entry.hdrBadge?.contains(trimmed, ignoreCase = true) == true) ||
                            (entry.videoCodec?.contains(trimmed, ignoreCase = true) == true)
                    }
                }
            }
        }

        val currentEntries = filterAndSortFileEntries(
            entries = rawEntries,
            hdrFilter = sortFilter.hdrFilter,
            sortOption = sortFilter.sortOption,
            ascending = sortFilter.ascending
        )

        LibraryUiState(
            selectedTab = tab,
            searchQuery = query,
            isScanning = scanning,
            videos = videos,
            audioItems = audio,
            networkStreams = extra.first,
            discoveredLanEndpoints = lanSnap.endpoints,
            isDiscoveringLan = lanSnap.isDiscovering,
            lanBreadcrumbs = lanSnap.breadcrumbs,
            lanRemoteEntries = lanSnap.entries,
            isLoadingLanFolder = lanSnap.isLoadingFolder,
            lanAuthPrompt = lanSnap.authPrompt,
            lanBrowseError = lanSnap.browseError,
            savedCredentialServerKeys = lanSnap.savedCredKeys,
            playlists = playlistSnap.playlists,
            selectedPlaylistId = playlistSnap.selectedId,
            activePlaylistEntries = playlistSnap.entries,
            continueWatching = extra.second,
            resumeProgressByUri = resumeMap,
            preferences = extra.fourth,
            pinnedSafFolders = sortedPinnedSaf,
            deviceVideoFolders = groupedFolders,
            folderBreadcrumbs = browserNav.breadcrumbs,
            activeFolderEntries = currentEntries,
            isLoadingFolder = browserNav.isLoading,
            folderSortOption = sortFilter.sortOption,
            folderSortAscending = sortFilter.ascending,
            folderHdrFilter = sortFilter.hdrFilter
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = LibraryUiState()
    )

    private fun sortMediaEntities(
        items: List<MediaItemEntity>,
        sortOption: FolderSortOption,
        ascending: Boolean
    ): List<MediaItemEntity> {
        val comparator: Comparator<MediaItemEntity> = when (sortOption) {
            FolderSortOption.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            FolderSortOption.SIZE -> compareBy<MediaItemEntity> { it.sizeBytes }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            FolderSortOption.DATE_MODIFIED -> compareBy<MediaItemEntity> { it.dateAddedEpochSec }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        }
        return items.sortedWith(if (ascending) comparator else comparator.reversed())
    }

    private fun filterAndSortFileEntries(
        entries: List<LocalFileEntry>,
        hdrFilter: FolderHdrFilter,
        sortOption: FolderSortOption,
        ascending: Boolean
    ): List<LocalFileEntry> {
        val filtered = entries.filter { entry ->
            entry.isDirectory || hdrFilter.matches(entry.hdrBadge)
        }
        val itemComparator: Comparator<LocalFileEntry> = when (sortOption) {
            FolderSortOption.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            FolderSortOption.SIZE -> compareBy<LocalFileEntry> { it.sizeBytes }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            FolderSortOption.DATE_MODIFIED -> compareBy<LocalFileEntry> { it.lastModifiedMs }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        val directional = if (ascending) itemComparator else itemComparator.reversed()
        return filtered.sortedWith(
            compareByDescending<LocalFileEntry> { it.isDirectory }.then(directional)
        )
    }

    fun selectTab(tab: LibraryTab) {
        selectedTabFlow.value = tab
        if (tab == LibraryTab.NETWORK) {
            lanDiscoveryManager.startDiscovery()
        } else {
            lanDiscoveryManager.stopDiscovery()
        }
    }

    fun refreshLanDiscovery() {
        lanDiscoveryManager.refreshDiscovery()
    }

    // =========================================================================
    // Now Playing Mini-Bar Controls (Background Audio Continuation)
    // =========================================================================

    fun toggleMiniBarPlayPause() = playerEngine.togglePlayPause()

    fun stopMiniBarPlayback() = playerEngine.stop()

    // =========================================================================
    // Interactive SMB / UPnP / DLNA / FTP Recursive Folder Browser & Vault
    // =========================================================================

    fun hasSavedCredentialsFor(uriOrHost: String): Boolean =
        credentialVault.hasCredentialsFor(uriOrHost)

    fun openCredentialsPrompt(uri: String, label: String, protocolBadge: String = "SMB") {
        lanAuthPromptFlow.value = LanAuthPromptTarget(
            serverUri = uri,
            hostLabel = label,
            pendingOpenLabel = label,
            pendingProtocolBadge = protocolBadge
        )
    }

    fun dismissLanAuthPrompt() {
        lanAuthPromptFlow.value = null
    }

    fun saveLanCredentialsAndBrowse(
        target: LanAuthPromptTarget,
        username: String,
        password: String,
        domain: String? = null,
        rememberInVault: Boolean = true
    ) {
        val creds = NetworkCredentials(
            username = username.trim(),
            password = password,
            domain = domain?.trim()?.takeIf { it.isNotEmpty() }
        )
        if (rememberInVault) {
            credentialVault.saveCredentialsFor(target.serverUri, creds)
        }
        lanAuthPromptFlow.value = null
        val label = target.pendingOpenLabel ?: target.hostLabel
        if (lanBreadcrumbsFlow.value.isEmpty()) {
            lanBreadcrumbsFlow.value = listOf(
                LanRemoteBreadcrumb(
                    uri = target.serverUri,
                    label = label,
                    protocolBadge = target.pendingProtocolBadge
                )
            )
        }
        loadLanDirectory(target.serverUri, explicitCredentials = creds)
    }

    fun clearSavedLanCredentials(uriOrHost: String) {
        credentialVault.removeCredentialsFor(uriOrHost)
    }

    fun openDiscoveredLanDirectory(endpoint: LanDiscoveredEndpoint) {
        selectedTabFlow.value = LibraryTab.NETWORK
        val rootCrumb = LanRemoteBreadcrumb(
            uri = endpoint.uri,
            label = endpoint.title,
            protocolBadge = endpoint.protocolBadge
        )
        lanBreadcrumbsFlow.value = listOf(rootCrumb)
        loadLanDirectory(endpoint.uri)
    }

    fun openLanSubdirectory(entry: LanRemoteEntry) {
        if (!entry.isDirectory) return
        val nextCrumb = LanRemoteBreadcrumb(
            uri = entry.uri,
            label = entry.name,
            protocolBadge = entry.protocolBadge
        )
        lanBreadcrumbsFlow.value = lanBreadcrumbsFlow.value + nextCrumb
        loadLanDirectory(entry.uri)
    }

    fun navigateToLanBreadcrumb(index: Int) {
        if (index < 0) {
            lanBreadcrumbsFlow.value = emptyList()
            lanRemoteEntriesFlow.value = emptyList()
            lanBrowseErrorFlow.value = null
            return
        }
        val crumbs = lanBreadcrumbsFlow.value
        if (index >= crumbs.size) return
        val sliced = crumbs.take(index + 1)
        lanBreadcrumbsFlow.value = sliced
        sliced.lastOrNull()?.let { target ->
            loadLanDirectory(target.uri)
        }
    }

    fun navigateUpLanFolder(): Boolean {
        val crumbs = lanBreadcrumbsFlow.value
        if (crumbs.isEmpty()) return false
        navigateToLanBreadcrumb(crumbs.size - 2)
        return true
    }

    fun playFromLanRemoteEntry(
        clickedEntry: LanRemoteEntry,
        siblingEntries: List<LanRemoteEntry>,
        onReadyQueue: (List<MediaQueueItem>, Int, String?, String?) -> Unit
    ) {
        if (clickedEntry.isDirectory) return
        val mediaFiles = siblingEntries.filter { !it.isDirectory }
        val startIndex = mediaFiles.indexOfFirst { it.uri == clickedEntry.uri }.coerceAtLeast(0)
        val serverCreds = credentialVault.getCredentialsFor(clickedEntry.uri)
        val folderTitle = lanBreadcrumbsFlow.value.lastOrNull()?.label ?: clickedEntry.protocolBadge

        val queue = mediaFiles.map { item ->
            val hint = when (item.hdrBadge) {
                "Dolby Vision" -> HdrFormat.DOLBY_VISION
                "HDR10+" -> HdrFormat.HDR10_PLUS
                "HLG" -> HdrFormat.HLG
                "HDR10" -> HdrFormat.HDR10
                else -> null
            }
            MediaQueueItem(
                uri = Uri.parse(item.uri),
                title = item.name.substringBeforeLast('.', item.name),
                subtitle = folderTitle,
                durationMs = item.durationMs,
                isNetworkStream = true,
                hdrHint = hint
            )
        }
        onReadyQueue(queue, startIndex, serverCreds?.username, serverCreds?.password)
    }

    private fun loadLanDirectory(
        directoryUri: String,
        explicitCredentials: NetworkCredentials? = null
    ) {
        viewModelScope.launch {
            isLoadingLanFolderFlow.value = true
            lanBrowseErrorFlow.value = null
            try {
                when (val result = lanDiscoveryManager.browseRemoteDirectory(directoryUri, explicitCredentials)) {
                    is LanBrowseResult.Success -> {
                        lanRemoteEntriesFlow.value = result.entries
                    }
                    is LanBrowseResult.AuthRequired -> {
                        lanRemoteEntriesFlow.value = emptyList()
                        val currentCrumb = lanBreadcrumbsFlow.value.lastOrNull()
                        lanAuthPromptFlow.value = LanAuthPromptTarget(
                            serverUri = result.serverUri,
                            hostLabel = result.hostLabel,
                            pendingOpenLabel = currentCrumb?.label ?: result.hostLabel,
                            pendingProtocolBadge = currentCrumb?.protocolBadge ?: "SMB"
                        )
                    }
                    is LanBrowseResult.Error -> {
                        lanRemoteEntriesFlow.value = emptyList()
                        lanBrowseErrorFlow.value = result.message
                    }
                }
            } finally {
                isLoadingLanFolderFlow.value = false
            }
        }
    }

    // =========================================================================
    // Custom Playlists & .m3u / .m3u8 Import / Export
    // =========================================================================

    fun selectPlaylist(playlistId: Long?) {
        selectedPlaylistIdFlow.value = playlistId
    }

    fun createPlaylist(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val createdId = mediaRepository.createPlaylist(clean)
            selectedPlaylistIdFlow.value = createdId
        }
    }

    fun renamePlaylist(playlistId: Long, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            mediaRepository.renamePlaylist(playlistId, clean)
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            mediaRepository.deletePlaylist(playlistId)
            if (selectedPlaylistIdFlow.value == playlistId) {
                selectedPlaylistIdFlow.value = null
            }
        }
    }

    fun addMediaEntityToPlaylist(playlistId: Long, item: MediaItemEntity) {
        viewModelScope.launch {
            mediaRepository.addMediaEntityToPlaylist(playlistId, item)
        }
    }

    fun createPlaylistWithMediaEntity(name: String, item: MediaItemEntity) {
        viewModelScope.launch {
            val createdId = mediaRepository.createPlaylist(name.trim().ifEmpty { "Playlist" })
            mediaRepository.addMediaEntityToPlaylist(createdId, item)
        }
    }

    fun deletePlaylistEntry(entryId: Long) {
        viewModelScope.launch {
            mediaRepository.deletePlaylistEntry(entryId)
        }
    }

    fun playPlaylist(
        playlistId: Long,
        startIndex: Int = 0,
        shuffle: Boolean = false,
        onReadyQueue: (List<MediaQueueItem>, Int) -> Unit
    ) {
        viewModelScope.launch {
            val items = mediaRepository.getPlaylistQueueItems(playlistId)
            if (items.isEmpty()) return@launch
            if (shuffle) {
                onReadyQueue(items.shuffled(), 0)
            } else {
                onReadyQueue(items, startIndex.coerceIn(items.indices))
            }
        }
    }

    fun importM3uPlaylist(uri: Uri) {
        viewModelScope.launch {
            val importedId = mediaRepository.importM3uPlaylistFromUri(uri)
            if (importedId != null) {
                selectedTabFlow.value = LibraryTab.PLAYLISTS
                selectedPlaylistIdFlow.value = importedId
            }
        }
    }

    fun exportPlaylistToM3u(playlistId: Long, targetUri: Uri) {
        viewModelScope.launch {
            mediaRepository.exportM3uPlaylistToUri(playlistId, targetUri)
        }
    }

    override fun onCleared() {
        lanDiscoveryManager.stopDiscovery()
        super.onCleared()
    }

    fun updateSearchQuery(query: String) {
        searchQueryFlow.value = query
    }

    fun scanLocalMedia() {
        if (isScanningFlow.value) return
        viewModelScope.launch {
            isScanningFlow.value = true
            try {
                mediaRepository.refreshLocalMediaIndex()
            } finally {
                isScanningFlow.value = false
            }
        }
    }

    // =========================================================================
    // Local File System Sorting, Filtering & Directory Browser Actions
    // =========================================================================

    fun setFolderSortOption(option: FolderSortOption) {
        if (folderSortOptionFlow.value == option) {
            folderSortAscendingFlow.value = !folderSortAscendingFlow.value
        } else {
            folderSortOptionFlow.value = option
        }
    }

    fun toggleFolderSortDirection() {
        folderSortAscendingFlow.value = !folderSortAscendingFlow.value
    }

    fun setFolderHdrFilter(filter: FolderHdrFilter) {
        folderHdrFilterFlow.value = filter
    }

    fun openDeviceBucketFolder(folderName: String) {
        selectedTabFlow.value = LibraryTab.FOLDERS
        folderBreadcrumbsFlow.value = listOf(FolderBreadcrumb.DeviceBucket(folderName))
        activeSafEntriesFlow.value = emptyList()
    }

    fun onSafFolderPicked(treeUri: Uri) {
        viewModelScope.launch {
            val uriStr = treeUri.toString()
            preferences.addPinnedFolderTreeUri(uriStr)
            selectedTabFlow.value = LibraryTab.FOLDERS
            val summary = mediaRepository.resolvePinnedSafFolderSummary(uriStr)
            val label = summary?.displayName ?: treeUri.lastPathSegment?.substringAfterLast(':') ?: "Folder"
            val rootCrumb = FolderBreadcrumb.SafDirectory(
                label = label,
                treeRootUri = treeUri,
                directoryUri = treeUri
            )
            folderBreadcrumbsFlow.value = listOf(rootCrumb)
            loadSafDirectory(treeUri, treeUri)
        }
    }

    fun openPinnedSafFolder(summary: SafPinnedFolderSummary) {
        selectedTabFlow.value = LibraryTab.FOLDERS
        val rootCrumb = FolderBreadcrumb.SafDirectory(
            label = summary.displayName,
            treeRootUri = summary.treeUri,
            directoryUri = summary.treeUri
        )
        folderBreadcrumbsFlow.value = listOf(rootCrumb)
        loadSafDirectory(summary.treeUri, summary.treeUri)
    }

    fun openSafSubdirectory(entry: LocalFileEntry) {
        if (!entry.isDirectory) return
        val currentSaf = folderBreadcrumbsFlow.value.lastOrNull() as? FolderBreadcrumb.SafDirectory ?: return
        val nextCrumb = FolderBreadcrumb.SafDirectory(
            label = entry.name,
            treeRootUri = currentSaf.treeRootUri,
            directoryUri = entry.uri
        )
        folderBreadcrumbsFlow.value = folderBreadcrumbsFlow.value + nextCrumb
        loadSafDirectory(currentSaf.treeRootUri, entry.uri)
    }

    fun navigateToBreadcrumb(index: Int) {
        if (index < 0) {
            folderBreadcrumbsFlow.value = emptyList()
            activeSafEntriesFlow.value = emptyList()
            return
        }
        val current = folderBreadcrumbsFlow.value
        if (index >= current.size) return
        val sliced = current.take(index + 1)
        folderBreadcrumbsFlow.value = sliced
        val target = sliced.lastOrNull()
        if (target is FolderBreadcrumb.SafDirectory) {
            loadSafDirectory(target.treeRootUri, target.directoryUri)
        } else {
            activeSafEntriesFlow.value = emptyList()
        }
    }

    fun navigateUpFolder(): Boolean {
        val crumbs = folderBreadcrumbsFlow.value
        if (crumbs.isEmpty()) return false
        navigateToBreadcrumb(crumbs.size - 2)
        return true
    }

    fun removePinnedSafFolder(treeUriString: String) {
        viewModelScope.launch {
            preferences.removePinnedFolderTreeUri(treeUriString)
            val activeRoot = (folderBreadcrumbsFlow.value.firstOrNull() as? FolderBreadcrumb.SafDirectory)
                ?.treeRootUri?.toString()
            if (activeRoot == treeUriString) {
                folderBreadcrumbsFlow.value = emptyList()
                activeSafEntriesFlow.value = emptyList()
            }
        }
    }

    fun playFromFolderEntry(
        clickedEntry: LocalFileEntry,
        siblingEntries: List<LocalFileEntry>,
        folderLabel: String,
        onReadyQueue: (List<MediaQueueItem>, Int) -> Unit
    ) {
        if (clickedEntry.isDirectory) return
        val videoFiles = siblingEntries.filter { !it.isDirectory }
        val startIndex = videoFiles.indexOfFirst { it.uri == clickedEntry.uri }.coerceAtLeast(0)

        viewModelScope.launch {
            mediaRepository.registerFolderVideoEntries(folderLabel, videoFiles)
            val queue = videoFiles.map { entry ->
                val hint = when (entry.hdrBadge) {
                    "Dolby Vision" -> HdrFormat.DOLBY_VISION
                    "HDR10+" -> HdrFormat.HDR10_PLUS
                    "HLG" -> HdrFormat.HLG
                    "HDR10" -> HdrFormat.HDR10
                    else -> null
                }
                MediaQueueItem(
                    uri = entry.uri,
                    title = entry.name.substringBeforeLast('.', entry.name),
                    subtitle = folderLabel,
                    mimeType = entry.mimeType,
                    isNetworkStream = false,
                    hdrHint = hint
                )
            }
            onReadyQueue(queue, startIndex)
        }
    }

    private fun loadSafDirectory(treeRootUri: Uri, directoryUri: Uri) {
        viewModelScope.launch {
            isLoadingFolderFlow.value = true
            try {
                activeSafEntriesFlow.value = mediaRepository.listSafDirectoryEntries(
                    treeRootUri = treeRootUri,
                    targetDirectoryUri = directoryUri
                )
            } finally {
                isLoadingFolderFlow.value = false
            }
        }
    }

    fun onPickedMediaUri(uri: Uri, onReadyToPlay: (MediaQueueItem) -> Unit) {
        viewModelScope.launch {
            val savedEntity = mediaRepository.registerPickedUri(uri)
            onReadyToPlay(savedEntity.toQueueItem())
        }
    }

    fun addAndPlayNetworkStream(
        url: String,
        title: String?,
        onCreated: (MediaQueueItem) -> Unit
    ) {
        if (url.isBlank()) return
        viewModelScope.launch {
            val saved = mediaRepository.addNetworkStream(url, title)
            onCreated(saved.toQueueItem())
        }
    }

    fun toggleFavorite(item: MediaItemEntity) {
        viewModelScope.launch {
            mediaRepository.toggleFavorite(item.uri, !item.isFavorite)
        }
    }

    fun clearResumeProgress(uri: String) {
        viewModelScope.launch {
            playbackStateDao.clearPlaybackState(uri)
        }
    }

    fun deleteNetworkStream(uri: String) {
        viewModelScope.launch {
            mediaRepository.deleteNetworkStream(uri)
        }
    }

    // Settings actions
    fun setHdrPassthrough(enabled: Boolean) {
        viewModelScope.launch { preferences.setHdrPassthroughEnabled(enabled) }
    }

    fun setToneMappingAlgorithm(algorithm: ToneMappingAlgorithm) {
        viewModelScope.launch { preferences.setToneMappingAlgorithm(algorithm) }
    }

    fun setHardwareAccelerationMode(mode: HardwareAccelerationMode) {
        viewModelScope.launch { preferences.setHardwareAccelerationMode(mode) }
    }

    fun setAutoResume(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutoResumePlayback(enabled) }
    }

    fun setAutoPip(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutoEnterPipOnBackground(enabled) }
    }

    fun setContinueAudioInBackground(enabled: Boolean) {
        viewModelScope.launch { preferences.setContinueAudioInBackground(enabled) }
    }

    fun setGooglebookMiniPlayer(enabled: Boolean) {
        viewModelScope.launch { preferences.setGooglebookAlwaysOnTopMiniPlayer(enabled) }
    }

    fun setDoubleTapSeekSeconds(seconds: Int) {
        viewModelScope.launch { preferences.setDoubleTapSeekSeconds(seconds) }
    }

    fun setNetworkCachingMs(cachingMs: Int) {
        viewModelScope.launch { preferences.setNetworkCachingMs(cachingMs) }
    }

    fun setSubtitleFontScale(scale: SubtitleFontScale) {
        viewModelScope.launch { preferences.setSubtitleFontScale(scale) }
    }

    fun setSubtitleBackgroundStyle(style: SubtitleBackgroundStyle) {
        viewModelScope.launch { preferences.setSubtitleBackgroundStyle(style) }
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutoCheckUpdatesEnabled(enabled) }
    }

    fun setApplicationLanguage(locale: SupportedLuminaLocale) {
        viewModelScope.launch {
            preferences.setAppLanguageTag(locale.languageTag)
            LuminaLocaleManager.applyLocale(locale)
        }
    }

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )

    private data class Quintuple<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E
    )
}

fun MediaItemEntity.toQueueItem(): MediaQueueItem {
    val hint = when (hdrBadge) {
        "Dolby Vision" -> HdrFormat.DOLBY_VISION
        "HDR10+" -> HdrFormat.HDR10_PLUS
        "HLG" -> HdrFormat.HLG
        "HDR10" -> HdrFormat.HDR10
        else -> null
    }
    return MediaQueueItem(
        uri = Uri.parse(uri),
        title = title,
        subtitle = folderName,
        durationMs = durationMs,
        mimeType = mimeType,
        isNetworkStream = isNetworkStream,
        hdrHint = hint
    )
}
