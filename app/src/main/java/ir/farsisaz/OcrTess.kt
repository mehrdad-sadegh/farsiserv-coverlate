package ir.farsisaz

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * موتور OCR دومی برای خط‌هایی که ML Kit پشتیبانی نمی‌کند (سیریلیک/روسی، عربی، یونانی، تایلندی…).
 * فایلِ زبان (`<lang>.traineddata`) داخل برنامه نیست؛ کاربر هر زبان را یک‌بار از سرور دانلود می‌کند
 * و در `filesDir/tessdata/` ذخیره می‌شود. همه‌چیز بعد از دانلود، آفلاین و روی خود گوشی کار می‌کند.
 */
object OcrTess {

    /** یک زبانِ قابل‌دانلود برای Tesseract. */
    data class Pack(val code: String, val name: String, val mb: Int)

    /**
     * زبان‌هایی که ML Kit ندارد و ارزش دانلود دارند (پرکاربردترین‌ها اول).
     * code همان کد Tesseract است (نام فایل: <code>.traineddata).
     */
    val packs: List<Pack> = listOf(
        Pack("rus", "روسی", 5),
        Pack("ara", "عربی", 2),
        Pack("spa", "اسپانیایی", 3),
        Pack("fra", "فرانسوی", 3),
        Pack("deu", "آلمانی", 3),
        Pack("por", "پرتغالی", 3),
        Pack("tur", "ترکی استانبولی", 3),
        Pack("ukr", "اوکراینی", 4),
        Pack("ell", "یونانی", 3),
        Pack("tha", "تایلندی", 4),
        Pack("heb", "عبری", 2),
        Pack("hin", "هندی", 3),
    )

    fun packName(code: String): String = packs.firstOrNull { it.code == code }?.name ?: code

    private fun dir(c: Context): File = File(c.filesDir, "tessdata")
    private fun file(c: Context, lang: String): File = File(dir(c), "$lang.traineddata")

    fun isInstalled(c: Context, lang: String): Boolean = file(c, lang).let { it.exists() && it.length() > 100_000 }

    fun installedCodes(c: Context): Set<String> =
        packs.map { it.code }.filter { isInstalled(c, it) }.toSet()

    // ------------------------------------------------------------ دانلود
    /** دانلودِ فایل زبان از سرور. baseUrl مثل https://farsisaz.softserv.ir/tess . موفق = true. */
    fun download(c: Context, lang: String, baseUrl: String): Boolean {
        val base = baseUrl.trimEnd('/')
        val url = "$base/$lang.traineddata"
        val d = dir(c); d.mkdirs()
        val tmp = File(d, "$lang.part")
        val out = file(c, lang)
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000; conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", UA)
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) { conn.disconnect(); return false }
            conn.inputStream.use { ins -> tmp.outputStream().use { os -> ins.copyTo(os, 64 * 1024) } }
            conn.disconnect()
            if (tmp.length() < 100_000) { tmp.delete(); return false }
            if (out.exists()) out.delete()
            val ok = tmp.renameTo(out)
            if (!ok) tmp.delete()
            ok
        } catch (_: Throwable) {
            try { tmp.delete() } catch (_: Throwable) {}
            false
        }
    }

    fun delete(c: Context, lang: String) { try { file(c, lang).delete() } catch (_: Throwable) {} }

    // ------------------------------------------------------------ تشخیص
    @Volatile private var api: TessBaseAPI? = null
    @Volatile private var curLang = ""

    @Synchronized
    private fun ensure(c: Context, lang: String): TessBaseAPI? {
        if (api != null && curLang == lang) return api
        try { api?.recycle() } catch (_: Throwable) {}
        api = null; curLang = ""
        if (!isInstalled(c, lang)) return null
        val a = TessBaseAPI()
        val ok = try { a.init(c.filesDir.absolutePath, lang) } catch (_: Throwable) { false }
        if (!ok) { try { a.recycle() } catch (_: Throwable) {}; return null }
        try { a.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO } catch (_: Throwable) {}
        api = a; curLang = lang
        return a
    }

    /**
     * خواندن متنِ یک زبان با Tesseract. برای اینکه متنِ لاتینِ قاطی‌شده هم خوانده شود، «eng» را هم
     * کنار زبان بار می‌کنیم (مثلاً "rus+eng"). خروجی به بلوک‌های پاراگرافی تبدیل می‌شود (مثل مسیر غیرلاتینِ ML Kit).
     */
    @Synchronized
    fun read(bmp: Bitmap, lang: String): List<Ocr.Block> {
        val c = try { FarsiApp.ctx } catch (_: Throwable) { return emptyList() }
        val a = ensure(c, lang) ?: return emptyList()
        // Tesseract روی تصویر بزرگ‌تر دقیق‌تر است؛ اگر تصویر کوچک بود ۲ برابرش می‌کنیم و بعد مختصات را برمی‌گردانیم.
        val sc = if (bmp.width < 1600) 2 else 1
        val img = if (sc == 1) bmp else try { Bitmap.createScaledBitmap(bmp, bmp.width * sc, bmp.height * sc, true) } catch (_: Throwable) { bmp }
        val words = ArrayList<Pair<String, Rect>>()
        try {
            a.setImage(img)
            a.getUTF8Text()   // اجرای تشخیص
            val it = a.resultIterator
            if (it != null) {
                it.begin()
                val lvl = TessBaseAPI.PageIteratorLevel.RIL_WORD
                do {
                    val t = try { it.getUTF8Text(lvl)?.trim() } catch (_: Throwable) { null }
                    val r = try { it.getBoundingRect(lvl) } catch (_: Throwable) { null }
                    if (!t.isNullOrEmpty() && r != null && r.width() > 0 && r.height() > 0) {
                        val rr = if (sc == 1) r else Rect(r.left / sc, r.top / sc, r.right / sc, r.bottom / sc)
                        words.add(t to rr)
                    }
                } while (it.next(lvl))
            }
        } catch (_: Throwable) {
            return emptyList()
        } finally {
            try { a.clear() } catch (_: Throwable) {}
            if (img !== bmp) try { img.recycle() } catch (_: Throwable) {}
        }
        return Ocr.groupWords(words)
    }
}
