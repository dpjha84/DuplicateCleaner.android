package com.example.duplicatecleaner.data

import android.os.Environment
import java.io.File

/**
 * Visible "recycle bin" on shared storage. Files keep their relative path inside it,
 * so they can be restored to the original location. A .nomedia file keeps gallery
 * and music apps from listing trashed items.
 */
object Trash {
    const val DIR_NAME = "DuplicateCleaner Trash"

    data class MoveResult(val moved: List<String>, val failed: Int)

    private val root: File get() = Environment.getExternalStorageDirectory()
    val dir: File get() = File(root, DIR_NAME)

    /** Everything currently in the trash, largest first. [MediaFile.folder] is the ORIGINAL folder. */
    fun list(): List<MediaFile> {
        val d = dir
        if (!d.exists()) return emptyList()
        return d.walkTopDown()
            .filter { it.isFile && it.name != ".nomedia" }
            .map { f ->
                MediaFile(
                    path = f.absolutePath,
                    name = f.name,
                    size = f.length(),
                    kind = kindOfExtension(f.extension),
                    dateModified = f.lastModified(),
                    folder = f.parentFile?.relativeTo(d)?.path?.replace('\\', '/')?.takeIf { it != "." } ?: ""
                )
            }
            .sortedByDescending { it.size }
            .toList()
    }

    fun moveToTrash(paths: List<String>): MoveResult {
        val rootPath = root.absolutePath
        val d = dir
        d.mkdirs()
        runCatching { File(d, ".nomedia").createNewFile() }

        val moved = mutableListOf<String>()
        var failed = 0
        for (p in paths) {
            val src = File(p)
            val rel = src.absolutePath.removePrefix(rootPath).trimStart('/')
            val target = uniqueTarget(File(d, rel))
            if (relocate(src, target)) moved += p else failed++
        }
        return MoveResult(moved, failed)
    }

    /** Permanently deletes files outside the trash (the "Delete permanently" clean mode). */
    fun deletePermanently(paths: List<String>): MoveResult {
        val deleted = mutableListOf<String>()
        var failed = 0
        for (p in paths) {
            if (runCatching { File(p).delete() }.getOrDefault(false)) deleted += p else failed++
        }
        return MoveResult(deleted, failed)
    }

    /** Moves the given trashed files back to their original paths. Returns restored paths and failure count. */
    fun restore(trashPaths: List<String>): Pair<List<String>, Int> {
        val d = dir
        val restored = mutableListOf<String>()
        var failed = 0
        for (p in trashPaths) {
            val f = File(p)
            val rel = f.relativeTo(d).path
            val dest = uniqueTarget(File(root, rel))
            if (relocate(f, dest)) restored += dest.absolutePath else failed++
        }
        pruneEmptyDirs()
        return restored to failed
    }

    /** Permanently deletes the given trashed files. Returns how many were removed. */
    fun delete(trashPaths: List<String>): Int {
        var removed = 0
        for (p in trashPaths) if (File(p).delete()) removed++
        pruneEmptyDirs()
        return removed
    }

    private fun pruneEmptyDirs() {
        val d = dir
        if (!d.exists()) return
        d.walkBottomUp().filter { it != d && it.isDirectory }.forEach { it.delete() }
    }

    private fun relocate(src: File, target: File): Boolean {
        target.parentFile?.mkdirs()
        if (src.renameTo(target)) return true
        return try {
            src.copyTo(target)
            if (src.delete()) true else {
                target.delete()
                false
            }
        } catch (e: Exception) {
            target.delete()
            false
        }
    }

    private fun uniqueTarget(file: File): File {
        if (!file.exists()) return file
        val base = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isEmpty()) "" else ".$it" }
        var n = 1
        while (true) {
            val candidate = File(file.parentFile, "$base ($n)$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }
}
