package ir.farsisaz

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/** پارامترهای پایه پنجره‌های شناور */
fun overlayParams(w: Int, h: Int, focusable: Boolean = false, touchable: Boolean = true): WindowManager.LayoutParams {
    var flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    val p = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT)
    p.gravity = Gravity.TOP or Gravity.START
    if (Build.VERSION.SDK_INT >= 28)
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    return p
}

fun realSize(c: Context): Pair<Int, Int> {
    val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    return if (Build.VERSION.SDK_INT >= 30) {
        val b = wm.maximumWindowMetrics.bounds
        Pair(b.width(), b.height())
    } else {
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        Pair(m.widthPixels, m.heightPixels)
    }
}

// ============================================================ حباب شناور
@SuppressLint("ClickableViewAccessibility")
class Bubble(private val c: Context, private val wm: WindowManager, onTap: () -> Unit, onLong: () -> Unit) {
    val size = Ui.dp(c, 54f)
    val view = TextView(c)
    val params = overlayParams(size, size)
    private val h = Handler(Looper.getMainLooper())
    // آیکون کوچک روی حباب (فقط نسخهٔ جهانی؛ نسخهٔ ایران این فایل را ندارد و به حرفِ گلیف برمی‌گردد).
    private val coverIcon: android.graphics.drawable.Drawable? =
        if (BuildConfig.EDITION == "global")
            try {
                val id = c.resources.getIdentifier("ic_cover_bubble", "drawable", c.packageName)
                if (id != 0) c.getDrawable(id) else null
            } catch (_: Throwable) { null }
        else null

    init {
        view.text = BuildConfig.BUBBLE_GLYPH
        view.textSize = 24f
        view.typeface = Ui.font(c, true)
        view.setTextColor(Color.WHITE)
        view.gravity = Gravity.CENTER
        view.background = Ui.gradient(size / 2f)
        if (coverIcon != null) { view.foregroundGravity = Gravity.CENTER; view.foreground = coverIcon; view.text = "" }
        view.elevation = Ui.dp(c, 6f).toFloat()
        view.setShadowLayer(4f, 0f, 1f, 0x66000000)
        val (sw, sh) = realSize(c)
        params.x = sw - size - Ui.dp(c, 8f)
        params.y = sh / 3
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false; var longFired = false
        val longRun = Runnable { longFired = true; onLong() }
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y
                    moved = false; longFired = false
                    h.postDelayed(longRun, 550)
                    view.alpha = 0.85f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > 12 || abs(dy) > 12)) { moved = true; h.removeCallbacks(longRun) }
                    if (moved) {
                        params.x = (startX + dx).toInt(); params.y = (startY + dy).toInt()
                        try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    h.removeCallbacks(longRun)
                    view.alpha = 1f
                    if (moved) snap()
                    else if (!longFired && e.action == MotionEvent.ACTION_UP) onTap()
                }
            }
            true
        }
    }

    private fun snap() {
        val (sw, _) = realSize(c)
        params.x = if (params.x + size / 2 < sw / 2) Ui.dp(c, 4f) else sw - size - Ui.dp(c, 4f)
        try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
    }

    fun show() { try { wm.addView(view, params) } catch (_: Exception) {} }
    fun remove() { try { wm.removeView(view) } catch (_: Exception) {} }
    private var isBusy = false
    private var mode = "idle"

    fun busy(b: Boolean) { isBusy = b; paint() }
    /** idle · live (ترجمه زنده یک ناحیه) · auto (ترجمه خودکار مداوم) */
    fun mode(m: String) { mode = m; paint() }
    fun live(on: Boolean) = mode(if (on) "live" else "idle")

    private fun paint() {
        val useIcon = coverIcon != null && !(isBusy && mode == "idle")
        view.text = if (useIcon) "" else if (isBusy && mode == "idle") "…" else BuildConfig.BUBBLE_GLYPH
        if (coverIcon != null) view.foreground = if (useIcon) coverIcon else null
        val r = size / 2f
        view.background = when (mode) {
            "live" -> Ui.round(Ui.GREEN, r, Ui.dp(c, 3f), 0xAAFFFFFF.toInt())
            "auto" -> {
                val g = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFF14B8A6.toInt(), 0xFF2FBF71.toInt()))
                g.cornerRadius = r
                g.setStroke(Ui.dp(c, 3f), if (isBusy) Ui.OACCENT2 else 0xAAFFFFFF.toInt())
                g
            }
            else -> Ui.gradient(r)
        }
    }

    /** محدوده حباب روی صفحه (برای اینکه در تشخیص تغییر صفحه حساب نشود) */
    fun rect(): Rect = Rect(params.x, params.y, params.x + size, params.y + size)
    fun hideForCapture(hide: Boolean) { view.alpha = if (hide) 0f else 1f }
}

