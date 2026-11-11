package com.homeprojector.launcher.ui

import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.homeprojector.launcher.Prefs
import com.homeprojector.launcher.books.BookItem
import com.homeprojector.launcher.books.BookStore
import com.homeprojector.launcher.download.DownloadManager
import com.homeprojector.launcher.music.MusicPlayer
import com.homeprojector.launcher.music.Track
import com.homeprojector.launcher.nas.NasClient
import com.homeprojector.launcher.nas.NasEntry
import com.homeprojector.launcher.util.ImageLoader
import com.homeprojector.launcher.util.formatSize
import com.homeprojector.launcher.util.isAudioFile
import com.homeprojector.launcher.util.isBookFile
import com.homeprojector.launcher.util.isImageFile
import com.homeprojector.launcher.util.isVideoFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FilesScreen(
    prefs: Prefs,
    onBack: () -> Unit,
    onPlay: (uri: String, title: String) -> Unit,
    onOpenBook: (key: String, path: String, title: String) -> Unit,
    onLocal: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { NasClient(prefs) }
    val rootUrl = remember { client.folderUrl("") }

    var segments by remember { mutableStateOf(listOf<String>()) }
    var entries by remember { mutableStateOf<List<NasEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<NasEntry>?>(null) }
    var previewImage by remember { mutableStateOf<NasEntry?>(null) }
    var bookProgress by remember { mutableStateOf<String?>(null) }

    val currentUrl = rootUrl + segments.joinToString("") { "$it/" }

    androidx.activity.compose.BackHandler(enabled = query.isNotBlank() || segments.isNotEmpty()) {
        if (query.isNotBlank()) query = "" else segments = segments.dropLast(1)
    }

    LaunchedEffect(currentUrl) {
        loading = true
        error = null
        try {
            entries = withContext(Dispatchers.IO) { client.listAll(currentUrl) }
        } catch (e: Exception) {
            entries = emptyList()
            error = e.message ?: e.javaClass.simpleName
        }
        loading = false
    }

    // Поиск с debounce — не дёргаем NAS на каждый символ
    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            searchResults = null
            return@LaunchedEffect
        }
        delay(450)
        searchResults = withContext(Dispatchers.IO) {
            runCatching { client.search(rootUrl, query.trim()) }.getOrDefault(emptyList())
        }
    }

    fun download(entry: NasEntry) {
        DownloadManager.enqueue(entry.url, entry.name)
        Toast.makeText(context, "Загрузка: ${entry.name}", Toast.LENGTH_SHORT).show()
    }

    fun openEntry(entry: NasEntry) {
        when {
            entry.isDirectory -> segments = segments + entry.name
            isVideoFile(entry.name) -> onPlay(entry.url, entry.name)
            isAudioFile(entry.name) -> MusicPlayer.playTrack(
                context, prefs,
                Track(entry.url, entry.name.substringBeforeLast('.'), "Неизвестный артист", "Без альбома", 0, 0)
            )
            isImageFile(entry.name) -> previewImage = entry
            isBookFile(entry.name) -> {
                val item = BookItem(entry.name, entry.url, entry.size, entry.modified)
                val cached = BookStore.cachedFile(context, item)
                if (cached != null) {
                    onOpenBook(BookStore.bookKey(item.url), cached.absolutePath, item.name)
                } else {
                    bookProgress = entry.name
                    scope.launch {
                        try {
                            val f = BookStore.ensureDownloaded(context, prefs, item) { _, _ -> }
                            bookProgress = null
                            onOpenBook(BookStore.bookKey(item.url), f.absolutePath, item.name)
                        } catch (e: Exception) {
                            bookProgress = null
                            Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            else -> download(entry)
        }
    }

    ScreenFrame(
        title = "Файлы NAS",
        onBack = {
            when {
                query.isNotBlank() -> query = ""
                segments.isNotEmpty() -> segments = segments.dropLast(1)
                else -> onBack()
            }
        },
        actions = {
            IconButton(onClick = onLocal) {
                Icon(
                    Icons.Filled.PhoneAndroid,
                    contentDescription = "Локальные файлы устройства",
                    tint = Primary
                )
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Поиск по всей видеотеке NAS (от 2 символов)…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (query.isBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "${prefs.nasHost}/${prefs.nasShare}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (segments.isEmpty()) Primary else OnSurfaceDim,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { segments = emptyList() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    segments.forEachIndexed { i, seg ->
                        Text("/", color = OnSurfaceDim)
                        Text(
                            seg,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (i == segments.lastIndex) Primary else OnSurfaceDim,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { segments = segments.take(i + 1) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                val shown = searchResults ?: entries
                when {
                    loading && searchResults == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Primary)
                    }
                    error != null && shown.isEmpty() -> ErrorHint(error ?: "", "Проверьте подключение к NAS в настройках.")
                    shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (searchResults != null) "Ничего не найдено" else "Папка пуста",
                            color = OnSurfaceDim,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(shown, key = { it.url }) { entry ->
                            FileRow(
                                entry = entry,
                                onClick = { openEntry(entry) },
                                onDownload = { download(entry) }
                            )
                        }
                    }
                }

                if (bookProgress != null) {
                    Box(
                        Modifier.fillMaxSize().background(Color(0xCC0A0E14)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Primary)
                            Text(
                                "Загрузка книги: $bookProgress",
                                color = OnSurface,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Простой полноэкранный просмотр изображения
    val img = previewImage
    if (img != null) {
        val bitmap by produceState<ImageBitmap?>(null, img.url) {
            value = ImageLoader.load("full:${img.url}") { ImageLoader.decodeSmb(prefs, img.url, 2560) }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { previewImage = null },
            contentAlignment = Alignment.Center
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(bmp, contentDescription = img.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                CircularProgressIndicator(color = Primary)
            }
            Text(
                img.name,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)
            )
        }
    }
}

@Composable
private fun FileRow(entry: NasEntry, onClick: () -> Unit, onDownload: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val date = remember(entry.modified) {
        SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(entry.modified))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) SurfaceVariant else Surface)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = when {
                entry.isDirectory -> Icons.Filled.Folder
                isVideoFile(entry.name) -> Icons.Filled.Movie
                isAudioFile(entry.name) -> Icons.Filled.AudioFile
                isImageFile(entry.name) -> Icons.Filled.Image
                isBookFile(entry.name) -> Icons.Filled.AutoStories
                else -> Icons.Filled.Description
            },
            contentDescription = null,
            tint = when {
                entry.isDirectory -> Secondary
                isVideoFile(entry.name) -> Primary
                isBookFile(entry.name) -> Primary
                else -> OnSurfaceDim
            },
            modifier = Modifier.size(28.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium, color = OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!entry.isDirectory) {
                Text(
                    listOf(formatSize(entry.size), date).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim
                )
            }
        }
        if (!entry.isDirectory) {
            IconButton(onClick = onDownload) {
                Icon(Icons.Filled.Download, contentDescription = "Скачать", tint = Primary)
            }
        }
    }
}