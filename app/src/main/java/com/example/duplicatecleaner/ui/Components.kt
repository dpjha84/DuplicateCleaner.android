package com.example.duplicatecleaner.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.example.duplicatecleaner.R
import com.example.duplicatecleaner.data.FileKind
import com.example.duplicatecleaner.data.MediaFile
import com.example.duplicatecleaner.ui.theme.brandGradient
import java.io.File

// ---- File kinds: one label, icon and colour everywhere ----

val FileKind.label: String
    get() = when (this) {
        FileKind.IMAGE -> "Photos"
        FileKind.VIDEO -> "Videos"
        FileKind.AUDIO -> "Audio"
        FileKind.DOCUMENT -> "Documents"
        FileKind.ARCHIVE -> "Archives"
        FileKind.APP -> "Apps"
        FileKind.OTHER -> "Other"
    }

val FileKind.icon: ImageVector
    get() = when (this) {
        FileKind.IMAGE -> Icons.Rounded.Image
        FileKind.VIDEO -> Icons.Rounded.Videocam
        FileKind.AUDIO -> Icons.Rounded.MusicNote
        FileKind.DOCUMENT -> Icons.Rounded.Description
        FileKind.ARCHIVE -> Icons.Rounded.Archive
        FileKind.APP -> Icons.Rounded.Android
        FileKind.OTHER -> Icons.Rounded.InsertDriveFile
    }

val FileKind.tint: Color
    get() = when (this) {
        FileKind.IMAGE -> Color(0xFF0E9F8E)
        FileKind.VIDEO -> Color(0xFF7C4DFF)
        FileKind.AUDIO -> Color(0xFFE0457B)
        FileKind.DOCUMENT -> Color(0xFF2F6FED)
        FileKind.ARCHIVE -> Color(0xFFE8A317)
        FileKind.APP -> Color(0xFF3DA35D)
        FileKind.OTHER -> Color(0xFF7A7F8E)
    }

fun folderLabel(folder: String): String = folder.ifEmpty { "Internal storage" }

// ---- Images ----

/** One image loader for the whole app (with video frame support), shared via remember. */
@Composable
fun rememberImageLoader(): ImageLoader {
    val context = LocalContext.current
    return remember {
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
    }
}

/** Square thumbnail: real preview for photos and videos, a coloured type icon for everything else. */
@Composable
fun FileThumb(
    file: MediaFile,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(file.kind.tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        if (file.kind == FileKind.IMAGE || file.kind == FileKind.VIDEO) {
            val context = LocalContext.current
            val px = with(LocalDensity.current) { size.roundToPx() }
            AsyncImage(
                model = ImageRequest.Builder(context).data(File(file.path)).size(px).crossfade(true).build(),
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (file.kind == FileKind.VIDEO) {
                Box(
                    Modifier.size(size * 0.42f).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(size * 0.3f))
                }
            }
        } else {
            Icon(file.kind.icon, null, tint = file.kind.tint, modifier = Modifier.size(size * 0.5f))
        }
    }
}

// ---- Small building blocks ----

/** Round tinted icon, used for card headers and empty states. */
@Composable
fun IconCircle(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    background: Color = MaterialTheme.colorScheme.primaryContainer,
    size: Dp = 40.dp
) {
    Box(modifier.size(size).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** Keep / Remove toggle shown on every file row. Tap to flip. */
@Composable
fun StatusPill(remove: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (remove) scheme.errorContainer else scheme.secondaryContainer
    val fg = if (remove) scheme.onErrorContainer else scheme.onSecondaryContainer
    Row(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (remove) Icons.Rounded.Delete else Icons.Rounded.Check, null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(if (remove) "Remove" else "Keep", style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** The app icon, drawn from the same vectors as the launcher icon. */
@Composable
fun AppMark(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.28f)),
        contentAlignment = Alignment.Center
    ) {
        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
        Image(
            painterResource(R.drawable.ic_launcher_foreground), null,
            Modifier.fillMaxSize().scale(1.5f)
        )
    }
}

/** Gradient banner container used for the Home and Results headers. */
@Composable
fun GradientCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(brandGradient())
            .padding(22.dp)
    ) { content() }
}

@Composable
fun StatCard(value: String, label: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(icon, size = 38.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(value, style = MaterialTheme.typography.titleMedium)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Friendly full-screen message with an icon and an optional button. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconCircle(icon, size = 88.dp)
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
