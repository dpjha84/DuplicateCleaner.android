package com.example.duplicatecleaner.billing

import android.content.Context
import android.os.Environment
import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Tracks how much free cleanup is left and whether Pro was bought (cached for offline starts;
 * Play Billing is the source of truth and refreshes it on every launch).
 *
 * Free allowance: [FREE_BYTES] for the life of the install, plus [REWARD_BYTES] for every rewarded ad.
 *
 * Reinstalling must not hand out a fresh allowance, so the counters live in three places:
 *  1. app preferences (fast path),
 *  2. Android Auto Backup (restored on reinstall or a new phone with the same Google account),
 *  3. a small signed marker file in shared storage, which survives uninstall and "clear data".
 * Counters only ever go up, so on conflict the larger value wins. A determined user can still delete
 * the marker and disable backups; that is an accepted limit without a server.
 */
class Entitlements(context: Context) {

    private val prefs = context.getSharedPreferences("entitlements", Context.MODE_PRIVATE)

    init {
        syncFromMarker()
    }

    var isProCached: Boolean
        get() = prefs.getBoolean(KEY_PRO, false)
        set(value) = prefs.edit().putBoolean(KEY_PRO, value).apply()

    private var usedBytes: Long
        get() = prefs.getLong(KEY_USED, 0L)
        set(value) = prefs.edit().putLong(KEY_USED, value).apply()

    private var bonusBytes: Long
        get() = prefs.getLong(KEY_BONUS, 0L)
        set(value) = prefs.edit().putLong(KEY_BONUS, value).apply()

    fun freeRemaining(): Long = (freeTotal() - usedBytes).coerceAtLeast(0L)

    /** Free allowance including bonuses from rewarded ads. */
    fun freeTotal(): Long = FREE_BYTES + bonusBytes

    fun recordUsed(bytes: Long) {
        usedBytes += bytes
        writeMarker()
    }

    fun addRewardBonus() {
        bonusBytes += REWARD_BYTES
        writeMarker()
    }

    /**
     * Merges the marker file into the preferences (larger counter wins) and rewrites it when it is
     * missing or behind. Returns true if the allowance changed. Needs storage access, so it quietly
     * does nothing until the user has granted it; call again after the permission is granted.
     */
    fun syncFromMarker(): Boolean {
        val stored = readMarker()
        var changed = false
        if (stored != null) {
            if (stored.first > usedBytes) { usedBytes = stored.first; changed = true }
            if (stored.second > bonusBytes) { bonusBytes = stored.second; changed = true }
        }
        if (stored == null || stored.first != usedBytes || stored.second != bonusBytes) writeMarker()
        return changed
    }

    private fun markerFile(): File? = try {
        File(Environment.getExternalStorageDirectory(), MARKER_NAME)
    } catch (e: Exception) {
        null
    }

    private fun readMarker(): Pair<Long, Long>? = try {
        val text = markerFile()?.takeIf { it.isFile }?.readText()?.trim() ?: return null
        val parts = text.split('|')
        if (parts.size != 4 || parts[0] != "v1") return null
        val used = parts[1].toLong()
        val bonus = parts[2].toLong()
        if (used < 0 || bonus < 0 || !sign(used, bonus).equals(parts[3], ignoreCase = true)) null else used to bonus
    } catch (e: Exception) {
        null
    }

    private fun writeMarker() {
        try {
            val f = markerFile() ?: return
            val used = usedBytes
            val bonus = bonusBytes
            // Never write a marker for a fresh install: nothing to protect, and it avoids touching
            // storage before the user has done anything.
            if (used == 0L && bonus == 0L) return
            f.writeText("v1|$used|$bonus|${sign(used, bonus)}")
        } catch (e: Exception) {
            // No storage access yet (or read-only): the preferences and backup still hold the counters.
        }
    }

    private fun sign(used: Long, bonus: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(KEY, "HmacSHA256"))
        return mac.doFinal("v1|$used|$bonus".toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val FREE_BYTES = 500L shl 20      // 500 MB
        const val REWARD_BYTES = 500L shl 20    // 500 MB per rewarded ad
        private const val KEY_PRO = "pro"
        private const val KEY_USED = "used_bytes"
        private const val KEY_BONUS = "bonus_bytes"
        private const val MARKER_NAME = ".cloneout_state"
        /** Stops casual edits of the marker; not a secret (it ships inside the app). */
        private val KEY = "cloneout-allowance-v1-2d7f9c41".toByteArray()
    }
}
