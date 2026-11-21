package ir.farsisaz

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "AdOverlay"
private const val SCRIM = 0xFF070A14.toInt()        // پس‌زمینهٔ تیرهٔ تمام‌صفحه (اینترستیشیال)

/**
 * تبلیغِ تمام‌صفحه (اینترستیشیال) روی اندروید — مثلِ اپ‌هایی که محورشان تبلیغ است:
 *   • کلِ پنجرهٔ برنامه را تا پایانِ شمارنده می‌پوشاند (متن / عکس / ویدیو، همه تمام‌صفحه)
 *   • دانلودِ مقاومِ رسانه: ریدایرکتِ http↔https را دستی دنبال می‌کند و خطاها را لاگ می‌کند
 *     (تا عکس/ویدیو حتی پشتِ Cloudflare هم بیاید)؛ ویدیو «دانلود، سپس پخشِ لوپِ بی‌صدا»
 *   • دکمهٔ بستن با «شمارنده» (close_after از سرور)
 *   • شمارشِ بازدید فقط **یک‌بار در هر شبانه‌روز برای هر دستگاه** (ضدِ تورمِ آمار)
 *   • بعد از بستن، یک چیپِ کوچکِ ماندگار در گوشهٔ پایین می‌ماند
 */
object AdOverlay {
    @Volatile private var shown = false

    fun abs(u: String) = if (u.startsWith("http")) u else SERVER_URL + u

    /** یک‌بار در هر اجرای برنامه: اگر تبلیغِ واجدِ شرایطی باشد، تمام‌صفحه نشان بده. */
    fun maybeShow(act: Activity, info: JSONObject, status: String) {
        if (shown) return
        val ad = try { Ads.pick(Ads.eligible(info, status)) } catch (_: Throwable) { null } ?: return
        shown = true
        try { AdPopup(act, ad).open() } catch (e: Throwable) { android.util.Log.w(TAG, "open failed", e); shown = false }
    }

    /** بازدید فقط یک‌بار در هر شبانه‌روز برای هر دستگاه به سرور گزارش می‌شود. */
    internal fun reportView(id: String) {
        if (id.isEmpty()) return
        Thread {
            try { Net.request(abs("/api/ad/$id/view?d=" + Prefs.deviceId), "POST", ByteArray(0), timeout = 10000) }
            catch (_: Exception) {}
        }.start()
    }

    internal fun openGo(act: Activity, ad: JSONObject) {
        val id = ad.optString("id")
        val link = when {
            id.isNotEmpty() -> abs("/ad/$id/go?d=" + Prefs.deviceId)
            else -> ad.optString("link").ifEmpty { ad.optString("url") }.let { if (it.isEmpty()) "" else abs(it) }
        }
        if (link.isEmpty()) return
        try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) } catch (_: Exception) {}
    }

    /**
     * اتصال با دنبال‌کردنِ دستیِ ریدایرکت — ازجمله http↔https که HttpURLConnection به‌طورِ خودکار
     * (به‌دلایلِ امنیتی) دنبال نمی‌کند. این دلیلِ رایجِ «عکس/ویدیو نمی‌آید ولی متن می‌آید» است.
     */
    private fun openConn(rawUrl: String): HttpURLConnection? {
        var url = abs(rawUrl)
        var hops = 0
        while (hops < 5) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 12000; c.readTimeout = 25000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location"); c.disconnect()
                if (loc.isNullOrEmpty()) { android.util.Log.w(TAG, "redirect without Location: $url"); return null }
                url = if (loc.startsWith("http")) loc else URL(URL(url), loc).toString()
                hops++; continue
            }
            if (code in 200..299) return c
            android.util.Log.w(TAG, "http $code for $url"); c.disconnect(); return null
        }
        android.util.Log.w(TAG, "too many redirects: $rawUrl"); return null
    }

    internal fun dlBitmap(url: String): Bitmap? {
        return try {
            val c = openConn(url) ?: return null
            try {
                val out = ByteArrayOutputStream()
                c.inputStream.use { it.copyTo(out) }
                val b = out.toByteArray()
                if (b.size > 10 * 1024 * 1024) { android.util.Log.w(TAG, "img too big ${b.size}B"); null }
                else BitmapFactory.decodeByteArray(b, 0, b.size).also {
                    if (it == null) android.util.Log.w(TAG, "decode fail ${b.size}B $url")
                }
            } finally { c.disconnect() }
        } catch (e: Exception) { android.util.Log.w(TAG, "img err $url", e); null }
    }

    internal fun dlFile(url: String, f: File): Boolean {
        return try {
            val c = openConn(url) ?: return false
            try {
                FileOutputStream(f).use { o -> c.inputStream.use { it.copyTo(o) } }
                (f.length() > 0).also { if (!it) android.util.Log.w(TAG, "vid empty $url") }
            } finally { c.disconnect() }
        } catch (e: Exception) { android.util.Log.w(TAG, "vid err $url", e); false }
    }
}

