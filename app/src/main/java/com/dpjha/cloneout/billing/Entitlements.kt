package com.dpjha.cloneout.billing

import android.content.Context
import android.os.Environment
import java.io.File
import java.time.LocalDate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Tracks how much free cleanup is left and whether Pro was bought (cached for offline starts;
 * Play Billing is the source of truth and refreshes it on every launch).
 *
 * Free allowance: [FREE_BYTES] for the life of the install, plus [REWARD_BYTES] for every rewarded ad,
 * at most [MAX_ADS_PER_DAY] ads per calendar day (device local time).
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

    /** Day (days since epoch, local time) the ad counter below belongs to; 0 = never watched one. */
    private var adDay: Long
        get() = prefs.getLong(KEY_AD_DAY, 0L)
        set(value) = prefs.edit().putLong(KEY_AD_DAY, value).apply()

    private var adCount: Int
        get() = prefs.getInt(KEY_AD_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_AD_COUNT, value).apply()

    /**
     * Today's date as a day number. Never earlier than the last day an ad was watched, so setting the
     * phone clock back does not hand out extra ads.
     */
    private fun today(): Long = maxOf(LocalDate.now().toEpochDay(), adDay)

    /** How many more top-up ads can be watched today. */
    fun adsLeftToday(): Int {
        if (adDay != today()) return MAX_ADS_PER_DAY
        return (MAX_ADS_PER_DAY - adCount).coerceIn(0, MAX_ADS_PER_DAY)
    }

    fun freeRemaining(): Long = (freeTotal() - usedBytes).coerceAtLeast(0L)

    /** Free allowance including bonuses from rewarded ads. */
    fun freeTotal(): Long = FREE_BYTES + bonusBytes

    fun recordUsed(bytes: Long) {
        usedBytes += bytes
        writeMarker()
    }

    /** Adds the ad reward. Returns false (and adds nothing) when today's limit is already used up. */
    fun addRewardBonus(): Boolean {
        if (adsLeftToday() <= 0) return false
        val day = today()
        if (adDay != day) {
            adDay = day
            adCount = 0
        }
        adCount += 1
        bonusBytes += REWARD_BYTES
        writeMarker()
        return true
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
            if (stored.used > usedBytes) { usedBytes = stored.used; changed = true }
            if (stored.bonus > bonusBytes) { bonusBytes = stored.bonus; changed = true }
            // The newer day wins; on the same day the higher count wins.
            if (stored.adDay > adDay) {
                adDay = stored.adDay; adCount = stored.adCount; changed = true
            } else if (stored.adDay == adDay && stored.adCount > adCount) {
                adCount = stored.adCount; changed = true
            }
        }
        if (stored == null || stored.version != MARKER_VERSION || stored.used != usedBytes ||
            stored.bonus != bonusBytes || stored.adDay != adDay || stored.adCount != adCount
        ) writeMarker()
        return changed
    }

    private fun markerFile(): File? = try {
        File(Environment.getExternalStorageDirectory(), MARKER_NAME)
    } catch (e: Exception) {
        null
    }

    private class Marker(val version: Int, val used: Long, val bonus: Long, val adDay: Long, val adCount: Int)

    private fun readMarker(): Marker? {
        try {
            val text = markerFile()?.takeIf { it.isFile }?.readText()?.trim() ?: return null
            val parts = text.split('|')
            if (parts.size == 4 && parts[0] == "v1") {
                // Written by version 1.0 (no daily ad counter).
                val used = parts[1].toLong()
                val bonus = parts[2].toLong()
                if (used < 0 || bonus < 0 || !sign("v1|$used|$bonus").equals(parts[3], ignoreCase = true)) return null
                return Marker(1, used, bonus, 0L, 0)
            }
            if (parts.size == 6 && parts[0] == "v2") {
                val used = parts[1].toLong()
                val bonus = parts[2].toLong()
                val day = parts[3].toLong()
                val count = parts[4].toInt()
                if (used < 0 || bonus < 0 || day < 0 || count < 0) return null
                if (!sign("v2|$used|$bonus|$day|$count").equals(parts[5], ignoreCase = true)) return null
                return Marker(2, used, bonus, day, count)
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    private fun writeMarker() {
        try {
            val f = markerFile() ?: return
            val used = usedBytes
            val bonus = bonusBytes
            val day = adDay
            val count = adCount
            // Never write a marker for a fresh install: nothing to protect, and it avoids touching
            // storage before the user has done anything.
            if (used == 0L && bonus == 0L && count == 0) return
            f.writeText("v2|$used|$bonus|$day|$count|${sign("v2|$used|$bonus|$day|$count")}")
        } catch (e: Exception) {
            // No storage access yet (or read-only): the preferences and backup still hold the counters.
        }
    }

    private fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(KEY, "HmacSHA256"))
        return mac.doFinal(payload.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val FREE_BYTES = 200L shl 20      // 200 MB
        const val REWARD_BYTES = 50L shl 20      // 50 MB per rewarded ad
        const val MAX_ADS_PER_DAY = 2
        private const val MARKER_VERSION = 2
        private const val KEY_PRO = "pro"
        private const val KEY_USED = "used_bytes"
        private const val KEY_BONUS = "bonus_bytes"
        private const val KEY_AD_DAY = "ad_day"
        private const val KEY_AD_COUNT = "ad_count"
        private const val MARKER_NAME = ".cloneout_state"
        /** Stops casual edits of the marker; not a secret (it ships inside the app). */
        private val KEY = "cloneout-allowance-v1-2d7f9c41".toByteArray()
    }
}
