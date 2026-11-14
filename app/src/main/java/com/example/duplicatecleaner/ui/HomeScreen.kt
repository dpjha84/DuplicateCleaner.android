@file:OptIn(ExperimentalLayoutApi::class)

package com.example.duplicatecleaner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.duplicatecleaner.MainViewModel
import com.example.duplicatecleaner.Tab
import com.example.duplicatecleaner.UiState
import com.example.duplicatecleaner.data.FileKind
import com.example.duplicatecleaner.formatSize

@Composable
fun HomeScreen(state: UiState, vm: MainViewModel, onScan: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Hero
        GradientCard {
            Column {
                AppMark(56.dp)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Reclaim your storage",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Finds identical files by content, even when renamed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f)
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onScan,
                    enabled = state.scanKinds.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = MaterialTheme.colorScheme.primary,
                        disabledContainerColor = Color.White.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icons.Rounded.Search, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan now", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // What to scan
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("What to scan")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FileKind.entries.forEach { kind ->
                    val selected = kind in state.scanKinds
                    FilterChip(
                        selected = selected,
                        onClick = { vm.toggleKind(kind) },
                        label = { Text(kind.label) },
                        leadingIcon = {
                            Icon(
                                if (selected) Icons.Rounded.Check else kind.icon,
                                null,
                                Modifier.size(18.dp)
                            )
                        }
                    )
                }
            }
        }

        // Impact so far
        if (state.lifetimeFreedBytes > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(formatSize(state.lifetimeFreedBytes), "Freed so far", Icons.Rounded.Storage, Modifier.weight(1f))
                StatCard("${state.lifetimeFiles}", "Files cleaned", Icons.Rounded.CleaningServices, Modifier.weight(1f))
            }
        }

        // Trash teaser
        if (state.trashItems.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth().clickable { vm.setTab(Tab.TRASH) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconCircle(
                        Icons.Rounded.RestoreFromTrash,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        background = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${formatSize(state.trashBytes)} in Trash", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Empty it to free the space",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                }
            }
        }

        // Trust line
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Shield, null,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Scanned on your device. Nothing is uploaded.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ScanningScreen(state: UiState, vm: MainViewModel) {
    val p = state.progress
    val fraction = if (p.total > 0) (p.done.toFloat() / p.total).coerceIn(0f, 1f) else 0f

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (p.total > 0) {
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(160.dp),
                    strokeWidth = 10.dp,
                    trackColor = MaterialTheme.colorScheme.primaryContainer
                )
                Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.displaySmall)
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(160.dp),
                    strokeWidth = 10.dp,
                    trackColor = MaterialTheme.colorScheme.primaryContainer
                )
                Icon(
                    Icons.Rounded.Search, null,
                    Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(p.message, style = MaterialTheme.typography.titleMedium)
        if (p.total > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (p.isBytes) "${formatSize(p.done)} of ${formatSize(p.total)}" else "${p.done} of ${p.total} files",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(28.dp))
        OutlinedButton(onClick = vm::cancelScan) {
            Icon(Icons.Rounded.Close, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Cancel")
        }
    }
}
