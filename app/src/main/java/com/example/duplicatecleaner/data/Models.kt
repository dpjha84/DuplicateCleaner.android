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
    val done: Int = 0,
    val total: Int = 0
)
