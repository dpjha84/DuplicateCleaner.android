package com.example.duplicatecleaner.data

enum class FileKind { IMAGE, VIDEO, AUDIO, DOCUMENT, OTHER }

data class MediaFile(
    val path: String,
    val name: String,
    val size: Long,
    val kind: FileKind,
    val dateModified: Long,
    val folder: String
)

data class DuplicateGroup(
    val size: Long,
    val files: List<MediaFile>
) {
    val reclaimable: Long get() = size * (files.size - 1)
}

data class ScanProgress(
    val message: String = "Starting…",
    val done: Long = 0,
    val total: Long = 0,
    /** True when done/total are bytes (shown as sizes), false when they are file counts. */
    val isBytes: Boolean = false
)
