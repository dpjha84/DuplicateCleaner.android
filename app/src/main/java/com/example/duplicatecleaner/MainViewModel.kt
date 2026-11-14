package com.example.duplicatecleaner

import android.app.Activity
import android.app.Application
import android.media.MediaScannerConnection
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.duplicatecleaner.ads.AdManager
import com.example.duplicatecleaner.billing.BillingManager
import com.example.duplicatecleaner.billing.Entitlements
import com.example.duplicatecleaner.data.CleanMode
import com.example.duplicatecleaner.data.DuplicateGroup
import com.example.duplicatecleaner.data.DuplicateScanner
import com.example.duplicatecleaner.data.FileKind
import com.example.duplicatecleaner.data.MediaFile
import com.example.duplicatecleaner.data.Prefs
import com.example.duplicatecleaner.data.ScanProgress
import com.example.duplicatecleaner.data.SmartChoice
import com.example.duplicatecleaner.data.SmartKeeper
import com.example.duplicatecleaner.data.ThemeMode
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

enum class Tab { HOME, TRASH, SETTINGS }

/** Which copy to keep in every duplicate group. Everything else is pre-selected. */
enum class KeepRule { SMART, OLDEST, NEWEST, SHORTEST_PATH, IN_FOLDER }

/** Optional button on a snackbar message. */
enum class SnackAction { VIEW_TRASH }

data class FolderStat(val folder: String, val files: Int, val bytes: Long)

/** What a "Clean up" tap would act on right now. */
data class SelectionSummary(
    val files: List<MediaFile>,
    val bytes: Long,
    /** Groups where every copy is selected (nothing would be left). */
    val fullyRemovedGroups: Int
) {
    val count: Int get() = files.size
}

data class UiState(
    val tab: Tab = Tab.HOME,
    val phase: Phase = Phase.IDLE,
    val progress: ScanProgress = ScanProgress(),

    // Results
    val groups: List<DuplicateGroup> = emptyList(),
    /** Paths of files that Clean up will act on. */
    val selected: Set<String> = emptySet(),
    val keepRule: KeepRule = KeepRule.SMART,
    val keepFolder: String? = null,
    /** Smart's decision for every copy path (all copies of a group map to the same choice). */
    val smart: Map<String, SmartChoice> = emptyMap(),
    /** Paths of groups the user has looked at, so they stop showing as "needs review". */
    val reviewed: Set<String> = emptySet(),
    val reviewOnly: Boolean = false,
    /** Show and clean only this file type; null = everything. */
    val typeFilter: FileKind? = null,

    // Settings
    val scanKinds: Set<FileKind> = FileKind.entries.toSet(),
    val minSizeBytes: Long = 1024L,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val cleanMode: CleanMode = CleanMode.TRASH,
    val lifetimeFreedBytes: Long = 0L,
    val lifetimeFiles: Int = 0,

    // Trash
    val trashItems: List<MediaFile> = emptyList(),
    val trashSelected: Set<String> = emptySet(),

    // Monetization
    val isPro: Boolean = false,
    val freeRemaining: Long = Entitlements.FREE_BYTES,
    val freeTotal: Long = Entitlements.FREE_BYTES,
    val proPrice: String? = null,
    val rewardReady: Boolean = false,
    val showPaywall: Boolean = false,

    val message: String? = null,
    val messageAction: SnackAction? = null
) {
    val trashCount: Int get() = trashItems.size
    val trashBytes: Long get() = trashItems.sumOf { it.size }

    /** Groups for the current type filter. Heavy on big lists: wrap in remember(). */
    val scopedGroups: List<DuplicateGroup>
        get() = typeFilter?.let { k -> groups.filter { it.kind == k } } ?: groups

    fun selectionSummary(): SelectionSummary {
        val files = ArrayList<MediaFile>()
        var fully = 0
        for (g in scopedGroups) {
            var all = true
            for (f in g.files) {
                if (f.path in selected) files += f else all = false
            }
            if (all) fully++
        }
        return SelectionSummary(files, files.sumOf { it.size }, fully)
    }

    /** True when [bytes] can be cleaned without paying. */
    fun fits(bytes: Long): Boolean = isPro || bytes <= freeRemaining

    fun choiceFor(group: DuplicateGroup): SmartChoice? =
        group.files.firstNotNullOfOrNull { smart[it.path] }

    /** Groups where Smart wasn't sure and the user hasn't looked yet. Heavy: wrap in remember(). */
    val reviewGroups: List<DuplicateGroup>
        get() = if (keepRule != KeepRule.SMART) emptyList() else scopedGroups.filter { g ->
            choiceFor(g)?.confident == false && g.files.none { it.path in reviewed }
        }

    /** Groups in display order: ones needing review first (or only those, if the filter is on). */
    val visibleGroups: List<DuplicateGroup>
        get() {
            val base = scopedGroups
            val review = reviewGroups
            if (review.isEmpty()) return base
            if (reviewOnly) return review
            val inReview = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<DuplicateGroup, Boolean>())
            inReview.addAll(review)
            return review + base.filter { it !in inReview }
        }
}

