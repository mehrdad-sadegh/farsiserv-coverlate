package ir.farsisaz

import org.json.JSONObject

/**
 * تنظیماتِ تبلیغات و پرداخت که از سرور (/api/info) خوانده می‌شوند.
 * همه‌چیز پیش‌فرض خاموش است؛ تا وقتی از پنل مدیریت روشن نشود، رفتار فعلی برنامه تغییر نمی‌کند.
 * SDKها از قبل در برنامه کامپایل شده‌اند، پس فعال‌سازی فقط با تغییر همین تنظیمات (بدون آپدیت) انجام می‌شود.
 */
object Monetize {

    /** کانالِ توزیع (استوری که برنامه از آن نصب شده). یک‌بار در شروع تنظیم می‌شود.
     *  برای نسخهٔ جهانی همیشه "global"؛ برای فارسی‌سرو بر اساس استور: bazaar/myket/ir. */
    @JvmStatic
    var channel: String = ""

    /** تشخیصِ استورِ نصب (بدون نیاز به بیلدِ جدا؛ یک APK روی همهٔ استورها). */
    fun detectChannel(ctx: android.content.Context): String {
        if (BuildConfig.EDITION != "fa") return "global"
        val installer = try {
            if (android.os.Build.VERSION.SDK_INT >= 30)
                ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
            else @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(ctx.packageName)
        } catch (_: Exception) { null }
        return when (installer) {
            "com.farsitel.bazaar" -> "bazaar"
            "ir.mservices.market" -> "myket"
            else -> "ir"   // نصبِ مستقیم (APK) یا نامشخص
        }
    }

    /** تنظیمات شبکهٔ تبلیغاتی (علاوه بر بنر مستقیمِ پنل). */
    data class AdsCfg(
        val on: Boolean,                 // شبکهٔ تبلیغاتی روشن است؟ (خاموش = همان بنر مستقیم فعلی)
        val provider: String,            // none | admob | tapsell | adivery
        val hideForLicensed: Boolean,    // برای کاربرِ دارای اشتراک تبلیغ نشان داده نشود
        val admobBanner: String,
        val tapsellKey: String,
        val tapsellZone: String,
        val adiveryApp: String,
        val adiveryPlacement: String,
    )

    fun ads(info: JSONObject): AdsCfg {
        val a = info.optJSONObject("ad_config") ?: JSONObject()
        return AdsCfg(
            on = a.optBoolean("on", false),
            provider = a.optString("provider", "none"),
            hideForLicensed = a.optBoolean("hide_for_licensed", true),
            admobBanner = a.optString("admob_banner", ""),
            tapsellKey = a.optString("tapsell_key", ""),
            tapsellZone = a.optString("tapsell_zone", ""),
            adiveryApp = a.optString("adivery_app", ""),
            adiveryPlacement = a.optString("adivery_placement", ""),
        )
    }

    /** یک روش پرداخت که در صفحهٔ لایسنس نمایش داده می‌شود. */
    data class PayMethod(
        val id: String,
        val type: String,     // url | crypto | googleplay | support
        val title: String,
        val sub: String,
        val url: String,      // برای url/crypto (می‌تواند {hwid} داشته باشد)
        val product: String,  // برای googleplay: شناسهٔ محصول در Play Console
        val primary: Boolean,
    )

    /** فهرست روش‌های پرداخت از سرور؛ اگر چیزی تنظیم نشده بود خالی برمی‌گردد (رفتار فعلی حفظ می‌شود). */
    fun payments(info: JSONObject): List<PayMethod> {
        val arr = info.optJSONArray("payments") ?: return emptyList()
        val out = ArrayList<PayMethod>()
        val edition = BuildConfig.FLAVOR   // "ir" یا "global" — هر نسخه فقط روش‌های خودش را نشان می‌دهد
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            if (!p.optBoolean("on", true)) continue
            val pl = p.optString("platform", "all")
            if (pl != "all" && pl != "android") continue
            val eds = p.optJSONArray("editions")
            if (eds != null && eds.length() > 0) {
                var match = false
                for (j in 0 until eds.length()) if (eds.optString(j) == edition) { match = true; break }
                if (!match) continue
            }
            out.add(
                PayMethod(
                    id = p.optString("id", i.toString()),
                    type = p.optString("type", "url"),
                    title = p.optString("title", "خرید اشتراک"),
                    sub = p.optString("sub", ""),
                    url = p.optString("url", ""),
                    product = p.optString("product", ""),
                    primary = p.optBoolean("primary", false),
                )
            )
        }
        return out
    }

    /** جای‌گذاری کد دستگاه در آدرس پرداخت (برای اینکه سرور بداند لایسنس برای کدام دستگاه است). */
    fun fillUrl(url: String, hwid: String): String =
        url.replace("{hwid}", hwid).replace("%7Bhwid%7D", hwid)

    /** بخشِ license در config؛ هر نسخه (global/ir/myket/…) جداگانه. سازگار با حالت قدیمِ تخت:
     *  اگر license["<edition>"] بود همان؛ وگرنه خودِ license (حالت قدیم که برای همه یکی بود). */
    private fun lic(info: JSONObject): JSONObject {
        val l = info.optJSONObject("license") ?: return JSONObject()
        val ch = channel.ifEmpty { BuildConfig.FLAVOR }
        // اول کانالِ استور (bazaar/myket/ir/global)، بعد flavor، بعد حالت قدیمِ تخت
        return l.optJSONObject(ch) ?: l.optJSONObject(BuildConfig.FLAVOR) ?: l
    }

    /** لایسنس/خرید به‌طور کامل حذف شده: برنامه همیشه رایگان است و فقط با تبلیغات کسب درآمد می‌کند.
     *  این سه تابع ثابت‌اند و دیگر به تنظیماتِ سرور وابسته نیستند (بخشِ لایسنس هیچ‌وقت نشان داده نمی‌شود). */
    @Suppress("UNUSED_PARAMETER")
    fun purchaseOn(info: JSONObject): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun licenseTab(info: JSONObject): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun freeMode(info: JSONObject): Boolean = true
}
