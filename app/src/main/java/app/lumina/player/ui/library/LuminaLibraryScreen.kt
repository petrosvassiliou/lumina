package app.lumina.player.ui.library

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MenuOpen
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumina.player.R
import app.lumina.player.data.local.entity.MediaItemEntity
import app.lumina.player.data.local.entity.PlaybackStateEntity
import app.lumina.player.data.network.LanDiscoveredEndpoint
import app.lumina.player.data.preferences.HardwareAccelerationMode
import app.lumina.player.data.preferences.SubtitleBackgroundStyle
import app.lumina.player.data.preferences.SubtitleFontScale
import app.lumina.player.data.preferences.ToneMappingAlgorithm
import app.lumina.player.data.repository.LocalFileEntry
import app.lumina.player.data.repository.SafPinnedFolderSummary
import app.lumina.player.player.model.MediaQueueItem
import app.lumina.player.ui.adaptive.LuminaAdaptiveEnvironment
import app.lumina.player.ui.adaptive.LuminaLayoutMode
import app.lumina.player.ui.components.HdrBadgeChip
import app.lumina.player.ui.components.LuminaExpressiveSegmentedGroup
import app.lumina.player.ui.components.LuminaGlassIconButton
import app.lumina.player.ui.components.LuminaGlassPillButton
import app.lumina.player.ui.components.LuminaGlassSurface
import app.lumina.player.ui.components.formatDurationMs
import app.lumina.player.ui.components.luminaTopWindowInsets
import app.lumina.player.ui.theme.LuminaCyan
import app.lumina.player.ui.theme.LuminaDanger
import app.lumina.player.ui.theme.LuminaGlassBorder
import app.lumina.player.ui.theme.LuminaObsidianElevated
import app.lumina.player.ui.theme.LuminaOledBlack
import app.lumina.player.ui.theme.LuminaPlatinum
import app.lumina.player.ui.theme.LuminaTextMuted
import app.lumina.player.ui.theme.LuminaTextPrimary
import app.lumina.player.ui.theme.LuminaTextSecondary
import app.lumina.player.update.LuminaUpdateState
import app.lumina.player.update.UpdateCheckStatus
import app.lumina.player.util.LuminaLocaleManager
import app.lumina.player.util.SupportedLuminaLocale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import java.util.Locale

