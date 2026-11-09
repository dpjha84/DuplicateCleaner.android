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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * Full-screen preview of one copy from a duplicate group. Lets the user flip through the
 * other copies, see where each one lives, and mark it for trash or keep it.
 */
@Composable
fun PreviewDialog(
    files: List<MediaFile>,
    index: Int,
    selectedPaths: Set<String>,
    imageLoader: ImageLoader,
    onIndex: (Int) -> Unit,
    onToggle: () -> Unit,
    onClose: () -> Unit
) {
    val file = files[index]
    val isSelected = file.path in selectedPaths
    val keepersLeft = files.count { it.path !in selectedPaths }
    val canToggle = isSelected || keepersLeft > 1
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
                    .padding(16.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Copy ${index + 1} of ${files.size}", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = onClose) { Text("Close") }
                }

                Text(
                    file.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "/" + file.folder,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatSize(file.size)} · Modified " +
                        DateFormat.getDateTimeInstance().format(Date(file.dateModified)),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    if (isSelected) "Marked for trash" else "Will be kept",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    key(file.path) { PreviewBody(file, imageLoader) }
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onIndex((index - 1 + files.size) % files.size) },
                        enabled = files.size > 1,
                        modifier = Modifier.weight(1f)
                    ) { Text("Previous copy") }
                    OutlinedButton(
                        onClick = { onIndex((index + 1) % files.size) },
                        enabled = files.size > 1,
                        modifier = Modifier.weight(1f)
                    ) { Text("Next copy") }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onToggle,
                    enabled = canToggle,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (isSelected) "Keep this copy" else "Mark for trash") }
                if (!canToggle) {
                    Text(
                        "At least one copy must be kept.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = { openExternally(context, file) }) {
                    Text("Open in another app")
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
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoPath(file.path)
                    setOnPreparedListener { seekTo(1) } // show the first frame
                    setOnClickListener { if (isPlaying) pause() else start() }
                }
            },
            onRelease = { it.stopPlayback() },
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        Text("Tap the video to play or pause", style = MaterialTheme.typography.bodySmall)
    }
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
        Text("♪", style = MaterialTheme.typography.displayLarge)
        if (player == null) {
            Text("Can't play this audio file")
        } else {
            val seconds = player.duration / 1000
            Text("Length %d:%02d".format(seconds / 60, seconds % 60))
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                if (playing) player.pause() else player.start()
                playing = !playing
            }) { Text(if (playing) "Pause" else "Play") }
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
            Text(
                ext.ifEmpty { "FILE" }.take(5).uppercase(),
                style = MaterialTheme.typography.displaySmall
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "No in-app preview for this type. Use \"Open in another app\" to see its contents.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { page-- }, enabled = page > 0) { Text("Previous page") }
                Text("${page + 1} / ${r.count}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { page++ }, enabled = page < r.count - 1) { Text("Next page") }
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
