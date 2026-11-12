package app.lumina.player.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lumina.player.data.local.dao.PlaybackStateDao
import app.lumina.player.data.local.entity.MediaItemEntity
import app.lumina.player.data.local.entity.PlaybackStateEntity
import app.lumina.player.data.network.LanDiscoveredEndpoint
import app.lumina.player.data.network.LuminaLanDiscoveryManager
import app.lumina.player.data.preferences.HardwareAccelerationMode
import app.lumina.player.data.preferences.LuminaPreferences
import app.lumina.player.data.preferences.SubtitleBackgroundStyle
import app.lumina.player.data.preferences.SubtitleFontScale
import app.lumina.player.data.preferences.ToneMappingAlgorithm
import app.lumina.player.data.preferences.UserPreferences
import app.lumina.player.data.repository.LocalFileEntry
import app.lumina.player.data.repository.MediaRepository
import app.lumina.player.data.repository.SafPinnedFolderSummary
import app.lumina.player.player.model.HdrFormat
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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibraryTab {
    VIDEOS,
    FOLDERS,
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

data class LibraryUiState(
    val selectedTab: LibraryTab = LibraryTab.VIDEOS,
    val searchQuery: String = "",
    val isScanning: Boolean = false,
    val videos: List<MediaItemEntity> = emptyList(),
    val audioItems: List<MediaItemEntity> = emptyList(),
    val networkStreams: List<MediaItemEntity> = emptyList(),
    val discoveredLanEndpoints: List<LanDiscoveredEndpoint> = emptyList(),
    val isDiscoveringLan: Boolean = false,
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
    private val lanDiscoveryManager: LuminaLanDiscoveryManager
) : ViewModel() {

    private val selectedTabFlow = MutableStateFlow(LibraryTab.VIDEOS)
    private val searchQueryFlow = MutableStateFlow("")
    private val isScanningFlow = MutableStateFlow(false)

    private val folderBreadcrumbsFlow = MutableStateFlow<List<FolderBreadcrumb>>(emptyList())
    private val activeSafEntriesFlow = MutableStateFlow<List<LocalFileEntry>>(emptyList())
    private val isLoadingFolderFlow = MutableStateFlow(false)

    private val folderSortOptionFlow = MutableStateFlow(FolderSortOption.NAME)
    private val folderSortAscendingFlow = MutableStateFlow(true)
    private val folderHdrFilterFlow = MutableStateFlow(FolderHdrFilter.ALL)

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

    private val lanDiscoverySnapshotFlow = combine(
        lanDiscoveryManager.discoveredEndpoints,
        lanDiscoveryManager.isDiscovering
    ) { endpoints, discovering ->
        endpoints to discovering
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
            combine(browserNavFlow, lanDiscoverySnapshotFlow) { nav, lan -> nav to lan }
        ) { net, cont, states, prefs, navAndLan ->
            Quintuple(net, cont, states, prefs, navAndLan)
        }
    ) { tab, query, scanning, localItems, extra ->
        val videos = localItems.filter { !it.mimeType.startsWith("audio/", ignoreCase = true) }
        val audio = localItems.filter { it.mimeType.startsWith("audio/", ignoreCase = true) }
        val resumeMap = extra.third.associateBy { it.mediaUri }
        val browserNav = extra.fifth.first
        val (lanEndpoints, lanDiscovering) = extra.fifth.second
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
            discoveredLanEndpoints = lanEndpoints,
            isDiscoveringLan = lanDiscovering,
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
        // Keep subdirectories grouped above video files while respecting sort direction within each group
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

    /**
     * Steps up one directory level when inside a folder; returns `true` if handled.
     */
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

