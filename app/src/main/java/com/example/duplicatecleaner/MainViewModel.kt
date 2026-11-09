package com.example.duplicatecleaner

import android.app.Application
import android.media.MediaScannerConnection
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.duplicatecleaner.data.DuplicateGroup
import com.example.duplicatecleaner.data.DuplicateScanner
import com.example.duplicatecleaner.data.MediaFile
import com.example.duplicatecleaner.data.ScanProgress
import com.example.duplicatecleaner.data.Trash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Phase { IDLE, SCANNING, RESULTS }

/** Which copy to keep in every duplicate group. Everything else is pre-selected. */
enum class KeepRule { OLDEST, NEWEST, SHORTEST_PATH, IN_FOLDER }

data class FolderStat(val folder: String, val files: Int, val bytes: Long)

data class UiState(
    val phase: Phase = Phase.IDLE,
    val progress: ScanProgress = ScanProgress(),
    val groups: List<DuplicateGroup> = emptyList(),
    val selected: Set<String> = emptySet(),
    val keepRule: KeepRule = KeepRule.OLDEST,
    val keepFolder: String? = null,
    val trashCount: Int = 0,
    val trashBytes: Long = 0,
    val message: String? = null
) {
    val selectedBytes: Long
        get() = groups.sumOf { g -> g.files.filter { it.path in selected }.sumOf { it.size } }
}

/** Folders ranked by how much duplicate data they contain. */
fun folderStats(groups: List<DuplicateGroup>): List<FolderStat> =
    groups.flatMap { it.files }
        .groupBy { it.folder }
        .map { (folder, files) -> FolderStat(folder, files.size, files.sumOf { it.size }) }
        .sortedByDescending { it.bytes }

class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private val scanner = DuplicateScanner()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var scanJob: Job? = null

    init {
        refreshTrash()
    }

    fun startScan() {
        scanJob?.cancel()
        _state.update { UiState(phase = Phase.SCANNING, trashCount = it.trashCount, trashBytes = it.trashBytes) }
        scanJob = viewModelScope.launch {
            try {
                val groups = scanner.scan { p -> _state.update { it.copy(progress = p) } }
                _state.update {
                    it.copy(
                        phase = Phase.RESULTS,
                        groups = groups,
                        keepRule = KeepRule.OLDEST,
                        keepFolder = null,
                        selected = selectionFor(groups, KeepRule.OLDEST, null),
                        message = if (groups.isEmpty()) "No duplicates found" else null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = Phase.IDLE, message = "Scan failed: ${e.message}") }
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        _state.update { UiState(trashCount = it.trashCount, trashBytes = it.trashBytes) }
    }

    fun applyRule(rule: KeepRule, folder: String? = null) {
        _state.update { s ->
            s.copy(
                keepRule = rule,
                keepFolder = if (rule == KeepRule.IN_FOLDER) folder else null,
                selected = selectionFor(s.groups, rule, folder),
                message = null
            )
        }
    }

    fun toggle(file: MediaFile) {
        _state.update { s ->
            val group = s.groups.firstOrNull { g -> g.files.any { it.path == file.path } }
                ?: return@update s
            if (file.path in s.selected) {
                s.copy(selected = s.selected - file.path, message = null)
            } else {
                val unselectedLeft = group.files.count { it.path !in s.selected && it.path != file.path }
                if (unselectedLeft == 0) {
                    s.copy(message = "Keep at least one copy in each group")
                } else {
                    s.copy(selected = s.selected + file.path, message = null)
                }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    // ---- Trash ----

    fun refreshTrash() {
        viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) { Trash.stats() }
            _state.update { it.copy(trashCount = stats.count, trashBytes = stats.bytes) }
        }
    }

    /** Moves the selected files to the trash folder instead of deleting them. */
    fun trashSelected() {
        val paths = _state.value.selected.toList()
        if (paths.isEmpty()) return

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val r = Trash.moveToTrash(paths)
                if (r.moved.isNotEmpty()) {
                    // Tell the media library so gallery / music apps drop the old entries.
                    MediaScannerConnection.scanFile(app, r.moved.toTypedArray(), null, null)
                }
                r
            }
            val gone = result.moved.toSet()
            _state.update { s ->
                val moved = s.groups.sumOf { g -> g.files.filter { it.path in gone }.sumOf { it.size } }
                val groups = s.groups
                    .map { g -> g.copy(files = g.files.filter { it.path !in gone }) }
                    .filter { it.files.size >= 2 }
                val msg = buildString {
                    append("Moved ${gone.size} files (${formatSize(moved)}) to trash")
                    if (result.failed > 0) append(" · ${result.failed} failed")
                }
                s.copy(groups = groups, selected = s.selected - gone, message = msg)
            }
            refreshTrash()
        }
    }

    fun restoreTrash() {
        viewModelScope.launch {
            val (restored, failed) = withContext(Dispatchers.IO) {
                val r = Trash.restoreAll()
                if (r.first.isNotEmpty()) {
                    MediaScannerConnection.scanFile(app, r.first.toTypedArray(), null, null)
                }
                r
            }
            _state.update {
                it.copy(
                    message = buildString {
                        append("Restored ${restored.size} files. Rescan to refresh the list")
                        if (failed > 0) append(" · $failed failed")
                    }
                )
            }
            refreshTrash()
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { Trash.empty() }
            _state.update { it.copy(message = "Permanently deleted $removed files from trash") }
            refreshTrash()
        }
    }

    // ---- Keep rules ----

    private fun keeper(group: DuplicateGroup, rule: KeepRule, folder: String?): MediaFile =
        when (rule) {
            KeepRule.OLDEST -> group.files.minBy { it.dateModified }
            KeepRule.NEWEST -> group.files.maxBy { it.dateModified }
            KeepRule.SHORTEST_PATH -> group.files.minBy { it.path.length }
            KeepRule.IN_FOLDER ->
                group.files.filter { folder != null && it.folder == folder }
                    .minByOrNull { it.dateModified }
                    ?: group.files.minBy { it.dateModified }
        }

    private fun selectionFor(
        groups: List<DuplicateGroup>,
        rule: KeepRule,
        folder: String?
    ): Set<String> = groups.flatMap { g ->
        val keep = keeper(g, rule, folder)
        g.files.filter { it.path != keep.path }.map { it.path }
    }.toSet()
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = -1
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024
        i++
    }
    return "%.1f %s".format(value, units[i])
}
