package com.example.duplicatecleaner

import android.app.Activity
import android.app.Application
import android.media.MediaScannerConnection
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.duplicatecleaner.ads.AdManager
import com.example.duplicatecleaner.billing.BillingManager
import com.example.duplicatecleaner.billing.Entitlements
import com.example.duplicatecleaner.data.DuplicateGroup
import com.example.duplicatecleaner.data.DuplicateScanner
import com.example.duplicatecleaner.data.FileKind
import com.example.duplicatecleaner.data.MediaFile
import com.example.duplicatecleaner.data.ScanProgress
import com.example.duplicatecleaner.data.SmartChoice
import com.example.duplicatecleaner.data.SmartKeeper
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
enum class KeepRule { SMART, OLDEST, NEWEST, SHORTEST_PATH, IN_FOLDER }

data class FolderStat(val folder: String, val files: Int, val bytes: Long)

data class UiState(
    val phase: Phase = Phase.IDLE,
    val progress: ScanProgress = ScanProgress(),
    val groups: List<DuplicateGroup> = emptyList(),
    val selected: Set<String> = emptySet(),
    val keepRule: KeepRule = KeepRule.SMART,
    val keepFolder: String? = null,
    /** Smart's decision for every copy path (all copies of a group map to the same choice). */
    val smart: Map<String, SmartChoice> = emptyMap(),
    /** Paths of groups the user has looked at, so they stop showing as "needs review". */
    val reviewed: Set<String> = emptySet(),
    val reviewOnly: Boolean = false,
    val trashCount: Int = 0,
    val trashBytes: Long = 0,
    // Monetization
    val isPro: Boolean = false,
    val freeRemaining: Long = Entitlements.FREE_BYTES,
    val proPrice: String? = null,
    val rewardReady: Boolean = false,
    val showPaywall: Boolean = false,
    val message: String? = null
) {
    val selectedBytes: Long
        get() = groups.sumOf { g -> g.files.filter { it.path in selected }.sumOf { it.size } }

    /** True when the current selection can be cleaned without paying. */
    val fitsFreeAllowance: Boolean get() = isPro || selectedBytes <= freeRemaining

    fun choiceFor(group: DuplicateGroup): SmartChoice? =
        group.files.firstNotNullOfOrNull { smart[it.path] }

    /** Groups where Smart wasn't sure and the user hasn't looked yet. Computationally heavy: remember() it. */
    val reviewGroups: List<DuplicateGroup>
        get() = if (keepRule != KeepRule.SMART) emptyList() else groups.filter { g ->
            choiceFor(g)?.confident == false && g.files.none { it.path in reviewed }
        }

    /** Groups in display order: ones needing review first (or only those, if the filter is on). */
    val visibleGroups: List<DuplicateGroup>
        get() {
            val review = reviewGroups
            if (review.isEmpty()) return groups
            if (reviewOnly) return review
            val inReview = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<DuplicateGroup, Boolean>())
            inReview.addAll(review)
            return review + groups.filter { it !in inReview }
        }
}

/** Folders ranked by how much duplicate data they contain. */
fun folderStats(groups: List<DuplicateGroup>): List<FolderStat> =
    groups.flatMap { it.files }
        .groupBy { it.folder }
        .map { (folder, files) -> FolderStat(folder, files.size, files.sumOf { it.size }) }
        .sortedByDescending { it.bytes }

/** Space that could be reclaimed per file type, largest first. */
fun reclaimableByKind(groups: List<DuplicateGroup>): List<Pair<FileKind, Long>> =
    groups.groupBy { it.files.first().kind }
        .map { (kind, g) -> kind to g.sumOf { it.reclaimable } }
        .sortedByDescending { it.second }

