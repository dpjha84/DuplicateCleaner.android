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

    data class Stats(val count: Int, val bytes: Long)
    data class MoveResult(val moved: List<String>, val failed: Int)

    private val root: File get() = Environment.getExternalStorageDirectory()
    val dir: File get() = File(root, DIR_NAME)

    fun stats(): Stats {
        val d = dir
        if (!d.exists()) return Stats(0, 0)
        var count = 0
        var bytes = 0L
        d.walkTopDown().filter { it.isFile && it.name != ".nomedia" }.forEach {
            count++
            bytes += it.length()
        }
        return Stats(count, bytes)
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

    /** Moves everything back to its original path. Returns restored paths and failure count. */
    fun restoreAll(): Pair<List<String>, Int> {
        val d = dir
        if (!d.exists()) return emptyList<String>() to 0
        val restored = mutableListOf<String>()
        var failed = 0
        val files = d.walkTopDown().filter { it.isFile && it.name != ".nomedia" }.toList()
        for (f in files) {
            val rel = f.relativeTo(d).path
            val dest = uniqueTarget(File(root, rel))
            if (relocate(f, dest)) restored += dest.absolutePath else failed++
        }
        pruneEmptyDirs()
        return restored to failed
    }

    /** Permanently deletes everything in the trash. Returns number of files removed. */
    fun empty(): Int {
        val d = dir
        if (!d.exists()) return 0
        var removed = 0
        d.walkTopDown().filter { it.isFile && it.name != ".nomedia" }.toList().forEach {
            if (it.delete()) removed++
        }
        pruneEmptyDirs()
        return removed
    }

    private fun pruneEmptyDirs() {
        val d = dir
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
