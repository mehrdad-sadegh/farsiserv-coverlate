package ir.farsisaz

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * بنر تبلیغاتی صفحه اصلی (تبلیغ‌ها از پنل مدیریت سرور می‌آیند).
 * فقط تبلیغ‌هایی که برای اندروید و وضعیت لایسنس این کاربر تنظیم شده‌اند نشان داده می‌شوند.
 */
object Ads {
    private val images = HashMap<String, Bitmap?>()
    private val viewed = HashSet<String>()
    private var lastId = ""
    private var tr: Translator? = null
    private fun translator(c: android.content.Context): Translator =
        tr ?: Translator(c.applicationContext).also { tr = it }

    fun abs(u: String) = if (u.startsWith("http")) u else SERVER_URL + u

    fun eligible(info: JSONObject, status: String): List<JSONObject> {
        val arr = info.optJSONArray("ads") ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val pl = a.optString("platform", "all")
            if (pl != "all" && pl != "android") continue
            // فیلتر زبان: تبلیغ می‌تواند فقط برای کاربران یک زبان نمایش داده شود (مثلاً فقط فارسی‌زبان‌ها)
            val lang = a.optString("lang", "all")
            if (lang != "all" && lang != Prefs.uiLang) continue
            when (a.optString("audience", "all")) {
                "trial" -> if (status != "trial") continue
                "licensed" -> if (status != "licensed") continue
                "free" -> if (status == "licensed") continue
            }
            out.add(a)
        }
        return out
    }

    /** یکی از تبلیغ‌ها با توجه به «سهم نمایش»؛ تا جای ممکن غیر از تبلیغ قبلی */
    fun pick(list: List<JSONObject>): JSONObject? {
        if (list.isEmpty()) return null
        val pool = list.filter { it.optString("id") != lastId }.ifEmpty { list }
        val total = pool.sumOf { it.optInt("weight", 1).coerceIn(1, 10) }
        var r = (Math.random() * total).toInt()
        for (a in pool) {
            r -= a.optInt("weight", 1).coerceIn(1, 10)
            if (r < 0) { lastId = a.optString("id"); return a }
        }
        return pool.last().also { lastId = it.optString("id") }
    }

    private fun download(url: String): Bitmap? = try {
        val c = URL(abs(url)).openConnection() as HttpURLConnection
        c.connectTimeout = 12000; c.readTimeout = 15000
        try {
            if (c.responseCode != 200) null else {
                val out = ByteArrayOutputStream()
                c.inputStream.use { it.copyTo(out) }
                val b = out.toByteArray()
                if (b.size > 4 * 1024 * 1024) null else BitmapFactory.decodeByteArray(b, 0, b.size)
            }
        } finally { c.disconnect() }
    } catch (_: Exception) { null }

    private fun countView(id: String) {
        if (id.isEmpty()) return
        synchronized(viewed) { if (!viewed.add(id)) return }     // یک‌بار در هر اجرای برنامه
        if (Prefs.adViewedToday(id)) return                       // قبلاً امروز برای این دستگاه شمرده شده
        Prefs.markAdViewed(id)
        Thread { try { Net.request(abs("/api/ad/$id/view?d=" + Prefs.deviceId), "POST", ByteArray(0), timeout = 10000) } catch (_: Exception) {} }.start()
    }

    /** کارت بنر؛ اگر تبلیغی نباشد null */
    fun banner(act: Activity, info: JSONObject, status: String): View? {
        val cfg = Monetize.ads(info)
        // کاربر دارای اشتراک: هیچ تبلیغی (مستقیم یا شبکه) نشان داده نمی‌شود
        if (status == "licensed" && cfg.hideForLicensed) return null
        // شبکهٔ تبلیغاتی روشن است؟ بنر شبکه را نشان بده (اگر نشد، به بنر مستقیم برگرد)
        if (cfg.on && cfg.provider != "none" && cfg.provider != "direct") {
            AdNet.banner(act, cfg)?.let { return it }
        }
        // بنر مستقیم از پنل (رفتار فعلی)
        val a = pick(eligible(info, status)) ?: return null
        val c = act
        val id = a.optString("id")
        val card = Ui.card(c)
        val rad = Ui.dp(c, 18f).toFloat()
        val color = a.optString("color")
        val border = try { if (color.isNotEmpty()) Color.parseColor(color) else Ui.BORDER } catch (_: Exception) { Ui.BORDER }
        card.background = Ui.ripple(Ui.round(0xFF171B2E.toInt(), rad, Ui.dp(c, 1f).coerceAtLeast(1), border), rad)
        val p = Ui.dp(c, 12f)
        card.setPadding(p, p, p, p)

        val img = ImageView(c)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.clipToOutline = true
        img.background = Ui.round(0xFF232846.toInt(), Ui.dp(c, 12f).toFloat())
        img.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        val imgUrl = a.optString("image")
        if (imgUrl.isNotEmpty()) {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
            lp.bottomMargin = Ui.dp(c, 10f)
            img.layoutParams = lp
            card.addView(img)
            fun setImg(b: Bitmap?) {
                if (b == null) { img.visibility = View.GONE; return }
                img.setImageBitmap(b)
                img.post {
                    val w = img.width.takeIf { it > 0 } ?: card.width
                    img.layoutParams = (img.layoutParams as LinearLayout.LayoutParams).also { it.height = (w * b.height / b.width.toFloat()).toInt().coerceAtMost(Ui.dp(c, 220f)) }
                }
            }
            if (images.containsKey(imgUrl)) setImg(images[imgUrl])
            else Thread {
                val b = download(imgUrl)
                synchronized(images) { images[imgUrl] = b }
                act.runOnUiThread { setImg(b) }
            }.start()
        }

        val row = Ui.row(c)
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        val head = Ui.row(c)
        // کارت تبلیغ همیشه پس‌زمینهٔ تیره دارد؛ پس متن‌ها باید همیشه روشن باشند (OTEXT/OMUTED)
        val tag = Ui.text(c, "تبلیغ", 10.5f, Ui.OMUTED)
        tag.background = Ui.round(0, Ui.dp(c, 6f).toFloat(), 1, 0xFF3A4370.toInt())
        tag.setPadding(Ui.dp(c, 6f), 0, Ui.dp(c, 6f), 0)
        head.addView(tag)
        val title = a.optString("title")
        if (title.isNotEmpty()) {
            val t = Ui.text(c, "", 15f, Ui.OTEXT, true)
            t.setPadding(Ui.dp(c, 8f), 0, 0, 0)
            head.addView(t)
            SrvText.load(translator(c), title) { s -> t.post { t.text = s } }
        }
        col.addView(head)
        val text = a.optString("text")
        if (text.isNotEmpty()) {
            val tv = Ui.text(c, "", 13f, Ui.OMUTED)
            col.addView(Ui.full(tv, 4f))
            SrvText.load(translator(c), text) { s -> tv.post { tv.text = s } }
        }
        row.addView(Ui.weight(col, 1f, 0f))
        val url = a.optString("url")
        val open = {
            try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(abs(url)))) } catch (_: Exception) {}
        }
        if (url.isNotEmpty()) {
            val b = Ui.button(c, a.optString("button").ifEmpty { "مشاهده" }) { open() }
            b.background = Ui.ripple(Ui.gradient(Ui.dp(c, 10f).toFloat()), Ui.dp(c, 10f).toFloat())
            b.minHeight = 0; b.minimumHeight = 0
            b.setPadding(Ui.dp(c, 14f), Ui.dp(c, 8f), Ui.dp(c, 14f), Ui.dp(c, 8f))
            row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.marginStart = Ui.dp(c, 10f) })
            card.setOnClickListener { open() }
        }
        row.gravity = Gravity.CENTER_VERTICAL
        card.addView(row)
        countView(id)
        return card
    }
}