private fun hasAnyGrantedMediaPermission(context: Context, permissions: Array<String>): Boolean =
    permissions.any { perm ->
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

/**
 * Adaptive Material 3 Expressive Lumina Media Library & Settings Screen.
 *
 * - **Compact (Phones):** Translucent bottom navigation bar & one-handed ergonomics.
 * - **Medium (Foldables/Tablets):** Glass Navigation Rail & multi-column cinema grid.
 * - **Googlebook OS (Desktop):** Collapsible glass sidebar, custom window caption bar,
 *   mouse hover highlights, and right-click context menus on media cards.
 */
@Composable
fun LuminaLibraryScreen(
    viewModel: LuminaLibraryViewModel,
    adaptiveEnv: LuminaAdaptiveEnvironment,
    updateState: LuminaUpdateState,
    onPlayQueue: (items: List<MediaQueueItem>, startIndex: Int, smbUser: String?, smbPassword: String?) -> Unit,
    onCheckForUpdates: () -> Unit,
    onCompleteFlexibleUpdate: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Modern Scoped Storage Permission Launcher (100% Play Protect Compliant)
    val mediaPermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    var sidebarExpanded by rememberSaveable { mutableStateOf(true) }
    var showNetworkStreamDialog by rememberSaveable { mutableStateOf(false) }
    var prefilledStreamUrl by rememberSaveable { mutableStateOf("") }
    var prefilledStreamTitle by rememberSaveable { mutableStateOf("") }
    var hasGrantedMediaPermission by remember {
        mutableStateOf(hasAnyGrantedMediaPermission(context, mediaPermissions))
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val grantedNow = hasAnyGrantedMediaPermission(context, mediaPermissions)
                if (grantedNow && !hasGrantedMediaPermission) {
                    viewModel.scanLocalMedia()
                }
                hasGrantedMediaPermission = grantedNow
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val anyGranted = result.values.any { it } || hasAnyGrantedMediaPermission(context, mediaPermissions)
        hasGrantedMediaPermission = anyGranted
        if (anyGranted) {
            viewModel.scanLocalMedia()
        }
    }

    // Official Android Photo/Video Media Picker (works with zero storage permissions!)
    val visualMediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.onPickedMediaUri(uri) { item ->
                onPlayQueue(listOf(item), 0, null, null)
            }
        }
    }

    // SAF OpenDocument Picker for MKV, AVI, MOV, TS, and arbitrary video containers
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.onPickedMediaUri(uri) { item ->
                onPlayQueue(listOf(item), 0, null, null)
            }
        }
    }

    // SAF OpenDocument Picker specifically for FLAC, WAV, Opus, OGG, MP3, and lossless audio
    val openAudioDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.onPickedMediaUri(uri) { item ->
                onPlayQueue(listOf(item), 0, null, null)
            }
        }
    }

    // SAF OpenDocumentTree Launcher for browsing local directories, SD cards, and USB-C drives
    val openDirectoryTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.onSafFolderPicked(treeUri)
        }
    }

    BackHandler(
        enabled = uiState.selectedTab == LibraryTab.FOLDERS && uiState.folderBreadcrumbs.isNotEmpty()
    ) {
        viewModel.navigateUpFolder()
    }

    LaunchedEffect(Unit) {
        viewModel.scanLocalMedia()
    }

    val isCompact = adaptiveEnv.layoutMode == LuminaLayoutMode.COMPACT_PHONE
    val isDesktop = adaptiveEnv.layoutMode == LuminaLayoutMode.GOOGLEBOOK_DESKTOP

    Scaffold(
        containerColor = LuminaOledBlack,
        bottomBar = {
            if (isCompact) {
                LuminaBottomNavigationBar(
                    selectedTab = uiState.selectedTab,
                    onSelectTab = viewModel::selectTab
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LuminaOledBlack)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            LuminaPlatinum.copy(alpha = 0.06f),
                            LuminaCyan.copy(alpha = 0.04f),
                            Color.Transparent
                        ),
                        radius = 1200f
                    )
                )
                .padding(innerPadding)
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Desktop Collapsible Glass Sidebar (Googlebook OS)
                if (isDesktop) {
                    LuminaDesktopSidebar(
                        selectedTab = uiState.selectedTab,
                        expanded = sidebarExpanded,
                        onToggleExpand = { sidebarExpanded = !sidebarExpanded },
                        onSelectTab = viewModel::selectTab,
                        onOpenFile = { openDocumentLauncher.launch(arrayOf("video/*", "application/x-matroska", "*/*")) },
                        onAddStream = {
                            prefilledStreamUrl = ""
                            prefilledStreamTitle = ""
                            showNetworkStreamDialog = true
                        }
                    )
                } else if (!isCompact) {
                    // Foldable / Tablet Navigation Rail
                    LuminaAdaptiveRail(
                        selectedTab = uiState.selectedTab,
                        onSelectTab = viewModel::selectTab
                    )
                }

                // Main Content Area
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .luminaTopWindowInsets()
                ) {
                    // Top Glass Header / Googlebook OS Window Caption Bar
                    LuminaTopHeaderBar(
                        isDesktop = isDesktop,
                        searchQuery = uiState.searchQuery,
                        isScanning = uiState.isScanning,
                        onSearchChange = viewModel::updateSearchQuery,
                        onScanClick = {
                            if (hasGrantedMediaPermission) {
                                viewModel.scanLocalMedia()
                            } else {
                                permissionLauncher.launch(mediaPermissions)
                            }
                        },
                        onPickMediaClick = {
                            visualMediaPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        },
                        onOpenFileClick = {
                            openDocumentLauncher.launch(
                                arrayOf("video/*", "application/x-matroska", "application/octet-stream")
                            )
                        },
                        onBrowseFolderClick = {
                            openDirectoryTreeLauncher.launch(null)
                        },
                        onAddStreamClick = {
                            prefilledStreamUrl = ""
                            prefilledStreamTitle = ""
                            showNetworkStreamDialog = true
                        }
                    )

                    // Flexible Google Play Update Ready Glass Banner
                    AnimatedVisibility(visible = updateState.isReadyToInstall || updateState.isDownloading) {
                        LuminaFlexibleUpdateBanner(
                            updateState = updateState,
                            onRestartToUpdate = onCompleteFlexibleUpdate,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }

                    // Active Tab Body
                    when (uiState.selectedTab) {
                        LibraryTab.VIDEOS -> VideosLibraryPane(
                            videos = uiState.videos,
                            continueWatching = uiState.continueWatching,
                            resumeMap = uiState.resumeProgressByUri,
                            showPermissionBanner = uiState.videos.isEmpty() && !hasGrantedMediaPermission,
                            onRequestPermissions = { permissionLauncher.launch(mediaPermissions) },
                            onOpenDocument = { openDocumentLauncher.launch(arrayOf("video/*", "*/*")) },
                            onPlayVideo = { index ->
                                val queue = uiState.videos.map { it.toQueueItem() }
                                onPlayQueue(queue, index, null, null)
                            },
                            onPlayContinueItem = { entity ->
                                onPlayQueue(listOf(entity.toQueueItem()), 0, null, null)
                            },
                            onToggleFavorite = viewModel::toggleFavorite,
                            onClearResume = { viewModel.clearResumeProgress(it.uri) }
                        )

                        LibraryTab.FOLDERS -> LocalFoldersBrowserPane(
                            uiState = uiState,
                            onMountSafFolder = { openDirectoryTreeLauncher.launch(null) },
                            onOpenSingleVideoFile = {
                                openDocumentLauncher.launch(
                                    arrayOf("video/*", "application/x-matroska", "*/*")
                                )
                            },
                            onOpenPinnedFolder = viewModel::openPinnedSafFolder,
                            onRemovePinnedFolder = viewModel::removePinnedSafFolder,
                            onOpenDeviceBucket = viewModel::openDeviceBucketFolder,
                            onOpenSafSubdirectory = viewModel::openSafSubdirectory,
                            onNavigateBreadcrumb = viewModel::navigateToBreadcrumb,
                            onNavigateUp = { viewModel.navigateUpFolder() },
                            onSelectSortOption = viewModel::setFolderSortOption,
                            onToggleSortDirection = viewModel::toggleFolderSortDirection,
                            onSelectHdrFilter = viewModel::setFolderHdrFilter,
                            onPlayFolderEntry = { clicked ->
                                val folderLabel = uiState.folderBreadcrumbs.lastOrNull()?.label ?: "Local Folder"
                                viewModel.playFromFolderEntry(
                                    clickedEntry = clicked,
                                    siblingEntries = uiState.activeFolderEntries,
                                    folderLabel = folderLabel
                                ) { queue, startIndex ->
                                    onPlayQueue(queue, startIndex, null, null)
                                }
                            }
                        )

                        LibraryTab.AUDIO -> AudioLibraryPane(
                            audioItems = uiState.audioItems,
                            onOpenAudioFile = {
                                openAudioDocumentLauncher.launch(
                                    arrayOf("audio/*", "application/ogg", "application/flac", "*/*")
                                )
                            },
                            onPlayAudio = { index ->
                                val queue = uiState.audioItems.map { it.toQueueItem() }
                                onPlayQueue(queue, index, null, null)
                            },
                            onToggleFavorite = viewModel::toggleFavorite
                        )

                        LibraryTab.NETWORK -> NetworkStreamsPane(
                            streams = uiState.networkStreams,
                            discoveredEndpoints = uiState.discoveredLanEndpoints,
                            isDiscoveringLan = uiState.isDiscoveringLan,
                            onScanLanClick = viewModel::refreshLanDiscovery,
                            onAddStreamClick = {
                                prefilledStreamUrl = ""
                                prefilledStreamTitle = ""
                                showNetworkStreamDialog = true
                            },
                            onOpenDiscoveredEndpoint = { endpoint ->
                                if (endpoint.protocolBadge.equals("SMB", ignoreCase = true) || endpoint.isDirectory) {
                                    prefilledStreamUrl = if (endpoint.uri.endsWith("/")) endpoint.uri else "${endpoint.uri}/"
                                    prefilledStreamTitle = endpoint.title
                                    showNetworkStreamDialog = true
                                } else {
                                    viewModel.addAndPlayNetworkStream(endpoint.uri, endpoint.title) { createdItem ->
                                        onPlayQueue(listOf(createdItem), 0, null, null)
                                    }
                                }
                            },
                            onPlayStream = { entity ->
                                onPlayQueue(listOf(entity.toQueueItem()), 0, null, null)
                            },
                            onDeleteStream = { viewModel.deleteNetworkStream(it.uri) }
                        )

                        LibraryTab.SETTINGS -> SettingsPane(
                            uiState = uiState,
                            updateState = updateState,
                            viewModel = viewModel,
                            onCheckForUpdates = onCheckForUpdates,
                            onCompleteFlexibleUpdate = onCompleteFlexibleUpdate
                        )
                    }
                }
            }
        }
    }

    if (showNetworkStreamDialog) {
        NetworkStreamLauncherDialog(
            initialUrl = prefilledStreamUrl,
            initialTitle = prefilledStreamTitle,
            onDismiss = { showNetworkStreamDialog = false },
            onSubmit = { url, title, smbUser, smbPass ->
                showNetworkStreamDialog = false
                viewModel.addAndPlayNetworkStream(url, title) { createdItem ->
                    onPlayQueue(listOf(createdItem), 0, smbUser, smbPass)
                }
            }
        )
    }
}

