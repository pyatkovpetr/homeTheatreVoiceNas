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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.homeprojector.launcher.music.AlbumGroup
import com.homeprojector.launcher.music.ArtistGroup
import com.homeprojector.launcher.music.MusicLibrary
import com.homeprojector.launcher.music.MusicPlayer
import com.homeprojector.launcher.music.Track
import com.homeprojector.launcher.util.formatDuration
import kotlinx.coroutines.launch

@Composable
fun MusicScreen(prefs: Prefs, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val libState by MusicLibrary.state.collectAsState()
    val playerState by MusicPlayer.state.collectAsState()

    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var selectedAlbum by remember { mutableStateOf<AlbumGroup?>(null) }
    var selectedArtist by remember { mutableStateOf<ArtistGroup?>(null) }
    var favorites by remember { mutableStateOf(prefs.favoriteTracks) }

    androidx.activity.compose.BackHandler(enabled = selectedAlbum != null || selectedArtist != null) {
        selectedAlbum = null
        selectedArtist = null
    }

    LaunchedEffect(Unit) {
        MusicLibrary.loadCache(context)
        if (MusicLibrary.state.value.tracks.isEmpty()) {
            MusicLibrary.scan(context, prefs)
        }
    }

    fun toggleFavorite(track: Track) {
        favorites = if (favorites.contains(track.url)) favorites - track.url else favorites + track.url
        prefs.favoriteTracks = favorites
    }

    fun playList(tracks: List<Track>, index: Int) {
        MusicPlayer.playQueue(context, prefs, tracks, index)
    }

    ScreenFrame(
        title = "Музыка",
        onBack = {
            when {
                selectedAlbum != null -> selectedAlbum = null
                selectedArtist != null -> selectedArtist = null
                else -> onBack()
            }
        },
        actions = {
            if (libState.scanning) {
                Text(libState.status, style = MaterialTheme.typography.bodyMedium, color = Secondary)
            } else {
                Text(
                    "${libState.tracks.size} треков",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim,
                    modifier = Modifier.padding(end = 8.dp)
                )
                IconButton(onClick = { scope.launch { MusicLibrary.scan(context, prefs, force = true) } }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Пересканировать", tint = OnSurface)
                }
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (libState.error != null && libState.tracks.isEmpty()) {
                ErrorHint(libState.error ?: "", "Проверьте папку музыки в настройках (раздел «Медиатека»).")
                return@Column
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("Треки", "Альбомы", "Артисты", "Избранное").forEachIndexed { i, label ->
                    FilterChip(
                        selected = tab == i,
                        onClick = { tab = i; selectedAlbum = null; selectedArtist = null },
                        label = { Text(label) }
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Поиск…") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp)) {
                if (libState.tracks.isEmpty() && libState.scanning) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Primary)
                            Text(
                                libState.status.ifEmpty { "Сканирование библиотеки…" },
                                color = OnSurfaceDim,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }
                    }
                } else {
                    val tracks = libState.tracks.filter {
                        query.isBlank() ||
                            it.title.lowercase().contains(query.lowercase()) ||
                            it.artist.lowercase().contains(query.lowercase()) ||
                            it.album.lowercase().contains(query.lowercase())
                    }
                    when {
                        selectedAlbum != null -> TrackList(
                            tracks = selectedAlbum!!.tracks,
                            currentUrl = playerState.track?.url,
                            favorites = favorites,
                            onPlay = { idx -> playList(selectedAlbum!!.tracks, idx) },
                            onFavorite = { toggleFavorite(it) }
                        )
                        selectedArtist != null -> TrackList(
                            tracks = selectedArtist!!.tracks,
                            currentUrl = playerState.track?.url,
                            favorites = favorites,
                            onPlay = { idx -> playList(selectedArtist!!.tracks, idx) },
                            onFavorite = { toggleFavorite(it) }
                        )
                        tab == 0 -> TrackList(
                            tracks = tracks,
                            currentUrl = playerState.track?.url,
                            favorites = favorites,
                            onPlay = { idx -> playList(tracks, idx) },
                            onFavorite = { toggleFavorite(it) }
                        )
                        tab == 1 -> LazyVerticalGrid(
                            columns = GridCells.Fixed(5),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val albums = MusicLibrary.albums(tracks)
                            items(albums, key = { it.name + it.artist }) { album ->
                                GroupCard(
                                    title = album.name,
                                    subtitle = album.artist,
                                    icon = Icons.Filled.Album,
                                    onClick = { selectedAlbum = album }
                                )
                            }
                        }
                        tab == 2 -> LazyVerticalGrid(
                            columns = GridCells.Fixed(5),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val artists = MusicLibrary.artists(tracks)
                            items(artists, key = { it.name }) { artist ->
                                GroupCard(
                                    title = artist.name,
                                    subtitle = "${artist.tracks.size} треков",
                                    icon = Icons.Filled.Person,
                                    onClick = { selectedArtist = artist }
                                )
                            }
                        }
                        else -> {
                            val favTracks = tracks.filter { favorites.contains(it.url) }
                            if (favTracks.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Отмечайте треки звёздочкой — они появятся здесь", color = OnSurfaceDim)
                                }
                            } else {
                                TrackList(
                                    tracks = favTracks,
                                    currentUrl = playerState.track?.url,
                                    favorites = favorites,
                                    onPlay = { idx -> playList(favTracks, idx) },
                                    onFavorite = { toggleFavorite(it) }
                                )
                            }
                        }
                    }
                }
            }

            // Now playing bar
            if (playerState.track != null) {
                val t = playerState.track!!
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Primary)
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(t.title, style = MaterialTheme.typography.titleMedium, color = OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${t.artist} · ${t.album}", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim, maxLines = 1)
                        }
                        Text(
                            "${formatDuration(playerState.positionMs)} / ${formatDuration(playerState.durationMs)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnSurfaceDim
                        )
                        IconButton(onClick = { MusicPlayer.prev() }) {
                            Icon(Icons.Filled.SkipPrevious, contentDescription = "Предыдущий", tint = OnSurface)
                        }
                        IconButton(onClick = { MusicPlayer.toggle() }) {
                            Icon(
                                if (playerState.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = "Пауза/play",
                                tint = Primary,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        IconButton(onClick = { MusicPlayer.next() }) {
                            Icon(Icons.Filled.SkipNext, contentDescription = "Следующий", tint = OnSurface)
                        }
                    }
                    Slider(
                        value = if (playerState.durationMs > 0) playerState.positionMs.toFloat() / playerState.durationMs else 0f,
                        onValueChange = { frac ->
                            MusicPlayer.seekTo((frac * playerState.durationMs).toLong())
                        },
                        modifier = Modifier.fillMaxWidth().height(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackList(
    tracks: List<Track>,
    currentUrl: String?,
    favorites: Set<String>,
    onPlay: (Int) -> Unit,
    onFavorite: (Track) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(tracks, key = { it.url }) { track ->
            val idx = tracks.indexOf(track)
            TrackRow(
                track = track,
                isCurrent = track.url == currentUrl,
                isFavorite = favorites.contains(track.url),
                onClick = { onPlay(idx) },
                onFavorite = { onFavorite(track) }
            )
        }
    }
}

@Composable
private fun TrackRow(track: Track, isCurrent: Boolean, isFavorite: Boolean, onClick: () -> Unit, onFavorite: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isCurrent) SurfaceVariant else if (focused) SurfaceVariant else Surface)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            tint = if (isCurrent) Primary else OnSurfaceDim,
            modifier = Modifier.size(24.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) Primary else OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${track.artist} · ${track.album}",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(formatDuration(track.durationMs), style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim)
        IconButton(onClick = onFavorite) {
            Icon(
                if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = "Избранное",
                tint = if (isFavorite) Secondary else OnSurfaceDim
            )
        }
    }
}

@Composable
private fun GroupCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) SurfaceVariant else Surface)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Primary, modifier = Modifier.size(36.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = OnSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}