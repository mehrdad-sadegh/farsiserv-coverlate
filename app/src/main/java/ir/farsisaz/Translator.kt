package ir.farsisaz

import android.content.Context
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ترجمه به فارسی: Microsoft (سرویس مترجم Bing) + Google (سرویس ترجمه صفحه Chrome).
 * اگر Microsoft دیر جواب بدهد، Google هم هم‌زمان پرسیده می‌شود و هر کدام زودتر رسید استفاده می‌شود.
 */
class Translator(c: Context) {
    private val db = Db.get(c)
    private val pool = Executors.newFixedThreadPool(4)      // موتورها
    private val workers = Executors.newFixedThreadPool(6)   // درخواست‌های موازی Microsoft

    @Volatile private var bing: Array<String>? = null   // IG, IID, key, token
    @Volatile private var bingExp = 0L
    private var bingN = 0

    // ---------------------------------------------------------------- واژه‌نامه و محافظت از کدها
    private val codeRe = Regex("\\{[^}]{0,30}\\}|%[0-9]*[sdif]|<[^>]{1,30}>|\\[[A-Z_0-9]{1,20}\\]")

    private fun glossary(): List<Pair<Regex, String>> =
        Prefs.glossary.lines().mapNotNull { ln ->
            val i = ln.indexOf('=')
            if (i <= 0) null else {
                val src = ln.substring(0, i).trim()
                val dst = ln.substring(i + 1).trim()
                if (src.isEmpty() || dst.isEmpty()) null
                else Pair(Regex("(?i)(?<![A-Za-z])" + Regex.escape(src) + "(?![A-Za-z])"), dst)
            }
        }

    private fun protect(text: String, gl: List<Pair<Regex, String>>): Pair<String, List<String>> {
        val tokens = ArrayList<String>()
        var t = codeRe.replace(text) { m -> tokens.add(m.value); "⟦${tokens.size - 1}⟧" }
        for ((rx, dst) in gl) t = rx.replace(t) { tokens.add(dst); "⟦${tokens.size - 1}⟧" }
        return Pair(t, tokens)
    }

    private fun unprotect(text: String, tokens: List<String>): String =
        Regex("⟦\\s*(\\d+)\\s*⟧").replace(text) { m -> tokens.getOrNull(m.groupValues[1].toInt()) ?: "" }

    /** اصلاح نگارش خروجی: فقط وقتی مقصد فارسی است قواعد فارسی اعمال می‌شود؛ بقیهٔ زبان‌ها دست‌نخورده. */
    private fun finish(text: String, target: String): String =
        if (target == "fa") Persian.polish(text) else text.trim()

    // ---------------------------------------------------------------- API اصلی
    /** ترجمه چند متن (ترجمهٔ صفحه)؛ خروجی هم‌اندازه ورودی. مقصد از تنظیمات خوانده می‌شود. */
    fun translateMany(texts: List<String>, target: String = Prefs.targetLang): List<String> {
        val provider = Prefs.provider
        val ck = "$provider#3#$target"   // نسخه۳: خروجیِ بافت‌دار؛ ترجمه‌های کلمه‌به‌کلمهٔ قدیمی (مثل I feel blue) دور ریخته می‌شوند
        val out = texts.toMutableList()
        val todo = LinkedHashMap<String, MutableList<Int>>()
        texts.forEachIndexed { i, t ->
            if (!Persian.needsTranslation(t)) return@forEachIndexed
            val c = db.cacheGet(ck, t)
            if (c != null) out[i] = c else todo.getOrPut(t) { ArrayList() }.add(i)
        }
        if (todo.isEmpty()) return out
        val srcs = todo.keys.toList()
        val gl = glossary()
        val prot = srcs.map { protect(it, gl) }
        val raw = hedged(prot.map { it.first }, provider, "auto", target)
        srcs.forEachIndexed { i, s ->
            val dst = finish(unprotect(raw[i], prot[i].second), target)
            db.cachePut(ck, s, dst)
            for (idx in todo[s]!!) out[idx] = dst
        }
        return out
    }

