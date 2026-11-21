package ir.farsisaz

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.RSAPublicKeySpec

/** همان سیستم لایسنس نسخه ویندوز (همان سرور، همان لایسنس‌ها؛ گوشی هم یکی از ۲ دستگاه حساب می‌شود) */
data class LicenseState(
    val status: String = "unknown",   // trial | licensed | expired | trial_over | blocked | need_online | unknown
    val until: Long = 0,
    val plan: String = "",
    val key: String = "",
    var online: Boolean = false,
    var message: String = "",
) {
    val allowed: Boolean get() = status == "trial" || status == "licensed"
    val daysLeft: Int
        get() = if (until <= 0) 0 else maxOf(0, Math.ceil((until - System.currentTimeMillis() / 1000.0) / DAY).toInt())

    companion object { const val DAY = 86400L }
}

class LicenseManager private constructor(c: Context) {
    private val sp = c.applicationContext.getSharedPreferences("license", Context.MODE_PRIVATE)
    private val ctx = c.applicationContext
    @Volatile var state: LicenseState = LicenseState()
    /** آیا حداقل یک‌بار در این اجرا، info را تازه از سرور گرفته‌ایم؟ (برای جلوگیری از نمایش اشتباهِ بخش خرید در اولین اجرا) */
    @Volatile var gotInfo: Boolean = false

    val hwid: String by lazy { computeHwid() }

    var info: JSONObject
        get() = try { JSONObject(sp.getString("info", null) ?: DEFAULT_INFO) } catch (e: Exception) { JSONObject(DEFAULT_INFO) }
        set(v) = sp.edit().putString("info", v.toString()).apply()

    @SuppressLint("HardwareIds")
    private fun computeHwid(): String {
        val aid = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "none"
        val raw = "farsisaz-android|$aid|${Build.MANUFACTURER}|${Build.MODEL}|${Build.BOARD}"
        val h = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        val hex = h.joinToString("") { "%02X".format(it) }.substring(0, 20)
        return hex.chunked(4).joinToString("-")
    }

    // ---------- بدون اینترنت
    fun offlineState(): LicenseState {
        if (!Integrity.ok(ctx))
            return LicenseState("blocked", message = "این نسخهٔ برنامه معتبر نیست (دستکاری شده). لطفاً برنامه را فقط از مسیر رسمی نصب کنید.")
        val tok = sp.getString("token", null)
        val p = tok?.let { verifyToken(it) }
        if (p == null || p.optString("hwid") != hwid)
            return LicenseState("need_online", message = "برای شروع، یک بار با اینترنت وارد برنامه شوید.")
        val now = System.currentTimeMillis() / 1000
        val seen = sp.getLong("max_seen", 0)
        if (now < seen - LicenseState.DAY)
            return LicenseState("need_online", message = "ساعت گوشی تغییر کرده؛ یک بار به اینترنت وصل شوید.")
        sp.edit().putLong("max_seen", maxOf(seen, now)).apply()
        var status = p.optString("status")
        val until = p.optLong("until", 0)
        if ((status == "trial" || status == "licensed") && now > until)
            status = if (status == "licensed") "expired" else "trial_over"
        if ((status == "trial" || status == "licensed") && now - p.optLong("iat", 0) > p.optLong("grace", 14 * LicenseState.DAY))
            return LicenseState("need_online", until, p.optString("plan"), p.optString("key"),
                message = "مدت زیادی بدون اینترنت گذشته؛ برای بررسی لایسنس یک بار وصل شوید.")
        return LicenseState(status, until, p.optString("plan"), p.optString("key"))
    }

    // نسخهٔ این برنامه برای سرور: فارسی‌سرو یا کاورلیت (تا پلن‌ها و تبلیغاتِ همان نسخه برگردد)
    private val serverEdition = if (BuildConfig.EDITION == "fa") "farsiserv" else "coverlate"

    private fun body(extra: Map<String, String> = emptyMap()): JSONObject {
        val j = JSONObject()
        j.put("hwid", hwid)
        j.put("version", "$APP_VERSION-android")
        j.put("pc", "${Build.MANUFACTURER} ${Build.MODEL}".take(60))
        j.put("edition", serverEdition)
        if (Monetize.channel.isNotEmpty()) j.put("channel", Monetize.channel)
        for ((k, v) in extra) j.put(k, v)
        return j
    }

    private fun apply(data: JSONObject) {
        val tok = data.optString("token", "")
        val p = if (tok.isNotEmpty()) verifyToken(tok) else null
        if (p == null || p.optString("hwid") != hwid) throw IllegalStateException("جواب سرور لایسنس معتبر نیست.")
        val now = System.currentTimeMillis() / 1000
        sp.edit().putString("token", tok).putLong("max_seen", maxOf(now, sp.getLong("max_seen", 0))).apply()
        data.optJSONObject("info")?.let { info = it; gotInfo = true }
    }

