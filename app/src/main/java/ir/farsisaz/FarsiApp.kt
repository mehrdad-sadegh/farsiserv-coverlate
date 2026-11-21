package ir.farsisaz

import android.app.Application
import android.content.Context

/**
 * کلاس Application: فقط برای اینکه گیرندهٔ خطا (CrashGuard) در زودترین نقطهٔ ممکن فعال شود،
 * قبل از ساخته‌شدن هر Activity یا اجرای کد کتابخانه‌ها. این‌طور حتی کرش‌های خیلی زودِ راه‌اندازی
 * هم ذخیره می‌شوند و دفعهٔ بعد روی صفحه نشان داده می‌شوند.
 */
class FarsiApp : Application() {
    companion object {
        /** کانتکست برنامه برای جاهایی که دستشان به Context نمی‌رسد (مثل موتور OCR تِسِرکت). */
        @Volatile lateinit var ctx: Context
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        ctx = base.applicationContext ?: base
        try { CrashGuard.install(this) } catch (_: Throwable) {}
    }

    override fun onCreate() {
        super.onCreate()
        try { CrashGuard.install(this) } catch (_: Throwable) {}
    }
}