// ============================================================ منوی حباب
class MenuItem(val icon: String, val title: String, val sub: String? = null, val active: Boolean = false,
               val danger: Boolean = false, val action: () -> Unit)

@SuppressLint("ClickableViewAccessibility")
class BubbleMenu(private val c: Context, private val wm: WindowManager) {
    private var view: View? = null

    fun show(anchorX: Int, anchorY: Int, items: List<MenuItem>) {
        dismiss()
        val box = LinearLayout(c)
        box.orientation = LinearLayout.VERTICAL
        box.background = Ui.round(0xF2171B2E.toInt(), Ui.dp(c, 18f).toFloat(), 1, 0x996B7CFF.toInt())
        box.elevation = Ui.dp(c, 10f).toFloat()
        val p = Ui.dp(c, 8f)
        box.setPadding(p, p, p, p)
        box.layoutDirection = if (L.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        val head = Ui.text(c, "فارسی ساز", 13f, Ui.OMUTED, true)
        head.setPadding(Ui.dp(c, 12f), Ui.dp(c, 4f), Ui.dp(c, 12f), Ui.dp(c, 6f))
        box.addView(head)
        for (it in items) {
            val r = Ui.row(c)
            val pp = Ui.dp(c, 10f)
            r.setPadding(pp, pp, pp, pp)
            val rad = Ui.dp(c, 12f).toFloat()
            val color = when { it.danger -> Ui.RED; it.active -> Ui.GREEN; else -> Ui.OACCENT2 }
            r.background = Ui.ripple(if (it.active) Ui.round(Ui.alpha(Ui.GREEN, 30), rad) else null, rad)
            val ic = Ui.text(c, it.icon, 16f, color, true)
            ic.gravity = Gravity.CENTER
            ic.textAlignment = View.TEXT_ALIGNMENT_CENTER
            ic.background = Ui.round(Ui.alpha(color, 34), Ui.dp(c, 10f).toFloat())
            r.addView(ic, LinearLayout.LayoutParams(Ui.dp(c, 34f), Ui.dp(c, 34f)))
            val col = LinearLayout(c)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(Ui.dp(c, 10f), 0, Ui.dp(c, 4f), 0)
            col.addView(Ui.text(c, it.title, 15f, if (it.danger) Ui.RED else Ui.OTEXT, true))
            if (it.sub != null) col.addView(Ui.text(c, it.sub, 11.5f, Ui.OMUTED))
            r.addView(col)
            val act = it.action
            r.setOnClickListener { dismiss(); act() }
            box.addView(r, LinearLayout.LayoutParams(Ui.dp(c, 264f), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val (sw, sh) = realSize(c)
        // در حالت افقی (ارتفاع کم) آیتم‌ها جا نمی‌شوند و زیر صفحه می‌روند؛ پس منو را قابل‌اسکرول می‌کنیم.
        val availH = sh - Ui.dp(c, 32f)
        val estH = Ui.dp(c, 48f) + items.size * Ui.dp(c, 60f)
        val scroll = estH > availH
        val root: View = if (scroll) ScrollView(c).apply { addView(box); isVerticalScrollBarEnabled = true; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS } else box
        val h = if (scroll) availH else ViewGroup.LayoutParams.WRAP_CONTENT
        val params = overlayParams(ViewGroup.LayoutParams.WRAP_CONTENT, h)
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        params.x = if (anchorX > sw / 2) maxOf(0, anchorX - Ui.dp(c, 290f)) else anchorX + Ui.dp(c, 62f)
        params.y = if (scroll) Ui.dp(c, 16f) else minOf(anchorY, sh - Ui.dp(c, 70f) * (items.size + 1)).coerceAtLeast(Ui.dp(c, 24f))
        root.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) { dismiss(); true } else false }
        root.alpha = 0f; root.scaleX = 0.94f; root.scaleY = 0.94f
        try { wm.addView(root, params); view = root } catch (_: Exception) {}
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).start()
    }

    fun dismiss() { view?.let { try { wm.removeView(it) } catch (_: Exception) {} }; view = null }
    val isOpen: Boolean get() = view != null
}

// ============================================================ انتخاب ناحیه
@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class RegionSelector(c: Context, private val wm: WindowManager, private val onDone: (Rect?) -> Unit) : FrameLayout(c) {
    private val paintDim = Paint().apply { color = 0x88000000.toInt() }
    private val paintBorder = Paint().apply {
        color = Ui.OACCENT2; style = Paint.Style.STROKE; strokeWidth = Ui.dp(c, 2.5f).toFloat(); isAntiAlias = true
    }
    private val paintClear = Paint().apply { color = 0x22FFFFFF }
    private var sx = 0f; private var sy = 0f; private var ex = 0f; private var ey = 0f
    private var has = false
    private val bar = LinearLayout(c)

    init {
        setWillNotDraw(false)
        val hint = Ui.text(c, "با انگشت دور متن یک کادر بکشید", 16f, Color.WHITE, true)
        hint.gravity = Gravity.CENTER
        hint.background = Ui.round(0xCC171B2E.toInt(), Ui.dp(c, 12f).toFloat())
        val hp = Ui.dp(c, 10f)
        hint.setPadding(hp * 2, hp, hp * 2, hp)
        val hl = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        hl.topMargin = Ui.dp(c, 48f)
        addView(hint, hl)

        bar.orientation = LinearLayout.HORIZONTAL
        bar.layoutDirection = if (L.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        bar.addView(Ui.weight(Ui.button(c, "✓ تأیید", true) { finish(true) }))
        bar.addView(Ui.weight(Ui.button(c, "کل صفحه") { close(); onDone(Rect(0, 0, width, height)) }))
        bar.addView(Ui.weight(Ui.button(c, "انصراف") { close(); onDone(null) }))
        val bl = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        val m = Ui.dp(c, 16f)
        bl.setMargins(m, m, m, Ui.dp(c, 56f))
        addView(bar, bl)
    }

    private fun rect(): RectF = RectF(minOf(sx, ex), minOf(sy, ey), maxOf(sx, ex), maxOf(sy, ey))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = rect()
        if (!has) { canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintDim); return }
        canvas.drawRect(0f, 0f, width.toFloat(), r.top, paintDim)
        canvas.drawRect(0f, r.bottom, width.toFloat(), height.toFloat(), paintDim)
        canvas.drawRect(0f, r.top, r.left, r.bottom, paintDim)
        canvas.drawRect(r.right, r.top, width.toFloat(), r.bottom, paintDim)
        canvas.drawRect(r, paintClear)
        canvas.drawRoundRect(r, 12f, 12f, paintBorder)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y; ex = e.x; ey = e.y; has = true; bar.visibility = INVISIBLE }
            MotionEvent.ACTION_MOVE -> { ex = e.x; ey = e.y }
            MotionEvent.ACTION_UP -> { ex = e.x; ey = e.y; bar.visibility = VISIBLE }
        }
        invalidate()
        return true
    }

    private fun finish(ok: Boolean) {
        val r = rect()
        close()
        if (!ok || !has || r.width() < 40 || r.height() < 25) { onDone(null); return }
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        onDone(Rect(r.left.toInt() + loc[0], r.top.toInt() + loc[1], r.right.toInt() + loc[0], r.bottom.toInt() + loc[1]))
    }

    fun show() {
        val (w, h) = realSize(context)
        val p = overlayParams(w, h)
        try { wm.addView(this, p) } catch (_: Exception) { onDone(null) }
    }

    private fun close() { try { wm.removeView(this) } catch (_: Exception) {} }
}

