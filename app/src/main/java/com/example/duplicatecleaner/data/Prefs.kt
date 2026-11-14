package com.example.duplicatecleaner.data

import android.content.Context

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** What "Clean up" does: move to the recoverable Trash, or delete straight away. */
enum class CleanMode { TRASH, DELETE }

/** User settings and lifetime stats, stored on the device. */
class Prefs(context: Context) {

    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var scanKinds: Set<FileKind>
        get() = p.getString(KEY_KINDS, null)
            ?.split(',')
            ?.mapNotNull { n -> FileKind.entries.firstOrNull { it.name == n } }
            ?.toSet()
            ?: FileKind.entries.toSet()
        set(v) = p.edit().putString(KEY_KINDS, v.joinToString(",") { it.name }).apply()

    var minSizeBytes: Long
        get() = p.getLong(KEY_MIN_SIZE, 1024L)
        set(v) = p.edit().putLong(KEY_MIN_SIZE, v).apply()

    var themeMode: ThemeMode
        get() = ThemeMode.entries.firstOrNull { it.name == p.getString(KEY_THEME, null) } ?: ThemeMode.SYSTEM
        set(v) = p.edit().putString(KEY_THEME, v.name).apply()

    var dynamicColor: Boolean
        get() = p.getBoolean(KEY_DYNAMIC, false)
        set(v) = p.edit().putBoolean(KEY_DYNAMIC, v).apply()

    var cleanMode: CleanMode
        get() = CleanMode.entries.firstOrNull { it.name == p.getString(KEY_CLEAN, null) } ?: CleanMode.TRASH
        set(v) = p.edit().putString(KEY_CLEAN, v.name).apply()

    var lifetimeFreedBytes: Long
        get() = p.getLong(KEY_FREED, 0L)
        private set(v) = p.edit().putLong(KEY_FREED, v).apply()

    var lifetimeFiles: Int
        get() = p.getInt(KEY_FILES, 0)
        private set(v) = p.edit().putInt(KEY_FILES, v).apply()

    /** Records space that was really freed (permanent deletes and emptied trash). */
    fun addFreed(bytes: Long, files: Int) {
        lifetimeFreedBytes += bytes
        lifetimeFiles += files
    }

    private companion object {
        const val KEY_KINDS = "kinds"
        const val KEY_MIN_SIZE = "min_size"
        const val KEY_THEME = "theme"
        const val KEY_DYNAMIC = "dynamic"
        const val KEY_CLEAN = "clean_mode"
        const val KEY_FREED = "freed_bytes"
        const val KEY_FILES = "freed_files"
    }
}
