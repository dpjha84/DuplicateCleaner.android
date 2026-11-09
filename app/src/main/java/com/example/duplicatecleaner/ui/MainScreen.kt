package com.example.duplicatecleaner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import com.example.duplicatecleaner.Phase
import com.example.duplicatecleaner.UiState
import com.example.duplicatecleaner.data.DuplicateGroup
import com.example.duplicatecleaner.data.MediaFile
import com.example.duplicatecleaner.data.FileKind
import java.io.File
import com.example.duplicatecleaner.formatSize

@Composable
fun MainScreen(
    state: UiState,
    onScan: () -> Unit,
    onCancel: () -> Unit,
    onToggle: (MediaFile) -> Unit,
    onDelete: () -> Unit,
    onMessageShown: () -> Unit
) {
    var confirming by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        when (state.phase) {
            Phase.IDLE -> IdleContent(onScan)
            Phase.SCANNING -> ScanningContent(state, onCancel)
            Phase.RESULTS -> ResultsContent(
                state = state,
                onToggle = onToggle,
                onRescan = onScan,
                onDelete = { confirming = true }
            )
        }

        state.message?.let { msg ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { TextButton(onClick = onMessageShown) { Text("OK") } }
            ) { Text(msg) }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete ${state.selected.size} files?") },
            text = {
                Text(
                    "This frees ${formatSize(state.selectedBytes)}. " +
                        "One copy of each file is always kept. This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirming = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun IdleContent(onScan: () -> Unit) {
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
                "delete across your storage.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onScan) { Text("Scan device") }
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
    onToggle: (MediaFile) -> Unit,
    onRescan: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .build()
    }

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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp)
        ) {
            items(state.groups, key = { g -> g.files.first().path }) { group ->
                GroupCard(group, state, imageLoader, onToggle)
            }
        }

        if (state.groups.isNotEmpty()) {
            Button(
                onClick = onDelete,
                enabled = state.selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Delete ${state.selected.size} files (${formatSize(state.selectedBytes)})")
            }
        }
    }
}

@Composable
private fun GroupCard(
    group: DuplicateGroup,
    state: UiState,
    imageLoader: ImageLoader,
    onToggle: (MediaFile) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${group.files.size} copies · ${formatSize(group.size)} each",
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.height(8.dp))
            group.files.sortedBy { it.dateModified }.forEach { file ->
                FileRow(file, file.path in state.selected, imageLoader) { onToggle(file) }
            }
        }
    }
}

@Composable
private fun FileRow(
    file: MediaFile,
    selected: Boolean,
    imageLoader: ImageLoader,
    onToggle: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
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
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (file.folder.isNotEmpty()) {
                Text(
                    file.folder,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(if (selected) "Remove" else "Keep", style = MaterialTheme.typography.labelSmall)
    }
}
