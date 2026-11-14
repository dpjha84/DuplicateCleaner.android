package com.example.duplicatecleaner.data

import android.os.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Walks shared storage and finds byte-identical files of any type, using a small pool of
 * parallel workers so large libraries scan quickly without overheating the phone.
 *
 * Pipeline (each stage only keeps what the previous stage could not rule out):
 *  1. List files in parallel (one worker per top-level folder at a time).
 *  2. Group by exact size.
 *  3. Hash 3 samples (start, middle, end) of every same-size file, in parallel.
 *  4. Full SHA-256 only for files whose samples still match, with byte-based progress.
 *
 * Requires all-files access (MANAGE_EXTERNAL_STORAGE on Android 11+,
 * READ/WRITE_EXTERNAL_STORAGE below).
 */
class DuplicateScanner(
    private val parallelism: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
) {

    suspend fun scan(onProgress: (ScanProgress) -> Unit): List<DuplicateGroup> =
        withContext(Dispatchers.IO) {
            val root = Environment.getExternalStorageDirectory()

            // Stage 1: list files.
            onProgress(ScanProgress("Listing files…"))
            val all = listFiles(root)
            onProgress(ScanProgress("Found ${all.size} files"))

            // Stage 2: only files with exactly the same size can be duplicates.
            val candidates = all.groupBy { it.size }.filter { it.value.size > 1 }.values.flatten()
            if (candidates.isEmpty()) return@withContext emptyList()

            // Stage 3: sampled hashes in parallel.
            val sampleProgress = Reporter("Comparing files…", candidates.size.toLong(), false, onProgress)
            val samples = parallelMap(candidates) { file ->
                val s = sampleHash(File(file.path), file.size)
                sampleProgress.add(1)
                s
            }
            sampleProgress.finish()

            val confirmed = mutableListOf<DuplicateGroup>()
            val needFullCheck = mutableListOf<List<MediaFile>>()

            candidates.indices
                .filter { samples[it] != null }
                .groupBy { "${candidates[it].size}:${samples[it]!!.hash}" }
                .values
                .forEach { idxs ->
                    if (idxs.size < 2) return@forEach
                    val files = idxs.map { candidates[it] }
                    // Small files were hashed completely already, so the match is final.
                    if (samples[idxs.first()]!!.complete) {
                        confirmed += DuplicateGroup(files.first().size, files)
                    } else {
                        needFullCheck += files
                    }
                }

            // Stage 4: full hash for the remaining matches, progress measured in bytes.
            if (needFullCheck.isNotEmpty()) {
                val toVerify = needFullCheck.flatten()
                val fullProgress = Reporter(
                    "Verifying matches…", toVerify.sumOf { it.size }, true, onProgress
                )
                val hashes = parallelMap(toVerify) { file ->
                    fullHash(File(file.path)) { fullProgress.add(it.toLong()) }
                }
                fullProgress.finish()

                val hashByPath = toVerify.indices
                    .associate { toVerify[it].path to hashes[it] }
                for (group in needFullCheck) {
                    group.filter { hashByPath[it.path] != null }
                        .groupBy { hashByPath[it.path] }
                        .values
                        .forEach { same ->
                            if (same.size >= 2) confirmed += DuplicateGroup(same.first().size, same)
                        }
                }
            }

            confirmed.sortedByDescending { it.reclaimable }
        }

    // ---- Listing ----

    private suspend fun listFiles(root: File): List<MediaFile> {
        val rootPath = root.absolutePath
        val children = root.listFiles()?.toList() ?: return emptyList()
        // Each top-level entry is a unit of work, so big folders (DCIM, Download…) run side by side.
        return parallelMap(children) { child -> walk(child, rootPath) }.flatten()
    }

    private suspend fun walk(child: File, rootPath: String): List<MediaFile> {
        if (child.isFile) return listOfNotNull(toMediaFile(child, rootPath))
        if (!shouldEnter(child, rootPath)) return emptyList()

        val out = mutableListOf<MediaFile>()
        var visited = 0
        child.walkTopDown()
            .onEnter { shouldEnter(it, rootPath) }
            .forEach { f ->
                if (++visited % 256 == 0) currentCoroutineContext().ensureActive()
                if (f.isFile) toMediaFile(f, rootPath)?.let { out += it }
            }
        return out
    }

    /** Skips hidden folders, app-private data under /Android, and our own trash. */
    private fun shouldEnter(dir: File, rootPath: String): Boolean =
        !dir.name.startsWith(".") &&
            dir.absolutePath != "$rootPath/Android" &&
            dir.absolutePath != "$rootPath/${Trash.DIR_NAME}"

    private fun toMediaFile(f: File, rootPath: String): MediaFile? {
        if (f.name.startsWith(".")) return null
        val size = f.length()
        if (size < MIN_BYTES) return null // ignore tiny files such as empty or stub files
        return MediaFile(
            path = f.absolutePath,
            name = f.name,
            size = size,
            kind = kindOf(f.extension),
            dateModified = f.lastModified(),
            folder = f.parentFile?.absolutePath?.removePrefix(rootPath)?.trimStart('/') ?: ""
        )
    }

    // ---- Hashing ----

    private data class Sample(val hash: String, val complete: Boolean)

    /** Hash of start + middle + end. For small files this is a full hash (complete = true). */
    private suspend fun sampleHash(file: File, size: Long): Sample? {
        if (size <= 3 * SAMPLE_BYTES) {
            return fullHash(file, null)?.let { Sample(it, true) }
        }
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            RandomAccessFile(file, "r").use { raf ->
                val buf = ByteArray(SAMPLE_BYTES.toInt())
                val offsets = longArrayOf(0, size / 2 - SAMPLE_BYTES / 2, size - SAMPLE_BYTES)
                for (offset in offsets) {
                    currentCoroutineContext().ensureActive()
                    raf.seek(offset)
                    raf.readFully(buf)
                    digest.update(buf)
                }
            }
            Sample(digest.digest().toHex(), false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fullHash(file: File, onBytes: ((Int) -> Unit)?): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                onBytes?.invoke(n)
            }
        }
        digest.digest().toHex()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // ---- Parallelism ----

    /**
     * Runs [transform] over [items] with a fixed number of workers pulling from a shared
     * counter. Memory stays flat (no coroutine per item) and slow items don't block others.
     */
    private suspend fun <T, R> parallelMap(items: List<T>, transform: suspend (T) -> R): List<R> {
        val results = arrayOfNulls<Any?>(items.size)
        val next = AtomicInteger(0)
        coroutineScope {
            repeat(parallelism) {
                launch(Dispatchers.IO) {
                    while (true) {
                        val i = next.getAndIncrement()
                        if (i >= items.size) break
                        results[i] = transform(items[i])
                    }
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        return results.toList() as List<R>
    }

    /** Thread-safe progress counter that publishes at most ~5 updates per second. */
    private class Reporter(
        private val message: String,
        private val total: Long,
        private val isBytes: Boolean,
        private val sink: (ScanProgress) -> Unit
    ) {
        private val done = AtomicLong(0)
        private val lastEmit = AtomicLong(0)

        fun add(amount: Long) {
            val d = done.addAndGet(amount)
            val now = System.nanoTime() / 1_000_000
            val prev = lastEmit.get()
            if (now - prev >= 200 && lastEmit.compareAndSet(prev, now)) {
                sink(ScanProgress(message, d, total, isBytes))
            }
        }

        fun finish() = sink(ScanProgress(message, total, total, isBytes))
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
        const val SAMPLE_BYTES = 64L * 1024
        const val MIN_BYTES = 1024L
    }
}
