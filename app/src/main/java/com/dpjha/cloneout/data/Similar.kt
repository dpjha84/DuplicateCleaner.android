package com.dpjha.cloneout.data

import java.util.Random
import kotlin.math.abs
import kotlin.math.ln

/*
 * Similar-photo detection: pure Kotlin (no Android types) so it can be unit-tested on the JVM.
 *
 * A photo is reduced to a small "signature":
 *  - a 512-bit difference hash (horizontal and vertical brightness gradients of a 17x17 grid),
 *    which survives resizing, recompression, mild edits and small camera movement;
 *  - a coarse 2x2 colour fingerprint, which rejects pictures that share a layout but not colours;
 *  - the upright pixel size, used for the aspect check and for picking the best copy.
 * Signatures are compared with Hamming distance; candidate pairs come from locality-sensitive
 * hashing, so big libraries do not need an all-pairs comparison.
 */

class PhotoSignature(
    /** [PhotoHash.WORDS] longs: 256 horizontal-gradient bits, then 256 vertical-gradient bits. */
    val bits: LongArray,
    /** 2x2 cells x (red, blue) chromaticity, 5 bits each: hue layout, independent of brightness. */
    val color: Long,
    /** Upright (EXIF-corrected) pixel size of the original photo. */
    val width: Int,
    val height: Int
) {
    val pixels: Long get() = width.toLong() * height
}

/** How eager the matcher is. Looser finds more (and riskier) matches. */
enum class Sensitivity(val maxBits: Int, val maxColor: Int, val maxAspect: Double) {
    STRICT(24, 6, 0.05),
    BALANCED(48, 10, 0.10),
    LOOSE(80, 16, 0.20)
}

class SimilarCandidate(val file: MediaFile, val sig: PhotoSignature)

class SimilarPhoto(val file: MediaFile, val width: Int, val height: Int) {
    val megapixels: Double get() = width.toDouble() * height / 1_000_000.0
}

/** Photos that look alike. [photos] is ordered best first; [bestPath] is the one to keep. */
data class SimilarGroup(
    val photos: List<SimilarPhoto>,
    val reason: String
) {
    val best: SimilarPhoto get() = photos.first()
    val bestPath: String get() = best.file.path
    /** Space freed if everything except the best photo is cleaned. */
    val reclaimable: Long get() = photos.drop(1).sumOf { it.file.size }
    val files: List<MediaFile> get() = photos.map { it.file }
}

object PhotoHash {
    const val GRID = 17
    private const val CELLS = (GRID - 1) * (GRID - 1)      // 256 comparisons per direction
    const val WORDS = CELLS * 2 / 64                        // 8 longs = 512 bits

    /**
     * Pictures with almost no visible brightness steps (blank pages, solid colours) cannot be
     * hashed reliably, so they are left out of similar-photo matching. Out of 512 comparisons.
     */
    @JvmField var minInfo = 16
    /** Brightness difference (0..255) that counts as a visible step rather than noise. */
    private const val STEP = 4

