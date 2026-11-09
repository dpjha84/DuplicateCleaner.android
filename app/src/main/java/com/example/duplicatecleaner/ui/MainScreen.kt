package com.example.duplicatecleaner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import com.example.duplicatecleaner.KeepRule
import com.example.duplicatecleaner.Phase
import com.example.duplicatecleaner.UiState
import com.example.duplicatecleaner.data.DuplicateGroup
import com.example.duplicatecleaner.data.FileKind
import com.example.duplicatecleaner.data.MediaFile
import com.example.duplicatecleaner.data.Trash
import com.example.duplicatecleaner.folderStats
import com.example.duplicatecleaner.formatSize
import java.io.File

@Composable
fun MainScreen(
    state: UiState,
    onScan: () -> Unit,
    onCancel: () -> Unit,
    onToggle: (MediaFile) -> Unit,
    onRule: (KeepRule, String?) -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onEmptyTrash: () -> Unit,
    onMessageShown: () -> Unit
) {
    var confirming by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf<MediaFile?>(null) }

    val context = LocalContext.current
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
    }

    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        when (state.phase) {
            Phase.IDLE -> IdleContent(state, onScan, onRestore, onEmptyTrash)
            Phase.SCANNING -> ScanningContent(state, onCancel)
            Phase.RESULTS -> ResultsContent(
                state = state,
                imageLoader = imageLoader,
                onPreview = { previewing = it },
                onToggle = onToggle,
                onRule = onRule,
                onRescan = onScan,
                onTrash = { confirming = true },
                onRestore = onRestore,
                onEmptyTrash = onEmptyTrash
            )
        }

        state.message?.let { msg ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { TextButton(onClick = onMessageShown) { Text("OK") } }
            ) { Text(msg) }
        }
    }

    previewing?.let { current ->
        val group = state.groups.firstOrNull { g -> g.files.any { it.path == current.path } }
        if (group != null) {
            val files = group.files.sortedBy { it.dateModified }
            val idx = files.indexOfFirst { it.path == current.path }.coerceAtLeast(0)
            PreviewDialog(
                files = files,
                index = idx,
                selectedPaths = state.selected,
                imageLoader = imageLoader,
                onIndex = { previewing = files[it] },
                onToggle = { onToggle(files[idx]) },
                onClose = { previewing = null }
            )
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Move ${state.selected.size} files to trash?") },
            text = {
                Text(
                    "They go to the \"${Trash.DIR_NAME}\" folder on your storage " +
                        "(${formatSize(state.selectedBytes)}) and can be restored. " +
                        "Space is only freed when you empty the trash. " +
                        "One copy of each file is always kept."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; onTrash() }) { Text("Move to trash") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun IdleContent(
    state: UiState,
    onScan: () -> Unit,
    onRestore: () -> Unit,
    onEmptyTrash: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Duplicate Cleaner", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "Finds identical files of any type (photos, videos, audio, documents, " +
                "downloads and more) by comparing their contents, so renamed copies " +
                "are caught too. The app asks for all-files access so it can scan and " +
                "move files across your storage.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onScan) { Text("Scan device") }
        if (state.trashCount > 0) {
            Spacer(Modifier.height(24.dp))
            TrashCard(state.trashCount, state.trashBytes, onRestore, onEmptyTrash)
        }
    }
}

@Composable
private fun ScanningContent(state: UiState, onCancel: () -> Unit) {
    val p = state.progress
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(p.message, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        if (p.total > 0) {
            LinearProgressIndicator(
                progress = { p.done.toFloat() / p.total },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text("${p.done} / ${p.total}", style = MaterialTheme.typography.bodySmall)
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun ResultsContent(
    state: UiState,
    imageLoader: ImageLoader,
    onPreview: (MediaFile) -> Unit,
    onToggle: (MediaFile) -> Unit,
    onRule: (KeepRule, String?) -> Unit,
    onRescan: () -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onEmptyTrash: () -> Unit
) {
    val folders = remember(state.groups) { folderStats(state.groups).take(6) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("${state.groups.size} duplicate groups", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Reclaimable: ${formatSize(state.groups.sumOf { it.reclaimable })}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            OutlinedButton(onClick = onRescan) { Text("Rescan") }
        }

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
        ) {
            if (state.groups.isNotEmpty()) {
                item { KeepRuleCard(state, onRule) }
                item { FolderSummaryCard(folders, state, onRule) }
            }
            if (state.trashCount > 0) {
                item { TrashCard(state.trashCount, state.trashBytes, onRestore, onEmptyTrash) }
            }
            items(state.groups, key = { g -> g.files.first().path }) { group ->
                GroupCard(group, state, imageLoader, onToggle, onPreview)
            }
        }

        if (state.groups.isNotEmpty()) {
            Button(
                onClick = onTrash,
                enabled = state.selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Move ${state.selected.size} files to trash (${formatSize(state.selectedBytes)})")
            }
        }
    }
}

@Composable
private fun KeepRuleCard(state: UiState, onRule: (KeepRule, String?) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Which copy should be kept?", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuleButton("Oldest", state.keepRule == KeepRule.OLDEST, Modifier.weight(1f)) {
                    onRule(KeepRule.OLDEST, null)
                }
                RuleButton("Newest", state.keepRule == KeepRule.NEWEST, Modifier.weight(1f)) {
                    onRule(KeepRule.NEWEST, null)
                }
                RuleButton("Short path", state.keepRule == KeepRule.SHORTEST_PATH, Modifier.weight(1f)) {
                    onRule(KeepRule.SHORTEST_PATH, null)
                }
            }
            if (state.keepRule == KeepRule.IN_FOLDER) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Keeping the copy in: ${folderLabel(state.keepFolder ?: "")}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun RuleButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val padding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
    if (selected) {
        Button(onClick = onClick, modifier = modifier, contentPadding = padding) {
            Text(label, maxLines = 1)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = padding) {
            Text(label, maxLines = 1)
        }
    }
}

@Composable
private fun FolderSummaryCard(
    folders: List<com.example.duplicatecleaner.FolderStat>,
    state: UiState,
    onRule: (KeepRule, String?) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Where the duplicates are", style = MaterialTheme.typography.labelLarge)
            Text(
                "Tap \"Keep here\" to keep the copy in that folder for every group that has one.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(4.dp))
            folders.forEach { f ->
                val active = state.keepRule == KeepRule.IN_FOLDER && state.keepFolder == f.folder
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(folderLabel(f.folder), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${f.files} files · ${formatSize(f.bytes)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(
                        onClick = { onRule(KeepRule.IN_FOLDER, f.folder) },
                        enabled = !active
                    ) { Text(if (active) "Keeping" else "Keep here") }
                }
            }
        }
    }
}

@Composable
private fun TrashCard(
    count: Int,
    bytes: Long,
    onRestore: () -> Unit,
    onEmpty: () -> Unit
) {
    var confirmEmpty by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Trash: $count files · ${formatSize(bytes)}", style = MaterialTheme.typography.labelLarge)
            Text(
                "Folder \"${Trash.DIR_NAME}\" on your storage. Space is freed when you empty it.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRestore) { Text("Restore all") }
                TextButton(onClick = { confirmEmpty = true }) { Text("Empty trash") }
            }
        }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty trash?") },
            text = { Text("This permanently deletes $count files (${formatSize(bytes)}) and cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmEmpty = false; onEmpty() }) { Text("Delete forever") }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun GroupCard(
    group: DuplicateGroup,
    state: UiState,
    imageLoader: ImageLoader,
    onToggle: (MediaFile) -> Unit,
    onPreview: (MediaFile) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${group.files.size} copies · ${formatSize(group.size)} each",
                style = MaterialTheme.typography.labelLarge
            )
            Text("Tap a file to preview it", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            group.files.sortedBy { it.dateModified }.forEach { file ->
                FileRow(
                    file = file,
                    selected = file.path in state.selected,
                    imageLoader = imageLoader,
                    onToggle = { onToggle(file) },
                    onPreview = { onPreview(file) }
                )
            }
        }
    }
}

@Composable
private fun FileRow(
    file: MediaFile,
    selected: Boolean,
    imageLoader: ImageLoader,
    onToggle: () -> Unit,
    onPreview: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onPreview),
            contentAlignment = Alignment.Center
        ) {
            when (file.kind) {
                FileKind.IMAGE, FileKind.VIDEO -> AsyncImage(
                    model = File(file.path),
                    imageLoader = imageLoader,
                    contentDescription = file.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                FileKind.AUDIO -> Text("♪", style = MaterialTheme.typography.headlineSmall)
                else -> Text(
                    file.name.substringAfterLast('.', "FILE").take(4).uppercase(),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Column(Modifier.weight(1f).clickable(onClick = onPreview).padding(start = 12.dp)) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                folderLabel(file.folder),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(if (selected) "Trash" else "Keep", style = MaterialTheme.typography.labelSmall)
    }
}

private fun folderLabel(folder: String): String = folder.ifEmpty { "Internal storage (root)" }
