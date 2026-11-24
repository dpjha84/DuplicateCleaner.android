@file:OptIn(ExperimentalMaterial3Api::class)

package com.dpjha.cloneout.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import com.dpjha.cloneout.MainViewModel
import com.dpjha.cloneout.UiState
import com.dpjha.cloneout.data.CleanMode
import com.dpjha.cloneout.data.FileKind
import com.dpjha.cloneout.data.MediaFile
import com.dpjha.cloneout.data.Tool
import com.dpjha.cloneout.data.WaCategory
import com.dpjha.cloneout.data.isWaSent
import com.dpjha.cloneout.data.waCategoryOf
import com.dpjha.cloneout.formatSize
import java.text.DateFormat
import java.util.Date

private val LARGE_SIZES = listOf(50L shl 20 to "50 MB+", 100L shl 20 to "100 MB+", 500L shl 20 to "500 MB+", 1024L shl 20 to "1 GB+")

/** Large files and WhatsApp media: a plain list of files, nothing selected until the user picks. */
@Composable
fun ToolScreen(state: UiState, vm: MainViewModel, imageLoader: ImageLoader, pro: ProActions) {
    val tool = state.tool ?: return
    var confirming by remember { mutableStateOf(false) }
    var previewPath by remember { mutableStateOf<String?>(null) }

    val visible = remember(state.toolFiles, state.largeMinBytes, state.toolKind, state.waCategory, state.tool) {
        state.toolVisible
    }
    val summary = remember(visible, state.toolSelected) { state.toolSummary(visible) }
    val title = if (tool == Tool.LARGE) "Large files" else "WhatsApp media"

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::leaveResults) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }

        when {
            state.toolRunning -> {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(Modifier.size(96.dp), strokeWidth = 8.dp)
                    Spacer(Modifier.height(24.dp))
                    Text(
                        if (tool == Tool.LARGE) "Looking for large files…" else "Looking through WhatsApp media…",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (state.toolScanned > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${state.toolScanned} files checked",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(onClick = vm::leaveResults) {
                        Icon(Icons.Rounded.Close, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Cancel")
                    }
                }
            }

            state.toolFiles.isEmpty() -> {
                EmptyState(
                    icon = Icons.Rounded.Search,
                    title = if (tool == Tool.LARGE) "No large files" else "No WhatsApp media found",
                    subtitle = if (tool == Tool.LARGE) "Nothing over 50 MB turned up on your phone."
                    else "WhatsApp isn't installed here, or it hasn't saved any media on this phone.",
                    actionLabel = "Back to Home",
                    onAction = vm::leaveResults,
                    modifier = Modifier.weight(1f)
                )
            }

            else -> {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item(key = "hero") {
                        HeroSummary(
                            state,
                            visible.sumOf { it.size },
                            if (tool == Tool.LARGE) "in ${visible.size} large files · nothing is selected yet"
                            else "in ${visible.size} WhatsApp files · nothing is selected yet",
                            pro
                        )
                    }
                    item(key = "filters") {
                        if (tool == Tool.LARGE) LargeFilters(state, vm) else WhatsAppFilters(state, vm)
                    }
                    if (tool == Tool.WHATSAPP) {
                        item(key = "wa-note") {
                            Text(
                                "Chats stay. Cleaned photos and videos show as missing inside WhatsApp, and Trash can bring them back.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (visible.isEmpty()) {
                        item(key = "none") {
                            Text(
                                "No files match these filters.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
                            )
                        }
                    } else {
                        item(key = "select-all") {
                            val allOn = visible.all { it.path in state.toolSelected }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${summary.count} of ${visible.size} selected",
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = vm::toggleSelectAllTool) {
                                    Text(if (allOn) "Clear" else "Select all")
                                }
                            }
                        }
                        items(visible, key = { it.path }) { file ->
                            ToolRow(
                                file = file,
                                tool = tool,
                                selected = file.path in state.toolSelected,
                                imageLoader = imageLoader,
                                onPreview = { previewPath = file.path },
                                onToggle = { vm.toggleTool(file) }
                            )
                        }
                    }
                }
                BottomBar(state, summary) {
                    if (state.fits(summary.bytes)) confirming = true else pro.openPaywall()
                }
            }
        }
    }

    // Preview
    previewPath?.let { path ->
        val idx = visible.indexOfFirst { it.path == path }
        if (idx >= 0) {
            val cur = visible[idx]
            val on = cur.path in state.toolSelected
            PreviewDialog(
                files = visible,
                index = idx,
                imageLoader = imageLoader,
                statusText = if (on) "Selected to clean" else "Not selected",
                statusIsWarning = on,
                actionLabel = if (on) "Keep this file" else "Select to clean",
                actionIcon = if (on) Icons.Rounded.Check else Icons.Rounded.Delete,
                actionEnabled = true,
                onIndex = { previewPath = visible[it].path },
                onAction = { vm.toggleTool(cur) },
                onClose = { previewPath = null }
            )
        } else {
            LaunchedEffect(path) { previewPath = null }
        }
    }

    // Confirm
    if (confirming) {
        val toTrash = state.cleanMode == CleanMode.TRASH
        AlertDialog(
            onDismissRequest = { confirming = false },
            icon = { Icon(if (toTrash) Icons.Rounded.DeleteSweep else Icons.Rounded.DeleteForever, null) },
            title = { Text(if (toTrash) "Move ${summary.count} files to Trash?" else "Delete ${summary.count} files?") },
            text = {
                Column {
                    Text(
                        formatSize(summary.bytes) + if (toTrash) {
                            " · you can review and restore them from Trash."
                        } else {
                            " · this can't be undone."
                        }
                    )
                    if (tool == Tool.LARGE) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "These are not duplicates: once they're gone there is no other copy. Check they're files you don't need.",
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
}

@Composable
private fun LargeFilters(state: UiState, vm: MainViewModel) {
    // Types present among the files that pass the size filter.
    val kinds = remember(state.toolFiles, state.largeMinBytes) {
        state.toolFiles.filter { it.size >= state.largeMinBytes }.groupingBy { it.kind }.eachCount()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LARGE_SIZES.forEach { (bytes, label) ->
                FilterChip(
                    selected = state.largeMinBytes == bytes,
                    onClick = { vm.setLargeMin(bytes) },
                    label = { Text(label) }
                )
            }
        }
        if (kinds.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.toolKind == null,
                    onClick = { vm.setToolKind(null) },
                    label = { Text("All types") }
                )
                kinds.entries.sortedByDescending { it.value }.forEach { (kind, n) ->
                    FilterChip(
                        selected = state.toolKind == kind,
                        onClick = { vm.setToolKind(if (state.toolKind == kind) null else kind) },
                        label = { Text("${kind.label} $n") },
                        leadingIcon = { Icon(kind.icon, null, Modifier.size(18.dp)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun WhatsAppFilters(state: UiState, vm: MainViewModel) {
    val sizes = remember(state.toolFiles) {
        val m = HashMap<WaCategory, Long>()
        state.toolFiles.forEach { m[waCategoryOf(it.path)] = (m[waCategoryOf(it.path)] ?: 0L) + it.size }
        m
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.waCategory == null,
            onClick = { vm.setWaCategory(null) },
            label = { Text("All") }
        )
        sizes.entries.sortedByDescending { it.value }.forEach { (cat, bytes) ->
            FilterChip(
                selected = state.waCategory == cat,
                onClick = { vm.setWaCategory(if (state.waCategory == cat) null else cat) },
                label = { Text("${cat.label} ${formatSize(bytes)}") }
            )
        }
    }
}

@Composable
private fun ToolRow(
    file: MediaFile,
    tool: Tool,
    selected: Boolean,
    imageLoader: ImageLoader,
    onPreview: () -> Unit,
    onToggle: () -> Unit
) {
    val date = remember(file.dateModified) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(file.dateModified)) }
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onPreview),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FileThumb(file, imageLoader)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatSize(file.size)} · $date" +
                        if (tool == Tool.WHATSAPP && isWaSent(file.path)) " · Sent by you" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (tool == Tool.LARGE) {
                    Text(
                        folderLabel(file.folder),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
        }
    }
}
