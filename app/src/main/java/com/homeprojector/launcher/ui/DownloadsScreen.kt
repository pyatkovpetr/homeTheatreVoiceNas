package com.homeprojector.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.homeprojector.launcher.download.DlStatus
import com.homeprojector.launcher.download.DownloadManager
import com.homeprojector.launcher.util.formatSize

@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val tasks by DownloadManager.tasks.collectAsState()

    ScreenFrame(
        title = "Загрузки",
        onBack = onBack,
        actions = {
            if (tasks.any { it.status == DlStatus.DONE }) {
                TextButton(onClick = { DownloadManager.clearFinished() }) {
                    Text("Очистить завершённые", color = OnSurfaceDim)
                }
            }
        }
    ) {
        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Нет активных загрузок",
                        style = MaterialTheme.typography.titleLarge,
                        color = OnSurfaceDim
                    )
                    Text(
                        "Скачивайте файлы из раздела «Файлы» — прогресс появится здесь.\nФайлы сохраняются в память устройства и доступны офлайн.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceDim,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(tasks, key = { it.id }) { task ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Surface)
                            .padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    task.fileName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = OnSurface,
                                    maxLines = 1
                                )
                                Text(
                                    text = when (task.status) {
                                        DlStatus.RUNNING -> "${formatSize(task.downloaded)} из ${formatSize(task.total)}"
                                        DlStatus.PAUSED -> "Пауза · ${formatSize(task.downloaded)} из ${formatSize(task.total)}"
                                        DlStatus.DONE -> "Готово · ${formatSize(task.total)}"
                                        DlStatus.ERROR -> "Ошибка: ${task.error ?: "неизвестно"}"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = when (task.status) {
                                        DlStatus.ERROR -> ErrorColor
                                        DlStatus.DONE -> SuccessColor
                                        else -> OnSurfaceDim
                                    }
                                )
                            }
                            when (task.status) {
                                DlStatus.RUNNING -> IconButton(onClick = { DownloadManager.pause(task.id) }) {
                                    Icon(Icons.Filled.Pause, contentDescription = "Пауза", tint = Secondary)
                                }
                                DlStatus.PAUSED -> IconButton(onClick = { DownloadManager.resume(task.id) }) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = "Продолжить", tint = Primary)
                                }
                                DlStatus.ERROR -> IconButton(onClick = { DownloadManager.resume(task.id) }) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "Повторить", tint = Primary)
                                }
                                DlStatus.DONE -> {}
                            }
                            if (task.status != DlStatus.DONE) {
                                IconButton(onClick = { DownloadManager.cancel(task.id) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Отменить", tint = ErrorColor)
                                }
                            }
                        }
                        if (task.status == DlStatus.RUNNING || task.status == DlStatus.PAUSED) {
                            LinearProgressIndicator(
                                progress = {
                                    if (task.total > 0) task.downloaded.toFloat() / task.total else 0f
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                color = if (task.status == DlStatus.RUNNING) Primary else Secondary
                            )
                        }
                    }
                }
            }
        }
    }
}