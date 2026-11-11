package com.homeprojector.launcher.nas

import com.homeprojector.launcher.Prefs
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthentication
import jcifs.smb.SmbFile
import java.util.Properties

data class NasEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
    val url: String
)

/** Доступ к видеотеке NAS по SMB2/3 (jcifs-ng). */
class NasClient(private val prefs: Prefs) {

    fun newContext(): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.connTimeout", "10000")
            setProperty("jcifs.smb.client.responseTimeout", "20000")
            setProperty("jcifs.smb.client.soTimeout", "20000")
            setProperty("jcifs.smb.client.dfs.disabled", "true")
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
        }
        val base = BaseContext(PropertyConfiguration(props))
        val user = prefs.nasUser
        // Пустое имя пользователя = анонимный/гостевой доступ
        val creds = NtlmPasswordAuthentication(base, prefs.nasDomain, user, prefs.nasPassword)
        return base.withCredentials(creds)
    }

    /** Корневой URL видеотеки: smb://host/share/стартовая-папка/ */
    fun rootUrl(): String {
        val host = prefs.nasHost.ifBlank { "192.168.1.100" }
        val share = prefs.nasShare.ifBlank { "video" }
        val start = prefs.nasPath.trim('/')
        return buildString {
            append("smb://").append(host).append('/').append(share).append('/')
            if (start.isNotEmpty()) append(start).append('/')
        }
    }

    fun list(url: String): List<NasEntry> {
        val dir = SmbFile(url, newContext())
        val children = dir.listFiles() ?: emptyArray()
        return children
            .filter { it.isDirectory || com.homeprojector.launcher.util.isVideoFile(it.name) }
            .map {
                NasEntry(
                    name = it.name.trimEnd('/'),
                    isDirectory = it.isDirectory,
                    size = if (it.isFile) it.length() else 0L,
                    modified = it.lastModified(),
                    url = it.toURL().toString()
                )
            }
            .sortedWith(compareByDescending<NasEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    /** Проверка подключения: возвращает число элементов в корне. */
    fun testConnection(): Int = list(rootUrl()).size

    /** URL папки внутри общего ресурса: smb://host/share/path/ */
    fun folderUrl(path: String): String {
        val host = prefs.nasHost.ifBlank { "192.168.1.100" }
        val share = prefs.nasShare.ifBlank { "video" }
        val p = path.trim('/')
        return "smb://$host/$share/" + if (p.isNotEmpty()) "$p/" else ""
    }

    /** Листинг всех файлов (не только видео). */
    fun listAll(url: String): List<NasEntry> {
        val dir = SmbFile(url, newContext())
        val children = dir.listFiles() ?: emptyArray()
        return children
            .map {
                NasEntry(
                    name = it.name.trimEnd('/'),
                    isDirectory = it.isDirectory,
                    size = if (it.isFile) it.length() else 0L,
                    modified = it.lastModified(),
                    url = it.toURL().toString()
                )
            }
            .sortedWith(compareByDescending<NasEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    /** Рекурсивный поиск по имени (BFS, ограничение глубины и числа результатов). */
    fun search(rootUrl: String, query: String, maxResults: Int = 200, maxDepth: Int = 5): List<NasEntry> {
        val q = query.lowercase()
        val ctx = newContext()
        val results = mutableListOf<NasEntry>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(rootUrl to 0)
        while (queue.isNotEmpty() && results.size < maxResults) {
            val (url, depth) = queue.removeFirst()
            val children = try {
                SmbFile(url, ctx).listFiles() ?: emptyArray()
            } catch (e: Exception) {
                continue
            }
            for (f in children) {
                val name = f.name.trimEnd('/')
                if (name.startsWith(".")) continue
                if (name.lowercase().contains(q)) {
                    results += NasEntry(
                        name = name,
                        isDirectory = f.isDirectory,
                        size = if (f.isFile) f.length() else 0L,
                        modified = f.lastModified(),
                        url = f.toURL().toString()
                    )
                    if (results.size >= maxResults) break
                }
                if (f.isDirectory && depth < maxDepth) {
                    queue.add(f.toURL().toString() to depth + 1)
                }
            }
        }
        return results.sortedWith(compareByDescending<NasEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    /** Рекурсивный сбор файлов с заданными расширениями. */
    fun collectFiles(rootUrl: String, extensions: Set<String>, maxFiles: Int = 5000, maxDepth: Int = 6): List<NasEntry> {
        val ctx = newContext()
        val results = mutableListOf<NasEntry>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(rootUrl to 0)
        while (queue.isNotEmpty() && results.size < maxFiles) {
            val (url, depth) = queue.removeFirst()
            val children = try {
                SmbFile(url, ctx).listFiles() ?: emptyArray()
            } catch (e: Exception) {
                continue
            }
            for (f in children) {
                val name = f.name.trimEnd('/')
                if (name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (depth < maxDepth) queue.add(f.toURL().toString() to depth + 1)
                } else {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (extensions.contains(ext)) {
                        results += NasEntry(name, false, f.length(), f.lastModified(), f.toURL().toString())
                        if (results.size >= maxFiles) break
                    }
                }
            }
        }
        return results
    }

    fun openStream(url: String): java.io.InputStream = SmbFile(url, newContext()).inputStream

    fun fileLength(url: String): Long = SmbFile(url, newContext()).length()
}