@Composable
private fun LuminaTopHeaderBar(
    isDesktop: Boolean,
    searchQuery: String,
    isScanning: Boolean,
    onSearchChange: (String) -> Unit,
    onScanClick: () -> Unit,
    onPickMediaClick: () -> Unit,
    onOpenFileClick: () -> Unit,
    onBrowseFolderClick: () -> Unit,
    onAddStreamClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(LuminaPlatinum.copy(alpha = 0.28f), LuminaCyan.copy(alpha = 0.22f))
                            )
                        )
                        .border(1.dp, LuminaPlatinum.copy(alpha = 0.7f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = LuminaPlatinum,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = if (isDesktop) {
                            stringResource(R.string.app_full_title)
                        } else {
                            stringResource(R.string.app_name)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = LuminaTextPrimary
                    )
                    Text(
                        text = stringResource(R.string.app_tagline),
                        style = MaterialTheme.typography.labelSmall,
                        color = LuminaCyan
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        color = LuminaPlatinum,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp)
                    )
                }
                LuminaGlassPillButton(
                    label = stringResource(R.string.library_open_file),
                    leadingIcon = Icons.Outlined.FolderOpen,
                    onClick = onOpenFileClick
                )
                if (isDesktop) {
                    LuminaGlassPillButton(
                        label = stringResource(R.string.folders_mount_folder),
                        leadingIcon = Icons.Outlined.CreateNewFolder,
                        onClick = onBrowseFolderClick
                    )
                    LuminaGlassPillButton(
                        label = stringResource(R.string.library_pick_media),
                        leadingIcon = Icons.Outlined.VideoLibrary,
                        onClick = onPickMediaClick
                    )
                    LuminaGlassPillButton(
                        label = stringResource(R.string.library_add_stream),
                        leadingIcon = Icons.Outlined.AddLink,
                        onClick = onAddStreamClick,
                        accentColor = LuminaCyan
                    )
                }
                LuminaGlassIconButton(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.library_scan_storage),
                    onClick = onScanClick
                )
            }
        }

        // Translucent Glass Search Bar
        LuminaGlassSurface(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = LuminaTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.library_search_hint),
                            color = LuminaTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                if (searchQuery.isNotEmpty()) {
                    LuminaGlassIconButton(
                        icon = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.action_close),
                        size = 32.dp,
                        iconSize = 16.dp,
                        onClick = { onSearchChange("") }
                    )
                }
            }
        }
    }
}

@Composable
private fun VideosLibraryPane(
    videos: List<MediaItemEntity>,
    continueWatching: List<MediaItemEntity>,
    resumeMap: Map<String, PlaybackStateEntity>,
    showPermissionBanner: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenDocument: () -> Unit,
    onPlayVideo: (Int) -> Unit,
    onPlayContinueItem: (MediaItemEntity) -> Unit,
    onToggleFavorite: (MediaItemEntity) -> Unit,
    onClearResume: (MediaItemEntity) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 240.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (showPermissionBanner) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LuminaGlassSurface(
                    accentGlow = LuminaPlatinum,
                    contentPadding = PaddingValues(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = stringResource(R.string.library_grant_permission_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = LuminaPlatinum
                        )
                        Text(
                            text = stringResource(R.string.library_grant_permission_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = LuminaTextSecondary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LuminaGlassPillButton(
                                label = stringResource(R.string.library_grant_permission_action),
                                leadingIcon = Icons.Outlined.VideoLibrary,
                                isActive = true,
                                onClick = onRequestPermissions
                            )
                            LuminaGlassPillButton(
                                label = stringResource(R.string.library_open_file),
                                leadingIcon = Icons.Outlined.FolderOpen,
                                onClick = onOpenDocument
                            )
                        }
                    }
                }
            }
        }

        if (continueWatching.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.library_continue_watching),
                        style = MaterialTheme.typography.titleMedium,
                        color = LuminaTextPrimary
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(continueWatching, key = { "cont_${it.uri}" }) { media ->
                            ContinueWatchingCard(
                                item = media,
                                resumeState = resumeMap[media.uri],
                                onClick = { onPlayContinueItem(media) }
                            )
                        }
                    }
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = stringResource(R.string.library_all_videos),
                style = MaterialTheme.typography.titleMedium,
                color = LuminaTextPrimary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (videos.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyLibraryPlaceholder(
                    message = stringResource(R.string.library_empty_videos),
                    actionLabel = stringResource(R.string.library_open_file),
                    onAction = onOpenDocument
                )
            }
        } else {
            itemsIndexed(videos, key = { _, item -> item.uri }) { index, item ->
                VideoMediaCard(
                    item = item,
                    resumeState = resumeMap[item.uri],
                    onClick = { onPlayVideo(index) },
                    onToggleFavorite = { onToggleFavorite(item) },
                    onResetProgress = { onClearResume(item) }
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    item: MediaItemEntity,
    resumeState: PlaybackStateEntity?,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val progress = remember(resumeState, item.durationMs) {
        val dur = resumeState?.durationMs?.takeIf { it > 0L } ?: item.durationMs
        if (dur > 0L && resumeState != null && !resumeState.completed) {
            (resumeState.positionMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    }

    LuminaGlassSurface(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .width(260.dp)
            .clickable(onClick = onClick)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(138.dp)
                    .background(LuminaObsidianElevated)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.uri)
                        .videoFrameMillis((resumeState?.positionMs ?: 5_000L).coerceAtLeast(1_000L))
                        .crossfade(true)
                        .build(),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, LuminaOledBlack.copy(alpha = 0.85f))
                            )
                        )
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    item.hdrBadge?.let { HdrBadgeChip(it) }
                    item.videoCodec?.let { HdrBadgeChip(it) }
                }
                if (progress > 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        color = LuminaPlatinum,
                        trackColor = Color.White.copy(alpha = 0.2f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .align(Alignment.BottomCenter)
                    )
                }
            }
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = LuminaTextPrimary
                )
                val resumeLabel = if (resumeState != null && resumeState.positionMs > 0L) {
                    "${formatDurationMs(resumeState.positionMs)} / ${formatDurationMs(item.durationMs)}"
                } else {
                    formatDurationMs(item.durationMs)
                }
                Text(
                    text = resumeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = LuminaPlatinum
                )
            }
        }
    }
}

/**
 * Cinema Video Card supporting:
 * - Touch tap & long-press
 * - Googlebook OS mouse hover border glow
 * - Googlebook OS right-click desktop context menu (`isSecondaryPressed`)
 */
