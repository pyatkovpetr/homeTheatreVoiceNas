package com.homeprojector.launcher.util

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import java.util.Locale

val VIDEO_EXTENSIONS = setOf(
    "mkv", "mp4", "m4v", "avi", "mov", "wmv", "flv", "webm",
    "ts", "m2ts", "mts", "mpg", "mpeg", "3gp", "vob", "ogv"
)

fun isVideoFile(name: String): Boolean =
    VIDEO_EXTENSIONS.contains(name.substringAfterLast('.', "").lowercase(Locale.ROOT))

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024
        i++
    }
    return if (i == 0) "$bytes Б" else String.format(Locale.US, "%.1f %s", value, units[i])
}

fun formatDuration(millis: Long): String {
    if (millis <= 0) return ""
    val totalSec = millis / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

fun Drawable.toImageBitmap(): ImageBitmap {
    val w = intrinsicWidth.coerceAtLeast(1)
    val h = intrinsicHeight.coerceAtLeast(1)
    val bitmap = createBitmap(w, h)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap.asImageBitmap()
}