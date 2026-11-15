package com.dpjha.cloneout.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.dpjha.cloneout.BuildConfig
import com.dpjha.cloneout.UiState
import com.dpjha.cloneout.billing.Entitlements
import com.dpjha.cloneout.formatSize
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

/** Shown only when the selection is larger than the free allowance. Honest, one-time price. */
@Composable
fun PaywallDialog(state: UiState, selectedBytes: Long, actions: ProActions) {
    Dialog(onDismissRequest = actions.closePaywall) {
        androidx.compose.material3.Card(Modifier.fillMaxWidth().padding(8.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                GradientCard {
                    Column {
                        Icon(Icons.Rounded.WorkspacePremium, null, tint = Color.White, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (selectedBytes > 0) "Free up ${formatSize(selectedBytes)}" else "Go Pro",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White
                        )
                        Text(
                            if (state.similarLocked) "Cleaning similar photos is a Pro feature"
                            else "${formatSize(state.freeRemaining)} of free cleanup left",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Perk("Clean similar photos")
                    Perk("Unlimited cleanup")
                    Perk("No ads")
                    Perk("One payment, no subscription")
                    Perk("Restore on any device")
                    Spacer(Modifier.height(4.dp))
                    // Stays tappable when the store can't be reached: a tap retries and explains why.
                    Button(
                        onClick = actions.buy,
                        enabled = state.proPrice != null || state.storeUnavailable,
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        Text(
                            when {
                                state.proPrice != null -> "Unlock Pro · ${state.proPrice}"
                                state.storeUnavailable -> "Store unavailable · Retry"
                                else -> "Loading price…"
                            }
                        )
                    }
                    // Ads and the free allowance only apply to exact duplicates.
                    if (!state.similarLocked) OutlinedButton(
                        onClick = actions.watchAd,
                        enabled = state.rewardReady,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.PlayCircle, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.rewardReady) "+${formatSize(Entitlements.REWARD_BYTES)} for a short ad" else "Ad loading…")
                    }
                    if (!state.similarLocked && state.freeRemaining > 0) {
                        TextButton(onClick = actions.cleanFree, modifier = Modifier.fillMaxWidth()) {
                            Text("Clean only what fits free (${formatSize(state.freeRemaining)})")
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = actions.restore) { Text("Restore purchase", maxLines = 1) }
                        TextButton(onClick = actions.closePaywall) { Text("Not now", maxLines = 1, softWrap = false) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Perk(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