@Composable
private fun VideoMediaCard(
    item: MediaItemEntity,
    resumeState: PlaybackStateEntity?,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onResetProgress: () -> Unit
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var contextMenuExpanded by remember { mutableStateOf(false) }

    val borderColor by animateColorAsState(
        targetValue = if (isHovered) LuminaPlatinum.copy(alpha = 0.85f) else LuminaGlassBorder,
        label = "cardHoverBorder"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xCC0D111A))
            .border(1.dp, borderColor, RoundedCornerShape(18.dp))
            .hoverable(interactionSource)
            .pointerInput(item.uri) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            event.changes.forEach { it.consume() }
                            contextMenuExpanded = true
                        }
                    }
                }
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = { contextMenuExpanded = true }
            )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(LuminaObsidianElevated)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.uri)
                        .videoFrameMillis(4_000L)
                        .crossfade(true)
                        .build(),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, LuminaOledBlack.copy(alpha = 0.82f))
                            )
                        )
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                ) {
                    item.hdrBadge?.let { HdrBadgeChip(it) }
                    item.videoCodec?.let { HdrBadgeChip(it) }
                }

                if (item.durationMs > 0L) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(LuminaOledBlack.copy(alpha = 0.82f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = formatDurationMs(item.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = LuminaTextPrimary
                        )
                    }
                }

                if (resumeState != null && !resumeState.completed && item.durationMs > 0L) {
                    val pct = (resumeState.positionMs.toFloat() / item.durationMs.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { pct },
                        color = LuminaPlatinum,
                        trackColor = Color.White.copy(alpha = 0.2f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .align(Alignment.BottomCenter)
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = LuminaTextPrimary
                    )
                    val resolutionTag = if (item.width > 0 && item.height > 0) {
                        "${item.width}×${item.height} • ${item.folderName}"
                    } else {
                        item.folderName
                    }
                    Text(
                        text = resolutionTag,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = LuminaTextSecondary
                    )
                }

                Icon(
                    imageVector = if (item.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = stringResource(R.string.action_favorite),
                    tint = if (item.isFavorite) LuminaPlatinum else LuminaTextMuted,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable { onToggleFavorite() }
                )
            }
        }

        // Right-Click / Long-Press Context Menu
        DropdownMenu(
            expanded = contextMenuExpanded,
            onDismissRequest = { contextMenuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_play)) },
                leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                onClick = {
                    contextMenuExpanded = false
                    onClick()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_favorite)) },
                leadingIcon = { Icon(Icons.Outlined.FavoriteBorder, contentDescription = null) },
                onClick = {
                    contextMenuExpanded = false
                    onToggleFavorite()
                }
            )
            if (resumeState != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_reset)) },
                    leadingIcon = { Icon(Icons.Outlined.RestartAlt, contentDescription = null) },
                    onClick = {
                        contextMenuExpanded = false
                        onResetProgress()
                    }
                )
            }
        }
    }
}

