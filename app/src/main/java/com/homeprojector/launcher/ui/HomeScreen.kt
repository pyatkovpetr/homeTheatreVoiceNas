package com.homeprojector.launcher.ui

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.homeprojector.launcher.Prefs
import com.homeprojector.launcher.Screen
import com.homeprojector.launcher.download.DlStatus
import com.homeprojector.launcher.download.DownloadManager
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RU_LOCALE = Locale("ru")

@Composable
fun HomeScreen(prefs: Prefs, onNavigate: (Screen) -> Unit) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1000)
        }
    }

    val usbMic = remember { findUsbMicName(context) }
    val tasks by DownloadManager.tasks.collectAsState()
    val activeDownloads = tasks.count { it.status == DlStatus.RUNNING || it.status == DlStatus.PAUSED }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 40.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = now.format(DateTimeFormatter.ofPattern("HH:mm")),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = now.format(
                        DateTimeFormatter.ofPattern("EEEE, d MMMM", RU_LOCALE)
                    ).replaceFirstChar { it.uppercase(RU_LOCALE) },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "HomeProjector",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Primary
                    )
                    Text(
                        text = if (usbMic != null) "Микрофон: $usbMic" else "USB-микрофон не подключён",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (usbMic != null) SuccessColor else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { onNavigate(Screen.Downloads) }, modifier = Modifier.size(52.dp)) {
                    if (activeDownloads > 0) {
                        BadgedBox(badge = { Badge { Text(activeDownloads.toString()) } }) {
                            Icon(Icons.Filled.Download, contentDescription = "Загрузки", tint = Secondary)
                        }
                    } else {
                        Icon(Icons.Filled.Download, contentDescription = "Загрузки", tint = OnSurfaceDim)
                    }
                }
                IconButton(onClick = { onNavigate(Screen.Settings) }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.Settings, contentDescription = "Настройки", tint = OnSurfaceDim)
                }
            }
        }

        val tileModifier = Modifier
            .weight(1f)
            .fillMaxHeight()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HomeTile(
                title = "Видеотека",
                subtitle = "Фильмы и сериалы с NAS",
                icon = Icons.Filled.VideoLibrary,
                accent = Primary,
                modifier = tileModifier,
                onClick = { onNavigate(Screen.Nas) }
            )
            HomeTile(
                title = "Учёба",
                subtitle = "Школьные видеоуроки и учебники",
                icon = Icons.Filled.School,
                accent = Secondary,
                modifier = tileModifier,
                onClick = { onNavigate(Screen.School) }
            )
            HomeTile(
                title = "Музыка",
                subtitle = "Альбомы, артисты, избранное",
                icon = Icons.Filled.MusicNote,
                accent = Primary,
                modifier = tileModifier,
                onClick = { onNavigate(Screen.Music) }
            )
            HomeTile(
                title = "Фото",
                subtitle = "Просмотр фото с NAS",
                icon = Icons.Filled.Image,
                accent = Secondary,
                modifier = tileModifier,
                onClick = { onNavigate(Screen.Photos) }
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HomeTile(
                title = "Книги",
                subtitle = "Читалка PDF/EPUB/FB2 с озвучкой",
                icon = Icons.Filled.AutoStories,
                accent = Primary,
                modifier = tileModifier,
                onClick = { onNavigate(Screen.Books) }
            )
            HomeTile(
                title = "Файлы",
                subtitle = "Поиск и скачивание с NAS",
                icon = Icons.Filled.Folder,
                accent = Secondary,
                modifier = tileModifier,
                onClick = { onNavigate(Screen.Files) }
            )
            HomeTile(
                title = "Приложения",
                subtitle = "Все установленные приложения",
                icon = Icons.Filled.GridView,
                accent = Primary,
                modifier = tileModifier,
                onClick = { onNavigate(Screen.Apps) }
            )
            HomeTile(
                title = "Ассистент",
                subtitle = "Голос и чат-бот NeuralDeep",
                icon = Icons.Filled.Mic,
                accent = Secondary,
                modifier = tileModifier,
                onClick = { onNavigate(Screen.Voice) }
            )
        }
    }
}

/** Имя подключённого USB-микрофона (ReSpeaker XVF3800 и подобные). */
fun findUsbMicName(context: Context): String? {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val device = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
        it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
    } ?: return null
    val name = device.productName?.toString()?.takeIf { it.isNotBlank() }
    return name ?: "USB-микрофон"
}