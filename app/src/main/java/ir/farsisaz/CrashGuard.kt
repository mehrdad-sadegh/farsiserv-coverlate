package ir.farsisaz

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.ScrollView
import android.widget.TextView
import java.io.PrintWriter
import java.io.StringWriter

/**
 * گیرندهٔ خطا: اگر برنامه به هر دلیلی کرش کند، متن کامل خطا ذخیره می‌شود و دفعهٔ بعدِ باز شدنِ برنامه
 * روی صفحه نشان داده می‌شود (با دکمهٔ «کپی»). این‌طور حتی روی گوشیِ کاربرِ بدون کامپیوتر هم می‌شود
 * علت کرش را دید و فرستاد. فقط ابزار عیب‌یابی است و رفتار عادی برنامه را تغییر نمی‌دهد.
 */
object CrashGuard {
    private const val PREF = "crash"
    private const val KEY = "last"
    @Volatile private var installed = false

    /** ثبت گیرندهٔ سراسری؛ باید اولین کار در onCreate باشد. */
    fun install(c: Context) {
        if (installed) return
        installed = true
        val app = c.applicationContext ?: c
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            save(app, e, t.name)
            prev?.uncaughtException(t, e)
        }
    }

    fun save(c: Context, e: Throwable, thread: String = "main") {
        try {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val head = "نسخه: ${BuildConfig.EDITION} ${APP_VERSION} (${BuildConfig.FLAVOR})\n" +
                "اندروید: ${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                "نخ: $thread\n\n"
            (c.applicationContext ?: c).getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY, head + sw).apply()
        } catch (_: Throwable) {}
    }

    /** اگر خطایی از اجرای قبلی ذخیره شده، نشانش می‌دهد و true برمی‌گرداند (یعنی ادامهٔ onCreate لازم نیست). */
    fun showIfAny(a: Activity): Boolean {
        val sp = a.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val last = sp.getString(KEY, null) ?: return false
        try {
            val tv = TextView(a)
            tv.text = last
            tv.setTextIsSelectable(true)
            tv.textSize = 11f
            tv.typeface = android.graphics.Typeface.MONOSPACE
            val pad = Ui.dp(a, 16f); tv.setPadding(pad, pad, pad, pad)
            val sv = ScrollView(a); sv.addView(tv)
            AlertDialog.Builder(a)
                .setTitle(L.sx("گزارش خطای اجرای قبلی"))
                .setView(sv)
                .setPositiveButton(L.sx("کپی")) { _, _ ->
                    try {
                        val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("crash", last))
                    } catch (_: Throwable) {}
                }
                .setNegativeButton(L.sx("بستن"), null)
                .setCancelable(true)
                .show()
            // فقط بعد از نمایش موفق پاک می‌شود تا اگر نمایش شکست خورد، دفعهٔ بعد باز نشان داده شود.
            sp.edit().remove(KEY).apply()
            return true
        } catch (_: Throwable) {
            return false
        }
    }
}
