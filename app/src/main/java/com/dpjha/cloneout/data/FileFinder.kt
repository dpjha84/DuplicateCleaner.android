package com.dpjha.cloneout.data

import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** The two list-style tools next to the duplicate scan. */
enum class Tool { LARGE, WHATSAPP }

/** Where a WhatsApp file lives, as shown to the user. */
enum class WaCategory(val label: String) {
    IMAGES("Photos"), VIDEOS("Videos"), VOICE("Voice notes"), AUDIO("Audio"),
    DOCUMENTS("Documents"), GIFS("GIFs"), OTHER("Other")
}

private val WA_MEDIA = Regex("/WhatsApp( Business)?/Media/")

/** Category of a file under WhatsApp's Media folder, from the folder it sits in. */
fun waCategoryOf(path: String): WaCategory {
    val m = WA_MEDIA.find(path) ?: return WaCategory.OTHER
    val seg = path.substring(m.range.last + 1).substringBefore('/').lowercase()
    return when {
        seg.contains("voice") -> WaCategory.VOICE
        seg.contains("animated") || seg.contains("gif") -> WaCategory.GIFS
        seg.contains("image") -> WaCategory.IMAGES
        seg.contains("video") -> WaCategory.VIDEOS
        seg.contains("audio") -> WaCategory.AUDIO
        seg.contains("document") -> WaCategory.DOCUMENTS
        else -> WaCategory.OTHER
    }
}

private val WA_FOLDER = Regex("^(?:Android/media/com\\.whatsapp(?:\\.w4b)?/)?(WhatsApp(?: Business)?)/Media(?:/(.*))?$")

/**
 * Readable folder name for the UI. WhatsApp's long storage path becomes "WhatsApp › Video › Sent";
 * every other folder is shown as it is ("Internal storage" for the root).
 */
fun friendlyFolder(folder: String): String {
    val m = WA_FOLDER.matchEntire(folder) ?: return folder.ifEmpty { "Internal storage" }
    val app = m.groupValues[1]
    val rest = m.groupValues[2].split('/').filter { it.isNotEmpty() }
        .mapIndexed { i, seg -> if (i == 0) seg.removePrefix("$app ").removePrefix("WhatsApp ") else seg }
    return (listOf(app) + rest).joinToString(" › ")
}

/** True for files the user sent themselves (WhatsApp keeps them in a "Sent" subfolder). */
fun isWaSent(path: String): Boolean = path.contains("/Sent/")

/**
 * Finds big files, and WhatsApp media, on shared storage. Read-only: cleaning goes through the same
 * Trash / allowance code as duplicates. Needs all-files access, like the duplicate scan.
 */
object FileFinder {
    /** Below this a file is never "large"; the on-screen size chips filter further up from here. */
    const val LARGE_FLOOR = 50L * 1024 * 1024
    const val MAX_LARGE = 500
    private const val WA_FLOOR = 1024L
    private const val PARALLELISM = 4

    /** Everything on shared storage of at least [minBytes], biggest first (at most [MAX_LARGE]). */
    suspend fun findLarge(
        minBytes: Long = LARGE_FLOOR,
        root: File = Environment.getExternalStorageDirectory(),
        onProgress: (Int) -> Unit = {}
    ): List<MediaFile> = withContext(Dispatchers.IO) {
        val rootPath = root.absolutePath
        val found = ConcurrentLinkedQueue<MediaFile>()
        val visited = AtomicInteger(0)
        val gate = Semaphore(PARALLELISM)
        val children = root.listFiles()?.toList().orEmpty()
        coroutineScope {
            children.map { child ->
                async { gate.withPermit { walk(child, rootPath, minBytes, found, visited, onProgress) } }
            }.awaitAll()
        }
        found.sortedByDescending { it.size }.take(MAX_LARGE)
    }

    /** Every file in WhatsApp's media folders (regular and Business), biggest first. */
    suspend fun findWhatsApp(
        root: File = Environment.getExternalStorageDirectory(),
        onProgress: (Int) -> Unit = {}
    ): List<MediaFile> = withContext(Dispatchers.IO) {
        val rootPath = root.absolutePath
        val found = ConcurrentLinkedQueue<MediaFile>()
        val visited = AtomicInteger(0)
        val seen = HashSet<String>()
        for (rel in WA_ROOTS) {
            val dir = File(root, rel)
            if (!dir.isDirectory) continue
            // The legacy and the Android/media folders can point at the same place.
            if (!seen.add(runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath))) continue
            for (top in dir.listFiles().orEmpty()) {
                if (!top.isDirectory || skipWaFolder(top.name)) continue
                walkWa(top, rootPath, found, visited, onProgress)
            }
        }
        found.sortedByDescending { it.size }
    }

    private val WA_ROOTS = listOf(
        "Android/media/com.whatsapp/WhatsApp/Media",
        "WhatsApp/Media",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media",
        "WhatsApp Business/Media"
    )

    /** Stickers, profile photos and hidden status caches are not "media" worth listing. */
    private fun skipWaFolder(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith(".") || n.contains("sticker") || n.contains("profile") ||
            n.contains("wallpaper") || n.contains("backup")
    }

    private suspend fun walk(
        child: File,
        rootPath: String,
        minBytes: Long,
        out: ConcurrentLinkedQueue<MediaFile>,
        visited: AtomicInteger,
        onProgress: (Int) -> Unit
    ) {
        if (child.isFile) {
            toMediaFile(child, rootPath, minBytes)?.let { out += it }
            return
        }
        if (!shouldEnter(child, rootPath)) return
        child.walkTopDown().onEnter { shouldEnter(it, rootPath) }.forEach { f ->
            val n = visited.incrementAndGet()
            if (n % 256 == 0) {
                currentCoroutineContext().ensureActive()
                onProgress(n)
            }
            if (f.isFile) toMediaFile(f, rootPath, minBytes)?.let { out += it }
        }
    }

    private suspend fun walkWa(
        dir: File,
        rootPath: String,
        out: ConcurrentLinkedQueue<MediaFile>,
        visited: AtomicInteger,
        onProgress: (Int) -> Unit
    ) {
        dir.walkTopDown().onEnter { !it.name.startsWith(".") }.forEach { f ->
            val n = visited.incrementAndGet()
            if (n % 256 == 0) {
                currentCoroutineContext().ensureActive()
                onProgress(n)
            }
            if (f.isFile) toMediaFile(f, rootPath, WA_FLOOR)?.let { out += it }
        }
    }

    /** Skips hidden folders, app-private data under /Android, and our own trash. */
    private fun shouldEnter(dir: File, rootPath: String): Boolean =
        !dir.name.startsWith(".") &&
            dir.absolutePath != "$rootPath/Android" &&
            dir.absolutePath != "$rootPath/${Trash.DIR_NAME}"

    private fun toMediaFile(f: File, rootPath: String, minBytes: Long): MediaFile? {
        if (f.name.startsWith(".")) return null
        val size = f.length()
        if (size < minBytes) return null
        return MediaFile(
            path = f.absolutePath,
            name = f.name,
            size = size,
            kind = kindOfExtension(f.extension),
            dateModified = f.lastModified(),
            folder = f.parentFile?.absolutePath?.removePrefix(rootPath)?.trimStart('/') ?: ""
        )
    }
}
