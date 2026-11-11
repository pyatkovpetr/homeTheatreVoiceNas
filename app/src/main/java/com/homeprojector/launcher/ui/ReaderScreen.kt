@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.homeprojector.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.LruCache
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.homeprojector.launcher.books.BookBookmark
import com.homeprojector.launcher.books.BookParser
import com.homeprojector.launcher.books.BookStore
import com.homeprojector.launcher.books.ParsedBook
import com.homeprojector.launcher.books.ReaderPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

@Composable
fun ReaderScreen(bookKey: String, filePath: String, title: String, onBack: () -> Unit) {
    val file = remember(filePath) { File(filePath) }
    if (file.extension.equals("pdf", true)) {
        PdfReader(bookKey, file, title, onBack)
    } else {
        TextReader(bookKey, file, title, onBack)
    }
}

// ---------------- Текстовые книги: EPUB / FB2 / TXT ----------------

private class ReaderTts(context: Context) {
    private var tts: TextToSpeech? = null
    private val main = Handler(Looper.getMainLooper())

    var onChunkDone: ((Int) -> Unit)? = null

    @Volatile
    var ready = false
        private set

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts ?: return@TextToSpeech
                val res = t.setLanguage(Locale("ru", "RU"))
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    t.setLanguage(Locale.getDefault())
                }
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        val idx = utteranceId?.removePrefix("c")?.toIntOrNull() ?: return
                        main.post { onChunkDone?.invoke(idx) }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        val idx = utteranceId?.removePrefix("c")?.toIntOrNull() ?: return
                        main.post { onChunkDone?.invoke(idx) }
                    }
                })
                ready = true
            }
        }
    }

    fun speak(text: String, index: Int, speed: Float) {
        tts?.setSpeechRate(speed)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "c$index")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextReader(bookKey: String, file: File, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedPos = remember { BookStore.getPosition(context, bookKey) }

    val parsed by produceState<ParsedBook?>(null, file) {
        value = runCatching { BookParser.parse(context, file, bookKey) }.getOrNull()
    }

    var chapterIndex by remember { mutableIntStateOf(savedPos.chapter) }
    var scrollY by remember { mutableIntStateOf(savedPos.scroll) }
    var speed by remember { mutableStateOf(savedPos.ttsSpeed) }
    var ttsPlaying by remember { mutableStateOf(false) }
    var chunkIndex by remember { mutableIntStateOf(0) }
    var showBookmarks by remember { mutableStateOf(false) }
    var bookmarks by remember { mutableStateOf(BookStore.getBookmarks(context, bookKey)) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val tts = remember { ReaderTts(context) }

    fun highlightChunk(wv: WebView?, spanIds: List<String>) {
        if (wv == null || spanIds.isEmpty()) return
        val ids = spanIds.joinToString(",") { "'$it'" }
        wv.evaluateJavascript(
            "document.querySelectorAll('.tts-active').forEach(e=>e.classList.remove('tts-active'));" +
                "[$ids].forEach(id=>{var e=document.getElementById(id); if(e){e.classList.add('tts-active');}});" +
                "var first=document.getElementById('${spanIds.first()}'); if(first){first.scrollIntoView({block:'center',behavior:'smooth'});}",
            null
        )
    }

    tts.onChunkDone = { doneIdx ->
        val book = parsed
        val chunks = book?.chapters?.getOrNull(chapterIndex)?.chunks ?: emptyList()
        if (ttsPlaying && doneIdx >= 0 && doneIdx + 1 < chunks.size) {
            chunkIndex = doneIdx + 1
            highlightChunk(webView, chunks[chunkIndex].spanIds)
            tts.speak(chunks[chunkIndex].text, chunkIndex, speed)
        } else {
            ttsPlaying = false
        }
    }

    fun savePos() {
        BookStore.savePosition(context, bookKey, ReaderPosition(chapterIndex, scrollY, 0, speed))
    }

    DisposableEffect(Unit) {
        onDispose {
            savePos()
            tts.shutdown()
        }
    }

    fun startTts(fromChunk: Int) {
        val book = parsed ?: return
        val chunks = book.chapters.getOrNull(chapterIndex)?.chunks ?: emptyList()
        if (chunks.isEmpty() || !tts.ready) return
        chunkIndex = fromChunk.coerceIn(0, chunks.lastIndex)
        ttsPlaying = true
        highlightChunk(webView, chunks[chunkIndex].spanIds)
        tts.speak(chunks[chunkIndex].text, chunkIndex, speed)
    }

    fun gotoChapter(idx: Int, scroll: Int = 0) {
        val book = parsed ?: return
        if (idx !in book.chapters.indices) return
        tts.stop()
        ttsPlaying = false
        chunkIndex = 0
        chapterIndex = idx
        scrollY = scroll
        webView?.loadUrl("file://${book.chapters[idx].htmlFile.absolutePath}")
        savePos()
    }

    ScreenFrame(
        title = parsed?.title?.takeIf { it != file.nameWithoutExtension } ?: title,
        onBack = {
            tts.stop()
            savePos()
            onBack()
        },
        actions = {
            IconButton(onClick = {
                val label = parsed?.chapters?.getOrNull(chapterIndex)?.title ?: "Глава ${chapterIndex + 1}"
                BookStore.addBookmark(
                    context, bookKey,
                    BookBookmark(UUID.randomUUID().toString(), chapterIndex, 0, label, System.currentTimeMillis())
                )
                bookmarks = BookStore.getBookmarks(context, bookKey)
            }) {
                Icon(Icons.Filled.BookmarkAdd, contentDescription = "Добавить закладку", tint = Secondary)
            }
            IconButton(onClick = { showBookmarks = true }) {
                Icon(Icons.Filled.Bookmarks, contentDescription = "Закладки", tint = OnSurface)
            }
        }
    ) {
        val book = parsed
        if (book == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Primary)
            }
            return@ScreenFrame
        }
        val safeChapter = chapterIndex.coerceIn(0, book.chapters.lastIndex.coerceAtLeast(0))

        Column(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.defaultTextEncodingName = "UTF-8"
                        settings.allowFileAccess = true
                        setBackgroundColor(0xFF0A0E14.toInt())
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onScroll(y: Int) {
                                scrollY = y
                            }
                        }, "Android")
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                view.evaluateJavascript(
                                    "window.scrollTo(0,$scrollY);" +
                                        "window.addEventListener('scroll',function(){Android.onScroll(Math.round(window.scrollY));});",
                                    null
                                )
                            }
                        }
                        webView = this
                        loadUrl("file://${book.chapters[safeChapter].htmlFile.absolutePath}")
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = { gotoChapter(safeChapter - 1) }, enabled = safeChapter > 0) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Предыдущая глава", tint = OnSurface)
                }
                Text(
                    text = "${safeChapter + 1}/${book.chapters.size} · ${book.chapters[safeChapter].title}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                listOf(0.75f, 1.0f, 1.25f, 1.5f).forEach { s ->
                    FilterChip(
                        selected = speed == s,
                        onClick = {
                            speed = s
                            savePos()
                            if (ttsPlaying) startTts(chunkIndex)
                        },
                        label = { Text("${s}x", style = MaterialTheme.typography.bodyMedium) }
                    )
                }
                IconButton(onClick = {
                    if (ttsPlaying) {
                        tts.stop()
                        ttsPlaying = false
                    } else {
                        startTts(chunkIndex)
                    }
                }) {
                    Icon(
                        if (ttsPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Озвучка",
                        tint = Primary,
                        modifier = Modifier.size(36.dp)
                    )
                }
                IconButton(onClick = { gotoChapter(safeChapter + 1) }, enabled = safeChapter < book.chapters.lastIndex) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Следующая глава", tint = OnSurface)
                }
            }
        }

        if (showBookmarks) {
            ModalBottomSheet(onDismissRequest = { showBookmarks = false }, containerColor = Surface) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Закладки", style = MaterialTheme.typography.titleLarge, color = OnSurface)
                    if (bookmarks.isEmpty()) {
                        Text(
                            "Пока нет закладок. Нажмите значок закладки вверху, чтобы сохранить место.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnSurfaceDim,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                    bookmarks.forEach { bm ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = {
                                showBookmarks = false
                                gotoChapter(bm.chapter)
                            }, modifier = Modifier.weight(1f)) {
                                Text(bm.label, color = Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = {
                                BookStore.removeBookmark(context, bookKey, bm.id)
                                bookmarks = BookStore.getBookmarks(context, bookKey)
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = ErrorColor)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- PDF ----------------

private object PdfPageCache {
    private val cache = object : LruCache<Int, Bitmap>(6) {}
    private val mutex = kotlinx.coroutines.sync.Mutex()

    suspend fun get(renderer: PdfRenderer, page: Int, widthPx: Int): Bitmap? {
        cache[page]?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            // PdfRenderer допускает только одну открытую страницу — сериализуем доступ
            mutex.withLock {
                runCatching {
                    renderer.openPage(page).use { p ->
                        val ratio = p.height.toFloat() / p.width
                        val bitmap = Bitmap.createBitmap(widthPx, (widthPx * ratio).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }.onFailure {
                    android.util.Log.e("PdfReader", "page $page render failed", it)
                }.getOrNull()
            }
        }
        if (bmp != null) cache.put(page, bmp)
        return bmp
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfReader(bookKey: String, file: File, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedPos = remember { BookStore.getPosition(context, bookKey) }

    val renderer by produceState<PdfRenderer?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                PdfRenderer(pfd)
            }.getOrNull()
        }
    }
    val r = renderer
    DisposableEffect(r) {
        onDispose { runCatching { r?.close() } }
    }

    var showBookmarks by remember { mutableStateOf(false) }
    var bookmarks by remember { mutableStateOf(BookStore.getBookmarks(context, bookKey)) }

    if (r == null) {
        ScreenFrame(title = title, onBack = onBack) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Primary)
            }
        }
        return
    }

    val pageCount = r.pageCount
    val pagerState = rememberPagerState(initialPage = savedPos.page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))) { pageCount }

    LaunchedEffect(pagerState.currentPage) {
        BookStore.savePosition(context, bookKey, ReaderPosition(0, 0, pagerState.currentPage, 1f))
    }

    ScreenFrame(
        title = title,
        onBack = onBack,
        actions = {
            Text(
                "Стр. ${pagerState.currentPage + 1} / $pageCount",
                style = MaterialTheme.typography.bodyLarge,
                color = OnSurfaceDim,
                modifier = Modifier.padding(end = 8.dp)
            )
            IconButton(onClick = {
                BookStore.addBookmark(
                    context, bookKey,
                    BookBookmark(UUID.randomUUID().toString(), 0, pagerState.currentPage, "Стр. ${pagerState.currentPage + 1}", System.currentTimeMillis())
                )
                bookmarks = BookStore.getBookmarks(context, bookKey)
            }) {
                Icon(Icons.Filled.BookmarkAdd, contentDescription = "Закладка", tint = Secondary)
            }
            IconButton(onClick = { showBookmarks = true }) {
                Icon(Icons.Filled.Bookmarks, contentDescription = "Закладки", tint = OnSurface)
            }
        }
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val bitmap by produceState<Bitmap?>(null, page) {
                value = PdfPageCache.get(r, page, 1600)
            }
            var scale by remember(page) { mutableStateOf(1f) }
            var offsetX by remember(page) { mutableStateOf(0f) }
            var offsetY by remember(page) { mutableStateOf(0f) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(page) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f; offsetY = 0f
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Страница ${page + 1}",
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

        if (showBookmarks) {
            ModalBottomSheet(onDismissRequest = { showBookmarks = false }, containerColor = Surface) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Закладки", style = MaterialTheme.typography.titleLarge, color = OnSurface)
                    bookmarks.forEach { bm ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                showBookmarks = false
                                scope.launch { pagerState.scrollToPage(bm.page.coerceIn(0, pageCount - 1)) }
                            }, modifier = Modifier.weight(1f)) {
                                Text(bm.label, color = Primary)
                            }
                            IconButton(onClick = {
                                BookStore.removeBookmark(context, bookKey, bm.id)
                                bookmarks = BookStore.getBookmarks(context, bookKey)
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Удалить", tint = ErrorColor)
                            }
                        }
                    }
                }
            }
        }
    }
}