    /** ترجمهٔ رشته‌های رابطِ کاربری از انگلیسی به زبانِ مقصد (برای چندزبانه‌کردنِ UI).
     *  در دسته‌های کوچک انجام می‌شود تا در صورتِ خطا فقط همان دسته انگلیسی بماند، نه همه‌چیز. */
    fun translateUi(texts: List<String>, target: String): List<String> {
        val out = texts.toMutableList()
        var i = 0
        while (i < texts.size) {
            val end = minOf(i + 40, texts.size)
            val chunk = texts.subList(i, end).toList()
            val raw = try { hedged(chunk, Prefs.provider, "en", target) } catch (_: Throwable) { chunk }
            for (j in chunk.indices) out[i + j] = raw.getOrNull(j)?.trim()?.ifBlank { chunk[j] } ?: chunk[j]
            i = end
        }
        return out
    }

    /** آزادسازیِ نخ‌ها (برای نمونه‌های یک‌بارمصرفِ ساختِ بستهٔ زبان). */
    fun close() { try { pool.shutdownNow() } catch (_: Throwable) {}; try { workers.shutdownNow() } catch (_: Throwable) {} }

    /** ترجمهٔ یک متنِ تایپ‌شده (کادر دوطرفه): مبدأ و مقصد دلخواه، بدون واژه‌نامهٔ بازی. */
    fun translateText(text: String, source: String, target: String): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        val raw = hedged(listOf(t), Prefs.provider, source, target)
        return finish(raw[0], target)
    }

    private fun hedged(texts: List<String>, provider: String, source: String, target: String): List<String> {
        val first: (List<String>) -> List<String> = if (provider == "google") { x -> google(x, source, target) } else { x -> microsoft(x, source, target) }
        val second: (List<String>) -> List<String> = if (provider == "google") { x -> microsoft(x, source, target) } else { x -> google(x, source, target) }
        val cs = ExecutorCompletionService<List<String>>(pool)
        cs.submit(Callable { first(texts) })
        var pending = 1
        var started2 = false
        var lastErr: Throwable? = null
        val f = cs.poll(650, TimeUnit.MILLISECONDS)
        if (f != null) {
            pending--
            try { return f.get() } catch (e: Exception) { lastErr = e.cause ?: e }
        }
        cs.submit(Callable { second(texts) }); pending++; started2 = true
        while (pending > 0) {
            val r = cs.poll(25, TimeUnit.SECONDS) ?: break
            pending--
            try { return r.get() } catch (e: Exception) { lastErr = e.cause ?: e }
        }
        if (!started2) throw RuntimeException(short(lastErr))
        throw RuntimeException(short(lastErr))
    }

    /** کد مبدأ مؤثر (قبل از نگاشت موتور): کد صریح کاربر؛ یا اگر «auto» و متن فقط لاتین است «en»، وگرنه «auto».
     *  (تشخیص خودکار روی جمله‌های کوتاه گاهی اشتباه می‌کرد.) */
    private fun srcLang(texts: List<String>, source: String): String {
        if (source != "auto") return source
        return if (texts.all { t -> t.all { it.code < 128 || it in "’‘“”…—–⟦⟧" } }) "en" else "auto"
    }

    // ---------------------------------------------------------------- Microsoft (Bing)
    @Synchronized
    private fun bingAuth(force: Boolean = false): Array<String> {
        val cur = bing
        if (cur != null && !force && System.currentTimeMillis() < bingExp) return cur
        val page = Net.request("https://www.bing.com/translator").body
        val ig = Regex("IG:\"([^\"]+)\"").find(page)?.groupValues?.get(1)
        val iid = Regex("data-iid=\"([^\"]+)\"").find(page)?.groupValues?.get(1)
        val ap = Regex("params_AbusePreventionHelper\\s*=\\s*\\[([^\\]]+)\\]").find(page)?.groupValues?.get(1)
        if (ig == null || iid == null || ap == null) throw RuntimeException("صفحه مترجم Bing تغییر کرده")
        val parts = ap.split(",").map { it.trim().trim('"') }
        val auth = arrayOf(ig, iid, parts[0], parts[1])
        bing = auth
        bingExp = System.currentTimeMillis() + 50 * 60 * 1000
        return auth
    }

    private fun bingOne(text: String, source: String, target: String): String {
        var err: Exception? = null
        for (attempt in 0..1) {
            try {
                val a = bingAuth(force = attempt > 0)
                val n = synchronized(this) { ++bingN }
                val form = "fromLang=" + Langs.bing(srcLang(listOf(text), source)) + "&to=" + Langs.bing(target) + "&text=" + URLEncoder.encode(text, "UTF-8") +
                    "&token=" + URLEncoder.encode(a[3], "UTF-8") + "&key=" + URLEncoder.encode(a[2], "UTF-8")
                val r = Net.request(
                    "https://www.bing.com/ttranslatev3?isVertical=1&IG=${a[0]}&IID=${a[1]}.$n", "POST",
                    form.toByteArray(), mapOf("Content-Type" to "application/x-www-form-urlencoded")
                )
                if (r.code == 429) throw RuntimeException("429")
                val body = r.body.trim()
                if (!body.startsWith("[")) throw RuntimeException("Bing ${r.code}")
                return JSONArray(body).getJSONObject(0).getJSONArray("translations").getJSONObject(0).getString("text")
            } catch (e: Exception) {
                err = e
                if (e.message?.contains("429") == true) break
            }
        }
        throw err ?: RuntimeException("Microsoft")
    }

    private fun microsoft(texts: List<String>, source: String, target: String): List<String> {
        val futures = texts.map { t -> workers.submit(Callable { bingOne(t, source, target) }) }
        return futures.map { it.get(25, TimeUnit.SECONDS) }
    }

    // ---------------------------------------------------------------- Google
    // از موتور کاملِ Google Translate (translate_a/single) استفاده می‌شود؛ این مدل جمله را با
    // بافت ترجمه می‌کند و اصطلاحات را درست می‌فهمد (برخلافِ مدلِ سبکِ translateHtml که کلمه‌به‌کلمه و
    // تحت‌اللفظی بود و مثلاً «I feel blue» را «احساس آبی» ترجمه می‌کرد).
    private fun google(texts: List<String>, source: String, target: String): List<String> {
        val futures = texts.map { t -> workers.submit(Callable { googleOne(t, source, target) }) }
        return futures.map { it.get(25, TimeUnit.SECONDS) }
    }

    private fun googleOne(text: String, source: String, target: String): String {
        val sl = Langs.google(srcLang(listOf(text), source))
        val tl = Langs.google(target)
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&dj=0&sl=$sl&tl=$tl&dt=t&q=" +
            URLEncoder.encode(text, "UTF-8")
        val r = Net.request(url)
        if (r.code == 429) throw RuntimeException("429")
        val body = r.body.trim()
        if (r.code != 200 || !body.startsWith("[")) throw RuntimeException("Google ${r.code}")
        val arr = JSONArray(body).getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val seg = arr.optJSONArray(i) ?: continue
            sb.append(seg.optString(0))
        }
        return sb.toString().trim()
    }

    companion object {
        const val GOOGLE_KEY = "AIzaSyATBXajvzQLTDHEQbcpq0Ihe0vWDHmO520"

        fun short(e: Throwable?): String {
            val m = e?.message ?: return "ترجمه ناموفق بود"
            return when {
                m.contains("429") -> "درخواست زیاد (محدودیت موقت)؛ کمی بعد دوباره امتحان کنید"
                m.contains("Unable to resolve") || m.contains("connect", true) -> "اتصال اینترنت برقرار نیست"
                m.contains("timeout", true) -> "سرور ترجمه دیر جواب داد"
                else -> m.take(90)
            }
        }
    }
}

