package com.homeprojector.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.homeprojector.launcher.Prefs
import com.homeprojector.launcher.nas.NasClient
import com.homeprojector.launcher.nas.NasEntry
import com.homeprojector.launcher.util.IMAGE_EXTENSIONS
import com.homeprojector.launcher.util.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PhotosScreen(prefs: Prefs, onBack: () -> Unit) {
    var photos by remember { mutableStateOf<List<NasEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var fullscreenIndex by remember { mutableIntStateOf(-1) }

    LaunchedEffect(Unit) {
        photos = withContext(Dispatchers.IO) {
            try {
                val client = NasClient(prefs)
                client.collectFiles(client.folderUrl(prefs.photosPath), IMAGE_EXTENSIONS, maxFiles = 2000)
                    .sortedByDescending { it.modified }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                emptyList()
            }
        }
    }

    BackHandler(enabled = fullscreenIndex >= 0) { fullscreenIndex = -1 }

    ScreenFrame(title = "Фото", onBack = onBack) {
        val list = photos
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Primary)
            }
            error != null && list.isEmpty() -> ErrorHint(error ?: "", "Проверьте папку фото в настройках (раздел «Медиатека»).")
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Фотографии не найдены", color = OnSurfaceDim, style = MaterialTheme.typography.titleMedium)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(list, key = { _, e -> e.url }) { idx, entry ->
                    PhotoThumb(entry = entry, prefs = prefs, onClick = { fullscreenIndex = idx })
                }
            }
        }
    }

    // Полноэкранный просмотр: свайп, зум, оригинальное качество
    val list = photos
    if (fullscreenIndex >= 0 && list != null && list.isNotEmpty()) {
        val pagerState = rememberPagerState(initialPage = fullscreenIndex) { list.size }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val entry = list[page]
                val bitmap by produceState<ImageBitmap?>(null, entry.url) {
                    value = ImageLoader.load("full:${entry.url}") {
                        ImageLoader.decodeSmb(prefs, entry.url, 2560)
                    }
                }
                var scale by remember(page) { mutableStateOf(1f) }
                var offsetX by remember(page) { mutableStateOf(0f) }
                var offsetY by remember(page) { mutableStateOf(0f) }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(page) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                if (scale > 1f) { offsetX += pan.x; offsetY += pan.y }
                                else { offsetX = 0f; offsetY = 0f }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = bitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = entry.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = scale; scaleY = scale
                                    translationX = offsetX; translationY = offsetY
                                }
                        )
                    } else {
                        CircularProgressIndicator(color = Primary)
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x99000000))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { fullscreenIndex = -1 }) {
                    Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = Color.White)
                }
                Text(
                    "${pagerState.currentPage + 1} / ${list.size} · ${list[pagerState.currentPage].name}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun PhotoThumb(entry: NasEntry, prefs: Prefs, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(null, entry.url) {
        value = ImageLoader.load("thumb:${entry.url}") {
            ImageLoader.decodeSmb(prefs, entry.url, 320)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceVariant)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = entry.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(Icons.Filled.Image, contentDescription = null, tint = OnSurfaceDim)
        }
        if (focused) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.99f }
                    .background(Color(0x334FD1C5))
            )
        }
    }
}