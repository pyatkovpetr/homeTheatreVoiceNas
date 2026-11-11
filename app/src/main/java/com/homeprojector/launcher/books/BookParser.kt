package com.homeprojector.launcher.books

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.zip.ZipFile

data class TtsChunk(val spanIds: List<String>, val text: String)
data class BookChapter(val title: String, val htmlFile: File, val chunks: List<TtsChunk>)
data class ParsedBook(val title: String, val chapters: List<BookChapter>)

/**
 * Парсеры книг: EPUB (порядок глав из OPF spine, сохранение картинок и стилей),
 * FB2 (включая .fb2.zip и встроенные изображения), TXT.
 * Результат — HTML-главы с размеченными для TTS фрагментами.
 */
object BookParser {

    private const val CSS = """
        body { background:#0A0E14; color:#E6EDF3; font-family:sans-serif;
               font-size:20px; line-height:1.65; max-width:920px;
               margin:0 auto; padding:24px 28px 96px; }
        img { max-width:100%; height:auto; }
        a { color:#4FD1C5; }
        h1,h2,h3 { color:#4FD1C5; line-height:1.3; }
        blockquote { border-left:3px solid #3A4656; margin-left:0; padding-left:16px; color:#9AA7B8; }
        .tts-active { background:rgba(79,209,197,0.22); border-radius:4px; }
    """

    suspend fun parse(context: Context, file: File, key: String): ParsedBook =
        withContext(Dispatchers.IO) {
            val outDir = File(BookStore.booksDir(context), key).apply { mkdirs() }
            when (file.extension.lowercase()) {
                "epub" -> parseEpub(file, outDir)
                "fb2" -> parseFb2(file, outDir)
                "zip" -> parseFb2Zip(file, outDir)
                else -> parseTxt(file, outDir)
            }
        }

    // ---------- EPUB ----------

