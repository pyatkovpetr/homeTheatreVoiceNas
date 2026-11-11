package com.homeprojector.launcher.ui

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.homeprojector.launcher.util.formatDuration
import com.homeprojector.launcher.util.formatSize
import com.homeprojector.launcher.util.isVideoFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class LocalVideo(
    val id: Long,
    val name: String,
    val duration: Long,
    val size: Long,
    val bucket: String,
    val uri: Uri
)

private val storagePermission: String
    get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
    else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
fun LocalScreen(onBack: () -> Unit, onPlay: (uri: String, title: String) -> Unit) {
    var tab by remember { mutableStateOf(0) }

    ScreenFrame(title = "Локальное хранилище", onBack = onBack) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Видео") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Папки") })
            }
            Box(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
                if (tab == 0) VideosTab(onPlay) else FoldersTab(onPlay)
            }
        }
    }
}

@Composable
private fun VideosTab(onPlay: (String, String) -> Unit) {
    val context = LocalContext.current
    var videos by remember { mutableStateOf<List<LocalVideo>?>(null) }
    var permTick by remember { mutableIntStateOf(0) }
    val granted = ContextCompat.checkSelfPermission(context, storagePermission) ==
        PackageManager.PERMISSION_GRANTED
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permTick++ }

    LaunchedEffect(permTick) {
        if (ContextCompat.checkSelfPermission(context, storagePermission) != PackageManager.PERMISSION_GRANTED) {
            videos = emptyList()
            return@LaunchedEffect
        }
        videos = withContext(Dispatchers.IO) {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME
            )
            val result = mutableListOf<LocalVideo>()
            runCatching {
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection, null, null,
                    "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val bucketCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        result += LocalVideo(
                            id = id,
                            name = c.getString(nameCol) ?: "video",
                            duration = c.getLong(durCol),
                            size = c.getLong(sizeCol),
                            bucket = c.getString(bucketCol) ?: "",
                            uri = ContentUris.withAppendedId(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                            )
                        )
                    }
                }
            }
            result
        }
    }

    val list = videos
    when {
        !granted -> Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Нужен доступ к видео",
                style = MaterialTheme.typography.titleLarge,
                color = OnSurface
            )
            Text(
                "Разрешите доступ к медиафайлам, чтобы показать видео на устройстве и USB-носителях.",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceDim,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
            )
            Button(onClick = { permissionLauncher.launch(storagePermission) }) {
                Text("Разрешить доступ")
            }
        }
        list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Primary)
        }
        list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Видео не найдены", color = OnSurfaceDim, style = MaterialTheme.typography.titleMedium)
        }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(list, key = { it.id }) { video ->
                VideoRow(video = video, onClick = { onPlay(video.uri.toString(), video.name) })
            }
        }
    }
}

@Composable
private fun VideoRow(video: LocalVideo, onClick: () -> Unit) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    val thumb by produceState<ImageBitmap?>(null, video.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bmp = if (Build.VERSION.SDK_INT >= 29) {
                    context.contentResolver.loadThumbnail(video.uri, Size(320, 180), null)
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Video.Thumbnails.getThumbnail(
                        context.contentResolver, video.id,
                        MediaStore.Video.Thumbnails.MINI_KIND, null
                    )
                }
                bmp?.asImageBitmap()
            }.getOrNull()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) SurfaceVariant else Surface)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .width(128.dp)
                .height(72.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val t = thumb
            if (t != null) {
                Image(bitmap = t, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Filled.Movie, contentDescription = null, tint = OnSurfaceDim)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.name,
                style = MaterialTheme.typography.titleMedium,
                color = OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOf(video.bucket, formatDuration(video.duration), formatSize(video.size))
                    .filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceDim
            )
        }
    }
}

private data class FsEntry(val file: File, val isDirectory: Boolean)

@Composable
private fun FoldersTab(onPlay: (String, String) -> Unit) {
    val context = LocalContext.current
    val root = remember { Environment.getExternalStorageDirectory() }
    var dir by remember { mutableStateOf(root) }
    var entries by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    var denied by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { tick++ }

    LaunchedEffect(dir, tick) {
        val granted = ContextCompat.checkSelfPermission(context, storagePermission) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            denied = true
            entries = emptyList()
            return@LaunchedEffect
        }
        denied = false
        entries = withContext(Dispatchers.IO) {
            dir.listFiles()
                ?.filter { it.isDirectory || isVideoFile(it.name) }
                ?.map { FsEntry(it, it.isDirectory) }
                ?.sortedWith(compareByDescending<FsEntry> { it.isDirectory }.thenBy { it.file.name.lowercase() })
                ?: emptyList()
        }
    }

    if (denied) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Нужен доступ к файлам",
                style = MaterialTheme.typography.titleLarge,
                color = OnSurface
            )
            Text(
                "Разрешите доступ к видео, чтобы просматривать локальные папки.",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceDim,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
            )
            Button(onClick = { permissionLauncher.launch(storagePermission) }) {
                Text("Разрешить доступ")
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (dir != root) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Наверх",
                    tint = Primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { dir = dir.parentFile ?: root }
                        .padding(8.dp)
                        .size(24.dp)
                )
            }
            Text(
                text = dir.absolutePath,
                style = MaterialTheme.typography.bodyLarge,
                color = OnSurfaceDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(entries, key = { it.file.absolutePath }) { entry ->
                FsRow(entry = entry, onClick = {
                    if (entry.isDirectory) dir = entry.file
                    else onPlay(Uri.fromFile(entry.file).toString(), entry.file.name)
                })
            }
        }
    }
}

@Composable
private fun FsRow(entry: FsEntry, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val date = remember(entry.file.lastModified()) {
        SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(entry.file.lastModified()))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) SurfaceVariant else Surface)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Movie,
            contentDescription = null,
            tint = if (entry.isDirectory) Secondary else Primary,
            modifier = Modifier.size(32.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.file.name,
                style = MaterialTheme.typography.titleMedium,
                color = OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (entry.isDirectory) date
                else listOf(formatSize(entry.file.length()), date).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceDim
            )
        }
    }
}