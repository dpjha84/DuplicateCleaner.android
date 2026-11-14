package com.example.duplicatecleaner.data

enum class FileKind { IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, APP, OTHER }

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

    /** Type of the group, taken from its first file. */
    val kind: FileKind get() = files.first().kind
}

data class ScanProgress(
    val message: String = "Starting…",
    val done: Long = 0,
    val total: Long = 0,
    /** True when done/total are bytes (shown as sizes), false when they are file counts. */
    val isBytes: Boolean = false
)

fun kindOfExtension(ext: String): FileKind = when (ext.lowercase()) {
    "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp" -> FileKind.IMAGE
    "mp4", "mkv", "mov", "avi", "3gp", "webm", "m4v" -> FileKind.VIDEO
    "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr" -> FileKind.AUDIO
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf",
    "odt", "ods", "epub", "md" -> FileKind.DOCUMENT
    "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz" -> FileKind.ARCHIVE
    "apk", "xapk", "apks", "aab" -> FileKind.APP
    else -> FileKind.OTHER
}
