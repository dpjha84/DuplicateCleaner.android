package com.dpjha.cloneout

import android.app.Activity
import android.app.Application
import android.media.MediaScannerConnection
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dpjha.cloneout.ads.AdManager
import com.dpjha.cloneout.billing.BillingManager
import com.dpjha.cloneout.billing.Entitlements
import com.dpjha.cloneout.data.CleanMode
import com.dpjha.cloneout.data.DuplicateGroup
import com.dpjha.cloneout.data.DuplicateScanner
import com.dpjha.cloneout.data.FileKind
import com.dpjha.cloneout.data.FileFinder
import com.dpjha.cloneout.data.MediaFile
import com.dpjha.cloneout.data.Prefs
import com.dpjha.cloneout.data.ScanProgress
import com.dpjha.cloneout.data.Sensitivity
import com.dpjha.cloneout.data.SimilarCandidate
import com.dpjha.cloneout.data.SimilarGrouper
import com.dpjha.cloneout.data.SimilarGroup
import com.dpjha.cloneout.data.SimilarPhoto
import com.dpjha.cloneout.data.SimilarPhotoScanner
import com.dpjha.cloneout.data.SmartChoice
import com.dpjha.cloneout.data.SmartKeeper
import com.dpjha.cloneout.data.ThemeMode
import com.dpjha.cloneout.data.Tool
import com.dpjha.cloneout.data.Trash
import com.dpjha.cloneout.data.WaCategory
import com.dpjha.cloneout.data.waCategoryOf
import com.dpjha.cloneout.telemetry.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** TOOL = the Large files / WhatsApp media screen (its own list, not duplicate groups). */
enum class Phase { IDLE, SCANNING, RESULTS, TOOL }

/** Free users see this many similar-photo groups in full; the rest are blurred teasers. Tune freely. */
const val FREE_SIMILAR_GROUPS = 3

enum class Tab { HOME, TRASH, SETTINGS }

/** The two kinds of results: identical files and similar-looking photos. */
enum class ResultsTab { IDENTICAL, SIMILAR }

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

    // Similar photos
    val resultsTab: ResultsTab = ResultsTab.IDENTICAL,
    /** True when this scan includes similar photos (the Similar tab exists). */
    val similarScanned: Boolean = false,
    val similarRunning: Boolean = false,
    val similarProgress: Float = 0f,
    val similarGroups: List<SimilarGroup> = emptyList(),
    /** Photos Clean up will act on while the Similar tab is open. */
    val similarSelected: Set<String> = emptySet(),

    // Large files / WhatsApp media
    val tool: Tool? = null,
    val toolRunning: Boolean = false,
    val toolScanned: Int = 0,
    val toolFiles: List<MediaFile> = emptyList(),
    val toolSelected: Set<String> = emptySet(),
    /** Large files: only show files at least this big (the scan itself keeps everything over 50 MB). */
    val largeMinBytes: Long = 100L shl 20,
    val toolKind: FileKind? = null,
    val waCategory: WaCategory? = null,

    // Settings
    val scanSimilar: Boolean = true,
    val similarLevel: Sensitivity = Sensitivity.BALANCED,
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
    /** Testing aid is on: the Free plan is shown even if Google Play says Pro is owned. */
    val testFree: Boolean = false,
    /** Play Billing could not provide the Pro product (e.g. a sideloaded build). */
    val storeUnavailable: Boolean = false,
    val freeRemaining: Long = Entitlements.FREE_BYTES,
    val freeTotal: Long = Entitlements.FREE_BYTES,
    val proPrice: String? = null,
    val rewardReady: Boolean = false,
    /** True once the consent flow has allowed ads (banner ads wait for this). */
    val adsAllowed: Boolean = false,
    /** Ad top-ups still available today (daily cap). */
    val adsLeftToday: Int = Entitlements.MAX_ADS_PER_DAY,
    val showPaywall: Boolean = false,
    /** "Share anonymous usage data" setting. */
    val shareUsage: Boolean = true,

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

    fun similarSummary(): SelectionSummary {
        val files = ArrayList<MediaFile>()
        var fully = 0
        for (g in similarGroups) {
            var all = true
            for (p in g.photos) {
                if (p.file.path in similarSelected) files += p.file else all = false
            }
            if (all) fully++
        }
        return SelectionSummary(files, files.sumOf { it.size }, fully)
    }

    /** Files the tool screen shows right now (after the size / type filters). Heavy: wrap in remember(). */
    val toolVisible: List<MediaFile>
        get() = when (tool) {
            Tool.LARGE -> toolFiles.filter { it.size >= largeMinBytes && (toolKind == null || it.kind == toolKind) }
            Tool.WHATSAPP -> toolFiles.filter { waCategory == null || waCategoryOf(it.path) == waCategory }
            null -> emptyList()
        }

    /** Selected files among the ones shown; filtered-out files are never cleaned. */
    fun toolSummary(visible: List<MediaFile> = toolVisible): SelectionSummary {
        val files = visible.filter { it.path in toolSelected }
        return SelectionSummary(files, files.sumOf { it.size }, 0)
    }

    /** What Clean up acts on for the screen that is open. */
    fun activeSummary(): SelectionSummary = when {
        tool != null -> toolSummary()
        resultsTab == ResultsTab.SIMILAR -> similarSummary()
        else -> selectionSummary()
    }

    /** Cleaning similar photos is a Pro feature (finding and previewing them stays free). */
    val similarLocked: Boolean get() = tool == null && resultsTab == ResultsTab.SIMILAR && !isPro

    /** True when [bytes] can be cleaned without paying. */
    fun fits(bytes: Long): Boolean =
        isPro || ((tool != null || resultsTab != ResultsTab.SIMILAR) && bytes <= freeRemaining)

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

