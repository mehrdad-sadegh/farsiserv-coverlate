package ir.farsisaz

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URL

const val APP_VERSION = "0.1"
const val SERVER_URL = "https://farsisaz.softserv.ir"
const val UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

/** تنظیمات برنامه */
object Prefs {
    lateinit var sp: SharedPreferences
    private var ready = false

    fun init(c: Context) {
        if (ready) return
        sp = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        ready = true
        if (CookieHandler.getDefault() == null) CookieHandler.setDefault(CookieManager())
    }

    var provider: String
        get() = sp.getString("provider", "microsoft") ?: "microsoft"
        set(v) = sp.edit().putString("provider", v).apply()
    var fontSize: Int
        get() = sp.getInt("font_size", 15)
        set(v) = sp.edit().putInt("font_size", v).apply()
    var opacity: Int
        get() = sp.getInt("opacity", 90)
        set(v) = sp.edit().putInt("opacity", v).apply()
    var persianDigits: Boolean
        get() = sp.getBoolean("persian_digits", true)
        set(v) = sp.edit().putBoolean("persian_digits", v).apply()
    var liveInterval: Int
        get() = sp.getInt("live_interval", 700)
        set(v) = sp.edit().putInt("live_interval", v).apply()
    var notifiedVersion: String
        get() = sp.getString("notified_version", "") ?: ""
        set(v) = sp.edit().putString("notified_version", v).apply()
    var glossary: String
        get() = sp.getString("glossary", "") ?: ""
        set(v) = sp.edit().putString("glossary", v).apply()
    var seenGuide: Boolean
        get() = sp.getBoolean("seen_guide", false)
        set(v) = sp.edit().putBoolean("seen_guide", v).apply()
    /** اولین باز شدن برنامه: صفحهٔ راهنما نمایش داده و این true می‌شود. */
    var seenIntro: Boolean
        get() = sp.getBoolean("seen_intro", false)
        set(v) = sp.edit().putBoolean("seen_intro", v).apply()
    /** "tap" = با زدن حباب یک‌بار ترجمه می‌شود · "auto" = ترجمه خودکار و مداوم تا وقتی کاربر خاموش کند */
    var mode: String
        get() = sp.getString("mode_v2", "tap") ?: "tap"      // v2: حالت پیش‌فرض برای همه «ترجمه با لمس» شد
        set(v) = sp.edit().putString("mode_v2", v).apply()
    /** جا و اندازه پنل ترجمه زنده (-1 = پیش‌فرض) */
    var panelBox: IntArray
        get() = (sp.getString("panel_box", "") ?: "").split(",").mapNotNull { it.toIntOrNull() }
            .let { if (it.size == 4) it.toIntArray() else intArrayOf(-1, -1, -1, -1) }
        set(v) = sp.edit().putString("panel_box", v.joinToString(",")).apply()
    var autoInterval: Int
        get() = sp.getInt("auto_interval", 900)
        set(v) = sp.edit().putInt("auto_interval", v).apply()

    /** زبان مقصدِ ترجمهٔ صفحه (پیش‌فرضِ هر نسخه از BuildConfig.DEF_LANG: ایران=فارسی، جهانی=انگلیسی) */
    var targetLang: String
        get() = sp.getString("target_lang", BuildConfig.DEF_LANG) ?: BuildConfig.DEF_LANG
        set(v) = sp.edit().putString("target_lang", v).apply()
    /** خطِ متنی که OCR روی صفحه می‌خواند؛ «auto» = تشخیص خودکارِ زبان/خط. */
    var sourceScript: String
        get() = sp.getString("source_script", "auto") ?: "auto"
        set(v) = sp.edit().putString("source_script", v).apply()
    /** ترجمهٔ دوطرفه: زبان مبدأ و مقصدِ کادر تایپ دستی */
    var duoSource: String
        get() = sp.getString("duo_source", "auto") ?: "auto"
        set(v) = sp.edit().putString("duo_source", v).apply()
    var duoTarget: String
        get() = sp.getString("duo_target", "en") ?: "en"
        set(v) = sp.edit().putString("duo_target", v).apply()
    /** زبان رابط کاربری (پیش‌فرضِ هر نسخه از BuildConfig.DEF_LANG) */
    var uiLang: String
        get() = sp.getString("ui_lang", BuildConfig.DEF_LANG) ?: BuildConfig.DEF_LANG
        set(v) = sp.edit().putString("ui_lang", v).apply()
    /** آیا کاربر در صفحهٔ اول زبان را انتخاب کرده؟ */
    var langChosen: Boolean
        get() = sp.getBoolean("lang_chosen", false)
        set(v) = sp.edit().putBoolean("lang_chosen", v).apply()
    /** تمِ برنامه: تیره (پیش‌فرض) یا روشن */
    var darkMode: Boolean
        get() = sp.getBoolean("dark_mode", true)
        set(v) = sp.edit().putBoolean("dark_mode", v).apply()

