package com.dpjha.cloneout.ads

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import com.dpjha.cloneout.BuildConfig
import com.dpjha.cloneout.telemetry.Telemetry
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Loads and shows the rewarded ad that earns extra free cleanup. Must be used on the main thread. */
class AdManager(private val app: Application) {

    private var rewarded: RewardedAd? = null
    private var loading = false

    private val _rewardReady = MutableStateFlow(false)
    val rewardReady: StateFlow<Boolean> = _rewardReady.asStateFlow()

    private var started = false
    private var failures = 0
    private val handler = Handler(Looper.getMainLooper())

    /** Call only once consent allows ads (see ConsentManager). Safe to call repeatedly. */
    fun init() {
        if (started) return
        started = true
        MobileAds.initialize(app) { loadRewarded() }
    }

    /** Starts loading an ad if none is ready. No-op until [init] ran (i.e. consent allows ads). */
    fun loadRewarded() {
        if (!started || loading || rewarded != null) return
        loading = true
        RewardedAd.load(
            app,
            BuildConfig.ADMOB_REWARDED_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewarded = ad
                    loading = false
                    failures = 0
                    _rewardReady.value = true
                    Telemetry.event("rewarded_ad_loaded")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewarded = null
                    loading = false
                    _rewardReady.value = false
                    failures++
                    Telemetry.event("rewarded_ad_load_failed", "code" to error.code, "attempt" to failures)
                    // Try again a little later (e.g. no fill, or the phone was offline), a few times only.
                    if (failures <= MAX_RETRIES) handler.postDelayed({ loadRewarded() }, RETRY_MS * failures)
                }
            }
        )
    }

    /** Shows the ad. Returns false when none is ready (a new one is requested). */
    fun showRewarded(activity: Activity, onReward: () -> Unit): Boolean {
        val ad = rewarded
        if (ad == null) {
            loadRewarded()
            return false
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                Telemetry.event("rewarded_ad_shown")
            }

            override fun onAdDismissedFullScreenContent() = reset()

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Telemetry.event("rewarded_ad_show_failed", "code" to error.code)
                reset()
            }
        }
        ad.show(activity) { onReward() }
        return true
    }

    private fun reset() {
        rewarded = null
        _rewardReady.value = false
        loadRewarded()
    }

    private companion object {
        const val MAX_RETRIES = 4
        const val RETRY_MS = 20_000L
    }
}