    private fun parseEpub(file: File, outDir: File): ParsedBook {
        val zip = ZipFile(file)
        var bookTitle = file.nameWithoutExtension
        var opfPath = ""
        val manifest = HashMap<String, Pair<String, String>>() // id -> (href, mediaType)
        val spine = mutableListOf<String>()

        zip.use { z ->
            val container = z.getEntry("META-INF/container.xml")
            if (container != null) {
                val text = z.getInputStream(container).readBytes().toString(Charsets.UTF_8)
                opfPath = Regex("full-path=\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: ""
            }
            // распаковка с защитой от zip-slip
            z.entries().asSequence().forEach { e ->
                if (e.isDirectory) return@forEach
                val target = File(outDir, e.name)
                if (!target.canonicalPath.startsWith(outDir.canonicalPath + File.separator)) return@forEach
                target.parentFile?.mkdirs()
                z.getInputStream(e).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
            if (opfPath.isNotEmpty()) {
                val opfFile = File(outDir, opfPath)
                if (opfFile.exists()) {
                    val parser = newParser(opfFile.inputStream())
                    var event = parser.eventType
                    var inMetadata = false
                    while (event != XmlPullParser.END_DOCUMENT) {
                        when (event) {
                            XmlPullParser.START_TAG -> when (parser.name) {
                                "metadata" -> inMetadata = true
                                "title" -> if (inMetadata) bookTitle = parser.nextText() ?: bookTitle
                                "item" -> {
                                    val id = parser.getAttributeValue(null, "id") ?: ""
                                    val href = parser.getAttributeValue(null, "href") ?: ""
                                    val type = parser.getAttributeValue(null, "media-type") ?: ""
                                    manifest[id] = href to type
                                }
                                "itemref" -> parser.getAttributeValue(null, "idref")?.let { spine.add(it) }
                            }
                            XmlPullParser.END_TAG -> if (parser.name == "metadata") inMetadata = false
                        }
                        event = parser.next()
                    }
                }
            }
        }

        val opfDir = File(outDir, opfPath).parentFile ?: outDir
        val chapterFiles = spine.mapNotNull { id ->
            val (href, type) = manifest[id] ?: return@mapNotNull null
            if (!type.contains("html") && !type.contains("xml")) return@mapNotNull null
            File(opfDir, href).takeIf { it.exists() }
        }.ifEmpty {
            outDir.walkTopDown().filter { it.extension.lowercase() in setOf("html", "xhtml", "htm") }
                .sortedBy { it.name }.toList()
        }

        val chapters = chapterFiles.mapIndexed { i, f ->
            makeChapterFromHtml(f, "Глава ${i + 1}")
        }
        return ParsedBook(bookTitle, chapters.ifEmpty { emptyChapters(outDir, bookTitle) })
    }

    // ---------- FB2 ----------

    private fun parseFb2Zip(file: File, outDir: File): ParsedBook {
        var fb2: File? = null
        ZipFile(file).use { z ->
            z.entries().asSequence().forEach { e ->
                if (e.isDirectory) return@forEach
                val target = File(outDir, e.name.substringAfterLast('/'))
                target.parentFile?.mkdirs()
                z.getInputStream(e).use { input -> target.outputStream().use { input.copyTo(it) } }
                if (target.extension.equals("fb2", true)) fb2 = target
            }
        }
        val f = fb2 ?: return ParsedBook(file.nameWithoutExtension, emptyChapters(outDir, file.nameWithoutExtension))
        return parseFb2(f, outDir)
    }

    private fun parseFb2(file: File, outDir: File): ParsedBook {
        // проход 1: встроенные изображения
        val binaries = HashMap<String, Pair<String, String>>() // id -> (contentType, base64)
        runCatching {
            val parser = newParser(file.inputStream())
            var event = parser.eventType
            var currentId: String? = null
            var currentType = ""
            val sb = StringBuilder()
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> if (parser.name == "binary") {
                        currentId = parser.getAttributeValue(null, "id")
                        currentType = parser.getAttributeValue(null, "content-type") ?: "image/jpeg"
                        sb.clear()
                    }
                    XmlPullParser.TEXT -> if (currentId != null) sb.append(parser.text)
                    XmlPullParser.END_TAG -> if (parser.name == "binary" && currentId != null) {
                        binaries[currentId!!] = currentType to sb.toString()
                        currentId = null
                    }
                }
                event = parser.next()
            }
        }
        binaries.forEach { (id, pair) ->
            val (type, b64) = pair
            val ext = type.substringAfterLast('/', "jpeg").replace("jpeg", "jpg")
            runCatching {
                File(outDir, "img_$id.$ext").writeBytes(Base64.decode(b64.filterNot { it.isWhitespace() }, Base64.DEFAULT))
            }
        }

        // проход 2: текст → HTML по секциям
        var bookTitle = file.nameWithoutExtension
        val chaptersHtml = mutableListOf<StringBuilder>()
        var current = StringBuilder()
        var sectionDepth = 0
        var inDescription = false
        var inBookTitle = false
        var titleBuf = StringBuilder()

        val parser = newParser(file.inputStream())
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "description" -> inDescription = true
                    "book-title" -> if (inDescription) { inBookTitle = true; titleBuf.clear() }
                    "body" -> { current = StringBuilder(); chaptersHtml.add(current) }
                    "section" -> {
                        if (sectionDepth == 0 && current.isNotEmpty()) {
                            current = StringBuilder(); chaptersHtml.add(current)
                        }
                        sectionDepth++
                    }
                    "title" -> current.append("<h2>")
                    "p" -> current.append("<p>")
                    "emphasis" -> current.append("<em>")
                    "strong" -> current.append("<strong>")
                    "cite", "epigraph" -> current.append("<blockquote>")
                    "empty-line" -> current.append("<br/>")
                    "image" -> {
                        val href = parser.getAttributeValue(null, "href")
                            ?: parser.getAttributeValue("http://www.w3.org/1999/xlink", "href") ?: ""
                        val src = resolveFb2Image(href, binaries, outDir)
                        if (src != null) current.append("<img src=\"$src\"/>")
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inBookTitle) titleBuf.append(parser.text)
                    else if (sectionDepth > 0 || current.isNotEmpty()) current.append(escapeHtml(parser.text))
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "description" -> inDescription = false
                    "book-title" -> { if (inBookTitle) { bookTitle = titleBuf.toString().trim(); inBookTitle = false } }
                    "section" -> sectionDepth--
                    "title" -> current.append("</h2>")
                    "p" -> current.append("</p>")
                    "emphasis" -> current.append("</em>")
                    "strong" -> current.append("</strong>")
                    "cite", "epigraph" -> current.append("</blockquote>")
                }
            }
            event = parser.next()
        }

        val chapters = chaptersHtml.filter { it.isNotBlank() }.mapIndexed { i, html ->
            val f = File(outDir, "ch$i.html")
            val (wrapped, chunks) = wrapTtsSpans(html.toString())
            f.writeText(htmlDoc(wrapped))
            BookChapter(firstHeading(wrapped) ?: "Глава ${i + 1}", f, chunks)
        }
        return ParsedBook(bookTitle, chapters.ifEmpty { emptyChapters(outDir, bookTitle) })
    }

    private fun resolveFb2Image(href: String, binaries: Map<String, Pair<String, String>>, outDir: File): String? {
        if (href.isBlank()) return null
        val id = href.removePrefix("#")
        val bin = binaries[id]
        if (bin != null) {
            val ext = bin.first.substringAfterLast('/', "jpeg").replace("jpeg", "jpg")
            return "img_$id.$ext"
        }
        // внешняя ссылка (fb2.zip): файл уже распакован
        val name = href.substringAfterLast('/')
        return name.takeIf { File(outDir, it).exists() }
    }

    // ---------- TXT ----------

    private fun parseTxt(file: File, outDir: File): ParsedBook {
        val bytes = file.readBytes()
        var text = bytes.toString(Charsets.UTF_8)
        if (text.contains('\uFFFD')) text = bytes.toString(charset("windows-1251"))
        val paragraphs = text.split(Regex("\r?\n")).filter { it.isNotBlank() }
        val chapters = mutableListOf<BookChapter>()
        var buf = StringBuilder()
        var count = 0
        fun flush() {
            if (buf.isEmpty()) return
            val f = File(outDir, "ch$count.html")
            val (wrapped, chunks) = wrapTtsSpans(buf.toString())
            f.writeText(htmlDoc(wrapped))
            chapters.add(BookChapter("Часть ${count + 1}", f, chunks))
            count++
            buf = StringBuilder()
        }
        paragraphs.forEach { p ->
            buf.append("<p>").append(escapeHtml(p.trim())).append("</p>")
            if (buf.length > 10000) flush()
        }
        flush()
        return ParsedBook(file.nameWithoutExtension, chapters.ifEmpty { emptyChapters(outDir, file.nameWithoutExtension) })
    }

    // ---------- общее ----------

    private fun makeChapterFromHtml(f: File, fallbackTitle: String): BookChapter {
        val raw = f.readText()
        val body = Regex("<body[^>]*>([\\s\\S]*)</body>", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.get(1) ?: raw
        val (wrapped, chunks) = wrapTtsSpans(body)
        // .html обязательно: WebView рендерит .xhtml как XML-дерево
        val htmlFile = File(f.parentFile, f.nameWithoutExtension + ".html")
        htmlFile.writeText(htmlDoc(wrapped))
        return BookChapter(firstHeading(wrapped) ?: fallbackTitle, htmlFile, chunks)
    }

    /** Оборачивает блочные элементы в span'ы для TTS-подсветки и режет на куски ≤ 2200 символов. */
    private fun wrapTtsSpans(bodyHtml: String): Pair<String, List<TtsChunk>> {
        val chunks = mutableListOf<TtsChunk>()
        val pendingIds = mutableListOf<String>()
        val pendingText = StringBuilder()
        var counter = 0

        fun flush() {
            if (pendingText.isBlank()) { pendingIds.clear(); return }
            splitLongText(pendingText.toString()).forEach { part ->
                chunks.add(TtsChunk(pendingIds.toList(), part))
            }
            pendingIds.clear()
            pendingText.clear()
        }

        val blockRegex = Regex("<(p|h[1-6]|li)(\\s[^>]*)?>([\\s\\S]*?)</\\1>", RegexOption.IGNORE_CASE)
        val result = blockRegex.replace(bodyHtml) { m ->
            val tag = m.groupValues[1]
            val attrs = m.groupValues[2]
            val inner = m.groupValues[3]
            val text = stripTags(inner).trim()
            if (text.isEmpty()) return@replace m.value
            val spanId = "t${counter++}"
            if (pendingText.length + text.length > 2200 && pendingText.isNotEmpty()) flush()
            pendingIds.add(spanId)
            pendingText.append(text).append(' ')
            "<$tag$attrs><span id=\"$spanId\">$inner</span></$tag>"
        }
        flush()
        return result to chunks
    }

    private fun splitLongText(text: String): List<String> {
        if (text.length <= 3500) return listOf(text)
        val parts = mutableListOf<String>()
        val sentences = text.split(Regex("(?<=[.!?…])\\s+"))
        val buf = StringBuilder()
        sentences.forEach { s ->
            if (buf.length + s.length > 3500 && buf.isNotEmpty()) {
                parts.add(buf.toString()); buf.clear()
            }
            if (s.length > 3500) {
                if (buf.isNotEmpty()) { parts.add(buf.toString()); buf.clear() }
                var i = 0
                while (i < s.length) {
                    parts.add(s.substring(i, minOf(i + 3500, s.length)))
                    i += 3500
                }
            } else {
                buf.append(s).append(' ')
            }
        }
        if (buf.isNotEmpty()) parts.add(buf.toString())
        return parts
    }

    private fun firstHeading(html: String): String? =
        Regex("<h[1-3][^>]*>([\\s\\S]*?)</h[1-3]>", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let { stripTags(it).trim() }?.takeIf { it.isNotEmpty() && it.length < 120 }

    private fun htmlDoc(body: String): String =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>" +
            "<style>$CSS</style></head><body>$body</body></html>"

    private fun emptyChapters(outDir: File, title: String): List<BookChapter> {
        val f = File(outDir, "ch0.html")
        f.writeText(htmlDoc("<p>Не удалось извлечь текст книги.</p>"))
        return listOf(BookChapter(title, f, emptyList()))
    }

    private fun newParser(input: java.io.InputStream): XmlPullParser {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(input, null)
        return parser
    }

    fun stripTags(html: String): String =
        html.replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}