/**
 * ترجمهٔ خودکارِ متن‌های آزادِ سرور (عنوان/متن تبلیغ، متن و دکمهٔ پشتیبانی) به زبانِ رابطِ کاربر.
 * فارسی (uiLang=fa) بدون تغییر می‌ماند؛ برای زبان‌های دیگر یک‌بار روی دستگاه ترجمه و کش می‌شود.
 */
object SrvText {
    private val cache = HashMap<String, String>()
    private fun key(t: String) = Prefs.uiLang + "\u0000" + t

    /** مقدارِ همین‌لحظه (کش‌شده یا خودِ متن) */
    fun now(text: String): String {
        if (text.isBlank() || Prefs.uiLang == "fa") return text
        return synchronized(cache) { cache[key(text)] } ?: text
    }

    /** cb فوراً با بهترین مقدار صدا زده می‌شود و در صورت نیاز، بعد از ترجمهٔ پس‌زمینه دوباره. */
    fun load(tr: Translator, text: String, cb: (String) -> Unit) {
        cb(now(text))
        if (text.isBlank() || Prefs.uiLang == "fa") return
        val k = key(text)
        if (synchronized(cache) { cache.containsKey(k) }) return
        Thread {
            val d = try { tr.translateText(text, "auto", Prefs.uiLang) } catch (_: Exception) { text }
            synchronized(cache) { cache[k] = d }
            cb(d)
        }.start()
    }
}
