package com.homeprojector.launcher.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.homeprojector.launcher.Prefs
import com.homeprojector.launcher.nas.NasClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Загрузка изображений (SMB/локальные) с LRU-кэшем и даунсемплингом. */
object ImageLoader {

    private val cache = object : LruCache<String, ImageBitmap>(120) {}

    suspend fun load(key: String, loader: suspend () -> Bitmap?): ImageBitmap? {
        cache[key]?.let { return it }
        val bmp = withContext(Dispatchers.IO) { runCatching { loader() }.getOrNull() } ?: return null
        val ib = bmp.asImageBitmap()
        cache.put(key, ib)
        return ib
    }

    /** Декодирование картинки с NAS с ограничением размера (два прохода: bounds + decode). */
    fun decodeSmb(prefs: Prefs, url: String, maxDim: Int): Bitmap? {
        val client = NasClient(prefs)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        client.openStream(url).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return client.openStream(url).use { BitmapFactory.decodeStream(it, null, opts) }
    }

    fun decodeFile(path: String, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif")
val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "m4b", "ogg", "oga", "opus", "wav", "aac", "wma")
val BOOK_EXTENSIONS = setOf("pdf", "epub", "fb2", "txt", "zip")

fun isImageFile(name: String) = IMAGE_EXTENSIONS.contains(name.substringAfterLast('.', "").lowercase())
fun isAudioFile(name: String) = AUDIO_EXTENSIONS.contains(name.substringAfterLast('.', "").lowercase())
fun isBookFile(name: String) = BOOK_EXTENSIONS.contains(name.substringAfterLast('.', "").lowercase())