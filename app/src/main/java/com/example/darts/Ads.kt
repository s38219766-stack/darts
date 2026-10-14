package com.example.darts

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.unity3d.ads.IUnityAdsInitializationListener
import com.unity3d.ads.IUnityAdsLoadListener
import com.unity3d.ads.IUnityAdsShowListener
import com.unity3d.ads.UnityAds
import com.unity3d.ads.UnityAdsShowOptions
import com.unity3d.ads.metadata.PlayerMetaData

object AdsConfig {
    const val GAME_ID = "800397390"
    const val REWARDED = "BP_Rewarded_Android"
    const val INTERSTITIAL = "BP_Interstitial_Android"

    // true = только тестовая реклама. Перед релизом поставить false.
    const val TEST_MODE = true
}

class Ads(private val appContext: Context) {
    var initialized by mutableStateOf(false)
        private set
    var rewardedReady by mutableStateOf(false)
        private set
    var interstitialReady by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    private val handler = Handler(Looper.getMainLooper())

    fun init() {
        UnityAds.initialize(
            appContext, AdsConfig.GAME_ID, AdsConfig.TEST_MODE,
            object : IUnityAdsInitializationListener {
                override fun onInitializationComplete() {
                    initialized = true
                    load(AdsConfig.INTERSTITIAL)
                    load(AdsConfig.REWARDED)
                }

                override fun onInitializationFailed(
                    error: UnityAds.UnityAdsInitializationError?, message: String?
                ) {
                    lastError = "Unity init: $error $message"
                }
            }
        )
    }

    private fun setReady(placement: String, ready: Boolean) {
        if (placement == AdsConfig.REWARDED) rewardedReady = ready else interstitialReady = ready
    }

    private fun load(placement: String) {
        UnityAds.load(placement, object : IUnityAdsLoadListener {
            override fun onUnityAdsAdLoaded(placementId: String?) {
                setReady(placement, true)
            }

            override fun onUnityAdsFailedToLoad(
                placementId: String?, error: UnityAds.UnityAdsLoadError?, message: String?
            ) {
                setReady(placement, false)
                lastError = "Load $placement: $error $message"
                handler.postDelayed({ load(placement) }, 20_000) // повторная попытка
            }
        })
    }

    // Реклама после поражения (без награды)
    fun showInterstitial(activity: Activity): Boolean {
        if (!interstitialReady) return false
        setReady(AdsConfig.INTERSTITIAL, false)
        UnityAds.show(
            activity, AdsConfig.INTERSTITIAL, UnityAdsShowOptions(),
            object : IUnityAdsShowListener {
                override fun onUnityAdsShowFailure(
                    placementId: String?, error: UnityAds.UnityAdsShowError?, message: String?
                ) {
                    lastError = "Show interstitial: $error $message"
                    load(AdsConfig.INTERSTITIAL)
                }

                override fun onUnityAdsShowStart(placementId: String?) {}
                override fun onUnityAdsShowClick(placementId: String?) {}
                override fun onUnityAdsShowComplete(
                    placementId: String?, state: UnityAds.UnityAdsShowCompletionState?
                ) {
                    load(AdsConfig.INTERSTITIAL)
                }
            }
        )
        return true
    }

    // Реклама за награду. userId уходит на сервер Unity как sid
    // и возвращается нам в подтверждении, по нему сервер начисляет баланс.
    // onResult(true) = досмотрено до конца. Сам баланс приложение не меняет.
    fun showRewarded(activity: Activity, userId: String, onResult: (Boolean) -> Unit): Boolean {
        if (!rewardedReady) return false
        setReady(AdsConfig.REWARDED, false)

        val meta = PlayerMetaData(activity)
        meta.setServerId(userId)
        meta.commit()

        UnityAds.show(
            activity, AdsConfig.REWARDED, UnityAdsShowOptions(),
            object : IUnityAdsShowListener {
                override fun onUnityAdsShowFailure(
                    placementId: String?, error: UnityAds.UnityAdsShowError?, message: String?
                ) {
                    lastError = "Show rewarded: $error $message"
                    load(AdsConfig.REWARDED)
                    onResult(false)
                }

                override fun onUnityAdsShowStart(placementId: String?) {}
                override fun onUnityAdsShowClick(placementId: String?) {}
                override fun onUnityAdsShowComplete(
                    placementId: String?, state: UnityAds.UnityAdsShowCompletionState?
                ) {
                    load(AdsConfig.REWARDED)
                    onResult(state == UnityAds.UnityAdsShowCompletionState.COMPLETED)
                }
            }
        )
        return true
    }
}