    // ---------------------------------------------------------------- شمارشِ منصفانهٔ تبلیغ
    @Volatile private var _devId: String? = null
    /** شناسهٔ یکتا و ماندگارِ این نصب — برای شمارشِ منصفانهٔ بازدیدِ تبلیغ (هیچ هویتی افشا نمی‌کند). */
    val deviceId: String
        get() {
            _devId?.let { return it }
            var id = sp.getString("device_id", null)
            if (id.isNullOrEmpty()) {
                id = java.util.UUID.randomUUID().toString().replace("-", "")
                sp.edit().putString("device_id", id).apply()
            }
            _devId = id
            return id!!
        }

    private fun todayKey(): Int {
        val c = java.util.Calendar.getInstance()
        return c.get(java.util.Calendar.YEAR) * 10000 +
            (c.get(java.util.Calendar.MONTH) + 1) * 100 + c.get(java.util.Calendar.DAY_OF_MONTH)
    }

    /** آیا امروز برای این تبلیغ «بازدید» شمرده و به سرور گزارش شده؟ (یک‌بار در هر شبانه‌روز برای هر دستگاه) */
    fun adViewedToday(id: String): Boolean = sp.getInt("adv_$id", 0) == todayKey()
    fun markAdViewed(id: String) = sp.edit().putInt("adv_$id", todayKey()).apply()

    /** آیا راهنمای پاپ‌آپ این بخش قبلاً به کاربر نشان داده شده؟ (بار اول هر بخش) */
    fun coachSeen(id: String): Boolean = sp.getBoolean("coach_$id", false)
    fun markCoach(id: String) = sp.edit().putBoolean("coach_$id", true).apply()

    // ---------------------------------------------------------------- بستهٔ زبانِ رابط (انگلیسی → زبانِ کاربر)
    /** آیا بستهٔ زبانِ رابط برای این زبان ساخته و ذخیره شده؟ (en/fa نیازی ندارند) */
    fun hasUiPack(lang: String): Boolean = lang == "en" || lang == "fa" || sp.contains("uipack_$lang")

    /** خواندنِ بستهٔ زبانِ رابط: نقشهٔ انگلیسی → زبانِ کاربر. اگر نبود null. */
    fun uiPack(lang: String): Map<String, String>? {
        val raw = sp.getString("uipack_$lang", null) ?: return null
        return try {
            val o = JSONObject(raw)
            val m = HashMap<String, String>(o.length() * 2)
            val keys = o.keys()
            while (keys.hasNext()) { val k = keys.next(); m[k] = o.getString(k) }
            m
        } catch (_: Throwable) { null }
    }

    /** ذخیرهٔ بستهٔ زبانِ رابط. */
    fun setUiPack(lang: String, map: Map<String, String>) {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        sp.edit().putString("uipack_$lang", o.toString()).apply()
    }
}

/** درخواست‌های ساده HTTP (بدون کتابخانه اضافه) */
object Net {
    class Resp(val code: Int, val body: String)

    fun request(
        url: String, method: String = "GET", body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(), timeout: Int = 15000,
    ): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val out = ByteArrayOutputStream()
            stream?.use { it.copyTo(out) }
            return Resp(code, out.toString("UTF-8"))
        } finally {
            c.disconnect()
        }
    }

    fun postJson(url: String, json: JSONObject, timeout: Int = 12000): Resp =
        request(url, "POST", json.toString().toByteArray(), mapOf("Content-Type" to "application/json"), timeout)
}

/** دیتابیس کوچک: حافظه ترجمه و تاریخچه */
class Db(c: Context) : SQLiteOpenHelper(c.applicationContext, "farsisaz.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cache (provider TEXT, src TEXT, dst TEXT, PRIMARY KEY(provider, src))")
        db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, src TEXT, dst TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    @Synchronized
    fun cacheGet(provider: String, src: String): String? {
        readableDatabase.rawQuery("SELECT dst FROM cache WHERE provider=? AND src=?", arrayOf(provider, src)).use {
            return if (it.moveToFirst()) it.getString(0) else null
        }
    }

    @Synchronized
    fun cachePut(provider: String, src: String, dst: String) {
        val v = ContentValues()
        v.put("provider", provider); v.put("src", src); v.put("dst", dst)
        writableDatabase.insertWithOnConflict("cache", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun addHistory(src: String, dst: String) {
        val db = writableDatabase
        db.rawQuery("SELECT src FROM history ORDER BY id DESC LIMIT 1", null).use {
            if (it.moveToFirst() && it.getString(0) == src) return
        }
        val v = ContentValues()
        v.put("ts", System.currentTimeMillis() / 1000); v.put("src", src); v.put("dst", dst)
        db.insert("history", null, v)
        db.execSQL("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY id DESC LIMIT 300)")
    }

    @Synchronized
    fun history(limit: Int = 150): List<Triple<Long, String, String>> {
        val out = ArrayList<Triple<Long, String, String>>()
        readableDatabase.rawQuery("SELECT ts, src, dst FROM history ORDER BY id DESC LIMIT $limit", null).use {
            while (it.moveToNext()) out.add(Triple(it.getLong(0), it.getString(1), it.getString(2)))
        }
        return out
    }

    @Synchronized
    fun clearAll() {
        writableDatabase.execSQL("DELETE FROM cache")
        writableDatabase.execSQL("DELETE FROM history")
    }

    companion object {
        @Volatile private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) { inst ?: Db(c).also { inst = it } }
    }
}

