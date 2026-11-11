package com.homeprojector.launcher.music

import android.content.Context
import com.homeprojector.launcher.Prefs
import com.homeprojector.launcher.nas.NasClient
import com.homeprojector.launcher.util.AUDIO_EXTENSIONS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Track(
    val url: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val trackNo: Int,
    val modified: Long = 0,
    val size: Long = 0
)

data class AlbumGroup(val name: String, val artist: String, val tracks: List<Track>)
data class ArtistGroup(val name: String, val tracks: List<Track>)

/**
 * Музыкальная библиотека NAS: рекурсивное сканирование, теги из заголовков файлов
 * (параллельно, до 4 потоков), кэш на диске — повторный запуск мгновенный,
 * перечитываются только новые/изменённые файлы.
 */
object MusicLibrary {

    data class State(
        val scanning: Boolean = false,
        val status: String = "",
        val tracks: List<Track> = emptyList(),
        val error: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private fun cacheFile(context: Context) = File(context.filesDir, "music_cache.json")

    fun loadCache(context: Context) {
        if (_state.value.tracks.isNotEmpty()) return
        val f = cacheFile(context)
        if (!f.exists()) return
        runCatching {
            val arr = JSONArray(f.readText())
            val tracks = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Track(
                    url = o.getString("url"),
                    title = o.getString("title"),
                    artist = o.getString("artist"),
                    album = o.getString("album"),
                    durationMs = o.optLong("dur"),
                    trackNo = o.optInt("no"),
                    modified = o.optLong("mod"),
                    size = o.optLong("size")
                )
            }
            _state.value = State(tracks = tracks, status = "Из кэша: ${tracks.size} треков")
        }
    }

    suspend fun scan(context: Context, prefs: Prefs, force: Boolean = false) {
        if (_state.value.scanning) return
        _state.value = _state.value.copy(scanning = true, error = null, status = "Сканирование папки…")
        try {
            val client = NasClient(prefs)
            val root = client.folderUrl(prefs.musicPath)
            val files = withContext(Dispatchers.IO) { client.collectFiles(root, AUDIO_EXTENSIONS) }
            if (files.isEmpty()) {
                _state.value = State(scanning = false, tracks = emptyList(), status = "", error = null)
                return
            }

            val cached = if (force) emptyMap() else _state.value.tracks.associateBy { it.url }
            val result = mutableListOf<Track>()
            var done = 0
            val sem = Semaphore(4)

            coroutineScope {
                val deferred = files.map { entry ->
                    async(Dispatchers.IO) {
                        val old = cached[entry.url]
                        val track = if (old != null && old.modified == entry.modified && old.size == entry.size) {
                            old
                        } else {
                            sem.withPermit {
                                val tags = runCatching {
                                    readTagsWithRetry(client, entry)
                                }.getOrElse {
                                    android.util.Log.e("MusicLibrary", "tag read failed: ${entry.name}", it)
                                    TagParser.Tags(null, null, null, 0, 0)
                                }
                                Track(
                                    url = entry.url,
                                    title = tags.title ?: entry.name.substringBeforeLast('.'),
                                    artist = tags.artist ?: "Неизвестный артист",
                                    album = tags.album ?: "Без альбома",
                                    durationMs = tags.durationMs,
                                    trackNo = tags.trackNo,
                                    modified = entry.modified,
                                    size = entry.size
                                )
                            }
                        }
                        synchronized(result) {
                            result += track
                            done++
                            if (done % 10 == 0 || done == files.size) {
                                _state.value = _state.value.copy(status = "Обработано $done из ${files.size}")
                            }
                        }
                    }
                }
                deferred.awaitAll()
            }

            val sorted = result.sortedWith(compareBy({ it.artist.lowercase() }, { it.album.lowercase() }, { it.trackNo }, { it.title.lowercase() }))
            _state.value = State(scanning = false, tracks = sorted, status = "${sorted.size} треков")
            saveCache(context, sorted)
        } catch (e: Exception) {
            _state.value = _state.value.copy(scanning = false, error = e.message ?: e.javaClass.simpleName)
        }
    }

    fun albums(tracks: List<Track>): List<AlbumGroup> =
        tracks.groupBy { it.album }
            .map { (name, list) -> AlbumGroup(name, list.first().artist, list.sortedBy { it.trackNo }) }
            .sortedBy { it.name.lowercase() }

    fun artists(tracks: List<Track>): List<ArtistGroup> =
        tracks.groupBy { it.artist }
            .map { (name, list) -> ArtistGroup(name, list.sortedWith(compareBy({ it.album }, { it.trackNo }))) }
            .sortedBy { it.name.lowercase() }

    private fun saveCache(context: Context, tracks: List<Track>) {
        runCatching {
            val arr = JSONArray()
            tracks.forEach { t ->
                arr.put(
                    JSONObject()
                        .put("url", t.url).put("title", t.title).put("artist", t.artist)
                        .put("album", t.album).put("dur", t.durationMs).put("no", t.trackNo)
                        .put("mod", t.modified).put("size", t.size)
                )
            }
            cacheFile(context).writeText(arr.toString())
        }
    }

    private fun readTagsWithRetry(client: NasClient, entry: com.homeprojector.launcher.nas.NasEntry): TagParser.Tags {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val headSize = minOf(256 * 1024, entry.size.toInt().coerceAtLeast(0))
                val head = readHead(client, entry.url, headSize)
                var tail: ByteArray? = null
                if (entry.name.endsWith(".m4a", true) || entry.name.endsWith(".mp4", true)) {
                    if (!containsMoov(head) && entry.size > head.size + 512 * 1024) {
                        tail = readTail(client, entry.url, entry.size, 512 * 1024)
                    }
                }
                return TagParser.parse(head, tail, entry.name)
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(300)
            }
        }
        throw lastError ?: IllegalStateException("tag read failed")
    }

    private fun readHead(client: NasClient, url: String, max: Int): ByteArray {
        client.openStream(url).use { input ->
            val buf = ByteArray(max)
            var total = 0
            while (total < max) {
                val n = input.read(buf, total, minOf(64 * 1024, max - total))
                if (n == -1) break
                total += n
            }
            return buf.copyOf(total)
        }
    }

    private fun readTail(client: NasClient, url: String, fileSize: Long, max: Int): ByteArray? {
        return runCatching {
            client.openStream(url).use { input ->
                val skip = fileSize - max
                var skipped = 0L
                while (skipped < skip) {
                    val s = input.skip(skip - skipped)
                    if (s <= 0) break
                    skipped += s
                }
                val buf = ByteArray(max)
                var total = 0
                while (total < max) {
                    val n = input.read(buf, total, minOf(64 * 1024, max - total))
                    if (n == -1) break
                    total += n
                }
                buf.copyOf(total)
            }
        }.getOrNull()
    }

    private fun containsMoov(head: ByteArray): Boolean {
        val needle = "moov".toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..head.size - 4) {
            for (j in 0 until 4) if (head[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}