    /**
     * Builds the signature from decoded ARGB [pixels] ([w] x [h], any size >= 17x17).
     * [orientation] is the EXIF orientation (1..8) so rotated copies of a photo still match.
     * [fullWidth]/[fullHeight] are the original file's raw pixel size (before the EXIF turn).
     */
    fun signature(
        pixels: IntArray, w: Int, h: Int,
        orientation: Int, fullWidth: Int, fullHeight: Int
    ): PhotoSignature? {
        if (w <= 0 || h <= 0 || pixels.size < w * h) return null
        val turned = orientation in 5..8
        val upW = if (turned) fullHeight else fullWidth
        val upH = if (turned) fullWidth else fullHeight

        val o = orient(pixels, w, h, orientation)
        if (o.w < GRID || o.h < GRID) return null
        val p = o.pixels
        val ow = o.w
        val oh = o.h

        // 17x17 grid of average brightness.
        val grid = IntArray(GRID * GRID)
        for (gy in 0 until GRID) {
            val y0 = gy * oh / GRID
            val y1 = maxOf(y0 + 1, (gy + 1) * oh / GRID)
            for (gx in 0 until GRID) {
                val x0 = gx * ow / GRID
                val x1 = maxOf(x0 + 1, (gx + 1) * ow / GRID)
                var sum = 0L
                for (y in y0 until y1) {
                    val row = y * ow
                    for (x in x0 until x1) sum += gray(p[row + x])
                }
                grid[gy * GRID + gx] = (sum / ((y1 - y0) * (x1 - x0))).toInt()
            }
        }

        val bits = LongArray(WORDS)
        var info = 0
        var i = 0
        for (y in 0 until GRID - 1) {
            for (x in 0 until GRID - 1) {
                val g = grid[y * GRID + x]
                val right = grid[y * GRID + x + 1]
                val below = grid[(y + 1) * GRID + x]
                if (g < right) bits[i shr 6] = bits[i shr 6] or (1L shl (i and 63))
                val j = CELLS + i
                if (g < below) bits[j shr 6] = bits[j shr 6] or (1L shl (j and 63))
                if (abs(g - right) >= STEP) info++
                if (abs(g - below) >= STEP) info++
                i++
            }
        }
        if (info < minInfo) return null

        // 2x2 hue fingerprint: red and blue share of each quadrant (gain-independent).
        var color = 0L
        for (cy in 0..1) {
            val y0 = cy * oh / 2
            val y1 = (cy + 1) * oh / 2
            for (cx in 0..1) {
                val x0 = cx * ow / 2
                val x1 = (cx + 1) * ow / 2
                var r = 0L; var g = 0L; var b = 0L
                for (y in y0 until y1) {
                    val row = y * ow
                    for (x in x0 until x1) {
                        val c = flatten(p[row + x])
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                    }
                }
                val total = r + g + b
                // Near-black quadrants have no reliable hue: treat them as neutral.
                val cr = if (total < 3 * 24L * ((y1 - y0) * (x1 - x0)).coerceAtLeast(1)) 10L else minOf(31L, r * 32 / total)
                val cb = if (total < 3 * 24L * ((y1 - y0) * (x1 - x0)).coerceAtLeast(1)) 10L else minOf(31L, b * 32 / total)
                val shift = (cy * 2 + cx) * 10
                color = color or (cr shl shift) or (cb shl (shift + 5))
            }
        }
        return PhotoSignature(bits, color, upW, upH)
    }

    fun hamming(a: PhotoSignature, b: PhotoSignature): Int {
        var d = 0
        for (i in 0 until WORDS) d += java.lang.Long.bitCount(a.bits[i] xor b.bits[i])
        return d
    }

    /** Sum of differences of the 2x2 hue fingerprint (0 = same hues). */
    fun colorDistance(a: Long, b: Long): Int {
        var d = 0
        for (i in 0 until 8) {
            val sh = i * 5
            d += abs(((a shr sh) and 31L).toInt() - ((b shr sh) and 31L).toInt())
        }
        return d
    }

    fun isSimilar(a: PhotoSignature, b: PhotoSignature, level: Sensitivity): Boolean {
        if (hamming(a, b) > level.maxBits) return false
        if (colorDistance(a.color, b.color) > level.maxColor) return false
        val ra = a.width.toDouble() / a.height
        val rb = b.width.toDouble() / b.height
        return abs(ln(ra / rb)) <= level.maxAspect
    }

    private fun gray(argb: Int): Int {
        val c = flatten(argb)
        return (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
    }

    /** Transparent pixels are shown over white, like a gallery would. */
    private fun flatten(argb: Int): Int {
        val a = argb ushr 24
        if (a == 255) return argb
        val r = ((argb shr 16) and 0xFF) * a / 255 + (255 - a)
        val g = ((argb shr 8) and 0xFF) * a / 255 + (255 - a)
        val b = (argb and 0xFF) * a / 255 + (255 - a)
        return (r shl 16) or (g shl 8) or b
    }

    private class Oriented(val pixels: IntArray, val w: Int, val h: Int)

    /** Rotates/flips so the picture is upright, following the EXIF orientation values. */
    private fun orient(src: IntArray, w: Int, h: Int, orientation: Int): Oriented {
        if (orientation !in 2..8) return Oriented(src, w, h)
        val swap = orientation >= 5
        val nw = if (swap) h else w
        val nh = if (swap) w else h
        val out = IntArray(nw * nh)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx: Int
                val dy: Int
                when (orientation) {
                    2 -> { dx = w - 1 - x; dy = y }
                    3 -> { dx = w - 1 - x; dy = h - 1 - y }
                    4 -> { dx = x; dy = h - 1 - y }
                    5 -> { dx = y; dy = x }
                    6 -> { dx = h - 1 - y; dy = x }
                    7 -> { dx = h - 1 - y; dy = w - 1 - x }
                    else -> { dx = y; dy = w - 1 - x }
                }
                out[dy * nw + dx] = src[y * w + x]
            }
        }
        return Oriented(out, nw, nh)
    }
}

object SimilarGrouper {

    private const val TABLES = 24
    private const val KEY_BITS = 12
    /** Buckets bigger than this (flat, near-identical-hash images) are compared in a sliding window. */
    private const val BUCKET_CAP = 300