/** The group after some photos were cleaned; if the best one went, the next best takes over. */
fun SimilarGroup.without(gone: Set<String>): SimilarGroup {
    if (photos.none { it.file.path in gone }) return this
    val left: List<SimilarPhoto> = photos.filter { it.file.path !in gone }
    val bestSurvived = left.isNotEmpty() && left.first().file.path == bestPath
    return SimilarGroup(left, if (bestSurvived) reason else "Best remaining photo")
}

/** Folders ranked by how much duplicate data they contain. */
fun folderStats(groups: List<DuplicateGroup>): List<FolderStat> =
    groups.flatMap { it.files }
        .groupBy { it.folder }
        .map { (folder, files) -> FolderStat(folder, files.size, files.sumOf { it.size }) }
        .sortedByDescending { it.bytes }

class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private val scanner = DuplicateScanner()
    private val similarScanner = SimilarPhotoScanner(app)
    private val entitlements = Entitlements(app)
    private val billing = BillingManager(app, entitlements)
    private val ads = AdManager(app)

    /** Called once the consent flow says ads may be requested. */
    fun startAds() {
        ads.init()
        _state.update { it.copy(adsAllowed = true) }
    }
    private val prefs = Prefs(app)

    private val _state = MutableStateFlow(
        UiState(
            scanSimilar = prefs.scanSimilar,
            similarLevel = prefs.similarLevel,
            scanKinds = prefs.scanKinds,
            minSizeBytes = prefs.minSizeBytes,
            themeMode = prefs.themeMode,
            dynamicColor = prefs.dynamicColor,
            cleanMode = prefs.cleanMode,
            lifetimeFreedBytes = prefs.lifetimeFreedBytes,
            lifetimeFiles = prefs.lifetimeFiles,
            isPro = entitlements.isProCached && !entitlements.testForceFree,
            testFree = entitlements.testForceFree,
            freeRemaining = entitlements.freeRemaining(),
            freeTotal = entitlements.freeTotal(),
            adsLeftToday = entitlements.adsLeftToday(),
            shareUsage = prefs.shareUsage
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var scanJob: Job? = null
    private var similarJob: Job? = null
    private var regroupJob: Job? = null
    private var scanStartedAt = 0L
    /** True while a clean is running, so a double tap can't start a second one against the same allowance. */
    private var cleaning = false
    private var similarStartedAt = 0L

    /** Signatures of this scan's photos, kept so changing the match level doesn't rescan. */
    private var similarSigs: List<SimilarCandidate> = emptyList()
    /** Files cleaned during this results session (a still-running similar scan must not resurrect them). */
    private val gonePaths = HashSet<String>()

    init {
        refreshTrash()
        billing.connect()

        viewModelScope.launch {
            billing.isPro.collect { pro ->
                Telemetry.setPlan(pro)
                _state.update {
                    val shown = pro && !it.testFree
                    it.copy(isPro = shown, showPaywall = it.showPaywall && !shown)
                }
            }
        }
        viewModelScope.launch { billing.price.collect { p -> _state.update { it.copy(proPrice = p) } } }
        viewModelScope.launch { billing.storeUnavailable.collect { u -> _state.update { it.copy(storeUnavailable = u) } } }
        viewModelScope.launch { billing.events.collect { m -> toast(m) } }
        viewModelScope.launch { ads.rewardReady.collect { r -> _state.update { it.copy(rewardReady = r) } } }
    }

    /** Testing aid (7 taps on the version in Settings): flips between the real plan and a forced Free view. */
    fun toggleTestFree() {
        val on = !entitlements.testForceFree
        entitlements.testForceFree = on
        Telemetry.event("test_mode_toggle", "on" to on)
        _state.update { it.copy(testFree = on, isPro = billing.isPro.value && !on, showPaywall = false) }
        toast(if (on) "Test mode on: showing the Free plan. Tap the version 7 times to turn it off." else "Test mode off.")
    }

    private fun toast(text: String, action: SnackAction? = null) =
        _state.update { it.copy(message = text, messageAction = action) }

    fun messageShown() = _state.update { it.copy(message = null, messageAction = null) }

    // ---- Navigation ----

    fun setTab(tab: Tab) {
        if (tab != _state.value.tab) {
            Telemetry.screen(when (tab) { Tab.HOME -> "home"; Tab.TRASH -> "trash"; Tab.SETTINGS -> "settings" })
        }
        _state.update { it.copy(tab = tab) }
        if (tab == Tab.TRASH) refreshTrash()
        if (tab == Tab.SETTINGS) {
            // A new day may have started since the screen last looked; also retries a failed ad load.
            refreshAllowance()
            ads.loadRewarded()
        }
    }

    /** Back from the results screen to Home. */
    fun leaveResults() {
        scanJob?.cancel()
        resetSimilar()
        _state.update {
            it.copy(
                phase = Phase.IDLE, groups = emptyList(), selected = emptySet(),
                smart = emptyMap(), reviewed = emptySet(), reviewOnly = false, typeFilter = null,
                tool = null, toolRunning = false, toolFiles = emptyList(), toolSelected = emptySet(),
                toolKind = null, waCategory = null, showPaywall = false
            )
        }
    }

    private fun resetSimilar() {
        similarJob?.cancel()
        regroupJob?.cancel()
        similarSigs = emptyList()
        gonePaths.clear()
        _state.update {
            it.copy(
                resultsTab = ResultsTab.IDENTICAL, similarScanned = false, similarRunning = false,
                similarProgress = 0f, similarGroups = emptyList(), similarSelected = emptySet()
            )
        }
    }

    fun setResultsTab(tab: ResultsTab) {
        val s = _state.value
        if (tab != s.resultsTab) {
            Telemetry.event("results_tab_view", "tab" to tab.name.lowercase(), "plan" to plan())
            if (tab == ResultsTab.SIMILAR && !s.isPro && s.similarGroups.size > FREE_SIMILAR_GROUPS) {
                Telemetry.event(
                    "similar_teaser_view",
                    "groups" to s.similarGroups.size,
                    "locked_groups" to (s.similarGroups.size - FREE_SIMILAR_GROUPS)
                )
            }
        }
        _state.update { it.copy(resultsTab = tab) }
    }

    private fun plan(): String = if (_state.value.isPro) "pro" else "free"

    // ---- Settings ----

    fun toggleKind(kind: FileKind) {
        val next = _state.value.scanKinds.let { if (kind in it) it - kind else it + kind }
        Telemetry.event("settings_change", "setting" to "scan_kind", "value" to "${kind.name.lowercase()}:${if (kind in next) "on" else "off"}")
        prefs.scanKinds = next
        // Choosing Photos switches similar-photo matching on, so users get a taste of it by default.
        val similarOn = if (kind == FileKind.IMAGE && kind in next) true else _state.value.scanSimilar
        prefs.scanSimilar = similarOn
        _state.update { it.copy(scanKinds = next, scanSimilar = similarOn) }
    }

    fun setScanSimilar(on: Boolean) {
        Telemetry.event("settings_change", "setting" to "scan_similar", "value" to on)
        prefs.scanSimilar = on
        _state.update { it.copy(scanSimilar = on) }
    }

    /** Changes how alike photos must be. Re-groups from the saved signatures, so it is instant. */
    fun setSimilarLevel(level: Sensitivity) {
        if (level == _state.value.similarLevel) return
        Telemetry.event("settings_change", "setting" to "similar_level", "value" to level.name.lowercase())
        prefs.similarLevel = level
        _state.update { it.copy(similarLevel = level) }
        if (similarSigs.isEmpty() || _state.value.similarRunning) return
        regroupJob?.cancel()
        regroupJob = viewModelScope.launch {
            val found = withContext(Dispatchers.Default) { SimilarGrouper.group(liveSigs(), level) }
            _state.update { it.copy(similarGroups = found, similarSelected = defaultSimilarSelection(found)) }
        }
    }

    fun setMinSize(bytes: Long) {
        Telemetry.event("settings_change", "setting" to "min_size_kb", "value" to (bytes shr 10))
        prefs.minSizeBytes = bytes
        _state.update { it.copy(minSizeBytes = bytes) }
    }

    fun setThemeMode(mode: ThemeMode) {
        Telemetry.event("settings_change", "setting" to "theme", "value" to mode.name.lowercase())
        prefs.themeMode = mode
        _state.update { it.copy(themeMode = mode) }
    }

    fun setDynamicColor(on: Boolean) {
        Telemetry.event("settings_change", "setting" to "dynamic_color", "value" to on)
        prefs.dynamicColor = on
        _state.update { it.copy(dynamicColor = on) }
    }

    fun setCleanMode(mode: CleanMode) {
        Telemetry.event("settings_change", "setting" to "clean_mode", "value" to mode.name.lowercase())
        prefs.cleanMode = mode
        _state.update { it.copy(cleanMode = mode) }
    }

    /** Turns analytics and crash reports on or off. The change is logged first, while still allowed. */
    fun setShareUsage(on: Boolean) {
        Telemetry.event("settings_change", "setting" to "share_usage", "value" to on)
        prefs.shareUsage = on
        Telemetry.setShareUsage(on)
        _state.update { it.copy(shareUsage = on) }
    }

    // ---- Scanning ----

    /** Re-reads the allowance marker (storage access may have just been granted) and today's ad count. */
    private fun refreshAllowance() {
        entitlements.syncFromMarker()
        val free = entitlements.freeRemaining()
        val total = entitlements.freeTotal()
        val left = entitlements.adsLeftToday()
        _state.update {
            if (it.freeRemaining == free && it.freeTotal == total && it.adsLeftToday == left) it
            else it.copy(freeRemaining = free, freeTotal = total, adsLeftToday = left)
        }
    }

    fun startScan() {
        refreshAllowance()
        val s = _state.value
        if (s.scanKinds.isEmpty()) {
            toast("Pick at least one file type to scan")
            return
        }
        scanJob?.cancel()
        resetSimilar()
        scanStartedAt = SystemClock.elapsedRealtime()
        Telemetry.event(
            "scan_start",
            "kinds" to s.scanKinds.joinToString(",") { it.name.lowercase() },
            "min_size_kb" to (s.minSizeBytes shr 10),
            "similar" to (s.scanSimilar && FileKind.IMAGE in s.scanKinds),
            "plan" to plan()
        )
        _state.update {
            it.copy(
                tab = Tab.HOME, phase = Phase.SCANNING, progress = ScanProgress(), groups = emptyList(),
                selected = emptySet(), typeFilter = null, showPaywall = false, message = null, messageAction = null,
                tool = null, toolFiles = emptyList(), toolSelected = emptySet()
            )
        }
        scanJob = viewModelScope.launch {
            try {
                val result = scanner.scan(s.scanKinds, s.minSizeBytes) { p ->
                    _state.update { it.copy(progress = p) }
                }
                val groups = result.groups
                val smart = withContext(Dispatchers.IO) { SmartKeeper.decideAll(groups) }
                Telemetry.event(
                    "scan_complete",
                    "duration_ms" to (SystemClock.elapsedRealtime() - scanStartedAt),
                    "groups" to groups.size,
                    "files" to groups.sumOf { it.files.size },
                    "reclaimable_mb" to (groups.sumOf { it.reclaimable } shr 20),
                    "photos" to result.images.size
                )
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
                if (s.scanSimilar && FileKind.IMAGE in s.scanKinds) startSimilar(result.images, groups, smart, s.minSizeBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Telemetry.error("scan", e)
                _state.update { it.copy(phase = Phase.IDLE) }
                toast("Scan failed: ${e.message}")
            }
        }
    }

    fun cancelScan() {
        Telemetry.event("scan_cancel", "elapsed_ms" to (SystemClock.elapsedRealtime() - scanStartedAt))
        scanJob?.cancel()
        resetSimilar()
        _state.update { it.copy(phase = Phase.IDLE, groups = emptyList(), selected = emptySet()) }
    }

    // ---- Large files / WhatsApp media ----

    /** Opens the Large files or WhatsApp media screen and starts looking. Nothing is selected by default. */
    fun openTool(tool: Tool) {
        refreshAllowance()
        scanJob?.cancel()
        resetSimilar()
        val startedAt = SystemClock.elapsedRealtime()
        Telemetry.event("tool_open", "tool" to tool.name.lowercase(), "plan" to plan())
        _state.update {
            it.copy(
                tab = Tab.HOME, phase = Phase.TOOL, tool = tool, toolRunning = true, toolScanned = 0,
                toolFiles = emptyList(), toolSelected = emptySet(), toolKind = null, waCategory = null,
                groups = emptyList(), selected = emptySet(), showPaywall = false, message = null, messageAction = null
            )
        }
        scanJob = viewModelScope.launch {
            try {
                val progress: (Int) -> Unit = { n -> _state.update { st -> st.copy(toolScanned = n) } }
                val files = when (tool) {
                    Tool.LARGE -> FileFinder.findLarge(onProgress = progress)
                    Tool.WHATSAPP -> FileFinder.findWhatsApp(onProgress = progress)
                }
                Telemetry.event(
                    "tool_scan_complete",
                    "tool" to tool.name.lowercase(),
                    "files" to files.size,
                    "total_mb" to (files.sumOf { it.size } shr 20),
                    "duration_ms" to (SystemClock.elapsedRealtime() - startedAt)
                )
                _state.update { it.copy(toolFiles = files, toolRunning = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Telemetry.error("tool_scan", e)
                _state.update { it.copy(toolRunning = false) }
                toast("Couldn't finish looking through your files")
            }
        }
    }

    fun toggleTool(file: MediaFile) = _state.update {
        it.copy(toolSelected = if (file.path in it.toolSelected) it.toolSelected - file.path else it.toolSelected + file.path)
    }

    /** Ticks every file currently shown, or clears them when they are all ticked already. */
    fun toggleSelectAllTool() = _state.update { s ->
        val paths = s.toolVisible.map { it.path }
        val allOn = paths.isNotEmpty() && paths.all { it in s.toolSelected }
        s.copy(toolSelected = if (allOn) s.toolSelected - paths.toSet() else s.toolSelected + paths)
    }

    fun setLargeMin(bytes: Long) = _state.update { it.copy(largeMinBytes = bytes) }

    fun setToolKind(kind: FileKind?) = _state.update { it.copy(toolKind = kind) }

    fun setWaCategory(category: WaCategory?) = _state.update { it.copy(waCategory = category) }

    // ---- Similar photos ----

    /**
     * Looks for similar photos in the background while the exact results are already on screen.
     * Photos that are exact copies of each other count once (their duplicates are handled by the
     * Identical tab), so the two tabs never overlap.
     */
    private fun startSimilar(
        images: List<MediaFile>,
        exact: List<DuplicateGroup>,
        smart: Map<String, SmartChoice>,
        minBytes: Long
    ) {
        similarStartedAt = SystemClock.elapsedRealtime()
        _state.update {
            it.copy(
                similarScanned = true, similarRunning = true, similarProgress = 0f,
                // Nothing identical to show: land on the tab that is working.
                resultsTab = if (exact.isEmpty()) ResultsTab.SIMILAR else it.resultsTab
            )
        }
        similarJob = viewModelScope.launch {
            try {
                val extraCopies = HashSet<String>()
                for (g in exact) {
                    if (g.kind != FileKind.IMAGE) continue
                    val keep = g.files.firstNotNullOfOrNull { smart[it.path] }?.keeperPath ?: g.files.first().path
                    g.files.forEach { if (it.path != keep) extraCopies += it.path }
                }
                val floor = maxOf(minBytes, SimilarPhotoScanner.MIN_BYTES)
                val candidates = images.filter { it.path !in extraCopies && it.size >= floor }

                val sigs = similarScanner.signatures(candidates) { done, total ->
                    _state.update { it.copy(similarProgress = if (total == 0) 1f else done.toFloat() / total) }
                }
                similarSigs = sigs
                val found = withContext(Dispatchers.Default) { SimilarGrouper.group(liveSigs(), _state.value.similarLevel) }
                Telemetry.event(
                    "similar_complete",
                    "duration_ms" to (SystemClock.elapsedRealtime() - similarStartedAt),
                    "photos" to candidates.size,
                    "groups" to found.size,
                    "level" to _state.value.similarLevel.name.lowercase()
                )
                _state.update {
                    it.copy(
                        similarRunning = false,
                        similarProgress = 1f,
                        similarGroups = found,
                        similarSelected = defaultSimilarSelection(found),
                        // Nothing identical, but similar photos found: make sure they are in view.
                        resultsTab = if (it.groups.isEmpty()) ResultsTab.SIMILAR else it.resultsTab
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Telemetry.error("similar", e)
                _state.update { it.copy(similarRunning = false) }
                toast("Couldn't finish looking for similar photos")
            }
        }
    }

    /** Signatures minus anything already cleaned in this session. */
    private fun liveSigs(): List<SimilarCandidate> =
        if (gonePaths.isEmpty()) similarSigs else similarSigs.filter { it.file.path !in gonePaths }

    /** Keep the best photo of every group, clean the rest. */
    private fun defaultSimilarSelection(groups: List<SimilarGroup>): Set<String> =
        groups.flatMapTo(HashSet()) { g -> g.photos.drop(1).map { it.file.path } }

    fun toggleSimilar(file: MediaFile) = _state.update {
        it.copy(similarSelected = if (file.path in it.similarSelected) it.similarSelected - file.path else it.similarSelected + file.path)
    }

    fun keepAllSimilar(group: SimilarGroup) = _state.update {
        it.copy(similarSelected = it.similarSelected - group.files.map { f -> f.path }.toSet())
    }

    /** Back to "keep the best, clean the rest" for one group. */
    fun resetSimilarGroup(group: SimilarGroup) = _state.update {
        val paths = group.files.map { f -> f.path }.toSet()
        it.copy(similarSelected = (it.similarSelected - paths) + group.photos.drop(1).map { p -> p.file.path })
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
        if (cleaning) return
        refreshAllowance()
        val s = _state.value
        val summary = s.activeSummary()
        if (summary.count == 0) return
        if (!s.fits(summary.bytes)) {
            openPaywall()
            return
        }
        val paths = summary.files.map { it.path }
        val sizeByPath = summary.files.associate { it.path to it.size }
        val mode = s.cleanMode
        val tabName = (s.tool?.name ?: s.resultsTab.name).lowercase()
        val startedAt = SystemClock.elapsedRealtime()
        Telemetry.event(
            "clean_start",
            "mode" to mode.name.lowercase(), "tab" to tabName,
            "files" to summary.count, "mb" to (summary.bytes shr 20), "plan" to plan()
        )

        val countsAgainstAllowance = !s.isPro
        cleaning = true
        viewModelScope.launch {
            try {
                // NonCancellable: once files start moving, the usage MUST be recorded even if the screen
                // is closed meanwhile, or a free user would get the cleanup without it being counted.
                val result = withContext(NonCancellable + Dispatchers.IO) {
                    val r = if (mode == CleanMode.TRASH) Trash.moveToTrash(paths) else Trash.deletePermanently(paths)
                    val movedBytes = r.moved.sumOf { sizeByPath[it] ?: 0L }
                    if (countsAgainstAllowance) entitlements.recordUsed(movedBytes)
                    if (mode == CleanMode.DELETE) prefs.addFreed(movedBytes, r.moved.size)
                    // Tell the media library so gallery / music apps drop the old entries.
                    try {
                        if (r.moved.isNotEmpty()) MediaScannerConnection.scanFile(app, r.moved.toTypedArray(), null, null)
                    } catch (e: Exception) {
                        Telemetry.error("media_scan", e)
                    }
                    r
                }
                val gone = result.moved.toSet()
                gonePaths += gone
                val doneBytes = result.moved.sumOf { sizeByPath[it] ?: 0L }

                _state.update { st ->
                    val groups = st.groups
                        .map { g -> g.copy(files = g.files.filter { it.path !in gone }) }
                        .filter { it.files.size >= 2 }
                    val similar = st.similarGroups
                        .map { g -> g.without(gone) }
                        .filter { it.photos.size >= 2 }
                    st.copy(
                        groups = groups,
                        selected = st.selected - gone,
                        similarGroups = similar,
                        similarSelected = st.similarSelected - gone,
                        toolFiles = st.toolFiles.filter { it.path !in gone },
                        toolSelected = st.toolSelected - gone,
                        freeRemaining = entitlements.freeRemaining(),
                        freeTotal = entitlements.freeTotal(),
                        lifetimeFreedBytes = prefs.lifetimeFreedBytes,
                        lifetimeFiles = prefs.lifetimeFiles
                    )
                }
                val failed = result.failed
                Telemetry.event(
                    "clean_complete",
                    "mode" to mode.name.lowercase(), "tab" to tabName,
                    "files" to gone.size, "mb" to (doneBytes shr 20), "failed" to failed,
                    "duration_ms" to (SystemClock.elapsedRealtime() - startedAt), "plan" to plan()
                )
                val summaryText = if (mode == CleanMode.TRASH) {
                    "Moved ${gone.size} files (${formatSize(doneBytes)}) to Trash"
                } else {
                    "Deleted ${gone.size} files · freed ${formatSize(doneBytes)}"
                }
                val text = summaryText + if (failed > 0) " · $failed failed" else ""
                toast(text, if (mode == CleanMode.TRASH) SnackAction.VIEW_TRASH else null)
                refreshTrash()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Telemetry.error("clean", e)
                toast("Couldn't finish cleaning. Check the Trash tab to see what was moved.")
                refreshTrash()
            } finally {
                cleaning = false
            }
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
            Telemetry.event("trash_restore", "files" to restored.size, "failed" to failed)
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
            Telemetry.event("trash_delete_forever", "files" to removed, "mb" to (bytes shr 20))
            toast("Deleted $removed files · freed ${formatSize(bytes)}")
            refreshTrash()
        }
    }

    // ---- Pro / allowance ----

    fun openPaywall() {
        refreshAllowance()
        val s = _state.value
        if (s.showPaywall || s.isPro) return
        Telemetry.event(
            "paywall_view",
            "from" to when {
                s.tab == Tab.SETTINGS -> "settings"
                s.tool != null -> s.tool.name.lowercase()
                s.resultsTab == ResultsTab.SIMILAR -> "similar"
                else -> "results"
            },
            "selected_mb" to (s.activeSummary().bytes shr 20),
            "free_left_mb" to (s.freeRemaining shr 20),
            "ads_left" to s.adsLeftToday
        )
        ads.loadRewarded()
        _state.update { it.copy(showPaywall = true) }
    }

    fun closePaywall() {
        if (_state.value.showPaywall) Telemetry.event("paywall_dismiss")
        _state.update { it.copy(showPaywall = false) }
    }

    fun buyPro(activity: Activity) = billing.launchPurchase(activity)

    fun restorePurchase() {
        Telemetry.event("restore_purchase_tap")
        billing.refresh(userInitiated = true)
        toast("Checking your purchases…")
    }

    fun watchAd(activity: Activity) {
        refreshAllowance()
        if (entitlements.adsLeftToday() <= 0) {
            Telemetry.event("rewarded_ad_cap_reached")
            toast("You've used today's ad top-ups. Come back tomorrow, or unlock Pro.")
            return
        }
        Telemetry.event("rewarded_ad_tap", "ads_left" to entitlements.adsLeftToday())
        ads.onShowFailed = { toast("The ad couldn't be shown. Please try again in a moment.") }
        val started = ads.showRewarded(activity) {
            val added = entitlements.addRewardBonus()
            Telemetry.event(
                "rewarded_ad_earned",
                "granted" to added,
                "ads_left" to entitlements.adsLeftToday(),
                "mb" to (Entitlements.REWARD_BYTES shr 20)
            )
            _state.update {
                it.copy(
                    freeRemaining = entitlements.freeRemaining(),
                    freeTotal = entitlements.freeTotal(),
                    adsLeftToday = entitlements.adsLeftToday(),
                    message = if (added) "Added ${formatSize(Entitlements.REWARD_BYTES)} of free cleanup"
                    else "You've used today's ad top-ups. Come back tomorrow.",
                    messageAction = null
                )
            }
        }
        if (!started) toast("The ad isn't ready yet. Try again in a moment.")
    }

    /** Shrinks the selection to the largest files that fit in what's left of the free allowance. */
    fun cleanFreePortion() {
        // The free allowance never covers similar photos.
        if (_state.value.similarLocked) return
        _state.update { s ->
            var budget = s.freeRemaining
            val keep = mutableSetOf<String>()
            s.activeSummary().files
                .sortedByDescending { it.size }
                .forEach { f ->
                    if (f.size <= budget) {
                        keep += f.path
                        budget -= f.size
                    }
                }
            val msg = if (keep.isEmpty()) "No selected file fits your free allowance" else null
            if (s.tool != null) {
                val inScope = s.toolVisible.map { it.path }.toSet()
                s.copy(
                    toolSelected = keep + (s.toolSelected - inScope),
                    showPaywall = false, message = msg, messageAction = null
                )
            } else if (s.resultsTab == ResultsTab.SIMILAR) {
                val inScope = s.similarGroups.flatMap { g -> g.files.map { it.path } }.toSet()
                s.copy(
                    similarSelected = keep + (s.similarSelected - inScope),
                    showPaywall = false, message = msg, messageAction = null
                )
            } else {
                val outsideScope = s.selected - s.scopedGroups.flatMap { g -> g.files.map { it.path } }.toSet()
                s.copy(
                    selected = keep + outsideScope,
                    showPaywall = false, message = msg, messageAction = null
                )
            }
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
