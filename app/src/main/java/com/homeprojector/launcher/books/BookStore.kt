package com.homeprojector.launcher.books

import android.content.Context
import com.homeprojector.launcher.Prefs
import com.homeprojector.launcher.nas.NasClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class BookItem(
    val name: String,
    val url: String,
    val size: Long,
    val modified: Long,
    val local: Boolean = false
)

data class ReaderPosition(
    val chapter: Int = 0,
    val scroll: Int = 0,
    val page: Int = 0,
    val ttsSpeed: Float = 1.0f
)

data class BookBookmark(
    val id: String,
    val chapter: Int,
    val page: Int,
    val label: String,
    val createdAt: Long
)

/** Библиотека книг: кэш файлов NAS, позиции чтения, закладки. */
object BookStore {

    fun booksDir(context: Context): File =
        File(context.filesDir, "books").apply { mkdirs() }

    fun bookKey(url: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }

    fun cachedFile(context: Context, item: BookItem): File? {
        if (item.local) return File(item.url).takeIf { it.exists() }
        val ext = item.name.substringAfterLast('.', "bin")
        val f = File(booksDir(context), "${bookKey(item.url)}.$ext")
        return f.takeIf { it.exists() && it.length() > 0 }
    }

    /** Скачивание книги с NAS с прогрессом; возвращает локальный файл. */
    suspend fun ensureDownloaded(
        context: Context,
        prefs: Prefs,
        item: BookItem,
        onProgress: (Long, Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        cachedFile(context, item)?.let { return@withContext it }
        val ext = item.name.substringAfterLast('.', "bin")
        val dest = File(booksDir(context), "${bookKey(item.url)}.$ext")
        val part = File(dest.absolutePath + ".part")
        val client = NasClient(prefs)
        val total = runCatching { client.fileLength(item.url) }.getOrDefault(item.size)
        client.openStream(item.url).use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    val toRead = if (total > 0) minOf(buf.size.toLong(), total - done).toInt() else buf.size
                    if (toRead <= 0) break
                    val n = input.read(buf, 0, toRead)
                    if (n == -1) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - lastReport >= 128 * 1024) {
                        lastReport = done
                        onProgress(done, total)
                    }
                }
                onProgress(done, total)
            }
        }
        if (dest.exists()) dest.delete()
        part.renameTo(dest)
        dest
    }

    // --- позиции и закладки (SharedPreferences, JSON) ---

    private fun sp(context: Context) =
        context.getSharedPreferences("book_reader", Context.MODE_PRIVATE)

    fun getPosition(context: Context, key: String): ReaderPosition {
        val raw = sp(context).getString("pos:$key", null) ?: return ReaderPosition()
        return runCatching {
            val o = JSONObject(raw)
            ReaderPosition(
                chapter = o.optInt("chapter"),
                scroll = o.optInt("scroll"),
                page = o.optInt("page"),
                ttsSpeed = o.optDouble("speed", 1.0).toFloat()
            )
        }.getOrDefault(ReaderPosition())
    }

    fun savePosition(context: Context, key: String, pos: ReaderPosition) {
        sp(context).edit().putString(
            "pos:$key",
            JSONObject()
                .put("chapter", pos.chapter).put("scroll", pos.scroll)
                .put("page", pos.page).put("speed", pos.ttsSpeed.toDouble()).toString()
        ).apply()
    }

    fun getBookmarks(context: Context, key: String): List<BookBookmark> {
        val raw = sp(context).getString("bm:$key", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BookBookmark(
                    id = o.getString("id"),
                    chapter = o.optInt("chapter"),
                    page = o.optInt("page"),
                    label = o.optString("label"),
                    createdAt = o.optLong("at")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun addBookmark(context: Context, key: String, bm: BookBookmark) {
        val list = getBookmarks(context, key) + bm
        saveBookmarks(context, key, list)
    }

    fun removeBookmark(context: Context, key: String, id: String) {
        saveBookmarks(context, key, getBookmarks(context, key).filterNot { it.id == id })
    }

    private fun saveBookmarks(context: Context, key: String, list: List<BookBookmark>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("chapter", it.chapter)
                    .put("page", it.page).put("label", it.label).put("at", it.createdAt)
            )
        }
        sp(context).edit().putString("bm:$key", arr.toString()).apply()
    }
}