/** قواعد نگارشی فارسی */
object Persian {
    private const val FA = "؀-ۿ"
    private const val ZWNJ = "‌"
    const val RLM = "‏"

    fun polish(text: String, digits: Boolean = Prefs.persianDigits): String {
        if (text.isBlank()) return text
        var t = text.replace('ي', 'ی').replace('ك', 'ک').replace('ى', 'ی')
        t = t.replace(Regex("(^|[\\s(«])(ن?می)\\s+(?=[$FA])"), "$1$2$ZWNJ")
        t = t.replace(
            Regex("([$FA])\\s+(ها|های|هایی|تر|ترین)(?=$|[\\s.,!?،؛:؟)»])"), "$1$ZWNJ$2"
        )
        t = t.replace(Regex("[ \\t]+([?,;.!:])"), "$1")
        t = t.replace(Regex("(?<=[$FA\\s])\\?"), "؟")
        t = t.replace(Regex("(?<=[$FA]),"), "،")
        t = t.replace(Regex("(?<=[$FA]);"), "؛")
        t = t.replace(Regex("\"([^\"]+)\""), "«$1»")
        t = t.replace(Regex("\\s+([.!؟،؛:)»])"), "$1")
        t = t.replace(Regex("([.!؟،؛:])(?=[${FA}A-Za-z])"), "$1 ")
        t = t.replace(Regex("[ \\t]{2,}"), " ")
        if (digits) t = faDigits(t)
        return t.trim()
    }

    fun faDigits(s: String): String {
        if (L.lang != "fa") return s            // در زبان‌های غیرفارسی، ارقام لاتین می‌مانند
        val sb = StringBuilder(s.length)
        for (ch in s) sb.append(if (ch in '0'..'9') ('۰' + (ch - '0')) else ch)
        return sb.toString()
    }

    fun display(s: String): String = s.split("\n").joinToString("\n") { if (it.isEmpty()) it else RLM + it }

    /** متن تمام حروف بزرگ (مثل منوی بازی‌ها) قبل از ترجمه عادی می‌شود تا مترجم بهتر بفهمد */
    fun fixCaps(s: String): String {
        val letters = s.count { it.isLetter() }
        if (letters < 4) return s
        val upper = s.count { it.isUpperCase() }
        if (upper < letters * 0.8) return s
        val low = s.lowercase()
        val sb = StringBuilder(low)
        var cap = true
        for (i in sb.indices) {
            val ch = sb[i]
            if (cap && ch.isLetter()) { sb.setCharAt(i, ch.uppercaseChar()); cap = false }
            if (ch == '.' || ch == '!' || ch == '?' || ch == '\n') cap = true
        }
        return sb.toString().replace(Regex("\\bi\\b"), "I")
    }

    fun needsTranslation(s: String): Boolean = s.count { it.isLetter() } >= 2

    fun jalali(ts: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = ts * 1000
        val gy = cal.get(java.util.Calendar.YEAR)
        val gm = cal.get(java.util.Calendar.MONTH) + 1
        val gd = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 + gd + gdm[gm - 1]
        var jy = -1595 + 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) { jy += (days - 1) / 365; days = (days - 1) % 365 }
        val jm: Int
        val jd: Int
        if (days < 186) { jm = 1 + days / 31; jd = 1 + days % 31 } else { jm = 7 + (days - 186) / 30; jd = 1 + (days - 186) % 30 }
        val months = arrayOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")
        return faDigits("$jd ${months[jm - 1]} $jy")
    }

    fun price(toman: Long): String {
        val s = String.format(java.util.Locale.US, "%,d", toman).replace(',', '٬')
        return faDigits(s) + " تومان"
    }

    // قیمت با واحد پولِ نسخه (تومان/ریال بدون اعشار، دلار/یورو/… با دو رقم اعشار).
    fun money(amount: Double, currency: String): String {
        val cur = currency.lowercase()
        val dec = if (cur == "toman" || cur == "rial") 0 else 2
        val raw = String.format(java.util.Locale.US, "%,.${dec}f", amount).replace(',', '٬')
        val n = faDigits(raw)
        return when (cur) {
            "toman" -> "$n تومان"
            "rial" -> "$n ریال"
            "usd" -> "$$n"
            "eur" -> "€$n"
            "gbp" -> "£$n"
            "try" -> "₺$n"
            "aed" -> "$n AED"
            "usdt" -> "$n USDT"
            else -> n
        }
    }
}
