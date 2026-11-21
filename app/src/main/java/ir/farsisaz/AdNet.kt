package ir.farsisaz

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds

import ir.tapsell.plus.AdRequestCallback
import ir.tapsell.plus.AdShowListener
import ir.tapsell.plus.TapsellPlus
import ir.tapsell.plus.TapsellPlusBannerType
import ir.tapsell.plus.TapsellPlusInitListener
import ir.tapsell.plus.model.AdNetworkError
import ir.tapsell.plus.model.AdNetworks
import ir.tapsell.plus.model.TapsellPlusAdModel
import ir.tapsell.plus.model.TapsellPlusErrorModel

import com.adivery.sdk.Adivery
import com.adivery.sdk.AdiveryBannerAdView
import com.adivery.sdk.BannerSize

/**
 * بنرِ شبکه‌های تبلیغاتی (AdMob/Tapsell/Adivery). همه اختیاری و با کنترل سرورند؛ اگر چیزی خطا بدهد،
 * به‌جای کرش، هیچ تبلیغی نشان داده نمی‌شود. SDKها از قبل کامپایل شده‌اند تا فعال‌سازی بدون آپدیت باشد.
 */
object AdNet {
    @Volatile private var admobInit = false
    @Volatile private var tapsellInit = false
    @Volatile private var adiveryInit = false

    /** یک کارت بنر بر اساس ارائه‌دهندهٔ انتخابی در پنل. */
    fun banner(act: Activity, cfg: Monetize.AdsCfg): View? = try {
        when (cfg.provider) {
            "admob" -> admob(act, cfg.admobBanner)
            "tapsell" -> tapsell(act, cfg.tapsellKey, cfg.tapsellZone)
            "adivery" -> adivery(act, cfg.adiveryApp, cfg.adiveryPlacement)
            else -> null
        }
    } catch (_: Throwable) { null }

    private fun frame(act: Activity): FrameLayout {
        val f = FrameLayout(act)
        f.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .also { it.bottomMargin = Ui.dp(act, 12f) }
        return f
    }

    // ---------------------------------------------------------------- AdMob
    private fun admob(act: Activity, unitId: String): View? {
        if (unitId.isBlank()) return null
        if (!admobInit) { try { MobileAds.initialize(act) {} } catch (_: Throwable) {}; admobInit = true }
        val f = frame(act)
        val ad = AdView(act)
        ad.setAdSize(AdSize.BANNER)
        ad.adUnitId = unitId
        f.addView(ad, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        ad.loadAd(AdRequest.Builder().build())
        return f
    }

    // ---------------------------------------------------------------- Tapsell Plus
    private fun tapsell(act: Activity, key: String, zone: String): View? {
        if (key.isBlank() || zone.isBlank()) return null
        if (!tapsellInit) {
            tapsellInit = true
            try {
                TapsellPlus.initialize(act, key, object : TapsellPlusInitListener {
                    override fun onInitializeSuccess(adNetworks: AdNetworks?) {}
                    override fun onInitializeFailed(adNetworks: AdNetworks?, error: AdNetworkError?) {}
                })
            } catch (_: Throwable) {}
        }
        val f = frame(act)
        try {
            TapsellPlus.requestStandardBannerAd(act, zone, TapsellPlusBannerType.BANNER_320x50, object : AdRequestCallback() {
                override fun response(ad: TapsellPlusAdModel?) {
                    val id = ad?.responseId ?: return
                    try {
                        TapsellPlus.showStandardBannerAd(act, id, f, object : AdShowListener() {
                            override fun onOpened(ad: TapsellPlusAdModel?) {}
                            override fun onError(error: TapsellPlusErrorModel?) {}
                        })
                    } catch (_: Throwable) {}
                }
                override fun error(message: String?) {}
            })
        } catch (_: Throwable) {}
        return f
    }

    // ---------------------------------------------------------------- Adivery
    private fun adivery(act: Activity, appId: String, placement: String): View? {
        if (appId.isBlank() || placement.isBlank()) return null
        if (!adiveryInit) { try { Adivery.configure(act.application, appId) } catch (_: Throwable) {}; adiveryInit = true }
        val f = frame(act)
        try {
            val banner = AdiveryBannerAdView(act)
            banner.setPlacementId(placement)
            banner.setBannerSize(BannerSize.BANNER)
            f.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            banner.loadAd()
        } catch (_: Throwable) {}
        return f
    }
}
