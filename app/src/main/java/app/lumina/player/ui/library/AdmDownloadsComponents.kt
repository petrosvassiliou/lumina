package app.lumina.player.ui.library

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lumina.player.R
import app.lumina.player.data.download.AdmDownloadItemUi
import app.lumina.player.data.download.AdmDownloadPlanner
import app.lumina.player.data.download.DownloadStatus
import app.lumina.player.data.download.StreamProbeResult
import app.lumina.player.player.model.MediaQueueItem
import app.lumina.player.ui.components.HdrBadgeChip
import app.lumina.player.ui.components.LuminaExpressiveSegmentedGroup
import app.lumina.player.ui.components.LuminaGlassIconButton
import app.lumina.player.ui.components.LuminaGlassPillButton
import app.lumina.player.ui.components.LuminaGlassSurface
import app.lumina.player.ui.theme.LuminaCyan
import app.lumina.player.ui.theme.LuminaCyanBright
import app.lumina.player.ui.theme.LuminaDanger
import app.lumina.player.ui.theme.LuminaGlassBorder
import app.lumina.player.ui.theme.LuminaObsidianElevated
import app.lumina.player.ui.theme.LuminaPlatinum
import app.lumina.player.ui.theme.LuminaTeal
import app.lumina.player.ui.theme.LuminaTextPrimary
import app.lumina.player.ui.theme.LuminaTextSecondary
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdmDownloadsPane(
    downloads: List<AdmDownloadItemUi>,
    onNewDownloadClick: () -> Unit,
    onPauseDownload: (Long) -> Unit,
    onResumeDownload: (Long) -> Unit,
    onRetryDownload: (Long) -> Unit,
    onDeleteDownload: (Long) -> Unit,
    onClearCompleted: () -> Unit,
    onPlayCompletedItem: (MediaQueueItem) -> Unit
) {
    val activeCount = remember(downloads) { downloads.count { it.status.isActive } }
    val completedCount = remember(downloads) { downloads.count { it.status == DownloadStatus.COMPLETED } }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "adm_header") {
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
                        imageVector = Icons.Outlined.ArrowDownward,
                        contentDescription = null,
                        tint = LuminaCyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = stringResource(R.string.adm_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = LuminaTextPrimary
                    )
                    if (activeCount > 0) {
                        HdrBadgeChip("$activeCount ACTIVE")
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (completedCount > 0) {
                        LuminaGlassPillButton(
                            label = stringResource(R.string.adm_clear_completed),
                            leadingIcon = Icons.Outlined.DeleteOutline,
                            onClick = onClearCompleted
                        )
                    }
                    LuminaGlassPillButton(
                        label = stringResource(R.string.adm_new_download),
                        leadingIcon = Icons.Outlined.Add,
                        isActive = true,
                        accentColor = LuminaCyan,
                        onClick = onNewDownloadClick
                    )
                }
            }
        }

        if (downloads.isEmpty()) {
            item(key = "adm_empty") {
                LuminaGlassSurface(
                    contentPadding = PaddingValues(28.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ArrowDownward,
                            contentDescription = null,
                            tint = LuminaCyan,
                            modifier = Modifier.size(34.dp)
                        )
                        Text(
                            text = stringResource(R.string.adm_empty_downloads),
                            style = MaterialTheme.typography.bodyLarge,
                            color = LuminaTextSecondary
                        )
                        LuminaGlassPillButton(
                            label = stringResource(R.string.adm_new_download),
                            leadingIcon = Icons.Outlined.Add,
                            isActive = true,
                            accentColor = LuminaCyan,
                            onClick = onNewDownloadClick
                        )
                    }
                }
            }
        } else {
            items(downloads, key = { it.id }) { task ->
                AdmDownloadTaskCard(
                    item = task,
                    onPause = { onPauseDownload(task.id) },
                    onResume = { onResumeDownload(task.id) },
                    onRetry = { onRetryDownload(task.id) },
                    onDelete = { onDeleteDownload(task.id) },
                    onPlayOffline = {
                        val exported = task.exportedUri
                        if (!exported.isNullOrBlank()) {
                            onPlayCompletedItem(
                                MediaQueueItem(
                                    uri = Uri.parse(exported),
                                    title = task.title,
                                    subtitle = "Lumina Downloads",
                                    mimeType = task.mimeType,
                                    isNetworkStream = false
                                )
                            )
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdmDownloadTaskCard(
    item: AdmDownloadItemUi,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onPlayOffline: () -> Unit
) {
    val accentGlow = when (item.status) {
        DownloadStatus.DOWNLOADING, DownloadStatus.PROBING, DownloadStatus.MERGING -> LuminaCyan
        DownloadStatus.COMPLETED -> LuminaTeal
        DownloadStatus.FAILED -> LuminaDanger
        else -> null
    }

    val statusColor = when (item.status) {
        DownloadStatus.DOWNLOADING, DownloadStatus.PROBING, DownloadStatus.MERGING -> LuminaCyan
        DownloadStatus.COMPLETED -> LuminaTeal
        DownloadStatus.FAILED -> LuminaDanger
        else -> LuminaPlatinum
    }

    val statusText = when (item.status) {
        DownloadStatus.QUEUED -> stringResource(R.string.adm_status_queued)
        DownloadStatus.PROBING -> stringResource(R.string.adm_status_probing)
        DownloadStatus.DOWNLOADING -> stringResource(R.string.adm_status_downloading)
        DownloadStatus.MERGING -> stringResource(R.string.adm_status_merging)
        DownloadStatus.PAUSED -> stringResource(R.string.adm_status_paused)
        DownloadStatus.COMPLETED -> stringResource(R.string.adm_status_completed)
        DownloadStatus.FAILED, DownloadStatus.CANCELED -> stringResource(R.string.adm_status_failed)
    }

    val modeBadge = when {
        item.isHlsStream -> "HLS • ${item.threadCount}×"
        item.acceptsRanges && item.threadCount > 1 -> "ADM ${item.threadCount}× RANGE"
        else -> "1× STREAM"
    }

    LuminaGlassSurface(
        shape = RoundedCornerShape(18.dp),
        accentGlow = accentGlow,
        contentPadding = PaddingValues(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (item.status == DownloadStatus.COMPLETED && !item.exportedUri.isNullOrBlank()) {
                    Modifier.clickable(onClick = onPlayOffline)
                } else {
                    Modifier
                }
            )
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Top Row: Title, Filename & Mode Chip
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = when (item.status) {
                        DownloadStatus.COMPLETED -> Icons.Outlined.CheckCircle
                        DownloadStatus.FAILED -> Icons.Outlined.ErrorOutline
                        DownloadStatus.PAUSED -> Icons.Outlined.Pause
                        else -> Icons.Outlined.ArrowDownward
                    },
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(24.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = LuminaTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.labelSmall,
                        color = LuminaTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                HdrBadgeChip(modeBadge)
            }

            // Signature ADM Multi-Segment Progress Bar (1..16 parallel bars)
            AdmSegmentedProgressBar(
                segmentFractions = item.segmentProgress,
                status = item.status,
                modifier = Modifier.fillMaxWidth()
            )

            // Telemetry & Actions Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val progressPercent = (item.overallProgress * 100f).toInt().coerceIn(0, 100)
                    val bytesSummary = buildString {
                        append(formatAdmBytes(item.downloadedBytes))
                        if (item.totalBytes > 0L) {
                            append(" / ")
                            append(formatAdmBytes(item.totalBytes))
                            append(" • $progressPercent%")
                        }
                    }
                    val speedAndEta = buildList {
                        add(statusText)
                        if (item.status == DownloadStatus.DOWNLOADING && item.speedBytesPerSec > 0L) {
                            add("${formatAdmBytes(item.speedBytesPerSec)}/s")
                        }
                        if (item.status == DownloadStatus.DOWNLOADING && item.etaSeconds > 0L) {
                            add("ETA ${formatEtaSeconds(item.etaSeconds)}")
                        }
                    }.joinToString(" • ")

                    Text(
                        text = bytesSummary,
                        style = MaterialTheme.typography.labelLarge,
                        color = LuminaTextPrimary
                    )
                    Text(
                        text = speedAndEta,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor
                    )
                    if (item.status == DownloadStatus.FAILED && !item.errorMessage.isNullOrBlank()) {
                        Text(
                            text = item.errorMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = LuminaDanger,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    when (item.status) {
                        DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED, DownloadStatus.PROBING -> {
                            LuminaGlassPillButton(
                                label = stringResource(R.string.adm_action_pause),
                                leadingIcon = Icons.Outlined.Pause,
                                accentColor = LuminaPlatinum,
                                onClick = onPause
                            )
                        }
                        DownloadStatus.PAUSED -> {
                            LuminaGlassPillButton(
                                label = stringResource(R.string.adm_action_resume),
                                leadingIcon = Icons.Outlined.PlayArrow,
                                isActive = true,
                                accentColor = LuminaCyan,
                                onClick = onResume
                            )
                        }
                        DownloadStatus.FAILED -> {
                            LuminaGlassPillButton(
                                label = stringResource(R.string.player_retry_action),
                                leadingIcon = Icons.Outlined.Refresh,
                                isActive = true,
                                accentColor = LuminaCyan,
                                onClick = onRetry
                            )
                        }
                        DownloadStatus.COMPLETED -> {
                            if (!item.exportedUri.isNullOrBlank()) {
                                LuminaGlassPillButton(
                                    label = stringResource(R.string.adm_action_play_offline),
                                    leadingIcon = Icons.Outlined.PlayArrow,
                                    isActive = true,
                                    accentColor = LuminaTeal,
                                    onClick = onPlayOffline
                                )
                            }
                        }
                        else -> Unit
                    }

                    LuminaGlassIconButton(
                        icon = Icons.Outlined.DeleteOutline,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = LuminaDanger,
                        size = 36.dp,
                        iconSize = 18.dp,
                        onClick = onDelete
                    )
                }
            }
        }
    }
}

@Composable
private fun AdmSegmentedProgressBar(
    segmentFractions: List<Float>,
    status: DownloadStatus,
    modifier: Modifier = Modifier
) {
    val cells = if (segmentFractions.isEmpty()) listOf(0f) else segmentFractions.take(16)
    val barBrush = when (status) {
        DownloadStatus.COMPLETED -> Brush.horizontalGradient(listOf(LuminaTeal, LuminaCyanBright))
        DownloadStatus.FAILED -> Brush.horizontalGradient(listOf(LuminaDanger, LuminaDanger.copy(alpha = 0.7f)))
        DownloadStatus.PAUSED -> Brush.horizontalGradient(listOf(LuminaPlatinum.copy(alpha = 0.75f), LuminaPlatinum))
        else -> Brush.horizontalGradient(listOf(LuminaCyan, LuminaCyanBright))
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier.height(8.dp)
    ) {
        cells.forEach { rawFraction ->
            val clamped = rawFraction.coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(LuminaObsidianElevated)
                    .border(0.5.dp, LuminaGlassBorder, RoundedCornerShape(4.dp))
            ) {
                if (clamped > 0.002f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(clamped)
                            .background(barBrush)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdmNewDownloadDialog(
    initialUrl: String = "",
    initialTitle: String = "",
    defaultConnections: Int = 8,
    probeInFlight: Boolean = false,
    probeResult: StreamProbeResult? = null,
    probeError: String? = null,
    onAnalyzeUrl: (url: String, referer: String, userAgent: String, authorization: String) -> Unit,
    onDismiss: () -> Unit,
    onStartDownload: (
        sourceUrl: String,
        selectedStreamUrl: String,
        customTitle: String?,
        customFileName: String?,
        connections: Int,
        referer: String,
        userAgent: String,
        authorization: String
    ) -> Unit
) {
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var fileName by rememberSaveable(initialUrl) { mutableStateOf("") }
    var fileNameEditedManually by rememberSaveable(initialUrl) { mutableStateOf(false) }
    var title by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    var connections by rememberSaveable(defaultConnections) {
        mutableIntStateOf(defaultConnections.coerceIn(1, 16))
    }
    var selectedVariantUrl by rememberSaveable(initialUrl) { mutableStateOf("") }
    var showCustomHeaders by rememberSaveable { mutableStateOf(false) }
    var referer by rememberSaveable { mutableStateOf("") }
    var userAgent by rememberSaveable { mutableStateOf("") }
    var authorization by rememberSaveable { mutableStateOf("") }

    val connectionChoices = remember { listOf(1, 4, 8, 16) }

    // Auto-probe if launched with a pre-filled HTTP/HTTPS/FTP link (e.g. Share Intent or Player HUD)
    LaunchedEffect(initialUrl) {
        val trimmed = initialUrl.trim()
        if (AdmDownloadPlanner.isDownloadableStreamProtocol(trimmed)) {
            onAnalyzeUrl(trimmed, referer, userAgent, authorization)
        }
    }

    // Populate suggested filename & default top HLS variant when probe finishes
    LaunchedEffect(probeResult) {
        if (probeResult != null) {
            if (!fileNameEditedManually || fileName.isBlank()) {
                fileName = probeResult.suggestedFileName
            }
            if (title.isBlank()) {
                title = probeResult.suggestedTitle
            }
            selectedVariantUrl = probeResult.hlsVariants.firstOrNull()?.url ?: probeResult.resolvedUrl
        }
    }

    val isValidDownloadUrl = AdmDownloadPlanner.isDownloadableStreamProtocol(url.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LuminaObsidianElevated,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ArrowDownward,
                    contentDescription = null,
                    tint = LuminaCyan
                )
                Text(
                    text = stringResource(R.string.adm_dialog_title),
                    color = LuminaTextPrimary
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("https://", "http://", "ftp://").forEach { prefix ->
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
                    LuminaGlassPillButton(
                        label = if (probeInFlight) {
                            stringResource(R.string.adm_analyzing)
                        } else {
                            stringResource(R.string.adm_analyze_action)
                        },
                        leadingIcon = Icons.Outlined.Search,
                        isActive = probeInFlight,
                        accentColor = LuminaPlatinum,
                        onClick = {
                            if (isValidDownloadUrl && !probeInFlight) {
                                onAnalyzeUrl(url.trim(), referer, userAgent, authorization)
                            }
                        }
                    )
                }

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.adm_url_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (probeInFlight) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            color = LuminaCyan,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.adm_analyzing),
                            style = MaterialTheme.typography.labelMedium,
                            color = LuminaCyan
                        )
                    }
                }

                if (!probeError.isNullOrBlank()) {
                    Text(
                        text = probeError,
                        style = MaterialTheme.typography.bodySmall,
                        color = LuminaDanger
                    )
                }

                if (probeResult != null) {
                    LuminaGlassSurface(
                        shape = RoundedCornerShape(14.dp),
                        accentGlow = LuminaCyan,
                        contentPadding = PaddingValues(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val capabilityText = when {
                                    probeResult.isHlsStream -> stringResource(R.string.adm_hls_detected)
                                    probeResult.acceptsRanges -> stringResource(R.string.adm_range_supported)
                                    else -> stringResource(R.string.adm_single_stream)
                                }
                                Text(
                                    text = capabilityText,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = LuminaCyan
                                )
                                val sizeMeta = when {
                                    probeResult.totalBytes > 0L -> formatAdmBytes(probeResult.totalBytes)
                                    probeResult.hlsSegmentCount > 0 -> "${probeResult.hlsSegmentCount} segs"
                                    else -> probeResult.mimeType
                                }
                                HdrBadgeChip(sizeMeta)
                            }

                            if (probeResult.hlsVariants.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.adm_hls_quality_label),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = LuminaTextSecondary
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    probeResult.hlsVariants.forEach { variant ->
                                        LuminaGlassPillButton(
                                            label = variant.label,
                                            isActive = selectedVariantUrl == variant.url,
                                            accentColor = LuminaCyan,
                                            onClick = { selectedVariantUrl = variant.url }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = fileName,
                    onValueChange = {
                        fileName = it
                        fileNameEditedManually = true
                    },
                    label = { Text(stringResource(R.string.adm_filename_label)) },
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

                Text(
                    text = stringResource(R.string.adm_connections_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = LuminaTextSecondary
                )
                LuminaExpressiveSegmentedGroup(
                    options = connectionChoices,
                    selectedOption = if (connections in connectionChoices) connections else 8,
                    labelProvider = { "${it}×" },
                    accentColor = LuminaCyan,
                    onSelect = { connections = it }
                )

                LuminaGlassPillButton(
                    label = stringResource(R.string.adm_advanced_headers),
                    leadingIcon = Icons.Outlined.Tune,
                    isActive = showCustomHeaders || referer.isNotBlank() || userAgent.isNotBlank() || authorization.isNotBlank(),
                    onClick = { showCustomHeaders = !showCustomHeaders }
                )

                AnimatedVisibility(visible = showCustomHeaders) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = referer,
                            onValueChange = { referer = it },
                            label = { Text(stringResource(R.string.adm_referer_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = userAgent,
                            onValueChange = { userAgent = it },
                            label = { Text(stringResource(R.string.adm_user_agent_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = authorization,
                            onValueChange = { authorization = it },
                            label = { Text(stringResource(R.string.adm_auth_header_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            LuminaGlassPillButton(
                label = stringResource(R.string.adm_start_download),
                leadingIcon = Icons.Outlined.ArrowDownward,
                isActive = isValidDownloadUrl,
                accentColor = LuminaCyan,
                onClick = {
                    if (isValidDownloadUrl) {
                        val trimmedSource = url.trim()
                        val targetStreamUrl = selectedVariantUrl.takeIf {
                            it.isNotBlank() && probeResult?.sourceUrl.equals(trimmedSource, ignoreCase = true)
                        } ?: trimmedSource
                        onStartDownload(
                            trimmedSource,
                            targetStreamUrl,
                            title.trim().takeIf { it.isNotBlank() },
                            fileName.trim().takeIf { it.isNotBlank() },
                            connections,
                            referer,
                            userAgent,
                            authorization
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

private fun formatAdmBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
        else -> "$bytes B"
    }
}

private fun formatEtaSeconds(seconds: Long): String {
    if (seconds <= 0L) return "--:--"
    val hrs = seconds / 3600L
    val mins = (seconds % 3600L) / 60L
    val secs = seconds % 60L
    return if (hrs > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}
