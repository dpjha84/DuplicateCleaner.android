package com.example.duplicatecleaner.ads

import android.app.Activity
import android.app.Application
import com.example.duplicatecleaner.BuildConfig
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

    fun init() {
        MobileAds.initialize(app) { loadRewarded() }
    }

    fun loadRewarded() {
        if (loading || rewarded != null) return
        loading = true
        RewardedAd.load(
            app,
            BuildConfig.ADMOB_REWARDED_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewarded = ad
                    loading = false
                    _rewardReady.value = true
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewarded = null
                    loading = false
                    _rewardReady.value = false
                }
            }
        )
    }

    fun showRewarded(activity: Activity, onReward: () -> Unit) {
        val ad = rewarded
        if (ad == null) {
            loadRewarded()
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = reset()
            override fun onAdFailedToShowFullScreenContent(error: AdError) = reset()
        }
        ad.show(activity) { onReward() }
    }

    private fun reset() {
        rewarded = null
        _rewardReady.value = false
        loadRewarded()
    }
}