@Composable
private fun AudioLibraryPane(
    audioItems: List<MediaItemEntity>,
    onOpenAudioFile: () -> Unit,
    onPlayAudio: (Int) -> Unit,
    onToggleFavorite: (MediaItemEntity) -> Unit
) {
    if (audioItems.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            EmptyLibraryPlaceholder(
                message = stringResource(R.string.library_empty_audio),
                actionLabel = stringResource(R.string.library_open_audio_file),
                onAction = onOpenAudioFile
            )
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.library_lossless_audio),
                    style = MaterialTheme.typography.titleMedium,
                    color = LuminaTextPrimary
                )
                LuminaGlassPillButton(
                    label = stringResource(R.string.library_open_audio_file),
                    leadingIcon = Icons.Outlined.FolderOpen,
                    accentColor = LuminaCyan,
                    onClick = onOpenAudioFile
                )
            }
        }
        items(audioItems.size, key = { idx -> audioItems[idx].uri }) { index ->
            val audio = audioItems[index]
            LuminaGlassSurface(
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlayAudio(index) }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Album,
                        contentDescription = null,
                        tint = LuminaCyan,
                        modifier = Modifier.size(28.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = audio.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = LuminaTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${audio.folderName} • ${formatDurationMs(audio.durationMs)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LuminaTextSecondary
                        )
                    }
                    audio.videoCodec?.let { HdrBadgeChip(it) }
                    LuminaGlassIconButton(
                        icon = if (audio.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = stringResource(R.string.action_favorite),
                        size = 36.dp,
                        iconSize = 18.dp,
                        isActive = audio.isFavorite,
                        onClick = { onToggleFavorite(audio) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkStreamsPane(
    streams: List<MediaItemEntity>,
    discoveredEndpoints: List<LanDiscoveredEndpoint>,
    isDiscoveringLan: Boolean,
    onScanLanClick: () -> Unit,
    onAddStreamClick: () -> Unit,
    onOpenDiscoveredEndpoint: (LanDiscoveredEndpoint) -> Unit,
    onPlayStream: (MediaItemEntity) -> Unit,
    onDeleteStream: (MediaItemEntity) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // 1. Live Local Network Discovery (UPnP / DLNA / SMB / mDNS)
        item {
            LuminaGlassSurface(
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.WifiFind,
                                contentDescription = null,
                                tint = LuminaCyan
                            )
                            Text(
                                text = stringResource(R.string.network_lan_section),
                                style = MaterialTheme.typography.titleMedium,
                                color = LuminaPlatinum
                            )
                        }
                        LuminaGlassPillButton(
                            label = if (isDiscoveringLan) {
                                stringResource(R.string.network_scanning_lan)
                            } else {
                                stringResource(R.string.network_scan_lan)
                            },
                            leadingIcon = Icons.Outlined.Refresh,
                            isActive = isDiscoveringLan,
                            accentColor = LuminaCyan,
                            onClick = onScanLanClick
                        )
                    }

                    if (discoveredEndpoints.isEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (isDiscoveringLan) {
                                CircularProgressIndicator(
                                    color = LuminaCyan,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = stringResource(R.string.network_lan_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = LuminaTextSecondary
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            discoveredEndpoints.forEach { endpoint ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color.White.copy(alpha = 0.05f))
                                        .border(1.dp, LuminaGlassBorder, RoundedCornerShape(14.dp))
                                        .clickable { onOpenDiscoveredEndpoint(endpoint) }
                                        .padding(horizontal = 14.dp, vertical = 12.dp)
                                ) {
                                    Icon(
                                        imageVector = if (endpoint.isDirectory) {
                                            Icons.Outlined.Storage
                                        } else {
                                            Icons.Outlined.Router
                                        },
                                        contentDescription = null,
                                        tint = LuminaCyan,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = endpoint.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = LuminaTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = endpoint.uri,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = LuminaTextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    HdrBadgeChip(endpoint.protocolBadge)
                                    Icon(
                                        imageVector = Icons.Outlined.ChevronRight,
                                        contentDescription = null,
                                        tint = LuminaTextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. Saved & Custom Network Streams Header
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.library_network_streams),
                    style = MaterialTheme.typography.titleMedium,
                    color = LuminaTextPrimary
                )
                LuminaGlassPillButton(
                    label = stringResource(R.string.library_add_stream),
                    leadingIcon = Icons.Outlined.AddLink,
                    isActive = true,
                    accentColor = LuminaCyan,
                    onClick = onAddStreamClick
                )
            }
        }

        if (streams.isEmpty()) {
            item {
                EmptyLibraryPlaceholder(
                    message = stringResource(R.string.library_empty_network),
                    actionLabel = stringResource(R.string.library_add_stream),
                    onAction = onAddStreamClick
                )
            }
        } else {
            items(streams, key = { it.uri }) { stream ->
                LuminaGlassSurface(
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlayStream(stream) }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Router,
                            contentDescription = null,
                            tint = LuminaCyan,
                            modifier = Modifier.size(26.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stream.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = LuminaTextPrimary
                            )
                            Text(
                                text = stream.uri,
                                style = MaterialTheme.typography.bodyMedium,
                                color = LuminaTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        stream.networkProtocol?.let { HdrBadgeChip(it) }
                        LuminaGlassIconButton(
                            icon = Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = LuminaDanger,
                            size = 38.dp,
                            onClick = { onDeleteStream(stream) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsPane(
    uiState: LibraryUiState,
    updateState: LuminaUpdateState,
    viewModel: LuminaLibraryViewModel,
    onCheckForUpdates: () -> Unit,
    onCompleteFlexibleUpdate: () -> Unit
) {
    val prefs = uiState.preferences
    val activeLocale = remember(prefs.appLanguageTag) {
        LuminaLocaleManager.getCurrentLocale()
    }
    val seekStepOptions = remember { listOf(5, 10, 15, 30) }
    val networkBufferOptions = remember { listOf(500, 1500, 3000, 5000) }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // 1. HDR & Video Engine Section
        item {
            LuminaGlassSurface(
                contentPadding = PaddingValues(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = stringResource(R.string.settings_hdr_section),
                        style = MaterialTheme.typography.titleMedium,
                        color = LuminaPlatinum
                    )

                    SettingsToggleRow(
                        title = stringResource(R.string.settings_hdr_passthrough),
                        subtitle = stringResource(R.string.settings_hdr_passthrough_desc),
                        checked = prefs.hdrPassthroughEnabled,
                        onCheckedChange = viewModel::setHdrPassthrough
                    )

                    Text(
                        text = stringResource(R.string.settings_tonemapping),
                        style = MaterialTheme.typography.labelLarge,
                        color = LuminaTextSecondary
                    )
                    LuminaExpressiveSegmentedGroup(
                        options = ToneMappingAlgorithm.entries,
                        selectedOption = prefs.sdrToneMappingAlgorithm,
                        labelProvider = { it.vlcValue.uppercase(Locale.ROOT) },
                        onSelect = viewModel::setToneMappingAlgorithm
                    )

                    Text(
                        text = stringResource(R.string.settings_hw_accel),
                        style = MaterialTheme.typography.labelLarge,
                        color = LuminaTextSecondary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HardwareAccelerationMode.entries.forEach { mode ->
                            LuminaGlassPillButton(
                                label = mode.name.replace('_', ' '),
                                isActive = prefs.hardwareAccelerationMode == mode,
                                accentColor = LuminaCyan,
                                onClick = { viewModel.setHardwareAccelerationMode(mode) }
                            )
                        }
                    }
                }
            }
        }

        // 2. Playback, Seek/Buffer Tuning & Googlebook OS Window Behavior
        item {
            LuminaGlassSurface(
                contentPadding = PaddingValues(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = stringResource(R.string.settings_playback_section),
                        style = MaterialTheme.typography.titleMedium,
                        color = LuminaCyan
                    )

                    SettingsToggleRow(
                        title = stringResource(R.string.settings_auto_resume),
                        subtitle = null,
                        checked = prefs.autoResumePlayback,
                        onCheckedChange = viewModel::setAutoResume
                    )

                    SettingsToggleRow(
                        title = stringResource(R.string.settings_auto_pip),
                        subtitle = null,
                        checked = prefs.autoEnterPipOnBackground,
                        onCheckedChange = viewModel::setAutoPip
                    )

                    SettingsToggleRow(
                        title = stringResource(R.string.settings_googlebook_mini),
                        subtitle = null,
                        checked = prefs.googlebookAlwaysOnTopMiniPlayer,
                        onCheckedChange = viewModel::setGooglebookMiniPlayer
                    )

                    Text(
                        text = stringResource(R.string.settings_seek_step),
                        style = MaterialTheme.typography.labelLarge,
                        color = LuminaTextSecondary
                    )
                    LuminaExpressiveSegmentedGroup(
                        options = seekStepOptions,
                        selectedOption = prefs.doubleTapSeekSeconds,
                        labelProvider = { "${it}s" },
                        onSelect = viewModel::setDoubleTapSeekSeconds
                    )

                    Text(
                        text = stringResource(R.string.settings_network_buffer),
                        style = MaterialTheme.typography.labelLarge,
                        color = LuminaTextSecondary
                    )
                    LuminaExpressiveSegmentedGroup(
                        options = networkBufferOptions,
                        selectedOption = prefs.networkCachingMs,
                        labelProvider = { "${it} ms" },
                        accentColor = LuminaCyan,
                        onSelect = viewModel::setNetworkCachingMs
                    )

                    Text(
                        text = stringResource(R.string.settings_subtitle_size),
                        style = MaterialTheme.typography.labelLarge,
                        color = LuminaTextSecondary
                    )
                    LuminaExpressiveSegmentedGroup(
                        options = SubtitleFontScale.entries,
                        selectedOption = prefs.subtitleFontScale,
                        labelProvider = { it.displayLabel },
                        onSelect = viewModel::setSubtitleFontScale
                    )

                    val subBgClear = stringResource(R.string.subtitle_bg_clear)
                    val subBgGlass = stringResource(R.string.subtitle_bg_glass)
                    val subBgSolid = stringResource(R.string.subtitle_bg_solid)
                    Text(
                        text = stringResource(R.string.settings_subtitle_background),
                        style = MaterialTheme.typography.labelLarge,
                        color = LuminaTextSecondary
                    )
                    LuminaExpressiveSegmentedGroup(
                        options = SubtitleBackgroundStyle.entries,
                        selectedOption = prefs.subtitleBackgroundStyle,
                        labelProvider = { style ->
                            when (style) {
                                SubtitleBackgroundStyle.NONE -> subBgClear
                                SubtitleBackgroundStyle.TRANSLUCENT -> subBgGlass
                                SubtitleBackgroundStyle.SOLID -> subBgSolid
                            }
                        },
                        accentColor = LuminaCyan,
                        onSelect = viewModel::setSubtitleBackgroundStyle
                    )

                    Text(
                        text = stringResource(R.string.settings_shortcuts_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = LuminaTextSecondary
                    )
                }
            }
        }

        // 3. Multi-Language Picker & Google Play Protect Updates (Automatic & Manual)
        item {
            LuminaGlassSurface(
                contentPadding = PaddingValues(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Language,
                                contentDescription = null,
                                tint = LuminaPlatinum
                            )
                            Text(
                                text = stringResource(R.string.settings_language_section),
                                style = MaterialTheme.typography.titleMedium,
                                color = LuminaPlatinum
                            )
                        }
                        HdrBadgeChip(
                            badgeText = stringResource(
                                R.string.update_version_badge,
                                updateState.installedVersionName
                            )
                        )
                    }

                    Text(
                        text = stringResource(R.string.settings_app_language),
                        style = MaterialTheme.typography.labelLarge,
                        color = LuminaTextSecondary
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SupportedLuminaLocale.entries.forEach { locale ->
                            val label = if (locale == SupportedLuminaLocale.SYSTEM) {
                                stringResource(R.string.settings_language_system)
                            } else {
                                locale.nativeDisplayName
                            }
                            LuminaGlassPillButton(
                                label = label,
                                isActive = activeLocale == locale,
                                onClick = { viewModel.setApplicationLanguage(locale) }
                            )
                        }
                    }

                    // Automatic Google Play Protect Updates Toggle
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_auto_updates),
                        subtitle = stringResource(R.string.settings_auto_updates_desc),
                        checked = prefs.autoCheckUpdatesEnabled,
                        onCheckedChange = viewModel::setAutoCheckUpdates
                    )

                    // Manual Check for Updates Button + Live Play Protect Status
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LuminaGlassPillButton(
                            label = stringResource(R.string.settings_check_updates),
                            leadingIcon = Icons.Outlined.SystemUpdate,
                            isActive = updateState.isChecking,
                            accentColor = LuminaCyan,
                            onClick = onCheckForUpdates
                        )

                        if (updateState.isReadyToInstall) {
                            LuminaGlassPillButton(
                                label = stringResource(R.string.update_restart_action),
                                leadingIcon = Icons.Outlined.VerifiedUser,
                                isActive = true,
                                accentColor = LuminaPlatinum,
                                onClick = onCompleteFlexibleUpdate
                            )
                        }
                    }

                    val statusMessage = when (updateState.status) {
                        UpdateCheckStatus.CHECKING -> stringResource(R.string.update_checking_status)
                        UpdateCheckStatus.UP_TO_DATE -> if (updateState.isManualCheckResultVisible) {
                            stringResource(R.string.update_up_to_date)
                        } else {
                            null
                        }
                        UpdateCheckStatus.UPDATE_AVAILABLE -> stringResource(R.string.update_available_flexible)
                        UpdateCheckStatus.DOWNLOADING -> stringResource(
                            R.string.update_downloading,
                            updateState.downloadProgressPercent
                        )
                        UpdateCheckStatus.READY_TO_INSTALL -> stringResource(R.string.update_downloaded_prompt)
                        UpdateCheckStatus.STORE_UNAVAILABLE -> if (updateState.isManualCheckResultVisible) {
                            stringResource(R.string.update_store_unavailable)
                        } else {
                            null
                        }
                        UpdateCheckStatus.IDLE -> null
                    }

                    if (statusMessage != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (updateState.isChecking) {
                                CircularProgressIndicator(
                                    color = LuminaCyan,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.VerifiedUser,
                                    contentDescription = null,
                                    tint = LuminaCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = statusMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = LuminaCyan
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = LuminaTextPrimary
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LuminaTextSecondary
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = LuminaOledBlack,
                checkedTrackColor = LuminaPlatinum,
                uncheckedThumbColor = LuminaTextSecondary,
                uncheckedTrackColor = LuminaObsidianElevated
            )
        )
    }
}

@Composable
private fun LuminaBottomNavigationBar(
    selectedTab: LibraryTab,
    onSelectTab: (LibraryTab) -> Unit
) {
    NavigationBar(
        containerColor = Color(0xEB080A10),
        modifier = Modifier.navigationBarsPadding()
    ) {
        LibraryDestinationItem.entries.forEach { dest ->
            NavigationBarItem(
                selected = selectedTab == dest.tab,
                onClick = { onSelectTab(dest.tab) },
                icon = { Icon(dest.icon, contentDescription = stringResource(dest.labelRes)) },
                label = { Text(stringResource(dest.labelRes)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = LuminaOledBlack,
                    selectedTextColor = LuminaPlatinum,
                    indicatorColor = LuminaPlatinum,
                    unselectedIconColor = LuminaTextSecondary,
                    unselectedTextColor = LuminaTextSecondary
                )
            )
        }
    }
}

@Composable
private fun LuminaAdaptiveRail(
    selectedTab: LibraryTab,
    onSelectTab: (LibraryTab) -> Unit
) {
    NavigationRail(
        containerColor = Color(0xD9080A10),
        modifier = Modifier.fillMaxHeight()
    ) {
        Spacer(Modifier.height(16.dp))
        LibraryDestinationItem.entries.forEach { dest ->
            NavigationRailItem(
                selected = selectedTab == dest.tab,
                onClick = { onSelectTab(dest.tab) },
                icon = { Icon(dest.icon, contentDescription = stringResource(dest.labelRes)) },
                label = { Text(stringResource(dest.labelRes)) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = LuminaOledBlack,
                    selectedTextColor = LuminaPlatinum,
                    indicatorColor = LuminaPlatinum,
                    unselectedIconColor = LuminaTextSecondary,
                    unselectedTextColor = LuminaTextSecondary
                )
            )
        }
    }
}

@Composable
private fun LuminaDesktopSidebar(
    selectedTab: LibraryTab,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelectTab: (LibraryTab) -> Unit,
    onOpenFile: () -> Unit,
    onAddStream: () -> Unit
) {
    val width = if (expanded) 240.dp else 76.dp
    LuminaGlassSurface(
        shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
        strongOpacity = true,
        contentPadding = PaddingValues(12.dp),
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .luminaTopWindowInsets()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                if (expanded) {
                    Text(
                        text = "LUMINA OS",
                        style = MaterialTheme.typography.labelLarge,
                        color = LuminaPlatinum,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                LuminaGlassIconButton(
                    icon = Icons.Outlined.MenuOpen,
                    contentDescription = null,
                    size = 38.dp,
                    onClick = onToggleExpand
                )
            }

            LibraryDestinationItem.entries.forEach { dest ->
                val isSelected = selectedTab == dest.tab
                val interactionSource = remember { MutableInteractionSource() }
                val hovered by interactionSource.collectIsHoveredAsState()

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            when {
                                isSelected -> LuminaPlatinum.copy(alpha = 0.20f)
                                hovered -> Color.White.copy(alpha = 0.08f)
                                else -> Color.Transparent
                            }
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) LuminaPlatinum.copy(alpha = 0.65f) else Color.Transparent,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .hoverable(interactionSource)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null
                        ) { onSelectTab(dest.tab) }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = dest.icon,
                        contentDescription = stringResource(dest.labelRes),
                        tint = if (isSelected) LuminaPlatinum else LuminaTextSecondary
                    )
                    if (expanded) {
                        Text(
                            text = stringResource(dest.labelRes),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isSelected) LuminaPlatinum else LuminaTextPrimary
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            if (expanded) {
                LuminaGlassPillButton(
                    label = stringResource(R.string.library_open_file),
                    leadingIcon = Icons.Outlined.FolderOpen,
                    onClick = onOpenFile,
                    modifier = Modifier.fillMaxWidth()
                )
                LuminaGlassPillButton(
                    label = stringResource(R.string.library_add_stream),
                    leadingIcon = Icons.Outlined.AddLink,
                    accentColor = LuminaCyan,
                    onClick = onAddStream,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun LuminaFlexibleUpdateBanner(
    updateState: LuminaUpdateState,
    onRestartToUpdate: () -> Unit,
    modifier: Modifier = Modifier
) {
    LuminaGlassSurface(
        accentGlow = LuminaCyan,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.SystemUpdate,
                    contentDescription = null,
                    tint = LuminaCyan
                )
                Text(
                    text = if (updateState.isReadyToInstall) {
                        stringResource(R.string.update_downloaded_prompt)
                    } else {
                        stringResource(R.string.update_downloading, updateState.downloadProgressPercent)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = LuminaTextPrimary
                )
            }
            if (updateState.isReadyToInstall) {
                LuminaGlassPillButton(
                    label = stringResource(R.string.update_restart_action),
                    isActive = true,
                    accentColor = LuminaPlatinum,
                    onClick = onRestartToUpdate
                )
            }
        }
    }
}

@Composable
private fun EmptyLibraryPlaceholder(
    message: String,
    actionLabel: String?,
    onAction: () -> Unit
) {
    LuminaGlassSurface(
        contentPadding = PaddingValues(28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = LuminaTextSecondary
            )
            if (actionLabel != null) {
                LuminaGlassPillButton(
                    label = actionLabel,
                    leadingIcon = Icons.Outlined.FolderOpen,
                    isActive = true,
                    onClick = onAction
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NetworkStreamLauncherDialog(
    initialUrl: String = "",
    initialTitle: String = "",
    onDismiss: () -> Unit,
    onSubmit: (url: String, title: String?, smbUser: String?, smbPassword: String?) -> Unit
) {
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var title by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    var smbUser by rememberSaveable { mutableStateOf("") }
    var smbPassword by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LuminaObsidianElevated,
        title = {
            Text(
                text = stringResource(R.string.network_dialog_title),
                color = LuminaTextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("smb://", "rtsp://", "https://", "ftp://", "upnp://").forEach { prefix ->
                        LuminaGlassPillButton(
                            label = prefix,
                            isActive = url.startsWith(prefix, ignoreCase = true),
                            accentColor = LuminaCyan,
                            onClick = {
                                val remainder = url.substringAfter("://", missingDelimiterValue = url)
                                url = if (remainder == url) prefix else "$prefix$remainder"
                            }
                        )
                    }
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.network_url_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.network_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (url.startsWith("smb://", ignoreCase = true) || url.startsWith("ftp://", ignoreCase = true)) {
                    OutlinedTextField(
                        value = smbUser,
                        onValueChange = { smbUser = it },
                        label = { Text(stringResource(R.string.network_smb_user_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = smbPassword,
                        onValueChange = { smbPassword = it },
                        label = { Text(stringResource(R.string.network_smb_password_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            LuminaGlassPillButton(
                label = stringResource(R.string.network_play_now),
                leadingIcon = Icons.Outlined.PlayArrow,
                isActive = url.isNotBlank() && !url.endsWith("://"),
                onClick = {
                    if (url.isNotBlank() && !url.endsWith("://")) {
                        onSubmit(
                            url.trim(),
                            title.takeIf { it.isNotBlank() },
                            smbUser.takeIf { it.isNotBlank() },
                            smbPassword.takeIf { it.isNotBlank() }
                        )
                    }
                }
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = LuminaTextSecondary)
            }
        }
    )
}

private enum class LibraryDestinationItem(
    val tab: LibraryTab,
    val icon: ImageVector,
    val labelRes: Int
) {
    VIDEOS(LibraryTab.VIDEOS, Icons.Outlined.Movie, R.string.nav_videos),
    FOLDERS(LibraryTab.FOLDERS, Icons.Outlined.FolderOpen, R.string.nav_folders),
    AUDIO(LibraryTab.AUDIO, Icons.Outlined.Album, R.string.nav_audio),
    NETWORK(LibraryTab.NETWORK, Icons.Outlined.Router, R.string.nav_network),
    SETTINGS(LibraryTab.SETTINGS, Icons.Outlined.Settings, R.string.nav_settings)
}

@Composable
private fun LocalFoldersBrowserPane(
    uiState: LibraryUiState,
    onMountSafFolder: () -> Unit,
    onOpenSingleVideoFile: () -> Unit,
    onOpenPinnedFolder: (SafPinnedFolderSummary) -> Unit,
    onRemovePinnedFolder: (String) -> Unit,
    onOpenDeviceBucket: (String) -> Unit,
    onOpenSafSubdirectory: (LocalFileEntry) -> Unit,
    onNavigateBreadcrumb: (Int) -> Unit,
    onNavigateUp: () -> Unit,
    onSelectSortOption: (FolderSortOption) -> Unit,
    onToggleSortDirection: () -> Unit,
    onSelectHdrFilter: (FolderHdrFilter) -> Unit,
    onPlayFolderEntry: (LocalFileEntry) -> Unit
) {
    val breadcrumbs = uiState.folderBreadcrumbs

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Top Browser Header + Mount Folder / Open Video File Actions
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.folders_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = LuminaTextPrimary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LuminaGlassPillButton(
                        label = stringResource(R.string.folders_open_video_file),
                        leadingIcon = Icons.Outlined.Movie,
                        onClick = onOpenSingleVideoFile
                    )
                    LuminaGlassPillButton(
                        label = stringResource(R.string.folders_mount_folder),
                        leadingIcon = Icons.Outlined.CreateNewFolder,
                        isActive = true,
                        accentColor = LuminaCyan,
                        onClick = onMountSafFolder
                    )
                }
            }
        }

        // Interactive Breadcrumb Trail Bar
        item {
            LuminaGlassSurface(
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (breadcrumbs.isNotEmpty()) {
                        LuminaGlassIconButton(
                            icon = Icons.Outlined.ArrowUpward,
                            contentDescription = stringResource(R.string.folders_up_level),
                            size = 30.dp,
                            iconSize = 16.dp,
                            onClick = onNavigateUp
                        )
                    }
                    LazyRow(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        item {
                            Text(
                                text = stringResource(R.string.folders_root_crumb),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (breadcrumbs.isEmpty()) LuminaPlatinum else LuminaCyan,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onNavigateBreadcrumb(-1) }
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                        items(breadcrumbs.size) { index ->
                            val crumb = breadcrumbs[index]
                            val isLast = index == breadcrumbs.lastIndex
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.ChevronRight,
                                    contentDescription = null,
                                    tint = LuminaTextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = crumb.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (isLast) LuminaPlatinum else LuminaTextSecondary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onNavigateBreadcrumb(index) }
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Material 3 Expressive Sort & HDR Format Filter Toolbar
        item {
            LuminaGlassSurface(
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.folders_sort_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = LuminaTextSecondary
                            )
                            LuminaExpressiveSegmentedGroup(
                                options = FolderSortOption.entries,
                                selectedOption = uiState.folderSortOption,
                                labelProvider = { opt ->
                                    when (opt) {
                                        FolderSortOption.NAME -> stringResource(R.string.folders_sort_name)
                                        FolderSortOption.SIZE -> stringResource(R.string.folders_sort_size)
                                        FolderSortOption.DATE_MODIFIED -> stringResource(R.string.folders_sort_date)
                                    }
                                },
                                onSelect = onSelectSortOption
                            )
                        }

                        LuminaGlassIconButton(
                            icon = if (uiState.folderSortAscending) {
                                Icons.Outlined.ArrowUpward
                            } else {
                                Icons.Outlined.ArrowDownward
                            },
                            contentDescription = if (uiState.folderSortAscending) {
                                stringResource(R.string.folders_sort_asc)
                            } else {
                                stringResource(R.string.folders_sort_desc)
                            },
                            size = 34.dp,
                            iconSize = 17.dp,
                            onClick = onToggleSortDirection
                        )
                    }

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(FolderHdrFilter.entries, key = { it.name }) { filter ->
                            val filterLabel = when (filter) {
                                FolderHdrFilter.ALL -> stringResource(R.string.folders_filter_all)
                                FolderHdrFilter.HDR_ANY -> stringResource(R.string.folders_filter_hdr_any)
                                FolderHdrFilter.DOLBY_VISION -> stringResource(R.string.folders_filter_dolby_vision)
                                FolderHdrFilter.HDR10_PLUS -> stringResource(R.string.folders_filter_hdr10_plus)
                                FolderHdrFilter.HDR10 -> stringResource(R.string.folders_filter_hdr10)
                                FolderHdrFilter.HLG -> stringResource(R.string.folders_filter_hlg)
                                FolderHdrFilter.SDR -> stringResource(R.string.folders_filter_sdr)
                            }
                            LuminaGlassPillButton(
                                label = filterLabel,
                                isActive = uiState.folderHdrFilter == filter,
                                accentColor = if (filter == FolderHdrFilter.ALL || filter == FolderHdrFilter.SDR) {
                                    LuminaPlatinum
                                } else {
                                    LuminaCyan
                                },
                                onClick = { onSelectHdrFilter(filter) }
                            )
                        }
                    }
                }
            }
        }

        // Root Storage Overview (Pinned SAF Trees + Indexed Device Video Folders)
        if (breadcrumbs.isEmpty()) {
            if (uiState.pinnedSafFolders.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.folders_pinned_section),
                        style = MaterialTheme.typography.labelLarge,
                        color = LuminaTextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(uiState.pinnedSafFolders, key = { "saf_${it.treeUriString}" }) { summary ->
                    LuminaGlassSurface(
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenPinnedFolder(summary) }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Storage,
                                contentDescription = null,
                                tint = LuminaCyan,
                                modifier = Modifier.size(26.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = summary.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = LuminaTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = summary.displayPath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LuminaTextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            LuminaGlassIconButton(
                                icon = Icons.Outlined.DeleteOutline,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = LuminaDanger,
                                size = 34.dp,
                                iconSize = 18.dp,
                                onClick = { onRemovePinnedFolder(summary.treeUriString) }
                            )
                        }
                    }
                }
            }

            if (uiState.deviceVideoFolders.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.folders_device_section),
                        style = MaterialTheme.typography.labelLarge,
                        color = LuminaTextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(uiState.deviceVideoFolders, key = { "bucket_${it.folderName}" }) { group ->
                    LuminaGlassSurface(
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenDeviceBucket(group.folderName) }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                tint = LuminaPlatinum,
                                modifier = Modifier.size(26.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = group.folderName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = LuminaTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = stringResource(R.string.folders_item_count, group.videos.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LuminaTextSecondary
                                )
                            }
                            Icon(
                                imageVector = Icons.Outlined.ChevronRight,
                                contentDescription = null,
                                tint = LuminaTextMuted
                            )
                        }
                    }
                }
            }

            if (uiState.pinnedSafFolders.isEmpty() && uiState.deviceVideoFolders.isEmpty()) {
                item {
                    val isFilteredEmpty = uiState.folderHdrFilter != FolderHdrFilter.ALL && uiState.videos.isNotEmpty()
                    EmptyLibraryPlaceholder(
                        message = if (isFilteredEmpty) {
                            stringResource(R.string.folders_empty_filtered)
                        } else {
                            stringResource(R.string.folders_empty_root)
                        },
                        actionLabel = if (isFilteredEmpty) {
                            stringResource(R.string.folders_reset_filter)
                        } else {
                            stringResource(R.string.folders_mount_folder)
                        },
                        onAction = {
                            if (isFilteredEmpty) {
                                onSelectHdrFilter(FolderHdrFilter.ALL)
                            } else {
                                onMountSafFolder()
                            }
                        }
                    )
                }
            }
        } else {
            // Active Directory Contents (Subfolders + Filtered/Sorted Video Files)
            if (uiState.isLoadingFolder) {
                item {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp)
                    ) {
                        CircularProgressIndicator(color = LuminaPlatinum, strokeWidth = 2.dp)
                    }
                }
            } else if (uiState.activeFolderEntries.isEmpty()) {
                item {
                    val hasActiveFilter = uiState.folderHdrFilter != FolderHdrFilter.ALL
                    EmptyLibraryPlaceholder(
                        message = if (hasActiveFilter) {
                            stringResource(R.string.folders_empty_filtered)
                        } else {
                            stringResource(R.string.folders_empty_dir)
                        },
                        actionLabel = if (hasActiveFilter) {
                            stringResource(R.string.folders_reset_filter)
                        } else {
                            stringResource(R.string.folders_open_video_file)
                        },
                        onAction = {
                            if (hasActiveFilter) {
                                onSelectHdrFilter(FolderHdrFilter.ALL)
                            } else {
                                onOpenSingleVideoFile()
                            }
                        }
                    )
                }
            } else {
                items(uiState.activeFolderEntries, key = { it.uri.toString() }) { entry ->
                    LuminaGlassSurface(
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (entry.isDirectory) {
                                    onOpenSafSubdirectory(entry)
                                } else {
                                    onPlayFolderEntry(entry)
                                }
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Icon(
                                imageVector = if (entry.isDirectory) Icons.Outlined.FolderOpen else Icons.Outlined.Movie,
                                contentDescription = null,
                                tint = if (entry.isDirectory) LuminaCyan else LuminaPlatinum,
                                modifier = Modifier.size(26.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = LuminaTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val subtitle = if (entry.isDirectory) {
                                    stringResource(R.string.folders_subfolder)
                                } else {
                                    formatFileSizeBytes(entry.sizeBytes)
                                }
                                if (subtitle.isNotEmpty()) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LuminaTextSecondary
                                    )
                                }
                            }
                            if (!entry.isDirectory) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    entry.hdrBadge?.let { HdrBadgeChip(it) }
                                    entry.videoCodec?.let { HdrBadgeChip(it) }
                                }
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.ChevronRight,
                                    contentDescription = null,
                                    tint = LuminaTextMuted
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatFileSizeBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}