// ============================================================ ترجمه روی خود متن
/** یک تکه ترجمه: جای متن اصلی روی صفحه، ترجمه و ارتفاع هر خط متن اصلی */
class TrItem(val rect: Rect, val text: String, val lineH: Int = 0)

/**
 * ترجمه‌ها دقیقاً روی جای متن اصلی.
 * حالت عادی: با زدن روی صفحه بسته می‌شود.
 * حالت خودکار (passive): لمس‌ها از آن رد می‌شوند تا کاربر بتواند بازی کند.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ResultOverlay(c: Context, private val wm: WindowManager) : FrameLayout(c) {
    private var shown = false
    private var passive = false
    private var params: WindowManager.LayoutParams? = null
    private var boxes: List<Rect> = emptyList()
    private var lastKey = ""

    init {
        setOnTouchListener { _, e -> if (!passive && e.action == MotionEvent.ACTION_UP) dismiss(); !passive }
    }

    /** محدوده ترجمه‌های نمایش داده‌شده (مختصات صفحه) */
    fun rects(): List<Rect> = if (shown) boxes else emptyList()

    fun show(items: List<TrItem>, passiveMode: Boolean = false) {
        // OCR هر بار چند پیکسل جابه‌جا می‌خواند؛ اگر متن‌ها همان است و جا تقریباً همان، دوباره کشیده نمی‌شود (بدون پرش)
        val key = items.joinToString("|") {
            val q = maxOf(10, it.lineH)
            "${it.rect.left / q},${it.rect.top / q}=${it.text}"
        } + passiveMode
        if (shown && key == lastKey) return
        lastKey = key
        removeAllViews()
        passive = passiveMode
        val (sw, sh) = realSize(context)
        var bgA = (Prefs.opacity * 255 / 100).coerceIn(80, 255)
        if (passive) bgA = bgA.coerceAtMost(228)     // کمی شفاف تا تغییر متن زیر ترجمه دیده شود
        val bg = (bgA shl 24) or 0x10131F
        val dm = context.resources.displayMetrics
        val maxPx = Prefs.fontSize * dm.scaledDensity
        val minPx = 9f * dm.scaledDensity
        val out = ArrayList<Rect>()
        val pad = Ui.dp(context, 2f)          // کادر دقیقاً اندازه متن اصلی (بدون حداقل پهنا)
        val inX = Ui.dp(context, 3f)
        // جلوگیری از هم‌پوشانی کادرها: کادرهای بزرگ‌تر اولویت دارند؛ کادری که بیش از ۴۰٪ روی کادرِ
        // پذیرفته‌شده بیفتد انداخته می‌شود (مخصوصاً وقتی OCRِ خط‌های غیرلاتین قطعه‌های تکراری می‌دهد).
        val placed = ArrayList<Rect>()
        val items2 = items.sortedByDescending { it.rect.width().toLong() * it.rect.height() }.filter { itm ->
            val r = itm.rect
            val keep = placed.none { p ->
                val ix = maxOf(0, minOf(r.right, p.right) - maxOf(r.left, p.left))
                val iy = maxOf(0, minOf(r.bottom, p.bottom) - maxOf(r.top, p.top))
                val inter = ix.toLong() * iy
                val smaller = minOf(r.width().toLong() * r.height(), p.width().toLong() * p.height()).coerceAtLeast(1)
                inter * 100 / smaller > 40
            }
            if (keep) placed.add(r)
            keep
        }
        for (it in items2) {
            val r = it.rect
            val t = Ui.text(context, Persian.display(it.text), Prefs.fontSize.toFloat(), Color.WHITE)
            t.includeFontPadding = false
            t.setLineSpacing(0f, 1.0f)
            t.background = Ui.round(bg, Ui.dp(context, 4f).toFloat(), 1, if (passive) 0x5514B8A6 else 0x886B7CFF.toInt())
            t.setShadowLayer(2f, 0f, 1f, 0xAA000000.toInt())
            t.setPadding(inX, 0, inX, 0)
            t.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            val w = (r.width() + pad * 2).coerceIn(Ui.dp(context, 16f), sw)
            val h = r.height() + pad * 2
            // بزرگ‌ترین اندازه متنی که در کادر متن اصلی جا شود (نزدیک به اندازه متن بازی)
            var px = if (it.lineH > 0) (it.lineH * 0.88f).coerceIn(minPx, maxPx) else maxPx
            val wSpec = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
            val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            while (true) {
                t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px)
                t.measure(wSpec, hSpec)
                if (t.measuredHeight <= h || px <= minPx) break
                px = maxOf(minPx, px * 0.92f)
            }
            val finalH = maxOf(h, t.measuredHeight)
            val lp = LayoutParams(w, finalH, Gravity.TOP or Gravity.LEFT)
            t.tag = "tr"
            lp.leftMargin = (r.left - pad).coerceIn(0, maxOf(0, sw - w))
            lp.topMargin = (r.top - pad).coerceIn(0, maxOf(0, sh - finalH))
            addView(t, lp)
            out.add(Rect(lp.leftMargin, lp.topMargin, lp.leftMargin + w, lp.topMargin + finalH))
        }
        boxes = out
        if (!passive) {
            val hint = Ui.text(context, "برای بستن، روی صفحه بزنید", 12f, Ui.OMUTED)
            hint.background = Ui.round(0xCC171B2E.toInt(), Ui.dp(context, 10f).toFloat())
            val hp = Ui.dp(context, 8f)
            hint.setPadding(hp, hp / 2, hp, hp / 2)
            val hl = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            hl.bottomMargin = Ui.dp(context, 70f)
            addView(hint, hl)
        }
        // بعد از چیدمان، اندازه واقعی کادرها (ممکن است ترجمه چندخطی شده باشد) ثبت می‌شود
        post {
            val real = ArrayList<Rect>()
            for (i in 0 until childCount) {
                val ch = getChildAt(i)
                if (ch.tag == "tr") real.add(Rect(ch.left, ch.top, ch.right, ch.bottom))
            }
            if (real.isNotEmpty()) boxes = real
        }
        val np = overlayParams(sw, sh, touchable = !passive)
        if (!shown) {
            try { wm.addView(this, np); shown = true; params = np } catch (_: Exception) {}
        } else if (params?.flags != np.flags || params?.width != sw || params?.height != sh) {
            try { wm.updateViewLayout(this, np); params = np } catch (_: Exception) {}
        }
        alpha = 1f
        // اگر پنجره شناور دقیقاً از گوشه صفحه شروع نشد (بعضی گوشی‌ها)، جای ترجمه‌ها اصلاح می‌شود
        post {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            for (i in 0 until childCount) {
                val ch = getChildAt(i)
                if (ch.tag == "tr") { ch.translationX = -loc[0].toFloat(); ch.translationY = -loc[1].toFloat() }
            }
        }
    }

    /** پنهان کردن لحظه‌ای برای عکس گرفتن از متن اصلی زیر ترجمه */
    fun hideForCapture(hide: Boolean) { if (shown) alpha = if (hide) 0f else 1f }

    fun dismiss() {
        if (shown) { try { wm.removeView(this) } catch (_: Exception) {} }
        shown = false
        boxes = emptyList()
        lastKey = ""
    }

    val isShown2: Boolean get() = shown
}