class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private val scanner = DuplicateScanner()
    private val entitlements = Entitlements(app)
    private val billing = BillingManager(app, entitlements)
    private val ads = AdManager(app)

    private val _state = MutableStateFlow(
        UiState(isPro = entitlements.isProCached, freeRemaining = entitlements.freeRemaining())
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var scanJob: Job? = null

    init {
        refreshTrash()
        billing.connect()
        ads.init()

        viewModelScope.launch {
            billing.isPro.collect { pro ->
                _state.update { it.copy(isPro = pro, showPaywall = it.showPaywall && !pro) }
            }
        }
        viewModelScope.launch { billing.price.collect { p -> _state.update { it.copy(proPrice = p) } } }
        viewModelScope.launch { billing.events.collect { m -> _state.update { it.copy(message = m) } } }
        viewModelScope.launch { ads.rewardReady.collect { r -> _state.update { it.copy(rewardReady = r) } } }
    }

    // ---- Scanning ----

    fun startScan() {
        scanJob?.cancel()
        _state.update {
            it.copy(
                phase = Phase.SCANNING, progress = ScanProgress(), groups = emptyList(),
                selected = emptySet(), message = null, showPaywall = false
            )
        }
        scanJob = viewModelScope.launch {
            try {
                val groups = scanner.scan { p -> _state.update { it.copy(progress = p) } }
                val smart = withContext(Dispatchers.IO) { SmartKeeper.decideAll(groups) }
                _state.update {
                    it.copy(
                        phase = Phase.RESULTS,
                        groups = groups,
                        keepRule = KeepRule.SMART,
                        keepFolder = null,
                        smart = smart,
                        reviewed = emptySet(),
                        reviewOnly = false,
                        selected = selectionFor(groups, KeepRule.SMART, null, smart),
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
        _state.update { it.copy(phase = Phase.IDLE, groups = emptyList(), selected = emptySet()) }
    }

    fun applyRule(rule: KeepRule, folder: String? = null) {
        _state.update { s ->
            s.copy(
                keepRule = rule,
                keepFolder = if (rule == KeepRule.IN_FOLDER) folder else null,
                selected = selectionFor(s.groups, rule, folder, s.smart),
                message = null
            )
        }
    }

    fun toggle(file: MediaFile) {
        _state.update { s ->
            val group = s.groups.firstOrNull { g -> g.files.any { it.path == file.path } }
                ?: return@update s
            if (file.path in s.selected) {
                s.copy(
                    selected = s.selected - file.path,
                    reviewed = s.reviewed + group.files.map { it.path },
                    message = null
                )
            } else {
                val unselectedLeft = group.files.count { it.path !in s.selected && it.path != file.path }
                if (unselectedLeft == 0) {
                    s.copy(message = "Keep at least one copy in each group")
                } else {
                    s.copy(
                        selected = s.selected + file.path,
                        reviewed = s.reviewed + group.files.map { it.path },
                        message = null
                    )
                }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    /** "Looks good": the user accepts Smart's pick for this group. */
    fun markReviewed(group: DuplicateGroup) =
        _state.update { it.copy(reviewed = it.reviewed + group.files.map { f -> f.path }) }

    fun toggleReviewOnly() = _state.update { it.copy(reviewOnly = !it.reviewOnly) }

    // ---- Pro / allowance ----

    fun openPaywall() = _state.update { it.copy(showPaywall = true) }
    fun closePaywall() = _state.update { it.copy(showPaywall = false) }

    fun buyPro(activity: Activity) = billing.launchPurchase(activity)

    fun restorePurchase() {
        billing.refresh()
        _state.update { it.copy(message = "Checking your purchases…") }
    }

    fun watchAd(activity: Activity) {
        ads.showRewarded(activity) {
            entitlements.addRewardBonus()
            _state.update {
                it.copy(
                    freeRemaining = entitlements.freeRemaining(),
                    message = "Added ${formatSize(Entitlements.REWARD_BYTES)} of free cleanup"
                )
            }
        }
    }

    /** Shrinks the selection to the largest files that fit in what's left of the free allowance. */
    fun cleanFreePortion() {
        _state.update { s ->
            var budget = s.freeRemaining
            val keep = mutableSetOf<String>()
            s.groups.flatMap { it.files }
                .filter { it.path in s.selected }
                .sortedByDescending { it.size }
                .forEach { f ->
                    if (f.size <= budget) {
                        keep += f.path
                        budget -= f.size
                    }
                }
            s.copy(
                selected = keep,
                showPaywall = false,
                message = if (keep.isEmpty()) "No selected file fits your free allowance" else null
            )
        }
    }

    // ---- Trash ----

    fun refreshTrash() {
        viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) { Trash.stats() }
            _state.update { it.copy(trashCount = stats.count, trashBytes = stats.bytes) }
        }
    }

    /** Moves the selected files to the trash folder, or shows the paywall if over the free allowance. */
    fun trashSelected() {
        val s = _state.value
        val paths = s.selected.toList()
        if (paths.isEmpty()) return
        if (!s.fitsFreeAllowance) {
            openPaywall()
            return
        }
        val sizeByPath = s.groups.flatMap { it.files }.associate { it.path to it.size }

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
            val movedBytes = result.moved.sumOf { sizeByPath[it] ?: 0L }
            if (!_state.value.isPro) entitlements.recordUsed(movedBytes)

            _state.update { st ->
                val groups = st.groups
                    .map { g -> g.copy(files = g.files.filter { it.path !in gone }) }
                    .filter { it.files.size >= 2 }
                val msg = buildString {
                    append("Moved ${gone.size} files (${formatSize(movedBytes)}) to trash")
                    if (result.failed > 0) append(" · ${result.failed} failed")
                }
                st.copy(
                    groups = groups,
                    selected = st.selected - gone,
                    freeRemaining = entitlements.freeRemaining(),
                    message = msg
                )
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

    private fun keeper(
        group: DuplicateGroup,
        rule: KeepRule,
        folder: String?,
        smart: Map<String, SmartChoice>
    ): MediaFile =
        when (rule) {
            KeepRule.SMART -> {
                val path = group.files.firstNotNullOfOrNull { smart[it.path] }?.keeperPath
                group.files.firstOrNull { it.path == path } ?: group.files.minBy { it.dateModified }
            }
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
        folder: String?,
        smart: Map<String, SmartChoice>
    ): Set<String> = groups.flatMap { g ->
        val keep = keeper(g, rule, folder, smart)
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