/** Folders ranked by how much duplicate data they contain. */
fun folderStats(groups: List<DuplicateGroup>): List<FolderStat> =
    groups.flatMap { it.files }
        .groupBy { it.folder }
        .map { (folder, files) -> FolderStat(folder, files.size, files.sumOf { it.size }) }
        .sortedByDescending { it.bytes }

class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private val scanner = DuplicateScanner()
    private val entitlements = Entitlements(app)
    private val billing = BillingManager(app, entitlements)
    private val ads = AdManager(app)
    private val prefs = Prefs(app)

    private val _state = MutableStateFlow(
        UiState(
            scanKinds = prefs.scanKinds,
            minSizeBytes = prefs.minSizeBytes,
            themeMode = prefs.themeMode,
            dynamicColor = prefs.dynamicColor,
            cleanMode = prefs.cleanMode,
            lifetimeFreedBytes = prefs.lifetimeFreedBytes,
            lifetimeFiles = prefs.lifetimeFiles,
            isPro = entitlements.isProCached,
            freeRemaining = entitlements.freeRemaining(),
            freeTotal = entitlements.freeTotal()
        )
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
        viewModelScope.launch { billing.events.collect { m -> toast(m) } }
        viewModelScope.launch { ads.rewardReady.collect { r -> _state.update { it.copy(rewardReady = r) } } }
    }

    private fun toast(text: String, action: SnackAction? = null) =
        _state.update { it.copy(message = text, messageAction = action) }

    fun messageShown() = _state.update { it.copy(message = null, messageAction = null) }

    // ---- Navigation ----

    fun setTab(tab: Tab) {
        _state.update { it.copy(tab = tab) }
        if (tab == Tab.TRASH) refreshTrash()
    }

    /** Back from the results screen to Home. */
    fun leaveResults() {
        scanJob?.cancel()
        _state.update {
            it.copy(
                phase = Phase.IDLE, groups = emptyList(), selected = emptySet(),
                smart = emptyMap(), reviewed = emptySet(), reviewOnly = false, typeFilter = null
            )
        }
    }

    // ---- Settings ----

    fun toggleKind(kind: FileKind) {
        val next = _state.value.scanKinds.let { if (kind in it) it - kind else it + kind }
        prefs.scanKinds = next
        _state.update { it.copy(scanKinds = next) }
    }

    fun setMinSize(bytes: Long) {
        prefs.minSizeBytes = bytes
        _state.update { it.copy(minSizeBytes = bytes) }
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.themeMode = mode
        _state.update { it.copy(themeMode = mode) }
    }

    fun setDynamicColor(on: Boolean) {
        prefs.dynamicColor = on
        _state.update { it.copy(dynamicColor = on) }
    }

    fun setCleanMode(mode: CleanMode) {
        prefs.cleanMode = mode
        _state.update { it.copy(cleanMode = mode) }
    }

    // ---- Scanning ----

    fun startScan() {
        val s = _state.value
        if (s.scanKinds.isEmpty()) {
            toast("Pick at least one file type to scan")
            return
        }
        scanJob?.cancel()
        _state.update {
            it.copy(
                tab = Tab.HOME, phase = Phase.SCANNING, progress = ScanProgress(), groups = emptyList(),
                selected = emptySet(), typeFilter = null, showPaywall = false, message = null, messageAction = null
            )
        }
        scanJob = viewModelScope.launch {
            try {
                val groups = scanner.scan(s.scanKinds, s.minSizeBytes) { p ->
                    _state.update { it.copy(progress = p) }
                }
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
                        selected = selectionFor(groups, KeepRule.SMART, null, smart)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(phase = Phase.IDLE) }
                toast("Scan failed: ${e.message}")
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        _state.update { it.copy(phase = Phase.IDLE, groups = emptyList(), selected = emptySet()) }
    }

    // ---- Choosing what to clean ----

    fun applyRule(rule: KeepRule, folder: String? = null) {
        _state.update { s ->
            s.copy(
                keepRule = rule,
                keepFolder = if (rule == KeepRule.IN_FOLDER) folder else null,
                selected = selectionFor(s.groups, rule, folder, s.smart)
            )
        }
    }

    fun setTypeFilter(kind: FileKind?) = _state.update { it.copy(typeFilter = kind, reviewOnly = false) }

    /** Tick or untick one file. Selecting every copy of a group is allowed (the confirm dialog warns). */
    fun toggle(file: MediaFile) {
        _state.update { s ->
            val group = s.groups.firstOrNull { g -> g.files.any { it.path == file.path } }
                ?: return@update s
            val reviewed = s.reviewed + group.files.map { it.path }
            val selected = if (file.path in s.selected) s.selected - file.path else s.selected + file.path
            s.copy(selected = selected, reviewed = reviewed)
        }
    }

    /** Keep every copy in this group (nothing from it will be cleaned). */
    fun keepAll(group: DuplicateGroup) = _state.update { s ->
        val paths = group.files.map { it.path }
        s.copy(selected = s.selected - paths.toSet(), reviewed = s.reviewed + paths)
    }

    /** Clean every copy in this group. */
    fun removeAll(group: DuplicateGroup) = _state.update { s ->
        val paths = group.files.map { it.path }
        s.copy(selected = s.selected + paths, reviewed = s.reviewed + paths)
    }

    /** Back to the automatic pick for this group. */
    fun resetGroup(group: DuplicateGroup) = _state.update { s ->
        val keep = keeper(group, s.keepRule, s.keepFolder, s.smart)
        val paths = group.files.map { it.path }
        val others = group.files.filter { it.path != keep.path }.map { it.path }
        s.copy(selected = (s.selected - paths.toSet()) + others)
    }

    /** "Looks good": the user accepts Smart's pick for this group. */
    fun markReviewed(group: DuplicateGroup) =
        _state.update { it.copy(reviewed = it.reviewed + group.files.map { f -> f.path }) }

    fun toggleReviewOnly() = _state.update { it.copy(reviewOnly = !it.reviewOnly) }

    // ---- Cleaning ----

    /** Cleans the current selection (to Trash or permanently, per settings), or shows the paywall if over the allowance. */
    fun cleanSelected() {
        val s = _state.value
        val summary = s.selectionSummary()
        if (summary.count == 0) return
        if (!s.fits(summary.bytes)) {
            openPaywall()
            return
        }
        val paths = summary.files.map { it.path }
        val sizeByPath = summary.files.associate { it.path to it.size }
        val mode = s.cleanMode

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val r = if (mode == CleanMode.TRASH) Trash.moveToTrash(paths) else Trash.deletePermanently(paths)
                // Tell the media library so gallery / music apps drop the old entries.
                if (r.moved.isNotEmpty()) MediaScannerConnection.scanFile(app, r.moved.toTypedArray(), null, null)
                r
            }
            val gone = result.moved.toSet()
            val doneBytes = result.moved.sumOf { sizeByPath[it] ?: 0L }
            if (!_state.value.isPro) entitlements.recordUsed(doneBytes)
            if (mode == CleanMode.DELETE) prefs.addFreed(doneBytes, gone.size)

            _state.update { st ->
                val groups = st.groups
                    .map { g -> g.copy(files = g.files.filter { it.path !in gone }) }
                    .filter { it.files.size >= 2 }
                st.copy(
                    groups = groups,
                    selected = st.selected - gone,
                    freeRemaining = entitlements.freeRemaining(),
                    freeTotal = entitlements.freeTotal(),
                    lifetimeFreedBytes = prefs.lifetimeFreedBytes,
                    lifetimeFiles = prefs.lifetimeFiles
                )
            }
            val failed = result.failed
            val summaryText = if (mode == CleanMode.TRASH) {
                "Moved ${gone.size} files (${formatSize(doneBytes)}) to Trash"
            } else {
                "Deleted ${gone.size} files · freed ${formatSize(doneBytes)}"
            }
            val text = summaryText + if (failed > 0) " · $failed failed" else ""
            toast(text, if (mode == CleanMode.TRASH) SnackAction.VIEW_TRASH else null)
            refreshTrash()
        }
    }

    // ---- Trash ----

    fun refreshTrash() {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { Trash.list() }
            _state.update { s ->
                val keep = items.map { it.path }.toSet()
                s.copy(trashItems = items, trashSelected = s.trashSelected.filter { it in keep }.toSet())
            }
        }
    }

    fun toggleTrashSelect(path: String) = _state.update {
        it.copy(trashSelected = if (path in it.trashSelected) it.trashSelected - path else it.trashSelected + path)
    }

    fun selectAllTrash() = _state.update { it.copy(trashSelected = it.trashItems.map { f -> f.path }.toSet()) }

    fun clearTrashSelection() = _state.update { it.copy(trashSelected = emptySet()) }

    /** Restores the given trashed files to where they came from. */
    fun restore(paths: List<String>) {
        if (paths.isEmpty()) return
        viewModelScope.launch {
            val (restored, failed) = withContext(Dispatchers.IO) {
                val r = Trash.restore(paths)
                if (r.first.isNotEmpty()) MediaScannerConnection.scanFile(app, r.first.toTypedArray(), null, null)
                r
            }
            toast("Restored ${restored.size} files" + if (failed > 0) " · $failed failed" else "")
            refreshTrash()
        }
    }

    /** Permanently deletes the given trashed files, freeing the space. */
    fun deleteFromTrash(paths: List<String>) {
        if (paths.isEmpty()) return
        val sizeByPath = _state.value.trashItems.associate { it.path to it.size }
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { Trash.delete(paths) }
            val bytes = paths.sumOf { sizeByPath[it] ?: 0L }
            prefs.addFreed(bytes, removed)
            _state.update { it.copy(lifetimeFreedBytes = prefs.lifetimeFreedBytes, lifetimeFiles = prefs.lifetimeFiles) }
            toast("Deleted $removed files · freed ${formatSize(bytes)}")
            refreshTrash()
        }
    }

    // ---- Pro / allowance ----

    fun openPaywall() = _state.update { it.copy(showPaywall = true) }
    fun closePaywall() = _state.update { it.copy(showPaywall = false) }

    fun buyPro(activity: Activity) = billing.launchPurchase(activity)

    fun restorePurchase() {
        billing.refresh()
        toast("Checking your purchases…")
    }

    fun watchAd(activity: Activity) {
        ads.showRewarded(activity) {
            entitlements.addRewardBonus()
            _state.update {
                it.copy(
                    freeRemaining = entitlements.freeRemaining(),
                    freeTotal = entitlements.freeTotal(),
                    message = "Added ${formatSize(Entitlements.REWARD_BYTES)} of free cleanup",
                    messageAction = null
                )
            }
        }
    }

    /** Shrinks the selection to the largest files that fit in what's left of the free allowance. */
    fun cleanFreePortion() {
        _state.update { s ->
            var budget = s.freeRemaining
            val keep = mutableSetOf<String>()
            s.selectionSummary().files
                .sortedByDescending { it.size }
                .forEach { f ->
                    if (f.size <= budget) {
                        keep += f.path
                        budget -= f.size
                    }
                }
            val outsideScope = s.selected - s.scopedGroups.flatMap { g -> g.files.map { it.path } }.toSet()
            s.copy(
                selected = keep + outsideScope,
                showPaywall = false,
                message = if (keep.isEmpty()) "No selected file fits your free allowance" else null,
                messageAction = null
            )
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
