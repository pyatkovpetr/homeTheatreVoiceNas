package com.homeprojector.launcher.download

import android.content.Context
import android.os.Environment
import com.homeprojector.launcher.Prefs
import com.homeprojector.launcher.nas.NasClient
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class DlStatus { RUNNING, PAUSED, DONE, ERROR }

data class DlTask(
    val id: String,
    val url: String,
    val fileName: String,
    val destPath: String,
    val total: Long,
    val downloaded: Long,
    val status: DlStatus,
    val error: String? = null
)

/**
 * Менеджер загрузок SMB → локальное хранилище.
 * Прогресс в реальном времени, пауза/продолжение (докачка с места остановки), отмена.
 */
object DownloadManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _tasks = MutableStateFlow<List<DlTask>>(emptyList())
    val tasks: StateFlow<List<DlTask>> = _tasks

    private val pauseFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun destDir(): File {
        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(appContext.filesDir, "downloads")
        dir.mkdirs()
        return dir
    }

    fun enqueue(url: String, fileName: String): DlTask {
        val prefs = Prefs.get(appContext)
        val existing = _tasks.value.firstOrNull { it.url == url && it.status != DlStatus.ERROR }
        if (existing != null) return existing
        val dest = File(destDir(), fileName)
        val task = DlTask(
            id = UUID.randomUUID().toString(),
            url = url,
            fileName = fileName,
            destPath = dest.absolutePath,
            total = runCatching { NasClient(prefs).fileLength(url) }.getOrDefault(0L),
            downloaded = 0L,
            status = DlStatus.RUNNING
        )
        _tasks.value = _tasks.value + task
        start(task)
        return task
    }

    fun pause(id: String) {
        pauseFlags[id]?.set(true)
    }

    fun resume(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.status != DlStatus.PAUSED && task.status != DlStatus.ERROR) return
        update(id) { it.copy(status = DlStatus.RUNNING, error = null) }
        start(task.copy(status = DlStatus.RUNNING))
    }

    fun cancel(id: String) {
        pauseFlags[id]?.set(true)
        jobs[id]?.cancel()
        val task = _tasks.value.firstOrNull { it.id == id }
        if (task != null) {
            runCatching { File(task.destPath + ".part").delete() }
        }
        _tasks.value = _tasks.value.filterNot { it.id == id }
        pauseFlags.remove(id)
        jobs.remove(id)
    }

    fun clearFinished() {
        _tasks.value = _tasks.value.filter { it.status != DlStatus.DONE }
    }

    private fun update(id: String, transform: (DlTask) -> DlTask) {
        _tasks.value = _tasks.value.map { if (it.id == id) transform(it) else it }
    }

    private fun start(task: DlTask) {
        val flag = AtomicBoolean(false)
        pauseFlags[task.id] = flag
        jobs[task.id] = scope.launch {
            val prefs = Prefs.get(appContext)
            val part = File(task.destPath + ".part")
            try {
                val offset = if (part.exists()) part.length() else 0L
                val raf = SmbRandomAccessFile(SmbFile(task.url, NasClient(prefs).newContext()), "r")
                val out = RandomAccessFile(part, "rw")
                try {
                    raf.seek(offset)
                    out.seek(offset)
                    var downloaded = offset
                    var lastReport = offset
                    val buf = ByteArray(64 * 1024)
                    while (!flag.get()) {
                        val toRead = if (task.total > 0) minOf(buf.size.toLong(), task.total - downloaded).toInt() else buf.size
                        if (toRead <= 0) break
                        val n = raf.read(buf, 0, toRead)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        downloaded += n
                        if (downloaded - lastReport >= 256 * 1024) {
                            lastReport = downloaded
                            update(task.id) { it.copy(downloaded = downloaded) }
                        }
                    }
                    if (flag.get()) {
                        update(task.id) { it.copy(downloaded = downloaded, status = DlStatus.PAUSED) }
                    } else {
                        out.close()
                        raf.close()
                        val dest = File(task.destPath)
                        if (dest.exists()) dest.delete()
                        part.renameTo(dest)
                        update(task.id) { it.copy(downloaded = downloaded, total = downloaded, status = DlStatus.DONE) }
                    }
                } finally {
                    runCatching { out.close() }
                    runCatching { raf.close() }
                }
            } catch (e: Exception) {
                if (flag.get()) {
                    update(task.id) { it.copy(status = DlStatus.PAUSED) }
                } else {
                    update(task.id) { it.copy(status = DlStatus.ERROR, error = e.message ?: e.javaClass.simpleName) }
                }
            }
        }
    }
}