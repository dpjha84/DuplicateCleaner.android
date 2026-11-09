package com.example.duplicatecleaner

import android.app.Application
import android.media.MediaScannerConnection
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.duplicatecleaner.data.DuplicateGroup
import com.example.duplicatecleaner.data.DuplicateScanner
import com.example.duplicatecleaner.data.MediaFile
import com.example.duplicatecleaner.data.ScanProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Phase { IDLE, SCANNING, RESULTS }

data class UiState(
    val phase: Phase = Phase.IDLE,
    val progress: ScanProgress = ScanProgress(),
    val groups: List<DuplicateGroup> = emptyList(),
    val selected: Set<String> = emptySet(),
    val message: String? = null
) {
    val selectedBytes: Long
        get() = groups.sumOf { g -> g.files.filter { it.path in selected }.sumOf { it.size } }
}

class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private val scanner = DuplicateScanner()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var scanJob: Job? = null

    fun startScan() {
        scanJob?.cancel()
        _state.value = UiState(phase = Phase.SCANNING)
        scanJob = viewModelScope.launch {
            try {
                val groups = scanner.scan { p -> _state.update { it.copy(progress = p) } }
                // Default: keep the oldest copy in each group, pre-select the rest.
                val selected = groups.flatMap { g ->
                    g.files.sortedBy { it.dateModified }.drop(1).map { it.path }
                }.toSet()
                _state.value = UiState(
                    phase = Phase.RESULTS,
                    groups = groups,
                    selected = selected,
                    message = if (groups.isEmpty()) "No duplicates found" else null
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState(phase = Phase.IDLE, message = "Scan failed: ${e.message}")
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        _state.value = UiState()
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

    fun deleteSelected() {
        val paths = _state.value.selected.toList()
        if (paths.isEmpty()) return

        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                val ok = paths.filter { runCatching { File(it).delete() }.getOrDefault(false) }
                // Tell the media library so gallery / music apps drop the deleted entries.
                if (ok.isNotEmpty()) {
                    MediaScannerConnection.scanFile(app, ok.toTypedArray(), null, null)
                }
                ok
            }
            val failed = paths.size - deleted.size
            removeFromState(deleted, failed)
        }
    }

    private fun removeFromState(removed: List<String>, failed: Int) {
        val gone = removed.toSet()
        _state.update { s ->
            val freed = s.groups.sumOf { g -> g.files.filter { it.path in gone }.sumOf { it.size } }
            val groups = s.groups
                .map { g -> g.copy(files = g.files.filter { it.path !in gone }) }
                .filter { it.files.size >= 2 }
            val msg = buildString {
                append("Deleted ${gone.size} files, freed ${formatSize(freed)}")
                if (failed > 0) append(" · $failed could not be deleted")
            }
            s.copy(groups = groups, selected = s.selected - gone, message = msg)
        }
    }
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
