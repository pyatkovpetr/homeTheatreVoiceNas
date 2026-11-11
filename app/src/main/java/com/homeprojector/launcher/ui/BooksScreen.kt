package com.homeprojector.launcher.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.homeprojector.launcher.Prefs
import com.homeprojector.launcher.books.BookItem
import com.homeprojector.launcher.books.BookStore
import com.homeprojector.launcher.download.DownloadManager
import com.homeprojector.launcher.nas.NasClient
import com.homeprojector.launcher.util.BOOK_EXTENSIONS
import com.homeprojector.launcher.util.formatSize
import com.homeprojector.launcher.util.isBookFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
fun BooksScreen(
    prefs: Prefs,
    onBack: () -> Unit,
    onOpenBook: (key: String, path: String, title: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var books by remember { mutableStateOf<List<BookItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf<BookItem?>(null) }
    var dlProgress by remember { mutableStateOf(0f) }

    LaunchedEffect(tab) {
        error = null
        books = null
        books = withContext(Dispatchers.IO) {
            try {
                if (tab == 0) {
                    val client = NasClient(prefs)
                    client.collectFiles(client.folderUrl(prefs.booksPath), BOOK_EXTENSIONS)
                        .map { BookItem(it.name, it.url, it.size, it.modified) }
                } else {
                    val dirs = listOfNotNull(
                        DownloadManager.destDir(),
                        File("/storage/emulated/0/Books"),
                        File("/storage/emulated/0/Download")
                    )
                    dirs.flatMap { d ->
                        d.listFiles()?.filter { it.isFile && isBookFile(it.name) }?.map {
                            BookItem(it.name, it.absolutePath, it.length(), it.lastModified(), local = true)
                        } ?: emptyList()
                    }
                }.sortedBy { it.name.lowercase(Locale.ROOT) }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                emptyList()
            }
        }
    }

    fun openBook(item: BookItem) {
        val cached = BookStore.cachedFile(context, item)
        if (cached != null) {
            onOpenBook(BookStore.bookKey(item.url), cached.absolutePath, item.name)
            return
        }
        downloading = item
        dlProgress = 0f
        scope.launch {
            try {
                val file = BookStore.ensureDownloaded(context, prefs, item) { done, total ->
                    dlProgress = if (total > 0) done.toFloat() / total else 0f
                }
                downloading = null
                onOpenBook(BookStore.bookKey(item.url), file.absolutePath, item.name)
            } catch (e: Exception) {
                downloading = null
                error = "Не удалось скачать книгу: ${e.message}"
                android.widget.Toast.makeText(context, "Не удалось скачать книгу: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    ScreenFrame(title = "Книги", onBack = onBack) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Библиотека NAS") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Локальные") })
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Поиск книги…") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Box(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
                val list = books
                when {
                    list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Primary)
                    }
                    error != null && list.isEmpty() -> ErrorHint(error ?: "", "Проверьте папку книг в настройках (раздел «Медиатека»).")
                    list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Книги не найдены (PDF, EPUB, FB2, TXT)", color = OnSurfaceDim, style = MaterialTheme.typography.titleMedium)
                    }
                    else -> {
                        val filtered = if (query.isBlank()) list
                        else list.filter { it.name.lowercase().contains(query.lowercase()) }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(filtered, key = { it.url }) { item ->
                                BookRow(item = item, onClick = { openBook(item) })
                            }
                        }
                    }
                }

                if (downloading != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(androidx.compose.ui.graphics.Color(0xCC0A0E14)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(Surface)
                                .padding(32.dp)
                                .width(480.dp)
                        ) {
                            Text(
                                "Загрузка книги",
                                style = MaterialTheme.typography.titleLarge,
                                color = OnSurface
                            )
                            Text(
                                downloading?.name ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnSurfaceDim,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                            )
                            LinearProgressIndicator(
                                progress = { dlProgress },
                                modifier = Modifier.fillMaxWidth(),
                                color = Primary
                            )
                            Text(
                                "${(dlProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnSurfaceDim,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookRow(item: BookItem, onClick: () -> Unit) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val ext = item.name.substringAfterLast('.', "").lowercase()
    val pos = remember(item.url) { BookStore.getPosition(context, BookStore.bookKey(item.url)) }
    val started = pos.chapter > 0 || pos.scroll > 0 || pos.page > 0

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
            imageVector = when (ext) {
                "pdf" -> Icons.Filled.PictureAsPdf
                "epub", "fb2" -> Icons.Filled.AutoStories
                else -> Icons.Filled.Description
            },
            contentDescription = null,
            tint = when (ext) {
                "pdf" -> ErrorColor
                "epub", "fb2" -> Primary
                else -> Secondary
            },
            modifier = Modifier.size(36.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                color = OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOf(formatSize(item.size), ext.uppercase()).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceDim
            )
        }
        if (started) {
            Text(
                text = if (ext == "pdf") "Стр. ${pos.page + 1}" else "Глава ${pos.chapter + 1}",
                style = MaterialTheme.typography.bodyMedium,
                color = Primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun ErrorHint(message: String, hint: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Не удалось загрузить список", style = MaterialTheme.typography.titleLarge, color = ErrorColor)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceDim,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
        )
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
    }
}