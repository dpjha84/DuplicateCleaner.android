package com.example.duplicatecleaner.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.ImageLoader
import coil.compose.AsyncImage
import com.example.duplicatecleaner.data.FileKind
import com.example.duplicatecleaner.data.MediaFile
import com.example.duplicatecleaner.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Full-screen preview of one file. Used for duplicates (action = keep / remove) and for the Trash
 * (action = restore). Flip through the other files in the list with the arrows.
 */
@Composable
fun PreviewDialog(
    files: List<MediaFile>,
    index: Int,
    imageLoader: ImageLoader,
    statusText: String,
    statusIsWarning: Boolean,
    actionLabel: String,
    actionIcon: ImageVector,
    actionEnabled: Boolean,
    onIndex: (Int) -> Unit,
    onAction: () -> Unit,
    onClose: () -> Unit
) {
    val file = files[index]
    val context = LocalContext.current

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // Top bar
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close") }
                    Column(Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${index + 1} of ${files.size} · ${formatSize(file.size)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { openExternally(context, file) }) {
                        Icon(Icons.Rounded.OpenInNew, "Open in another app")
                    }
                }

                // Where it lives
                Text(
                    folderLabel(file.folder) + " · " + DateFormat.getDateInstance().format(Date(file.dateModified)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (statusIsWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )

                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
                    key(file.path) { PreviewBody(file, imageLoader) }
                }

                // Bottom bar: previous, main action, next
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledIconButton(
                        onClick = { onIndex((index - 1 + files.size) % files.size) },
                        enabled = files.size > 1
                    ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Previous") }
                    Button(
                        onClick = onAction,
                        enabled = actionEnabled,
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Icon(actionIcon, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(actionLabel)
                    }
                    FilledIconButton(
                        onClick = { onIndex((index + 1) % files.size) },
                        enabled = files.size > 1
                    ) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Next") }
                }
            }
        }
    }
}

@Composable
private fun PreviewBody(file: MediaFile, imageLoader: ImageLoader) {
    when (file.kind) {
        FileKind.IMAGE -> ZoomableImage(file, imageLoader)
        FileKind.VIDEO -> VideoPreview(file)
        FileKind.AUDIO -> AudioPreview(file)
        else -> DocumentPreview(file)
    }
}

@Composable
private fun ZoomableImage(file: MediaFile, imageLoader: ImageLoader) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    AsyncImage(
        model = File(file.path),
        imageLoader = imageLoader,
        contentDescription = file.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y
            )
    )
}

@Composable
private fun VideoPreview(file: MediaFile) {
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoPath(file.path)
                setOnPreparedListener { seekTo(1) } // show the first frame
                setOnClickListener { if (isPlaying) pause() else start() }
            }
        },
        onRelease = { it.stopPlayback() },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun AudioPreview(file: MediaFile) {
    var playing by remember { mutableStateOf(false) }
    val player = remember(file.path) {
        try {
            MediaPlayer().apply {
                setDataSource(file.path)
                prepare()
            }
        } catch (e: Exception) {
            null
        }
    }
    DisposableEffect(player) {
        player?.setOnCompletionListener { playing = false }
        onDispose { player?.release() }
    }

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (player == null) {
            IconCircle(FileKind.AUDIO.icon, tint = FileKind.AUDIO.tint, background = FileKind.AUDIO.tint.copy(alpha = 0.14f), size = 96.dp)
            Spacer(Modifier.height(12.dp))
            Text("Can't play this audio file", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            FilledIconButton(
                onClick = {
                    if (playing) player.pause() else player.start()
                    playing = !playing
                },
                modifier = Modifier.size(96.dp)
            ) {
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (playing) "Pause" else "Play",
                    Modifier.size(48.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            val seconds = player.duration / 1000
            Text("%d:%02d".format(seconds / 60, seconds % 60), style = MaterialTheme.typography.titleMedium)
        }
    }
}

private val TEXT_EXTENSIONS = setOf("txt", "csv", "md", "json", "xml", "log", "html", "rtf")

@Composable
private fun DocumentPreview(file: MediaFile) {
    val ext = file.name.substringAfterLast('.', "").lowercase()
    when {
        ext == "pdf" -> PdfPreview(file)
        ext in TEXT_EXTENSIONS -> TextPreview(file)
        else -> Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IconCircle(file.kind.icon, tint = file.kind.tint, background = file.kind.tint.copy(alpha = 0.14f), size = 96.dp)
            Spacer(Modifier.height(16.dp))
            Text(ext.ifEmpty { "FILE" }.take(5).uppercase(), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "No preview for this type. Tap the open icon to view it in another app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}

@Composable
private fun TextPreview(file: MediaFile) {
    val text by produceState<String?>(null, file.path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                File(file.path).bufferedReader().use { r ->
                    val buf = CharArray(5000)
                    val n = r.read(buf)
                    if (n > 0) String(buf, 0, n) else ""
                }
            }.getOrNull()
        }
    }
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            text ?: "Loading…",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

private data class PdfPage(val bitmap: Bitmap?, val count: Int)

@Composable
private fun PdfPreview(file: MediaFile) {
    var page by remember { mutableIntStateOf(0) }
    val rendered by produceState<PdfPage?>(null, file.path, page) {
        value = withContext(Dispatchers.IO) { renderPdfPage(file.path, page) }
    }

    Column(Modifier.fillMaxSize()) {
        val r = rendered
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                r == null -> Text("Loading…")
                r.bitmap == null -> Text("Can't preview this PDF")
                else -> Image(
                    bitmap = r.bitmap.asImageBitmap(),
                    contentDescription = file.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (r != null && r.count > 1) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { page-- }, enabled = page > 0) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Previous page")
                }
                Text("${page + 1} / ${r.count}", style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { page++ }, enabled = page < r.count - 1) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Next page")
                }
            }
        }
    }
}

private fun renderPdfPage(path: String, index: Int): PdfPage = try {
    val pfd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = PdfRenderer(pfd)
    try {
        val count = renderer.pageCount
        val p = renderer.openPage(index.coerceIn(0, count - 1))
        try {
            val w = 1080
            val h = (w.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            PdfPage(bmp, count)
        } finally {
            p.close()
        }
    } finally {
        renderer.close()
        pfd.close()
    }
} catch (e: Exception) {
    PdfPage(null, 0) // unreadable or password-protected
}

private fun openExternally(context: Context, file: MediaFile) {
    val f = File(file.path)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
    val mime = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this file type", Toast.LENGTH_SHORT).show()
    }
}
