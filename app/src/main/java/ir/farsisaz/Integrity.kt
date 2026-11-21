package ir.farsisaz

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/**
 * بررسی اینکه برنامه با کلید رسمی «فارسی ساز» امضا شده و دستکاری/ری‌پکیج نشده باشد (ضدِ ماد).
 * اگر کسی برنامه را باز کند، کد را عوض کند و دوباره با کلید خودش امضا کند، هش امضا فرق می‌کند
 * و این تابع false برمی‌گرداند؛ در نتیجه دروازهٔ لایسنس بسته می‌شود و برنامه کار نمی‌کند.
 * در برابر خطای غیرمنتظرهٔ سیستم (روی بعضی گوشی‌ها) محتاطانه true برمی‌گرداند تا کاربر واقعی قفل نشود.
 */
object Integrity {
    // هش SHA-256 گواهی امضای DER رسمی برنامه. با هر کلید دیگری امضا شود، متفاوت می‌شود.
    private val EXPECTED = intArrayOf(
        0x52, 0x2A, 0xBB, 0x1D, 0xBC, 0x97, 0xE2, 0x0D, 0x61, 0x1F, 0xCD, 0xA2, 0xFE, 0xAA, 0xB0, 0xA3,
        0xA7, 0xD7, 0x1B, 0xC9, 0x1D, 0x5E, 0x37, 0x19, 0xC1, 0x20, 0x3A, 0xDC, 0xFF, 0x7C, 0xF7, 0x2C
    )

    @Volatile private var cached: Int = 0   // 0=هنوز بررسی نشده، 1=درست، -1=نادرست

    fun ok(c: Context): Boolean {
        val v = cached
        if (v != 0) return v == 1
        val r = try { verify(c.applicationContext) } catch (e: Exception) { true }
        cached = if (r) 1 else -1
        return r
    }

    @Suppress("DEPRECATION")
    private fun verify(c: Context): Boolean {
        val pm = c.packageManager
        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val si = info.signingInfo ?: return true
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        if (sigs == null || sigs.isEmpty()) return true
        val md = MessageDigest.getInstance("SHA-256")
        for (s in sigs) {
            val h = md.digest(s.toByteArray())
            if (h.size == EXPECTED.size && h.indices.all { (h[it].toInt() and 0xFF) == EXPECTED[it] }) return true
        }
        return false
    }
}
