@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.example.duplicatecleaner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import com.example.duplicatecleaner.KeepRule
import com.example.duplicatecleaner.MainViewModel
import com.example.duplicatecleaner.UiState
import com.example.duplicatecleaner.data.CleanMode
import com.example.duplicatecleaner.data.DuplicateGroup
import com.example.duplicatecleaner.data.FileKind
import com.example.duplicatecleaner.data.MediaFile
import com.example.duplicatecleaner.folderStats
import com.example.duplicatecleaner.formatSize

@Composable
fun ResultsScreen(
    state: UiState,
    vm: MainViewModel,
    imageLoader: ImageLoader,
    pro: ProActions
) {
    var confirming by remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf<MediaFile?>(null) }

    // Derived data is cached; the lists can be thousands of groups long.
    val summary = remember(state.groups, state.selected, state.typeFilter) { state.selectionSummary() }
    val visible = remember(
        state.groups, state.smart, state.reviewed, state.reviewOnly, state.keepRule, state.typeFilter
    ) { state.visibleGroups }
    val reviewCount = remember(
        state.groups, state.smart, state.reviewed, state.keepRule, state.typeFilter
    ) { state.reviewGroups.size }
    val kindCounts = remember(state.groups) { state.groups.groupingBy { it.kind }.eachCount() }
    val totalReclaim = remember(state.groups) { state.groups.sumOf { it.reclaimable } }
    val totalFiles = remember(state.groups) { state.groups.sumOf { it.files.size } }

    if (state.groups.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            TopRow(onBack = vm::leaveResults, onTune = null)
            EmptyState(
                icon = Icons.Rounded.AutoAwesome,
                title = "Nothing to clean",
                subtitle = "No duplicates here. Your storage is tidy.",
                actionLabel = "Back to Home",
                onAction = vm::leaveResults
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        TopRow(onBack = vm::leaveResults, onTune = { showSheet = true })

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "hero") {
                HeroSummary(state, totalReclaim, totalFiles, pro)
            }
            if (kindCounts.size > 1) {
                item(key = "filters") {
                    KindFilters(state.typeFilter, kindCounts, state.groups.size, vm)
                }
            }
            if (reviewCount > 0) {
                item(key = "review") {
                    ReviewBanner(reviewCount, state.reviewOnly, vm::toggleReviewOnly)
                }
            }
            items(visible, key = { it.files.first().path }) { group ->
                GroupCard(group, state, imageLoader, vm) { previewing = it }
            }
        }

        BottomBar(state, summary) {
            if (state.fits(summary.bytes)) confirming = true else pro.openPaywall()
        }
    }

    // Preview
    previewing?.let { current ->
        val group = state.groups.firstOrNull { g -> g.files.any { it.path == current.path } }
        if (group != null) {
            val files = remember(group) { group.files.sortedBy { it.dateModified } }
            val idx = files.indexOfFirst { it.path == current.path }.coerceAtLeast(0)
            val remove = files[idx].path in state.selected
            PreviewDialog(
                files = files,
                index = idx,
                imageLoader = imageLoader,
                statusText = if (remove) "Will be cleaned" else "Will be kept",
                statusIsWarning = remove,
                actionLabel = if (remove) "Keep this copy" else "Clean this copy",
                actionIcon = if (remove) Icons.Rounded.Check else Icons.Rounded.Delete,
                actionEnabled = true,
                onIndex = { previewing = files[it] },
                onAction = { vm.toggle(files[idx]) },
                onClose = { previewing = null }
            )
        }
    }

    // Confirm
    if (confirming) {
        val toTrash = state.cleanMode == CleanMode.TRASH
        AlertDialog(
            onDismissRequest = { confirming = false },
            icon = { Icon(if (toTrash) Icons.Rounded.DeleteSweep else Icons.Rounded.DeleteForever, null) },
            title = {
                Text(if (toTrash) "Move ${summary.count} files to Trash?" else "Delete ${summary.count} files?")
            },
            text = {
                Column {
                    Text(
                        formatSize(summary.bytes) + if (toTrash) {
                            " · you can review and restore them from Trash."
                        } else {
                            " · this can't be undone."
                        }
                    )
                    if (summary.fullyRemovedGroups > 0) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (summary.fullyRemovedGroups == 1) "1 group will lose every copy."
                            else "${summary.fullyRemovedGroups} groups will lose every copy.",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; vm.cleanSelected() }) {
                    Text(if (toTrash) "Move to Trash" else "Delete")
                }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } }
        )
    }

    // Choose what to keep
    if (showSheet) {
        val folders = remember(state.groups) { folderStats(state.groups).take(8) }
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            Column(
                Modifier
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Which copy to keep?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RuleChip("Smart", Icons.Rounded.AutoAwesome, state.keepRule == KeepRule.SMART) {
                        vm.applyRule(KeepRule.SMART); showSheet = false
                    }
                    RuleChip("Oldest", Icons.Rounded.History, state.keepRule == KeepRule.OLDEST) {
                        vm.applyRule(KeepRule.OLDEST); showSheet = false
                    }
                    RuleChip("Newest", Icons.Rounded.Update, state.keepRule == KeepRule.NEWEST) {
                        vm.applyRule(KeepRule.NEWEST); showSheet = false
                    }
                    RuleChip("Shortest path", null, state.keepRule == KeepRule.SHORTEST_PATH) {
                        vm.applyRule(KeepRule.SHORTEST_PATH); showSheet = false
                    }
                }
                Spacer(Modifier.height(20.dp))
                SectionTitle("Or keep the copy in a folder")
                Spacer(Modifier.height(4.dp))
                folders.forEach { f ->
                    val active = state.keepRule == KeepRule.IN_FOLDER && state.keepFolder == f.folder
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { vm.applyRule(KeepRule.IN_FOLDER, f.folder); showSheet = false }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconCircle(Icons.Rounded.Folder, size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(folderLabel(f.folder), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${f.files} files · ${formatSize(f.bytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (active) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopRow(onBack: () -> Unit, onTune: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Text("Duplicates", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (onTune != null) {
            IconButton(onClick = onTune) { Icon(Icons.Rounded.Tune, "Choose what to keep") }
        }
    }
}

@Composable
private fun HeroSummary(state: UiState, totalReclaim: Long, totalFiles: Int, pro: ProActions) {
    GradientCard {
        Column {
            Text(
                formatSize(totalReclaim),
                style = MaterialTheme.typography.displaySmall,
                color = Color.White
            )
            Text(
                "can be reclaimed · $totalFiles files in ${state.groups.size} groups",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f)
            )
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .background(Color.White.copy(alpha = 0.18f))
                    .then(if (state.isPro) Modifier else Modifier.clickable(onClick = pro.openPaywall))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (state.isPro) Icons.Rounded.Verified else Icons.Rounded.Bolt, null,
                    Modifier.size(18.dp), tint = Color.White
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.isPro) "Pro · unlimited cleanup"
                    else "Free cleanup left: ${formatSize(state.freeRemaining)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun KindFilters(
    selected: FileKind?,
    counts: Map<FileKind, Int>,
    total: Int,
    vm: MainViewModel
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { vm.setTypeFilter(null) },
            label = { Text("All $total") }
        )
        counts.entries.sortedByDescending { it.value }.forEach { (kind, n) ->
            FilterChip(
                selected = selected == kind,
                onClick = { vm.setTypeFilter(if (selected == kind) null else kind) },
                label = { Text("${kind.label} $n") },
                leadingIcon = { Icon(kind.icon, null, Modifier.size(18.dp)) }
            )
        }
    }
}

@Composable
private fun ReviewBanner(count: Int, reviewOnly: Boolean, onToggle: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(10.dp))
            Text(
                if (count == 1) "1 needs a quick look" else "$count need a quick look",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onToggle) { Text(if (reviewOnly) "Show all" else "Show only these") }
        }
    }
}

@Composable
private fun RuleChip(label: String, icon: ImageVector?, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            val shown = if (selected) Icons.Rounded.Check else icon
            if (shown != null) Icon(shown, null, Modifier.size(18.dp))
        }
    )
}