    /** Bit positions (0 until 512) sampled by each LSH table. Fixed seed, so results are repeatable. */
    private val tablePositions: Array<IntArray> by lazy {
        val rnd = Random(20240607L)
        Array(TABLES) {
            val all = IntArray(PhotoHash.WORDS * 64) { i -> i }
            for (i in all.indices.reversed()) {
                val j = rnd.nextInt(i + 1)
                val t = all[i]; all[i] = all[j]; all[j] = t
            }
            all.copyOf(KEY_BITS)
        }
    }

    fun group(candidates: List<SimilarCandidate>, level: Sensitivity): List<SimilarGroup> {
        val n = candidates.size
        if (n < 2) return emptyList()
        val sigs = Array(n) { candidates[it].sig }
        val uf = IntArray(n) { it }

        fun find(x: Int): Int {
            var r = x
            while (uf[r] != r) r = uf[r]
            var c = x
            while (uf[c] != r) { val nx = uf[c]; uf[c] = r; c = nx }
            return r
        }

        fun tryJoin(a: Int, b: Int) {
            if (find(a) != find(b) && PhotoHash.isSimilar(sigs[a], sigs[b], level)) uf[find(a)] = find(b)
        }

        // Candidate pairs: photos that agree on all sampled bits of at least one table.
        val keys = IntArray(n)
        val counts = IntArray((1 shl KEY_BITS) + 1)
        for (positions in tablePositions) {
            java.util.Arrays.fill(counts, 0)
            for (i in 0 until n) {
                var key = 0
                val s = sigs[i]
                for (k in 0 until KEY_BITS) {
                    val pos = positions[k]
                    val bit = (s.bits[pos shr 6] ushr (pos and 63)) and 1L
                    key = (key shl 1) or bit.toInt()
                }
                keys[i] = key
                counts[key + 1]++
            }
            for (k in 1 until counts.size) counts[k] += counts[k - 1]
            val start = counts.copyOf()
            val order = IntArray(n)
            val fill = counts.copyOf()
            for (i in 0 until n) order[fill[keys[i]]++] = i

            for (bucket in 0 until (1 shl KEY_BITS)) {
                val from = start[bucket]
                val to = start[bucket + 1]
                val m = to - from
                if (m < 2) continue
                for (a in from until to) {
                    val last = if (m <= BUCKET_CAP) to else minOf(to, a + 1 + BUCKET_CAP)
                    for (b in a + 1 until last) tryJoin(order[a], order[b])
                }
            }
        }

        // Components -> groups. Chains (A~B, B~C but A!~C) are cut: every member must resemble the best photo.
        val components = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) components.getOrPut(find(i)) { ArrayList() }.add(i)

        val groups = ArrayList<SimilarGroup>()
        for (members in components.values) {
            if (members.size < 2) continue
            var remaining: List<Int> = members.sortedWith(bestFirst(candidates))
            while (remaining.size >= 2) {
                val anchor = remaining.first()
                val taken = remaining.filter { it == anchor || PhotoHash.isSimilar(sigs[anchor], sigs[it], level) }
                if (taken.size >= 2) groups += build(taken.map { candidates[it] })
                val takenSet = taken.toHashSet()
                remaining = remaining.filter { it !in takenSet }
            }
        }
        return groups.sortedByDescending { it.reclaimable }
    }

    private fun bestFirst(c: List<SimilarCandidate>): Comparator<Int> =
        Comparator { a, b -> compareBest(c[a], c[b]) }

    /** Higher resolution, then bigger file (less compressed), then older (more likely the original). */
    private fun compareBest(a: SimilarCandidate, b: SimilarCandidate): Int {
        var r = b.sig.pixels.compareTo(a.sig.pixels)
        if (r != 0) return r
        r = b.file.size.compareTo(a.file.size)
        if (r != 0) return r
        r = a.file.dateModified.compareTo(b.file.dateModified)
        if (r != 0) return r
        return a.file.path.compareTo(b.file.path)
    }

    private fun build(members: List<SimilarCandidate>): SimilarGroup {
        val sorted = members.sortedWith { a, b -> compareBest(a, b) }
        val best = sorted.first()
        val rest = sorted.drop(1)
        val reason = when {
            best.sig.pixels > rest.maxOf { it.sig.pixels } * 1.05 ->
                "Highest resolution · ${best.sig.width}×${best.sig.height}"
            best.file.size > rest.maxOf { it.file.size } -> "Best quality · largest file"
            else -> "Oldest · likely the original"
        }
        return SimilarGroup(sorted.map { SimilarPhoto(it.file, it.sig.width, it.sig.height) }, reason)
    }
}