// ============================================================ پنل ترجمه زنده
@SuppressLint("ClickableViewAccessibility")
class LivePanel(private val c: Context, private val wm: WindowManager, onClose: () -> Unit) {
    private val root = LinearLayout(c)
    private val body = LinearLayout(c)
    private val status: TextView
    private val params: WindowManager.LayoutParams
    private val sv: ScrollView
    private var shown = false
    private var stickBottom = true

    init {
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = if (L.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        val alpha = (Prefs.opacity * 255 / 100).coerceIn(90, 255)
        root.background = Ui.round((alpha shl 24) or 0x121629, Ui.dp(c, 16f).toFloat(), 1, 0x996B7CFF.toInt())
        val p = Ui.dp(c, 12f)
        root.setPadding(p, p / 2, p, Ui.dp(c, 4f))
        val head = Ui.row(c)
        val grip = Ui.text(c, "⠿", 16f, Ui.OMUTED)
        grip.setPadding(0, 0, Ui.dp(c, 6f), 0)
        head.addView(grip)
        status = Ui.text(c, "● ترجمه زنده", 12f, Ui.GREEN, true)
        head.addView(Ui.weight(status, 1f, 0f))
        val close = Ui.text(c, "✕", 18f, Ui.OMUTED)
        close.setPadding(p, 0, 0, 0)
        close.setOnClickListener { onClose() }
        head.addView(close)
        head.setPadding(0, Ui.dp(c, 4f), 0, Ui.dp(c, 6f))
        root.addView(head)
        body.orientation = LinearLayout.VERTICAL
        // اندازه پنل ثابت است (کاربر تعیین می‌کند)؛ اگر متن بیشتر شد، اسکرول می‌شود
        sv = ScrollView(c)
        sv.isVerticalScrollBarEnabled = true
        sv.isFillViewport = true
        sv.addView(body)
        sv.setOnScrollChangeListener { v, _, y, _, _ ->
            val sc = v as ScrollView
            val child = sc.getChildAt(0)
            stickBottom = child == null || y + sc.height >= child.height - Ui.dp(c, 8f)
        }
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // دستگیره تغییر اندازه (گوشه پایین)
        val foot = Ui.row(c)
        val resize = Ui.text(c, "◢", 16f, Ui.OMUTED)
        resize.setPadding(Ui.dp(c, 8f), 0, 0, Ui.dp(c, 2f))
        val hint = Ui.text(c, "برای تغییر اندازه، این گوشه را بکشید", 10f, 0x88A3AAC8.toInt())
        foot.addView(Ui.weight(hint, 1f, 0f))
        foot.addView(resize)
        root.addView(foot)

        val (sw, sh) = realSize(c)
        val box = Prefs.panelBox
        params = overlayParams(
            if (box[2] > 0) box[2] else sw - Ui.dp(c, 16f),
            if (box[3] > 0) box[3] else Ui.dp(c, 170f)
        )
        params.x = if (box[0] >= 0) box[0] else Ui.dp(c, 8f)
        params.y = if (box[1] >= 0) box[1] else sh - params.height - Ui.dp(c, 60f)

        // جابه‌جایی با کشیدن نوار بالا
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var startW = 0; var startH = 0
        head.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + (e.rawX - downX)).toInt()
                    params.y = (startY + (e.rawY - downY)).toInt()
                    clamp(); update()
                }
                MotionEvent.ACTION_UP -> save()
            }
            true
        }
        // تغییر اندازه با کشیدن گوشه (در چیدمان راست‌به‌چپ، این گوشه سمت چپ است)
        resize.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startW = params.width; startH = params.height
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    val minW = Ui.dp(c, 180f); val minH = Ui.dp(c, 90f)
                    val nw = (startW - dx).coerceIn(minW, sw)
                    params.x = startX + (startW - nw)
                    params.width = nw
                    params.height = (startH + dy).coerceIn(minH, sh - Ui.dp(c, 40f))
                    clamp(); update()
                }
                MotionEvent.ACTION_UP -> save()
            }
            true
        }
    }

    private fun update() { if (shown) try { wm.updateViewLayout(root, params) } catch (_: Exception) {} }

    private fun save() = run { Prefs.panelBox = intArrayOf(params.x, params.y, params.width, params.height) }

    /** پنل همیشه کامل داخل صفحه می‌ماند */
    private fun clamp() {
        val (sw, sh) = realSize(c)
        params.width = params.width.coerceIn(Ui.dp(c, 180f), sw)
        params.height = params.height.coerceIn(Ui.dp(c, 90f), sh - Ui.dp(c, 40f))
        params.x = params.x.coerceIn(0, maxOf(0, sw - params.width))
        params.y = params.y.coerceIn(Ui.dp(c, 20f), maxOf(Ui.dp(c, 20f), sh - params.height - Ui.dp(c, 16f)))
    }

    /** بار اول (بدون جای ذخیره‌شده) پنل در نیمه‌ای از صفحه قرار می‌گیرد که ناحیه ترجمه در آن نیست */
    fun show(region: Rect) {
        val (_, sh) = realSize(c)
        if (Prefs.panelBox[1] < 0) {
            params.y = if (region.centerY() > sh / 2) Ui.dp(c, 40f) else sh - params.height - Ui.dp(c, 60f)
        }
        clamp()
        if (!shown) { try { wm.addView(root, params); shown = true } catch (_: Exception) {} }
    }

    fun setStatus(s: String, color: Int = Ui.GREEN) { status.text = s; status.setTextColor(color) }

    fun push(texts: List<String>) {
        body.removeAllViews()
        val list = texts.takeLast(4)
        list.forEachIndexed { i, t ->
            val latest = i == list.size - 1
            val tv = Ui.text(c, Persian.display(t), Prefs.fontSize.toFloat() + (if (latest) 1 else -2), if (latest) Color.WHITE else 0xFF9AA3C0.toInt())
            tv.setPadding(0, Ui.dp(c, 3f), 0, Ui.dp(c, 3f))
            body.addView(tv)
        }
        stickBottom = true
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    fun remove() { if (shown) { try { wm.removeView(root) } catch (_: Exception) {} }; shown = false }
}