/** ویوِ تبلیغِ تمام‌صفحه که روی کلِ پنجرهٔ برنامه کشیده می‌شود. */
class AdPopup(private val act: Activity, private val ad: JSONObject) : FrameLayout(act) {
    private val c = act
    private var remain = ad.optInt("close_after", 0).coerceAtLeast(0)
    private val ui = Handler(Looper.getMainLooper())
    private var closeBtn: TextView? = null
    private var player: MediaPlayer? = null
    private var tick: Runnable? = null
    private val id = ad.optString("id")

    private val img = ad.optString("image")
    private val vid = ad.optString("video")
    private val mediaType: String = ad.optString("media_type").ifEmpty {
        when {
            vid.isNotEmpty() -> "video"
            img.isNotEmpty() -> "image"
            else -> "text"
        }
    }
    private val hasVideo = mediaType == "video" && vid.isNotEmpty()
    private val hasMedia = hasVideo || img.isNotEmpty()

    fun open() {
        isClickable = true; isFocusable = true
        setBackgroundColor(SCRIM)
        setOnClickListener { /* کلیکِ پس‌زمینه بلعیده می‌شود؛ تا پایانِ شمارنده بسته نمی‌شود */ }

        // ۱) لایهٔ رسانهٔ تمام‌صفحه
        when {
            hasVideo -> addFullVideo()
            img.isNotEmpty() -> addFullImage()
        }
        // ۲) پنلِ اطلاعات (عنوان/متن/دکمه)
        addInfoPanel()
        // ۳) دکمهٔ بستن + برچسب
        addFloatingClose()
        addFloatingTag()

        act.addContentView(
            this,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // شمارشِ بازدید فقط یک‌بار در روز برای هر دستگاه (ولی نمایش هر بار انجام می‌شود)
        if (id.isNotEmpty() && !Prefs.adViewedToday(id)) {
            Prefs.markAdViewed(id)
            AdOverlay.reportView(id)
        }
        startCountdown()
    }

    private fun goOnClick() {
        if (id.isNotEmpty() || ad.optString("url").isNotEmpty() || ad.optString("link").isNotEmpty())
            setOnClickListener { AdOverlay.openGo(act, ad) }
    }

    // ---------------------------------------------------------- عکسِ تمام‌صفحه
    private fun addFullImage() {
        val iv = ImageView(c)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(iv)
        loadBitmapInto(iv, img)
        goOnClick()
    }

    // ---------------------------------------------------------- ویدیوی تمام‌صفحه
    private fun addFullVideo() {
        val box = FrameLayout(c)
        box.layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        box.setBackgroundColor(SCRIM)
        addView(box)
        // تا دانلود/آماده‌شدن: اگر عکس داریم همان را نشان بده (پوستر)؛ وگرنه پس‌زمینهٔ تیره
        if (img.isNotEmpty()) {
            val pv = ImageView(c)
            pv.scaleType = ImageView.ScaleType.FIT_CENTER
            pv.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            box.addView(pv); loadBitmapInto(pv, img)
        }
        val rel = vid
        Thread {
            val f = File(c.cacheDir, "advid_" + Integer.toHexString(AdOverlay.abs(rel).hashCode()) + ".mp4")
            val ok = (f.exists() && f.length() > 0) || AdOverlay.dlFile(rel, f)
            act.runOnUiThread {
                if (ok && f.exists() && f.length() > 0) {
                    val vv = VideoView(c)
                    vv.setZOrderMediaOverlay(true)         // تا ویدیو روی پس‌زمینه دیده شود (نه سیاه)
                    vv.layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER
                    )
                    vv.setOnPreparedListener { mp ->
                        mp.isLooping = true
                        try { mp.setVolume(0f, 0f) } catch (_: Exception) {}
                        player = mp; vv.start()
                    }
                    vv.setOnErrorListener { _, _, _ -> android.util.Log.w(TAG, "VideoView error"); true } // روی پوستر می‌مانیم
                    box.addView(vv)
                    vv.setVideoURI(Uri.fromFile(f))
                }
                // اگر ویدیو نیامد: پوستر/پس‌زمینهٔ تیره می‌ماند
            }
        }.start()
        goOnClick()
    }

