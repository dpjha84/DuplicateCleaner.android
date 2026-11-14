package com.example.duplicatecleaner.billing

import android.content.Context

/**
 * Tracks how much free cleanup is left and whether Pro was bought (cached for offline starts;
 * Play Billing is the source of truth and refreshes it on every launch).
 *
 * Free allowance: [FREE_BYTES] for the life of the install, plus [REWARD_BYTES] for every rewarded ad.
 */
class Entitlements(context: Context) {

    private val prefs = context.getSharedPreferences("entitlements", Context.MODE_PRIVATE)

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
    }

    fun addRewardBonus() {
        bonusBytes += REWARD_BYTES
    }

    companion object {
        const val FREE_BYTES = 1L shl 30        // 1 GB
        const val REWARD_BYTES = 500L shl 20    // 500 MB per rewarded ad
        private const val KEY_PRO = "pro"
        private const val KEY_USED = "used_bytes"
        private const val KEY_BONUS = "bonus_bytes"
    }
}