// ============================================================ انتخابگر زبان شناور (بیرون برنامه)
@SuppressLint("ClickableViewAccessibility")
class LangOverlay(private val c: Context, private val wm: WindowManager) {
    private var view: View? = null

    fun show(title: String, current: String, includeAuto: Boolean, onPick: (String) -> Unit) {
        dismiss()
        val list = if (includeAuto) Langs.sources else Langs.targets
        val (sw, sh) = realSize(c)
        val root = LinearLayout(c)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = if (L.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        root.background = Ui.round(0xF7171B2E.toInt(), Ui.dp(c, 16f).toFloat(), 1, 0x996B7CFF.toInt())
        root.elevation = Ui.dp(c, 12f).toFloat()
        val p = Ui.dp(c, 12f); root.setPadding(p, p, p, p)

        val head = Ui.row(c)
        head.addView(Ui.weight(Ui.text(c, title, 15f, Color.WHITE, true), 1f, 0f))
        val x = Ui.text(c, "✕", 18f, Ui.OMUTED)
        x.setOnClickListener { dismiss() }
        head.addView(x)
        root.addView(head)

        val search = android.widget.EditText(c)
        search.hint = L.sx("جست‌وجوی زبان…")
        search.setTextColor(Ui.OTEXT); search.setHintTextColor(Ui.OMUTED); search.typeface = Ui.font(c)
        search.background = Ui.round(0xFF141829.toInt(), Ui.dp(c, 10f).toFloat(), 1, Ui.OBORDER)
        val sp = Ui.dp(c, 10f); search.setPadding(sp, sp, sp, sp)
        root.addView(Ui.full(search, 8f))

        val sv = ScrollView(c)
        val col = LinearLayout(c); col.orientation = LinearLayout.VERTICAL; col.layoutDirection = if (L.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        sv.addView(col)
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).also { it.topMargin = Ui.dp(c, 8f) })

        fun render(q: String) {
            col.removeAllViews()
            val query = q.trim()
            for (lg in list) {
                if (query.isNotEmpty() && !lg.name.contains(query) && !L.sx(lg.name).contains(query, true) && !lg.code.contains(query, true)) continue
                val on = lg.code == current
                val row = Ui.text(c, lg.name, 15f, if (on) Color.WHITE else Ui.OTEXT, on)
                val rp = Ui.dp(c, 11f); row.setPadding(rp, rp, rp, rp)
                row.background = Ui.ripple(if (on) Ui.round(Ui.alpha(Ui.ACCENT, 50), Ui.dp(c, 10f).toFloat()) else null, Ui.dp(c, 10f).toFloat())
                row.setOnClickListener { onPick(lg.code); dismiss() }
                col.addView(row)
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { render(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, cc: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, cc: Int) {}
        })
        render("")

        val w = minOf(sw - Ui.dp(c, 24f), Ui.dp(c, 360f))
        val h = minOf(sh - Ui.dp(c, 120f), Ui.dp(c, 460f))
        val params = overlayParams(w, h, focusable = true)
        params.x = (sw - w) / 2
        params.y = Ui.dp(c, 70f)
        try { wm.addView(root, params); view = root } catch (_: Exception) {}
    }

    fun dismiss() { view?.let { try { wm.removeView(it) } catch (_: Exception) {} }; view = null }
}

// ============================================================ پنل ترجمهٔ دوطرفه شناور (بیرون برنامه)
@SuppressLint("ClickableViewAccessibility")
class DuoPanel(
    private val c: Context,
    private val wm: WindowManager,
    private val translate: (String, String, String) -> String,
    private val onClose: () -> Unit,
) {
    private val root = LinearLayout(c)
    private val params: WindowManager.LayoutParams
    private var shown = false
    private val main = Handler(Looper.getMainLooper())
    private val langs = LangOverlay(c, wm)

    init {
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = if (L.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        root.background = Ui.round(0xF7121629.toInt(), Ui.dp(c, 16f).toFloat(), 1, 0x996B7CFF.toInt())
        root.elevation = Ui.dp(c, 12f).toFloat()
        val p = Ui.dp(c, 12f); root.setPadding(p, p, p, p)

        // سربرگ (قابل جابه‌جایی) + بستن
        val head = Ui.row(c)
        val grip = Ui.text(c, "⠿", 15f, Ui.OMUTED); grip.setPadding(0, 0, Ui.dp(c, 6f), 0)
        head.addView(grip)
        head.addView(Ui.weight(Ui.text(c, "ترجمهٔ دوطرفه", 14f, Ui.OACCENT2, true), 1f, 0f))
        val close = Ui.text(c, "✕", 18f, Ui.OMUTED)
        close.setOnClickListener { onClose() }
        head.addView(close)
        head.setPadding(0, Ui.dp(c, 2f), 0, Ui.dp(c, 6f))
        root.addView(head)

        // انتخاب زبان مبدأ ⇄ مقصد
        val bar = Ui.row(c)
        val srcBtn = Ui.ghost(c, Langs.name(Prefs.duoSource), Ui.OACCENT2) {}
        val dstBtn = Ui.ghost(c, Langs.name(Prefs.duoTarget), Ui.OACCENT2) {}
        val swap = Ui.text(c, "⇄", 18f, Ui.OACCENT2, true)
        swap.gravity = Gravity.CENTER; swap.textAlignment = View.TEXT_ALIGNMENT_CENTER
        srcBtn.setOnClickListener { langs.show(L.sx("زبان مبدأ"), Prefs.duoSource, true) { Prefs.duoSource = it; srcBtn.text = L.sx(Langs.name(it)) } }
        dstBtn.setOnClickListener { langs.show(L.sx("زبان مقصد"), Prefs.duoTarget, false) { Prefs.duoTarget = it; dstBtn.text = L.sx(Langs.name(it)) } }
        swap.setOnClickListener {
            if (Prefs.duoSource != "auto") {
                val s = Prefs.duoSource; Prefs.duoSource = Prefs.duoTarget; Prefs.duoTarget = s
                srcBtn.text = L.sx(Langs.name(Prefs.duoSource)); dstBtn.text = L.sx(Langs.name(Prefs.duoTarget))
            }
        }
        bar.addView(Ui.weight(srcBtn, 1f))
        bar.addView(swap, LinearLayout.LayoutParams(Ui.dp(c, 36f), ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(Ui.weight(dstBtn, 1f))
        root.addView(Ui.full(bar, 2f))

        // ورودی
        val input = android.widget.EditText(c)
        input.hint = L.sx("متن را بنویسید…")
        input.setTextColor(Ui.OTEXT); input.setHintTextColor(Ui.OMUTED); input.typeface = Ui.font(c)
        input.minLines = 2; input.maxLines = 4; input.gravity = Gravity.TOP or Gravity.START
        input.background = Ui.round(0xFF141829.toInt(), Ui.dp(c, 10f).toFloat(), 1, Ui.OBORDER)
        val ip = Ui.dp(c, 10f); input.setPadding(ip, ip, ip, ip)
        root.addView(Ui.full(input, 8f))

        // خروجی
        val out = Ui.text(c, "", 15f, Color.WHITE)
        out.setTextIsSelectable(true)
        val outSv = ScrollView(c)
        outSv.background = Ui.round(0xFF10131F.toInt(), Ui.dp(c, 10f).toFloat(), 1, Ui.alpha(Ui.ACCENT, 90))
        outSv.setPadding(ip, ip, ip, ip)
        outSv.addView(out)
        root.addView(LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 84f)).let { lp -> lp.topMargin = Ui.dp(c, 8f); outSv.layoutParams = lp; outSv })

        // دکمه‌ها
        val btns = Ui.row(c)
        val copyBtn = Ui.ghost(c, "📋 کپی", Ui.OACCENT2) {
            val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("farsisaz", out.text.toString()))
            toast("کپی شد؛ در چت بچسبانید.")
        }
        val goBtn = Ui.button(c, "ترجمه", true) {
            val text = input.text.toString().trim()
            if (text.isEmpty()) { toast("اول متنی بنویسید."); return@button }
            val src = Prefs.duoSource; val dst = Prefs.duoTarget
            out.text = L.sx("در حال ترجمه…"); out.setTextColor(Ui.OMUTED)
            Thread {
                val res = try { translate(text, src, dst) } catch (e: Exception) { "⚠ " + Translator.short(e) }
                main.post {
                    if (res.startsWith("⚠")) { out.text = res; out.setTextColor(Ui.AMBER) }
                    else {
                        val rtl = Langs.isRtl(dst)
                        out.text = if (rtl) Persian.display(res) else res
                        out.setTextColor(Color.WHITE)
                        out.textDirection = if (rtl) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
                    }
                }
            }.start()
        }
        btns.addView(Ui.weight(goBtn, 2f))
        btns.addView(Ui.weight(copyBtn, 1f))
        root.addView(Ui.full(btns, 8f))

        val (sw, sh) = realSize(c)
        val w = minOf(sw - Ui.dp(c, 20f), Ui.dp(c, 380f))
        params = overlayParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, focusable = true)
        params.x = (sw - w) / 2
        params.y = Ui.dp(c, 54f)

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        head.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + (e.rawX - downX)).toInt().coerceIn(0, maxOf(0, sw - w))
                    params.y = (startY + (e.rawY - downY)).toInt().coerceIn(0, maxOf(0, sh - Ui.dp(c, 120f)))
                    if (shown) try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
                }
            }
            true
        }
    }

    private fun toast(s: String) = main.post { android.widget.Toast.makeText(c, L.s(s) ?: s, android.widget.Toast.LENGTH_SHORT).show() }

    fun show() { if (!shown) { try { wm.addView(root, params); shown = true } catch (_: Exception) {} } }
    fun remove() { langs.dismiss(); if (shown) { try { wm.removeView(root) } catch (_: Exception) {} }; shown = false }
    val isShown: Boolean get() = shown
}
