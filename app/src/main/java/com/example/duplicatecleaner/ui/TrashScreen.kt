@file:OptIn(ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import com.example.duplicatecleaner.MainViewModel
import com.example.duplicatecleaner.UiState
import com.example.duplicatecleaner.data.MediaFile
import com.example.duplicatecleaner.formatSize

private enum class TrashConfirm { DELETE_SELECTED, EMPTY_ALL }

@Composable
fun TrashScreen(state: UiState, vm: MainViewModel, imageLoader: ImageLoader) {
    var confirm by remember { mutableStateOf<TrashConfirm?>(null) }
    var previewing by remember { mutableStateOf<MediaFile?>(null) }
    var menu by remember { mutableStateOf(false) }
    val selecting = state.trashSelected.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        // Top bar: switches to a selection toolbar
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selecting) {
                IconButton(onClick = vm::clearTrashSelection) { Icon(Icons.Rounded.Close, "Clear selection") }
                Text(
                    "${state.trashSelected.size} selected",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = vm::selectAllTrash) { Icon(Icons.Rounded.SelectAll, "Select all") }
                IconButton(onClick = { vm.restore(state.trashSelected.toList()) }) {
                    Icon(Icons.Rounded.Restore, "Restore")
                }
                IconButton(onClick = { confirm = TrashConfirm.DELETE_SELECTED }) {
                    Icon(Icons.Rounded.DeleteForever, "Delete forever", tint = MaterialTheme.colorScheme.error)
                }
            } else {
                Column(Modifier.weight(1f)) {
                    Text("Trash", style = MaterialTheme.typography.headlineSmall)
                    if (state.trashItems.isNotEmpty()) {
                        Text(
                            "${state.trashItems.size} files · ${formatSize(state.trashBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (state.trashItems.isNotEmpty()) {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Select all") },
                                leadingIcon = { Icon(Icons.Rounded.SelectAll, null) },
                                onClick = { menu = false; vm.selectAllTrash() }
                            )
                            DropdownMenuItem(
                                text = { Text("Restore all") },
                                leadingIcon = { Icon(Icons.Rounded.Restore, null) },
                                onClick = { menu = false; vm.restore(state.trashItems.map { it.path }) }
                            )
                            DropdownMenuItem(
                                text = { Text("Empty Trash") },
                                leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) },
                                onClick = { menu = false; confirm = TrashConfirm.EMPTY_ALL }
                            )
                        }
                    }
                }
            }
        }

        if (state.trashItems.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.RestoreFromTrash,
                title = "Trash is empty",
                subtitle = "Files you clean up wait here, so you can change your mind."
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                item(key = "info") {
                    Card(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Space is only freed when Trash is emptied.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
                items(state.trashItems, key = { it.path }) { file ->
                    val checked = file.path in state.trashSelected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { if (selecting) vm.toggleTrashSelect(file.path) else previewing = file }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { vm.toggleTrashSelect(file.path) })
                        FileThumb(file, imageLoader)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${formatSize(file.size)} · ${folderLabel(file.folder)}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { vm.restore(listOf(file.path)) }) {
                            Icon(Icons.Rounded.Restore, "Restore", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    previewing?.let { current ->
        val files = state.trashItems
        val idx = files.indexOfFirst { it.path == current.path }
        if (idx >= 0) {
            PreviewDialog(
                files = files,
                index = idx,
                imageLoader = imageLoader,
                statusText = "In Trash · from ${folderLabel(files[idx].folder)}",
                statusIsWarning = false,
                actionLabel = "Restore",
                actionIcon = Icons.Rounded.Restore,
                actionEnabled = true,
                onIndex = { previewing = files[it] },
                onAction = { vm.restore(listOf(files[idx].path)); previewing = null },
                onClose = { previewing = null }
            )
        } else previewing = null
    }

    confirm?.let { kind ->
        val all = kind == TrashConfirm.EMPTY_ALL
        val paths = if (all) state.trashItems.map { it.path } else state.trashSelected.toList()
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Rounded.DeleteForever, null) },
            title = { Text(if (all) "Empty Trash?" else "Delete ${paths.size} files forever?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirm = null; vm.deleteFromTrash(paths) }) { Text("Delete forever") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }
}