    /** بررسی آنلاین (در پس‌زمینه صدا زده شود) */
    fun checkOnline(): LicenseState {
        val st = try {
            val r = Net.postJson("$SERVER_URL/api/check", body())
            apply(JSONObject(r.body))
            offlineState().also { it.online = true }
        } catch (e: Exception) {
            offlineState().also { if (it.message.isEmpty()) it.message = "اتصال به سرور لایسنس برقرار نشد." }
        }
        state = st
        return st
    }

    fun activate(key: String): Pair<Boolean, String> {
        return try {
            val r = Net.postJson("$SERVER_URL/api/activate", body(mapOf("key" to key.trim())))
            val data = JSONObject(r.body)
            if (data.optString("token").isNotEmpty()) apply(data)
            Pair(data.optBoolean("ok"), data.optString("message"))
        } catch (e: Exception) {
            Pair(false, "اتصال به سرور لایسنس برقرار نشد: ${e.message}")
        }
    }

    fun fetchInfo(): Boolean = try {
        val r = Net.request("$SERVER_URL/api/info?edition=$serverEdition")
        val j = JSONObject(r.body)
        if (j.has("plans")) { info = j; gotInfo = true; true } else false
    } catch (e: Exception) { false }

    /** واحد پولِ این نسخه (از سرور) */
    val currency: String get() = info.optString("currency", "toman")

    /** درگاه‌های پرداختِ روشنِ این نسخه (بدون کلید محرمانه) */
    fun providers(): org.json.JSONArray = info.optJSONArray("providers") ?: org.json.JSONArray()

    /** شروع پرداخت: لینکِ درگاه را برمی‌گرداند ({ok, pay_url, payment_id}) */
    fun payStart(planId: String, providerId: String): JSONObject = try {
        val b = JSONObject().put("hwid", hwid).put("edition", serverEdition).put("plan_id", planId).put("provider", providerId)
        JSONObject(Net.postJson("$SERVER_URL/api/pay/start", b).body)
    } catch (e: Exception) { JSONObject().put("ok", false).put("message", "اتصال به سرور برقرار نشد.") }

    /** وضعیت پرداخت ({status: pending|paid|failed, key}) */
    fun payStatus(pid: String): JSONObject = try {
        JSONObject(Net.request("$SERVER_URL/api/pay/status/$pid").body)
    } catch (e: Exception) { JSONObject().put("status", "pending") }

    /** نسخه جدید اندروید، اگر منتشر شده باشد */
    fun updateAvailable(): JSONObject? {
        val latest = info.optJSONObject("update_android")?.optJSONObject("latest") ?: return null
        return if (vcmp(latest.optString("version"), APP_VERSION) > 0) latest else null
    }

    companion object {
        @Volatile private var inst: LicenseManager? = null
        fun get(c: Context): LicenseManager = inst ?: synchronized(this) { inst ?: LicenseManager(c).also { inst = it } }

        private val N = BigInteger(
            "8cb1134b369fad0837a63cb6dfc847ca80ba4eb230fa712abeb3d68bb7e277bc3cd7a4b5ea83ec7932f15b2cdaa00b87613581340f35be2a491995e5e893feadabd7f435aced7b120c6747d1638797288c5bc9f212d06ae68076109ef17822a55cd32cf277d67aad8439cf450aa01d9ca7ac6bef21f15539e4a52cc563b1ecc9af4b205130aef8ef7f0315a51c6e2ad9fe49efca67def63c77fc45f07a726b29ea8721e56da5970b0dab870309368e6cfc459c522c7044eded723b2134f7244034ac8a32b3c30c5220352c236a2b0aad035acca0a5a73af34b483d497fd781e2450e22b37ae4aa9ddb9ae5126dd04d780c8cbfeb14cbf5d7ab9dd9cee6a96af7",
            16
        )
        private val E = BigInteger.valueOf(65537)

        fun verifyToken(token: String): JSONObject? = try {
            val parts = token.split(".")
            val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            val raw = Base64.decode(parts[0], flags)
            val sig = Base64.decode(parts[1], flags)
            val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(N, E))
            val v = Signature.getInstance("SHA256withRSA")
            v.initVerify(key)
            v.update(raw)
            if (v.verify(sig)) JSONObject(String(raw, Charsets.UTF_8)) else null
        } catch (e: Exception) { null }

        fun vcmp(a: String, b: String): Int {
            val x = a.trim().trimStart('v', 'V').split(".").map { it.toIntOrNull() ?: 0 }
            val y = b.trim().trimStart('v', 'V').split(".").map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = (x.getOrNull(i) ?: 0) - (y.getOrNull(i) ?: 0)
                if (d != 0) return d
            }
            return 0
        }

        const val DEFAULT_INFO = """{"trial_days":7,"max_devices":2,
            "support":{"text":"برای خرید لایسنس به پشتیبانی پیام بدهید و «کد دستگاه» خود را بفرستید.","telegram":"","label":"پیام به پشتیبانی"},
            "plans":[{"id":"1m","title":"یک ماهه","days":30,"price":50000},{"id":"3m","title":"سه ماهه","days":90,"price":130000},
            {"id":"6m","title":"شش ماهه","days":180,"price":230000},{"id":"12m","title":"یک ساله","days":365,"price":400000}]}"""
    }
}