    // ---------------------------------------------------------- پنلِ عنوان/متن/دکمه
    private fun addInfoPanel() {
        val title = ad.optString("title")
        val text = ad.optString("text")
        val url = ad.optString("url").ifEmpty { ad.optString("link") }
        val hasCta = url.isNotEmpty() || id.isNotEmpty()
        if (hasMedia && title.isEmpty() && text.isEmpty() && !hasCta) return   // فقط رسانه

        val panel = LinearLayout(c)
        panel.orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(c, 22f)

        if (hasMedia) {
            // پنلِ پایین با محوشدگیِ تیره روی رسانه (مثلِ اینترستیشیال‌های واقعی)
            panel.setPadding(pad, Ui.dp(c, 48f), pad, Ui.dp(c, 28f))
            panel.background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xF2070A14.toInt(), 0xBF070A14.toInt(), 0x00070A14)
            )
            panel.layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
            )
        } else {
            // تبلیغِ متنی: بلوکِ مرکزی روی پس‌زمینهٔ گرادیانِ تیره
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF0B0F1E.toInt(), 0xFF161C33.toInt())
            )
            panel.setPadding(pad, pad, pad, pad)
            panel.gravity = Gravity.CENTER_HORIZONTAL
            val w = minOf(c.resources.displayMetrics.widthPixels - Ui.dp(c, 56f), Ui.dp(c, 520f))
            panel.layoutParams = LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        addView(panel)

        if (title.isNotEmpty()) {
            val t = TextView(c)
            t.text = title; t.setTextColor(Color.WHITE)
            t.textSize = if (hasMedia) 21f else 25f
            t.typeface = Ui.font(c, true)
            applyDir(t, title); if (!hasMedia) t.gravity = Gravity.CENTER
            panel.addView(t)
        }
        if (text.isNotEmpty()) {
            val tv = TextView(c)
            tv.text = text; tv.setTextColor(0xFFCBD3F0.toInt())
            tv.textSize = if (hasMedia) 14f else 15f
            tv.typeface = Ui.font(c, false)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = Ui.dp(c, 8f); tv.layoutParams = lp
            applyDir(tv, text); if (!hasMedia) tv.gravity = Gravity.CENTER
            panel.addView(tv)
        }
        if (hasCta) {
            val btn = Ui.button(c, ad.optString("button").ifEmpty { L.sx("مشاهده") }, true) { AdOverlay.openGo(act, ad) }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = Ui.dp(c, 16f); btn.layoutParams = lp
            panel.addView(btn)
        }
    }

    private fun loadBitmapInto(iv: ImageView, url: String) {
        if (url.isEmpty()) return
        Thread {
            val b = AdOverlay.dlBitmap(url)
            act.runOnUiThread { if (b != null) iv.setImageBitmap(b) }
        }.start()
    }

    // ---------------------------------------------------------- دکمهٔ بستن + شمارنده + برچسب
    private fun makeCloseButton(): TextView {
        val b = TextView(c)
        b.gravity = Gravity.CENTER
        b.textSize = 15f
        b.typeface = Ui.font(c, true)
        val sz = Ui.dp(c, 40f)
        b.layoutParams = LayoutParams(sz, sz)
        b.background = Ui.round(0xCC000000.toInt(), (sz / 2).toFloat(), 1, Ui.OBORDER)
        b.setOnClickListener { if (remain <= 0) close() }
        return b
    }

    private fun addFloatingClose() {
        val b = makeCloseButton()
        val sz = Ui.dp(c, 40f)
        val lp = LayoutParams(sz, sz, Gravity.TOP or Gravity.END)
        val m = Ui.dp(c, 14f); lp.setMargins(m, m, m, m)
        b.layoutParams = lp
        closeBtn = b
        addView(b)
    }

    private fun addFloatingTag() {
        val tag = TextView(c)
        tag.text = L.sx("تبلیغ"); tag.textSize = 10.5f; tag.setTextColor(Color.WHITE)
        tag.typeface = Ui.font(c, false)
        tag.background = Ui.round(0x99000000.toInt(), Ui.dp(c, 8f).toFloat())
        val p = Ui.dp(c, 9f); tag.setPadding(p, Ui.dp(c, 3f), p, Ui.dp(c, 3f))
        val lp = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
        val m = Ui.dp(c, 14f); lp.setMargins(m, m, m, m)
        tag.layoutParams = lp
        addView(tag)
    }

    private fun refreshClose() {
        val b = closeBtn ?: return
        if (remain > 0) {
            b.text = remain.toString(); b.setTextColor(Ui.OMUTED); b.isEnabled = false
        } else {
            b.text = "✕"; b.setTextColor(Color.WHITE); b.isEnabled = true
        }
    }

    private fun startCountdown() {
        refreshClose()
        if (remain <= 0) return
        tick = object : Runnable {
            override fun run() {
                remain -= 1
                refreshClose()
                if (remain > 0) ui.postDelayed(this, 1000)
            }
        }
        ui.postDelayed(tick!!, 1000)
    }

    private fun close() {
        tick?.let { ui.removeCallbacks(it) }
        try { player?.let { it.stop(); it.release() } } catch (_: Exception) {}
        player = null
        (parent as? ViewGroup)?.removeView(this)
        AdDockChip.show(act, ad)
    }

    private fun applyDir(t: TextView, s: String) {
        val rtl = s.any { it.code in 0x0590..0x08FF || it.code in 0xFB1D..0xFDFF || it.code in 0xFE70..0xFEFF }
        t.textDirection = if (rtl) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
        t.gravity = if (rtl) Gravity.END else Gravity.START
    }
}

