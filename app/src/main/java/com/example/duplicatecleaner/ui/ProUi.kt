package com.example.duplicatecleaner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.example.duplicatecleaner.BuildConfig
import com.example.duplicatecleaner.UiState
import com.example.duplicatecleaner.billing.Entitlements
import com.example.duplicatecleaner.data.DuplicateGroup
import com.example.duplicatecleaner.data.FileKind
import com.example.duplicatecleaner.formatSize
import com.example.duplicatecleaner.reclaimableByKind
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

/** Everything the UI can do about Pro, bundled so screens don't need six separate callbacks. */
data class ProActions(
    val openPaywall: () -> Unit,
    val closePaywall: () -> Unit,
    val buy: () -> Unit,
    val watchAd: () -> Unit,
    val cleanFree: () -> Unit,
    val restore: () -> Unit
)

@Composable
fun BannerAd(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            AdView(ctx).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = BuildConfig.ADMOB_BANNER_ID
                loadAd(AdRequest.Builder().build())
            }
        },
        onRelease = { it.destroy() }
    )
}

/** Free value moment: shows what is worth cleaning before anything is asked of the user. */
@Composable
fun ReclaimByTypeCard(groups: List<DuplicateGroup>) {
    val rows = reclaimableByKind(groups)
    if (rows.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("What you can reclaim", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            rows.forEach { (kind, bytes) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(kindLabel(kind))
                    Text(formatSize(bytes), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun kindLabel(kind: FileKind) = when (kind) {
    FileKind.IMAGE -> "Photos"
    FileKind.VIDEO -> "Videos"
    FileKind.AUDIO -> "Audio"
    FileKind.DOCUMENT -> "Documents"
    FileKind.OTHER -> "Other files"
}

/** Shown only when the selection is larger than the free allowance. Plain, honest, one-time price. */
@Composable
fun PaywallDialog(state: UiState, actions: ProActions) {
    Dialog(onDismissRequest = actions.closePaywall) {
        Card(Modifier.fillMaxWidth().padding(8.dp)) {
            Column(
                Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    "Free up ${formatSize(state.selectedBytes)}",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Your free allowance has ${formatSize(state.freeRemaining)} left. " +
                        "Scanning, previews and restoring from trash are always free.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))

                Text("Pro · one payment, yours forever", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Text("✓ Unlimited cleanup", style = MaterialTheme.typography.bodyMedium)
                Text("✓ No ads", style = MaterialTheme.typography.bodyMedium)
                Text("✓ No subscription, no renewals", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "✓ Restore on any device with your Google account",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = actions.buy,
                    enabled = state.proPrice != null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(state.proPrice?.let { "Unlock Pro · $it" } ?: "Loading price…") }

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = actions.watchAd,
                    enabled = state.rewardReady,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (state.rewardReady) "Watch a short ad for +${formatSize(Entitlements.REWARD_BYTES)}"
                        else "Ad loading…"
                    )
                }

                if (state.freeRemaining > 0) {
                    TextButton(onClick = actions.cleanFree, modifier = Modifier.fillMaxWidth()) {
                        Text("Clean only what fits free (${formatSize(state.freeRemaining)})")
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = actions.restore) { Text("Restore purchase") }
                    TextButton(onClick = actions.closePaywall) { Text("Not now") }
                }
            }
        }
    }
}
