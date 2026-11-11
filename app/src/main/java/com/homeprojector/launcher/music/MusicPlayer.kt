@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.homeprojector.launcher.music

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.homeprojector.launcher.Prefs
import com.homeprojector.launcher.nas.ProjectorDataSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Плеер музыки уровня приложения: живёт между экранами, очередь треков,
 * автопереход к следующему, непрерывное воспроизведение.
 */
object MusicPlayer {

    data class State(
        val track: Track? = null,
        val playing: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val index: Int = -1,
        val queueSize: Int = 0
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var player: ExoPlayer? = null
    private var queue: List<Track> = emptyList()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

    private fun ensure(context: Context, prefs: Prefs): ExoPlayer {
        player?.let { return it }
        val appCtx = context.applicationContext
        val p = ExoPlayer.Builder(appCtx)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(appCtx)
                    .setDataSourceFactory(ProjectorDataSourceFactory(appCtx, prefs))
            )
            .build()
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = sync()
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                sync()
                if (isPlaying) startPolling() else pollJob?.cancel()
            }
            override fun onPlaybackStateChanged(playbackState: Int) = sync()
        })
        player = p
        return p
    }

    fun playQueue(context: Context, prefs: Prefs, tracks: List<Track>, index: Int) {
        if (tracks.isEmpty()) return
        queue = tracks
        val p = ensure(context, prefs)
        p.setMediaItems(tracks.map { MediaItem.fromUri(it.url) }, index.coerceIn(0, tracks.lastIndex), 0L)
        p.prepare()
        p.play()
    }

    fun playTrack(context: Context, prefs: Prefs, track: Track) =
        playQueue(context, prefs, listOf(track), 0)

    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun next() {
        player?.seekToNextMediaItem()
    }

    fun prev() {
        player?.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms)
        sync()
    }

    fun stop() {
        player?.stop()
        player?.clearMediaItems()
        queue = emptyList()
        _state.value = State()
    }

    private fun sync() {
        val p = player ?: return
        val idx = p.currentMediaItemIndex
        _state.value = State(
            track = queue.getOrNull(idx),
            playing = p.isPlaying,
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = p.duration.coerceAtLeast(0),
            index = idx,
            queueSize = queue.size
        )
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                sync()
                delay(500)
            }
        }
    }
}