/** چیپِ کوچکِ ماندگار در گوشهٔ پایین بعد از بستنِ تبلیغ (معادلِ دکِ نسخهٔ ویندوز). */
object AdDockChip {
    private var view: View? = null

    fun show(act: Activity, ad: JSONObject) {
        remove()
        val c = act
        val chip = LinearLayout(c)
        chip.orientation = LinearLayout.HORIZONTAL
        chip.gravity = Gravity.CENTER_VERTICAL
        chip.background = Ui.round(0xFF171B2E.toInt(), Ui.dp(c, 14f).toFloat(), 1, Ui.OBORDER)
        val p = Ui.dp(c, 8f); chip.setPadding(p, p, p, p)
        chip.elevation = Ui.dp(c, 6f).toFloat()

        val img = ad.optString("image")
        if (img.isNotEmpty()) {
            val iv = ImageView(c)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.clipToOutline = true
            iv.background = Ui.round(0xFF232846.toInt(), Ui.dp(c, 8f).toFloat())
            iv.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            val s = Ui.dp(c, 34f)
            iv.layoutParams = LinearLayout.LayoutParams(s, s)
            chip.addView(iv)
            Thread { val b = AdOverlay.dlBitmap(img); act.runOnUiThread { if (b != null) iv.setImageBitmap(b) } }.start()
        } else {
            val g = TextView(c); g.text = "📣"; g.textSize = 17f; g.gravity = Gravity.CENTER
            chip.addView(g, LinearLayout.LayoutParams(Ui.dp(c, 34f), Ui.dp(c, 34f)))
        }

        val label = TextView(c)
        label.text = ad.optString("title").ifEmpty { L.sx("تبلیغ") }
        label.setTextColor(Ui.OTEXT); label.textSize = 12.5f; label.typeface = Ui.font(c, true)
        label.maxLines = 1
        label.setPadding(Ui.dp(c, 8f), 0, Ui.dp(c, 8f), 0)
        chip.addView(label)

        val x = TextView(c)
        x.text = "✕"; x.textSize = 13f; x.setTextColor(Ui.OMUTED); x.gravity = Gravity.CENTER
        x.setPadding(Ui.dp(c, 6f), 0, Ui.dp(c, 6f), 0)
        x.setOnClickListener { remove() }
        chip.addView(x)

        chip.setOnClickListener { AdOverlay.openGo(act, ad) }

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START
        )
        val m = Ui.dp(c, 12f); lp.setMargins(m, m, m, Ui.dp(c, 78f))
        act.addContentView(chip, lp)
        view = chip
    }

    fun remove() { (view?.parent as? ViewGroup)?.removeView(view); view = null }
}
