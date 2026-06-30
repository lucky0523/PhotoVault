package com.photovault.ui.main.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.photovault.data.local.entity.PhotoStatusValue
import com.photovault.service.BackupForegroundService
import com.photovault.service.BackupQueue
import com.photovault.service.FileInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.util.concurrent.TimeUnit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photovault.data.local.dao.PhotoStatusDao
import dagger.hilt.android.lifecycle.HiltViewModel
import com.photovault.data.local.entity.PhotoStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import android.net.Uri

data class TrashImage(
    val uri: Uri,
    val name: String,
    val fileSize: Long,
    val status: PhotoStatus?
) {
    val isTrashed: Boolean get() = status?.status == PhotoStatusValue.TRASHED
    val isPurged: Boolean get() = status?.status == PhotoStatusValue.PURGED
}

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val photoStatusDao: PhotoStatusDao
) : ViewModel() {

    private val _images = MutableStateFlow<List<TrashImage>>(emptyList())
    val images: StateFlow<List<TrashImage>> = _images.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun loadImages() {
        viewModelScope.launch {
            _loading.value = true
            try {
                val trashed = photoStatusDao.getByStatus(PhotoStatusValue.TRASHED)
                val purged = photoStatusDao.getByStatus(PhotoStatusValue.PURGED)
                _images.value = (trashed + purged)
                    .sortedByDescending { it.updatedAt }
                    .map { status ->
                        TrashImage(
                            uri = Uri.parse(status.fileUri),
                            name = status.fileUri.split("/").last(),
                            fileSize = 0,
                            status = status
                        )
                    }
            } finally {
                _loading.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashTab(
    viewModel: TrashViewModel = hiltViewModel()
) {
    val images by viewModel.images.collectAsState()
    val loading by viewModel.loading.collectAsState()

    var restoreTarget by remember { mutableStateOf<TrashImage?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadImages()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("回收站") },
                actions = {
                    if (images.isNotEmpty()) {
                        IconButton(onClick = { viewModel.loadImages() }) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "刷新"
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        if (images.isEmpty() && !loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "回收站为空",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(images) { image ->
                    TrashImageItem(
                        image = image,
                        onLongPress = { img ->
                            restoreTarget = img
                        }
                    )
                }
            }
        }
    }

    restoreTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text("恢复照片") },
            text = {
                Text(
                    if (target.isTrashed) {
                        "该照片将从回收站恢复，重新备份到服务器。"
                    } else {
                        "该照片已从服务器删除，重新备份将创建新的备份记录。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    restoreTarget = null
                }) {
                    Text("恢复")
                }
            },
            dismissButton = {
                TextButton(onClick = { restoreTarget = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TrashImageItem(
    image: TrashImage,
    onLongPress: (TrashImage) -> Unit
) {
    val alpha = if (image.isPurged) 0.4f else 0.6f

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(
                onClick = {},
                onLongClick = { onLongPress(image) }
            )
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(image.uri)
                .crossfade(true)
                .size(200)
                .build(),
            contentDescription = image.name,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(alpha = alpha),
            contentScale = ContentScale.Crop
        )

        TrashStatusBadge(image)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.TrashStatusBadge(image: TrashImage) {
    if (image.isTrashed) {
        val remaining = formatRemainingTime(image.status?.expiresAt)
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .background(Color(0xFFFF9800), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "回收站中",
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = if (remaining != null) "回收站 $remaining" else "回收站",
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    } else if (image.isPurged) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .size(20.dp)
                .background(Color(0xFFF44336), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "已删除",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

private fun formatRemainingTime(expiresAt: Long?): String? {
    if (expiresAt == null) return null
    val remainingMs = expiresAt - System.currentTimeMillis()
    if (remainingMs <= 0) return null
    val days = TimeUnit.MILLISECONDS.toDays(remainingMs)
    val hours = TimeUnit.MILLISECONDS.toHours(remainingMs) - days * 24
    return when {
        days > 0 -> "${days}天${hours}小时"
        hours > 0 -> "${hours}小时"
        else -> "<1小时"
    }
}
