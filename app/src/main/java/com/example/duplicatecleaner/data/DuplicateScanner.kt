package com.example.duplicatecleaner.data

import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Walks shared storage and finds byte-identical files of any type.
 * Requires all-files access (MANAGE_EXTERNAL_STORAGE on Android 11+,
 * READ/WRITE_EXTERNAL_STORAGE below).
 */
class DuplicateScanner {

    suspend fun scan(onProgress: (ScanProgress) -> Unit): List<DuplicateGroup> =
        withContext(Dispatchers.IO) {
            val root = Environment.getExternalStorageDirectory()
            onProgress(ScanProgress("Listing files…"))
            val all = listFiles(root)

            // Step 1: only files with exactly the same size can be duplicates.
            val bySize = all.groupBy { it.size }.filter { it.value.size > 1 }

            val total = bySize.values.sumOf { it.size }
            var done = 0
            val groups = mutableListOf<DuplicateGroup>()

            for ((size, sameSize) in bySize) {
                ensureActive()

                // Step 2: cheap hash of the first 64 KB to split groups quickly.
                val byQuick = HashMap<String, MutableList<MediaFile>>()
                for (file in sameSize) {
                    ensureActive()
                    val h = hash(File(file.path), QUICK_BYTES)
                    done++
                    onProgress(ScanProgress("Comparing files…", done, total))
                    if (h != null) byQuick.getOrPut(h) { mutableListOf() }.add(file)
                }

                for (candidates in byQuick.values) {
                    if (candidates.size < 2) continue

                    // Step 3: full hash, skipped when the quick hash already covered the whole file.
                    val confirmed: Collection<List<MediaFile>> = if (size <= QUICK_BYTES) {
                        listOf(candidates)
                    } else {
                        val byFull = HashMap<String, MutableList<MediaFile>>()
                        for (file in candidates) {
                            ensureActive()
                            val h = hash(File(file.path), null)
                            if (h != null) byFull.getOrPut(h) { mutableListOf() }.add(file)
                        }
                        byFull.values
                    }

                    for (g in confirmed) {
                        if (g.size >= 2) groups += DuplicateGroup(size, g.toList())
                    }
                }
            }

            groups.sortedByDescending { it.reclaimable }
        }

    private suspend fun listFiles(root: File): List<MediaFile> {
        val out = mutableListOf<MediaFile>()
        val rootPath = root.absolutePath
        root.walkTopDown()
            .onEnter { dir ->
                // Skip hidden folders, app-private data under /Android, and our own trash.
                !dir.name.startsWith(".") &&
                    dir.absolutePath != "$rootPath/Android" &&
                    dir.absolutePath != "$rootPath/${Trash.DIR_NAME}"
            }
            .forEach { f ->
                if (out.size % 500 == 0) kotlin.coroutines.coroutineContext.ensureActive()
                if (f.isFile && !f.name.startsWith(".")) {
                    val size = f.length()
                    if (size >= MIN_BYTES) {
                        out += MediaFile(
                            path = f.absolutePath,
                            name = f.name,
                            size = size,
                            kind = kindOf(f.extension),
                            dateModified = f.lastModified(),
                            folder = f.parentFile?.absolutePath?.removePrefix(rootPath)?.trimStart('/') ?: ""
                        )
                    }
                }
            }
        return out
    }

    private fun hash(file: File, limit: Long?): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            var remaining = limit ?: Long.MAX_VALUE
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val n = input.read(buffer, 0, toRead)
                if (n < 0) break
                digest.update(buffer, 0, n)
                remaining -= n
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null
    }

    private fun kindOf(ext: String): FileKind = when (ext.lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp" -> FileKind.IMAGE
        "mp4", "mkv", "mov", "avi", "3gp", "webm", "m4v" -> FileKind.VIDEO
        "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr" -> FileKind.AUDIO
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf",
        "odt", "ods", "epub", "md" -> FileKind.DOCUMENT
        else -> FileKind.OTHER
    }

    private companion object {
        const val QUICK_BYTES = 64L * 1024
        const val MIN_BYTES = 1024L // ignore tiny files such as empty or stub files
    }
}
