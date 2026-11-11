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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.homeprojector.launcher.Prefs
import com.homeprojector.launcher.nas.NasClient
import com.homeprojector.launcher.nas.NasEntry
import com.homeprojector.launcher.util.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NasScreen(
    prefs: Prefs,
    onBack: () -> Unit,
    onPlay: (uri: String, title: String) -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    val client = remember { NasClient(prefs) }
    val rootUrl = remember { client.rootUrl() }

    var segments by remember { mutableStateOf(listOf<String>()) }
    var entries by remember { mutableStateOf<List<NasEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    val currentUrl = rootUrl + segments.joinToString("") { "$it/" }

    androidx.activity.compose.BackHandler(enabled = segments.isNotEmpty()) {
        segments = segments.dropLast(1)
    }

    LaunchedEffect(currentUrl, tick) {
        loading = true
        error = null
        try {
            entries = withContext(Dispatchers.IO) { client.list(currentUrl) }
        } catch (e: Exception) {
            entries = emptyList()
            error = e.message ?: e.javaClass.simpleName
        }
        loading = false
    }

    ScreenFrame(
        title = "Видеотека NAS",
        onBack = {
            if (segments.isNotEmpty()) segments = segments.dropLast(1) else onBack()
        },
        actions = {
            IconButton(onClick = { tick++ }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Обновить", tint = OnSurface)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Настройки NAS", tint = OnSurface)
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Хлебные крошки: host/share / папка / подпапка
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Crumb(
                    label = "${prefs.nasHost}/${prefs.nasShare}",
                    active = segments.isEmpty()
                ) { segments = emptyList() }
                segments.forEachIndexed { i, seg ->
                    Text("/", color = OnSurfaceDim, style = MaterialTheme.typography.bodyLarge)
                    Crumb(label = seg, active = i == segments.lastIndex) {
                        segments = segments.take(i + 1)
                    }
                }
            }

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Primary)
                }
                error != null -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Не удалось открыть папку NAS",
                        style = MaterialTheme.typography.titleLarge,
                        color = ErrorColor
                    )
                    Text(
                        error ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceDim,
                        modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
                    )
                    Text(
                        "Проверьте адрес, общую папку и учётные данные в настройках.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceDim
                    )
                    Button(onClick = onSettings, modifier = Modifier.padding(top = 16.dp)) {
                        Text("Открыть настройки")
                    }
                }
                entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Папка пуста", color = OnSurfaceDim, style = MaterialTheme.typography.titleMedium)
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(entries, key = { it.url }) { entry ->
                        NasRow(entry = entry, onClick = {
                            if (entry.isDirectory) {
                                segments = segments + entry.name
                            } else {
                                onPlay(entry.url, entry.name)
                            }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun Crumb(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = if (active) Primary else OnSurfaceDim,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun NasRow(entry: NasEntry, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
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
                text = entry.name,
                style = MaterialTheme.typography.titleMedium,
                color = OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!entry.isDirectory) {
                val date = remember(entry.modified) {
                    SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(entry.modified))
                }
                Text(
                    text = listOf(formatSize(entry.size), date).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim
                )
            }
        }
    }
}