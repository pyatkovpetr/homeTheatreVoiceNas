package com.homeprojector.launcher.ui

import android.widget.Toast
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import com.homeprojector.launcher.nas.NasClient
import com.homeprojector.launcher.nas.NasEntry
import com.homeprojector.launcher.util.formatSize
import com.homeprojector.launcher.util.isBookFile
import com.homeprojector.launcher.util.isVideoFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Учебная станция: школьные видеоуроки по предметам + учебники.
 * Структура NAS: папка «school» → подпапки-предметы → видео/книги.
 */
@Composable
fun SchoolScreen(
    prefs: Prefs,
    onBack: () -> Unit,
    onPlay: (uri: String, title: String) -> Unit,
    onOpenBook: (key: String, path: String, title: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { NasClient(prefs) }
    val rootUrl = remember { client.folderUrl(prefs.schoolPath) }

    var segments by remember { mutableStateOf(listOf<String>()) }
    var entries by remember { mutableStateOf<List<NasEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<NasEntry>?>(null) }
    var bookProgress by remember { mutableStateOf<String?>(null) }

    val currentUrl = rootUrl + segments.joinToString("") { "$it/" }

    androidx.activity.compose.BackHandler(enabled = query.isNotBlank() || segments.isNotEmpty()) {
        if (query.isNotBlank()) query = "" else segments = segments.dropLast(1)
    }

    LaunchedEffect(currentUrl) {
        entries = null
        error = null
        try {
            entries = withContext(Dispatchers.IO) { client.listAll(currentUrl) }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            entries = emptyList()
        }
    }

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

    fun openBook(entry: NasEntry) {
        val item = BookItem(entry.name, entry.url, entry.size, entry.modified)
        val cached = BookStore.cachedFile(context, item)
        if (cached != null) {
            onOpenBook(BookStore.bookKey(item.url), cached.absolutePath, item.name)
            return
        }
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

    fun openEntry(entry: NasEntry) {
        when {
            entry.isDirectory -> segments = segments + entry.name
            isVideoFile(entry.name) -> onPlay(entry.url, entry.name)
            isBookFile(entry.name) -> openBook(entry)
            else -> Toast.makeText(context, "Файл можно скачать в разделе «Файлы»", Toast.LENGTH_SHORT).show()
        }
    }

    ScreenFrame(
        title = "Учёба",
        onBack = {
            when {
                query.isNotBlank() -> query = ""
                segments.isNotEmpty() -> segments = segments.dropLast(1)
                else -> onBack()
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Поиск уроков и учебников…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (segments.isNotEmpty() && query.isBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Все предметы",
                        style = MaterialTheme.typography.bodyLarge,
                        color = OnSurfaceDim,
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

            Box(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                val list = entries
                val shown = searchResults ?: list
                when {
                    list == null && searchResults == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Primary)
                    }
                    error != null -> ErrorHint(
                        error ?: "",
                        "Создайте на NAS папку «${prefs.schoolPath}» с подпапками предметов " +
                            "(Математика, Русский язык, …) и положите туда видеоуроки и учебники. " +
                            "Имя папки меняется в настройках."
                    )
                    shown == null || shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (searchResults != null) "Ничего не найдено" else "Папка пуста",
                            color = OnSurfaceDim,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    segments.isEmpty() && searchResults == null && shown.any { it.isDirectory } -> {
                        // Корень: плитка предметов + файлы общего доступа
                        val subjects = shown.filter { it.isDirectory }
                        val rootFiles = shown.filter { !it.isDirectory }
                        Column(modifier = Modifier.fillMaxSize()) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(4),
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(subjects, key = { it.url }) { subject ->
                                    SubjectCard(subject = subject, onClick = { openEntry(subject) })
                                }
                            }
                            if (rootFiles.isNotEmpty()) {
                                Text(
                                    "Общие материалы",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = OnSurfaceDim,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                                LazyColumn(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(rootFiles, key = { it.url }) { entry ->
                                        SchoolFileRow(entry = entry, onClick = { openEntry(entry) })
                                    }
                                }
                            }
                        }
                    }
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(shown, key = { it.url }) { entry ->
                            SchoolFileRow(entry = entry, onClick = { openEntry(entry) })
                        }
                    }
                }

                if (bookProgress != null) {
                    Box(
                        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xCC0A0E14)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Primary)
                            Text(
                                "Загрузка учебника: $bookProgress",
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
}

@Composable
private fun SubjectCard(subject: NasEntry, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) SurfaceVariant else Surface)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Filled.School, contentDescription = null, tint = Secondary, modifier = Modifier.size(40.dp))
        Text(
            subject.name,
            style = MaterialTheme.typography.titleLarge,
            color = OnSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text("Видеоуроки и учебники", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
    }
}

@Composable
private fun SchoolFileRow(entry: NasEntry, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
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
                else -> Icons.Filled.AutoStories
            },
            contentDescription = null,
            tint = when {
                entry.isDirectory -> Secondary
                isVideoFile(entry.name) -> Primary
                else -> Secondary
            },
            modifier = Modifier.size(28.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium, color = OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!entry.isDirectory) {
                Text(
                    listOf(
                        if (isVideoFile(entry.name)) "Видеоурок" else "Учебник",
                        formatSize(entry.size)
                    ).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim
                )
            }
        }
    }
}