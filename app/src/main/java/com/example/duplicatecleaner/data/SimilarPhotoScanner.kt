package com.example.duplicatecleaner.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Turns photos into [PhotoSignature]s. Photos seen before come straight from [HashCache];
 * the rest are decoded as tiny thumbnails (the decoder skips most of the pixels), a few at a time.
 * Progress is saved as it goes, so a cancelled scan resumes instead of starting over.
 */
class SimilarPhotoScanner(
    context: Context,
    private val parallelism: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
) {
    private val cache = HashCache(context)

    suspend fun signatures(
        images: List<MediaFile>,
        onProgress: (done: Int, total: Int) -> Unit
    ): List<SimilarCandidate> = withContext(Dispatchers.IO) {
        val total = images.size
        if (total == 0) return@withContext emptyList()

        val stored = cache.loadAll()
        val results = arrayOfNulls<SimilarCandidate>(total)
        val todo = ArrayList<Int>()
        for ((i, f) in images.withIndex()) {
            val e = stored[f.path]
            if (e != null && e.size == f.size && e.modified == f.dateModified) {
                e.signature?.let { results[i] = SimilarCandidate(f, it) }
            } else {
                todo += i
            }
        }

        val done = AtomicInteger(total - todo.size)
        val lastEmit = AtomicLong(0)
        fun report(force: Boolean = false) {
            val now = System.nanoTime() / 1_000_000
            val prev = lastEmit.get()
            if (force || (now - prev >= 200 && lastEmit.compareAndSet(prev, now))) onProgress(done.get(), total)
        }
        report(force = true)

        val pending = ArrayList<Pair<String, HashCache.Entry>>()
        fun flush(all: Boolean) {
            val batch = synchronized(pending) {
                if (!all && pending.size < FLUSH_EVERY) return
                pending.toList().also { pending.clear() }
            }
            cache.putAll(batch)
        }

        try {
            val next = AtomicInteger(0)
            coroutineScope {
                repeat(parallelism) {
                    launch(Dispatchers.IO) {
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val k = next.getAndIncrement()
                            if (k >= todo.size) break
                            val i = todo[k]
                            val f = images[i]
                            val sig = decode(f)
                            if (sig != null) results[i] = SimilarCandidate(f, sig)
                            synchronized(pending) { pending += f.path to HashCache.Entry(f.size, f.dateModified, sig) }
                            flush(all = false)
                            done.incrementAndGet()
                            report()
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { flush(all = true) }
        }
        report(force = true)

        // Keep the cache from growing forever when many photos were deleted.
        if (stored.size > total * 2 + 2000) {
            val keep = images.mapTo(HashSet()) { it.path }
            cache.retainOnly(keep)
        }
        results.filterNotNull()
    }

    /** Decodes a ~100-190 px thumbnail and hashes it. Returns null for anything unusable. */
    private fun decode(file: MediaFile): PhotoSignature? {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val rw = bounds.outWidth
            val rh = bounds.outHeight
            if (rw <= 0 || rh <= 0) return null
            if (minOf(rw, rh) < MIN_SIDE || rw.toLong() * rh > MAX_PIXELS) return null

            var sample = 1
            while (minOf(rw, rh) / (sample * 2) >= TARGET_SIDE) sample *= 2

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
            try {
                val w = bmp.width
                val h = bmp.height
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                return PhotoHash.signature(px, w, h, orientationOf(file.path), rw, rh)
            } finally {
                bmp.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
    }

    private fun orientationOf(path: String): Int = try {
        ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    companion object {
        /** Photos smaller than this (icons, stickers, thumbnails) are not worth comparing. */
        const val MIN_BYTES = 20L * 1024
        private const val MIN_SIDE = 64
        private const val TARGET_SIDE = 96
        private const val MAX_PIXELS = 200_000_000L
        private const val FLUSH_EVERY = 200
    }
}
