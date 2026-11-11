package com.homeprojector.launcher.music

import java.nio.charset.Charset

/**
 * Быстрый парсер музыкальных тегов прямо из заголовка файла (без скачивания целиком):
 * ID3v2 (MP3), FLAC (STREAMINFO + VORBIS_COMMENT), MP4/M4A (moov/udta/ilst).
 */
object TagParser {

    data class Tags(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long,
        val trackNo: Int
    )

    fun parse(head: ByteArray, tail: ByteArray?, fileName: String): Tags {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var duration = 0L
        var trackNo = 0

        try {
            when {
                head.size > 10 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> {
                    val t = parseId3(head)
                    title = t["TIT2"]; artist = t["TPE1"]; album = t["TALB"]
                    trackNo = t["TRCK"]?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
                    duration = t["TLEN"]?.trim()?.toLongOrNull() ?: 0L
                }
                head.size > 8 && String(head, 0, 4, Charsets.US_ASCII) == "fLaC" -> {
                    val t = parseFlac(head)
                    title = t["TITLE"]; artist = t["ARTIST"]; album = t["ALBUM"]
                    trackNo = t["TRACKNUMBER"]?.toIntOrNull() ?: 0
                    duration = t["__duration"]?.toLongOrNull() ?: 0L
                }
                head.size > 12 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp" -> {
                    var t = parseMp4(head, 0, head.size)
                    if (t.isEmpty() && tail != null) {
                        val idx = indexOf(tail, "moov".toByteArray(Charsets.US_ASCII))
                        if (idx >= 4) t = parseMp4(tail, idx - 4, tail.size)
                    }
                    title = t["\u00A9nam"]; artist = t["\u00A9ART"]; album = t["\u00A9alb"]
                    trackNo = t["trkn"]?.toIntOrNull() ?: 0
                    duration = t["__duration"]?.toLongOrNull() ?: 0L
                }
            }
        } catch (e: Exception) {
            // повреждённый тег — не повод падать, используем имя файла
            android.util.Log.w("TagParser", "parse failed for $fileName: ${e.javaClass.simpleName} ${e.message}")
        }

