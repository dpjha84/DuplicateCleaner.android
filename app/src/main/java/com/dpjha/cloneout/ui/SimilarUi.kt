package com.dpjha.cloneout.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.dpjha.cloneout.FREE_SIMILAR_GROUPS
import com.dpjha.cloneout.MainViewModel
import com.dpjha.cloneout.UiState
import com.dpjha.cloneout.data.FileKind
import com.dpjha.cloneout.data.Sensitivity
import com.dpjha.cloneout.data.SimilarGroup
import com.dpjha.cloneout.data.SimilarPhoto
import com.dpjha.cloneout.formatSize
import java.io.File

/** Everything the Similar tab shows, as items of the results list. */
fun LazyListScope.similarItems(
    state: UiState,
    vm: MainViewModel,
    imageLoader: ImageLoader,
    pro: ProActions,
    reclaim: Long,
    photoCount: Int,
    onPreview: (String) -> Unit
) {
    if (state.similarRunning) {
        item(key = "similar-progress") { SimilarProgress(state.similarProgress) }
    } else if (state.similarGroups.isNotEmpty()) {
        item(key = "similar-hero") {
            HeroSummary(state, reclaim, "$photoCount photos in ${state.similarGroups.size} groups", pro)
        }
    }
    item(key = "similar-level") { LevelChips(state.similarLevel, vm::setSimilarLevel) }
    if (!state.similarRunning && state.similarGroups.isEmpty()) {
        item(key = "similar-empty") {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconCircle(Icons.Rounded.AutoAwesome, size = 72.dp)
                Spacer(Modifier.height(16.dp))
                Text("No similar photos", style = MaterialTheme.typography.titleMedium)
                if (state.similarLevel != Sensitivity.LOOSE) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Try a looser match",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    // Free users get a full taste of the first few groups; the rest are blurred teasers.
    val visibleFull = if (state.isPro) state.similarGroups.size else minOf(FREE_SIMILAR_GROUPS, state.similarGroups.size)
    items(state.similarGroups.take(visibleFull), key = { it.bestPath }) { group ->
        SimilarGroupCard(group, state.similarSelected, imageLoader, vm, locked = false, onPreview = onPreview, onLocked = pro.openPaywall)
    }
    val lockedGroups = state.similarGroups.drop(visibleFull)
    if (lockedGroups.isNotEmpty()) {
        item(key = "similar-unlock") { UnlockBanner(lockedGroups.size, pro.openPaywall) }
        items(lockedGroups, key = { it.bestPath }) { group ->
            SimilarGroupCard(group, state.similarSelected, imageLoader, vm, locked = true, onPreview = onPreview, onLocked = pro.openPaywall)
        }
    }
}

@Composable
private fun UnlockBanner(hidden: Int, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                if (hidden == 1) "Unlock to see 1 more group" else "Unlock to see $hidden more groups",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SimilarProgress(progress: Float) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconCircle(Icons.Rounded.PhotoLibrary)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Finding similar photos",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
            }
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp))
        }
    }
}

@Composable
private fun LevelChips(level: Sensitivity, onLevel: (Sensitivity) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Tune, "Match level", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(Sensitivity.STRICT to "Strict", Sensitivity.BALANCED to "Balanced", Sensitivity.LOOSE to "Loose")
            .forEach { (l, label) ->
                FilterChip(
                    selected = level == l,
                    onClick = { onLevel(l) },
                    label = { Text(label) },
                    leadingIcon = if (level == l) {
                        { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }
                    } else null
                )
            }
    }
}

@Composable
private fun SimilarGroupCard(
    group: SimilarGroup,
    selected: Set<String>,
    imageLoader: ImageLoader,
    vm: MainViewModel,
    locked: Boolean,
    onPreview: (String) -> Unit,
    onLocked: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (locked) {
                    Icon(Icons.Rounded.Lock, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(20.dp), tint = FileKind.IMAGE.tint)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("${group.photos.size} similar photos", style = MaterialTheme.typography.titleSmall)
                    if (!locked) {
                        Text(
                            group.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (locked) Spacer(Modifier.height(48.dp)) else Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Group options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Keep all photos") },
                            leadingIcon = { Icon(Icons.Rounded.DoneAll, null) },
                            onClick = { menu = false; vm.keepAllSimilar(group) }
                        )
                        DropdownMenuItem(
                            text = { Text("Keep only the best") },
                            leadingIcon = { Icon(Icons.Rounded.Restore, null) },
                            onClick = { menu = false; vm.resetSimilarGroup(group) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(group.photos, key = { _, p -> p.file.path }) { index, photo ->
                    PhotoTile(
                        photo = photo,
                        isBest = !locked && index == 0,
                        remove = !locked && photo.file.path in selected,
                        locked = locked,
                        imageLoader = imageLoader,
                        onOpen = { if (locked) onLocked() else onPreview(photo.file.path) },
                        onToggle = { vm.toggleSimilar(photo.file) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoTile(
    photo: SimilarPhoto,
    isBest: Boolean,
    remove: Boolean,
    locked: Boolean,
    imageLoader: ImageLoader,
    onOpen: () -> Unit,
    onToggle: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    val tile = 148.dp
    val px = with(LocalDensity.current) { tile.roundToPx() }
    val context = LocalContext.current

    Box(
        Modifier
            .size(tile)
            .clip(shape)
            .background(scheme.surfaceVariant)
            .clickable(onClick = onOpen)
            .then(if (remove) Modifier.border(2.dp, scheme.error, shape) else Modifier)
    ) {
        AsyncImage(
            // Locked tiles decode a ~10 px thumbnail and stretch it: a blur on every Android version,
            // and the real picture never reaches the screen.
            model = ImageRequest.Builder(context).data(File(photo.file.path))
                .size(if (locked) 10 else px).crossfade(true).build(),
            imageLoader = imageLoader,
            contentDescription = if (locked) null else photo.file.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(if (remove) 0.55f else 1f)
        )
        if (locked) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Lock, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            return@Box
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))))
                .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 6.dp)
        ) {
            Text(
                "${"%.1f".format(photo.megapixels)} MP · ${formatSize(photo.file.size)}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1
            )
        }
        if (isBest) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(scheme.primary)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Star, null, Modifier.size(12.dp), tint = scheme.onPrimary)
                Spacer(Modifier.width(3.dp))
                Text("Best", style = MaterialTheme.typography.labelSmall, color = scheme.onPrimary)
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(if (remove) scheme.error else Color(0xFF0E9F8E))
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (remove) Icons.Rounded.Delete else Icons.Rounded.Check,
                if (remove) "Will be cleaned. Tap to keep" else "Kept. Tap to clean",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