@Composable
private fun GroupCard(
    group: DuplicateGroup,
    state: UiState,
    imageLoader: ImageLoader,
    vm: MainViewModel,
    onPreview: (MediaFile) -> Unit
) {
    val choice = if (state.keepRule == KeepRule.SMART) state.choiceFor(group) else null
    val needsReview = choice?.confident == false && group.files.none { it.path in state.reviewed }
    val files = remember(group) { group.files.sortedBy { it.dateModified } }
    var menu by remember { mutableStateOf(false) }

    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            // Header
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(group.kind.icon, null, Modifier.size(20.dp), tint = group.kind.tint)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${group.files.size} copies · ${formatSize(group.size)} each",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Group options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Keep all copies") },
                            leadingIcon = { Icon(Icons.Rounded.DoneAll, null) },
                            onClick = { menu = false; vm.keepAll(group) }
                        )
                        DropdownMenuItem(
                            text = { Text("Clean all copies") },
                            leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) },
                            onClick = { menu = false; vm.removeAll(group) }
                        )
                        DropdownMenuItem(
                            text = { Text("Reset to automatic") },
                            leadingIcon = { Icon(Icons.Rounded.Restore, null) },
                            onClick = { menu = false; vm.resetGroup(group) }
                        )
                    }
                }
            }

            // Why this copy was chosen
            if (choice != null) {
                Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Rounded.AutoAwesome, null,
                        Modifier.padding(top = 2.dp).size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        choice.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Not sure: the text takes the free space, the button keeps its size
            if (needsReview) {
                Row(
                    Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Warning, null,
                        Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Please check",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.markReviewed(group) }) { Text("Looks good") }
                }
            }

            // Copies
            files.forEach { file ->
                FileRow(
                    file = file,
                    remove = file.path in state.selected,
                    imageLoader = imageLoader,
                    onPreview = { onPreview(file) },
                    onToggle = { vm.toggle(file) }
                )
            }
        }
    }
}

@Composable
private fun FileRow(
    file: MediaFile,
    remove: Boolean,
    imageLoader: ImageLoader,
    onPreview: () -> Unit,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPreview)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FileThumb(file, imageLoader)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                folderLabel(file.folder),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        StatusPill(remove = remove, onClick = onToggle)
    }
}

@Composable
private fun BottomBar(state: UiState, summary: com.example.duplicatecleaner.SelectionSummary, onClean: () -> Unit) {
    val fits = state.fits(summary.bytes)
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column {
            if (!state.isPro) BannerAd(Modifier.fillMaxWidth())
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (summary.count == 0) "Nothing selected" else formatSize(summary.bytes),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (summary.count > 0) {
                        Text(
                            "${summary.count} files selected",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Button(
                    onClick = onClean,
                    enabled = summary.count > 0,
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(if (fits) Icons.Rounded.DeleteSweep else Icons.Rounded.Lock, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (fits) "Clean up" else "Unlock")
                }
            }
        }
    }
}