        return Tags(
            title = title?.trim()?.takeIf { it.isNotEmpty() }
                ?: fileName.substringBeforeLast('.'),
            artist = artist?.trim()?.takeIf { it.isNotEmpty() } ?: "Неизвестный артист",
            album = album?.trim()?.takeIf { it.isNotEmpty() } ?: "Без альбома",
            durationMs = duration,
            trackNo = trackNo
        )
    }

    // --- ID3v2 ---

    private fun parseId3(b: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        val ver = b[3].toInt()
        val flags = b[5].toInt()
        val tagSize = syncsafe(b, 6)
        var pos = 10
        if (flags and 0x40 != 0) { // extended header
            val extSize = if (ver >= 4) syncsafe(b, pos) else be32(b, pos)
            pos += if (ver >= 4) extSize else extSize + 4
        }
        val end = minOf(10 + tagSize, b.size)
        while (pos + 10 <= end) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            if (id[0].code == 0) break
            val fsize = if (ver >= 4) syncsafe(b, pos + 4) else be32(b, pos + 4)
            if (fsize <= 0 || pos + 10 + fsize > b.size) break
            if (id in setOf("TIT2", "TPE1", "TALB", "TRCK", "TLEN")) {
                out[id] = decodeTextFrame(b, pos + 10, fsize)
            }
            pos += 10 + fsize
        }
        return out
    }

    private fun decodeTextFrame(b: ByteArray, off: Int, len: Int): String {
        if (len < 2) return ""
        val enc = b[off].toInt()
        val data = b.copyOfRange(off + 1, off + len)
        val charset: Charset = when (enc) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(data, charset).trimEnd('\u0000').trim()
    }

    // --- FLAC ---

    private fun parseFlac(b: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        var pos = 4
        while (pos + 4 <= b.size) {
            val header = b[pos].toInt()
            val last = header and 0x80 != 0
            val type = header and 0x7F
            val len = ((b[pos + 1].toInt() and 0xFF) shl 16) or
                ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)
            val dataOff = pos + 4
            if (dataOff + len > b.size) break
            when (type) {
                0 -> { // STREAMINFO
                    if (len >= 18) {
                        val sampleRate = ((b[dataOff + 10].toInt() and 0xFF) shl 12) or
                            ((b[dataOff + 11].toInt() and 0xFF) shl 4) or
                            ((b[dataOff + 12].toInt() and 0xF0) shr 4)
                        val totalSamples = ((b[dataOff + 13].toLong() and 0x0F) shl 32) or
                            (b[dataOff + 14].toLong() and 0xFF shl 24) or
                            (b[dataOff + 15].toLong() and 0xFF shl 16) or
                            (b[dataOff + 16].toLong() and 0xFF shl 8) or
                            (b[dataOff + 17].toLong() and 0xFF)
                        if (sampleRate > 0) out["__duration"] = (totalSamples * 1000 / sampleRate).toString()
                    }
                }
                4 -> { // VORBIS_COMMENT
                    var p = dataOff
                    fun le32(): Int {
                        val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                            ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
                        p += 4
                        return v
                    }
                    val vendorLen = le32()
                    p += vendorLen
                    val count = le32()
                    repeat(count) {
                        if (p + 4 > dataOff + len) return@repeat
                        val clen = le32()
                        if (p + clen > dataOff + len) return@repeat
                        val comment = String(b, p, clen, Charsets.UTF_8)
                        p += clen
                        val eq = comment.indexOf('=')
                        if (eq > 0) out[comment.substring(0, eq).uppercase()] = comment.substring(eq + 1)
                    }
                }
            }
            pos = dataOff + len
            if (last) break
        }
        return out
    }

    // --- MP4 ---

    private fun parseMp4(b: ByteArray, start: Int, limit: Int): Map<String, String> {
        val out = HashMap<String, String>()
        walkAtoms(b, start, limit) { type, off, size ->
            when (type) {
                "moov", "udta", "ilst" -> walkAtoms(b, off, off + size) { t2, o2, s2 ->
                    handleIlstOrNested(b, t2, o2, s2, out)
                }
                "meta" -> walkAtoms(b, off + 4, off + size) { t2, o2, s2 ->
                    handleIlstOrNested(b, t2, o2, s2, out)
                }
                "mvhd" -> {
                    if (size >= 20) {
                        val ver = b[off].toInt()
                        if (ver == 1 && size >= 32) {
                            val ts = be32(b, off + 20).toLong()
                            val dur = be64(b, off + 24)
                            if (ts > 0) out["__duration"] = (dur * 1000 / ts).toString()
                        } else {
                            val ts = be32(b, off + 12).toLong()
                            val dur = be32(b, off + 16).toLong() and 0xFFFFFFFFL
                            if (ts > 0) out["__duration"] = (dur * 1000 / ts).toString()
                        }
                    }
                }
            }
        }
        return out
    }

    private fun handleIlstOrNested(b: ByteArray, type: String, off: Int, size: Int, out: HashMap<String, String>) {
        when (type) {
            "ilst" -> walkAtoms(b, off, off + size) { t, o, s -> readIlstItem(b, t, o, s, out) }
            "\u00A9nam", "\u00A9ART", "\u00A9alb", "trkn" -> readIlstItem(b, type, off, size, out)
            "udta", "moov" -> walkAtoms(b, off, off + size) { t2, o2, s2 ->
                handleIlstOrNested(b, t2, o2, s2, out)
            }
            "meta" -> walkAtoms(b, off + 4, off + size) { t2, o2, s2 ->
                handleIlstOrNested(b, t2, o2, s2, out)
            }
        }
    }

    private fun readIlstItem(b: ByteArray, type: String, off: Int, size: Int, out: HashMap<String, String>) {
        walkAtoms(b, off, off + size) { t, o, s ->
            if (t == "data" && s >= 8) {
                val valueOff = o + 8
                val valueLen = s - 8
                if (valueLen <= 0 || valueOff + valueLen > b.size) return@walkAtoms
                if (type == "trkn") {
                    if (valueLen >= 4) out[type] = (((b[valueOff + 2].toInt() and 0xFF) shl 8) or (b[valueOff + 3].toInt() and 0xFF)).toString()
                } else {
                    out[type] = String(b, valueOff, valueLen, Charsets.UTF_8).trimEnd('\u0000')
                }
            }
        }
    }

    private inline fun walkAtoms(b: ByteArray, start: Int, limit: Int, action: (String, Int, Int) -> Unit) {
        var pos = start
        while (pos + 8 <= limit && pos + 8 <= b.size) {
            val size = be32(b, pos)
            val type = String(b, pos + 4, 4, Charsets.US_ASCII)
            if (size < 8 || pos + size > limit.coerceAtMost(b.size)) break
            action(type, pos + 8, size - 8)
            pos += size
        }
    }

    // --- утилиты ---

    private fun syncsafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7F) shl 21) or ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or (b[off + 3].toInt() and 0x7F)

    private fun be32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun be64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}