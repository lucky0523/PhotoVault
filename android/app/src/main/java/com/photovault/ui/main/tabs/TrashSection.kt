package com.photovault.ui.main.tabs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.photovault.data.api.model.FileBrowseInfo
import com.photovault.data.api.model.TrashItemInfo
import com.photovault.ui.main.components.CloudStatusColors
import com.photovault.ui.theme.LocalBottomBarPadding
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * The recycle-bin view, surfaced inside the Cloud Tab (reached via the pinned
 * "回收站" entry row at the top of the root directory listing).
 *
 * Shows trashed files in a grid. Long-pressing an item opens an action sheet
 * offering restore (move back to the original location) or permanent deletion.
 */
@Composable
fun TrashView(
    items: List<TrashItemInfo>,
    isLoading: Boolean,
    error: String?,
    serverBaseUrl: String,
    onBack: () -> Unit,
    onRestore: (TrashItemInfo) -> Unit,
    onPurge: (TrashItemInfo) -> Unit
) {
    var actionTarget by remember { mutableStateOf<TrashItemInfo?>(null) }
    var previewTarget by remember { mutableStateOf<TrashItemInfo?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Lightweight header aligned with the browser's breadcrumb row (no
        // TopAppBar — this content already sits below the app's GlassHeader, so
        // an inset-aware TopAppBar would add a large empty gap on top).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回"
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "回收站",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        HorizontalDivider()

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isLoading && items.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                error != null && items.isEmpty() -> {
                    TrashMessage(text = error)
                }

                items.isEmpty() -> {
                    TrashMessage(text = "回收站为空")
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 4.dp,
                            end = 4.dp,
                            top = 4.dp,
                            bottom = 4.dp + LocalBottomBarPadding.current
                        ),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(items, key = { it.id }) { item ->
                            TrashGridItem(
                                item = item,
                                serverBaseUrl = serverBaseUrl,
                                onClick = { previewTarget = it },
                                onLongPress = { actionTarget = it }
                            )
                        }
                    }
                }
            }
        }
    }

    actionTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(target.fileName) },
            text = {
                val remaining = formatRemainingTime(target.expiresAt)
                Text(
                    if (remaining != null) {
                        "该文件将于 $remaining 后自动永久删除。你可以将其还原到原位置，或立即永久删除。"
                    } else {
                        "你可以将该文件还原到原位置，或立即永久删除。"
                    }
                )
            },
            // Three actions: 永久删除 / 还原 / 取消. AlertDialog lays the
            // confirm/dismiss slots out in a trailing flow row, so grouping the
            // two positive-ish actions in confirmButton keeps 取消 visually apart.
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        onPurge(target)
                        actionTarget = null
                    }) {
                        Text("永久删除", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = {
                        onRestore(target)
                        actionTarget = null
                    }) {
                        Text("还原")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { actionTarget = null }) {
                    Text("取消")
                }
            }
        )
    }

    // Full-screen preview when a trash item is tapped. Reuse the browser's
    // preview dialog by adapting the trash item into a FileBrowseInfo.
    previewTarget?.let { target ->
        ImagePreviewDialog(
            file = FileBrowseInfo(
                id = target.id,
                fileName = target.fileName,
                fileSize = target.fileSize,
                mimeType = target.mimeType,
                exifTime = target.exifTime,
                thumbnailUrl = null,
                createdAt = target.createdAt ?: ""
            ),
            downloadUrl = "$serverBaseUrl/api/v1/files/download/${target.id}",
            onDismiss = { previewTarget = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashGridItem(
    item: TrashItemInfo,
    serverBaseUrl: String,
    onClick: (TrashItemInfo) -> Unit,
    onLongPress: (TrashItemInfo) -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(
                onClick = { onClick(item) },
                onLongClick = { onLongPress(item) }
            )
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data("$serverBaseUrl/api/v1/files/thumbnail/${item.id}")
                .crossfade(true)
                .size(200)
                .build(),
            contentDescription = item.fileName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Remaining-time badge (bottom-start), aligned with FolderDetailScreen's
        // BadgeLabel: a horizontal icon + text pill, text hidden when unknown.
        val remaining = formatRemainingTime(item.expiresAt)
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .background(CloudStatusColors.Trashed.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
                if (!remaining.isNullOrEmpty()) {
                    Text(
                        text = remaining,
                        color = Color.White,
                        fontSize = 9.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun TrashMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/**
 * Format the remaining time before auto-purge from an ISO-8601 [expiresAt]
 * timestamp. Returns null when the input is missing, unparseable, or already
 * elapsed.
 */
private fun formatRemainingTime(expiresAt: String?): String? {
    if (expiresAt.isNullOrBlank()) return null
    val expiryMs = parseIsoToEpochMillis(expiresAt) ?: return null
    val remainingMs = expiryMs - System.currentTimeMillis()
    if (remainingMs <= 0) return null
    val days = TimeUnit.MILLISECONDS.toDays(remainingMs)
    val hours = TimeUnit.MILLISECONDS.toHours(remainingMs) - days * 24
    return when {
        days > 0 -> "${days}天${hours}小时"
        hours > 0 -> "${hours}小时"
        else -> "<1小时"
    }
}

private fun parseIsoToEpochMillis(value: String): Long? {
    return try {
        // Handles offsets like "2024-02-02T00:00:00+00:00".
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    } catch (_: Exception) {
        try {
            // Handles trailing-Z UTC form.
            Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            try {
                // Handles naive local timestamps "2024-02-02T00:00:00" (assume UTC).
                OffsetDateTime.of(
                    java.time.LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    ZoneOffset.UTC
                ).toInstant().toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }
}
