@file:OptIn(ExperimentalLayoutApi::class)

package com.example.duplicatecleaner.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.duplicatecleaner.BuildConfig
import com.example.duplicatecleaner.MainViewModel
import com.example.duplicatecleaner.UiState
import com.example.duplicatecleaner.data.CleanMode
import com.example.duplicatecleaner.data.ThemeMode
import com.example.duplicatecleaner.formatSize

@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel, pro: ProActions) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        PlanCard(state, pro)

        if (state.lifetimeFreedBytes > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(formatSize(state.lifetimeFreedBytes), "Freed", Icons.Rounded.Storage, Modifier.weight(1f))
                StatCard("${state.lifetimeFiles}", "Files cleaned", Icons.Rounded.CleaningServices, Modifier.weight(1f))
            }
        }

        Section("Scan sizes") {
            val options = listOf(1024L to "Any", 100L * 1024 to "100 KB+", 1024L * 1024 to "1 MB+", 10L * 1024 * 1024 to "10 MB+")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (bytes, label) ->
                    FilterChip(
                        selected = state.minSizeBytes == bytes,
                        onClick = { vm.setMinSize(bytes) },
                        label = { Text(label) }
                    )
                }
            }
        }

        Section("When cleaning") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.cleanMode == CleanMode.TRASH,
                    onClick = { vm.setCleanMode(CleanMode.TRASH) },
                    label = { Text("Move to Trash") },
                    leadingIcon = { Icon(Icons.Rounded.RestoreFromTrash, null, Modifier.size(18.dp)) }
                )
                FilterChip(
                    selected = state.cleanMode == CleanMode.DELETE,
                    onClick = { vm.setCleanMode(CleanMode.DELETE) },
                    label = { Text("Delete now") },
                    leadingIcon = { Icon(Icons.Rounded.DeleteForever, null, Modifier.size(18.dp)) }
                )
            }
        }

        Section("Appearance") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark").forEach { (m, l) ->
                    FilterChip(selected = state.themeMode == m, onClick = { vm.setThemeMode(m) }, label = { Text(l) })
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Match wallpaper colors", Modifier.weight(1f))
                    Switch(checked = state.dynamicColor, onCheckedChange = vm::setDynamicColor)
                }
            }
        }

        Section("About") {
            if (BuildConfig.PRIVACY_URL.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_URL)))
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.PrivacyTip, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text("Privacy policy", Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                }
            }
            Text(
                "Version ${BuildConfig.VERSION_NAME} · No account needed · Files never leave your device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(title)
        content()
    }
}

@Composable
private fun PlanCard(state: UiState, pro: ProActions) {
    if (state.isPro) {
        GradientCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Verified, null, tint = Color.White, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Pro", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text(
                        "Unlimited cleanup, no ads. Thank you!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }
        return
    }
    val used = (state.freeTotal - state.freeRemaining).coerceAtLeast(0)
    val fraction = if (state.freeTotal > 0) (used.toFloat() / state.freeTotal).coerceIn(0f, 1f) else 0f
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconCircle(Icons.Rounded.WorkspacePremium)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Free plan", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${formatSize(state.freeRemaining)} of ${formatSize(state.freeTotal)} left",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(8.dp))
            Button(onClick = pro.openPaywall, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(state.proPrice?.let { "Unlock Pro · $it" } ?: "Unlock Pro")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = pro.watchAd, enabled = state.rewardReady, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.PlayCircle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("+500 MB")
                }
                TextButton(onClick = pro.restore) { Text("Restore purchase") }
            }
        }
    }
}
