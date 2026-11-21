package ir.farsisaz

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** رنگ‌ها، فونت و اجزای رابط کاربری (هم‌رنگ نسخه ویندوز) */
object Ui {
    // تمِ روشن/تیره. رنگ‌های پایه بسته به حالت تغییر می‌کنند؛ رنگ‌های تأکیدی (ACCENT/GREEN/…) ثابت‌اند.
    @Volatile var dark: Boolean = true
    fun applyTheme() { dark = Prefs.darkMode }

    val BG: Int get() = if (dark) 0xFF0F1220.toInt() else 0xFFEEF1F8.toInt()
    val PANEL: Int get() = if (dark) 0xFF171B2E.toInt() else 0xFFFFFFFF.toInt()
    val CARD: Int get() = if (dark) 0xFF1D2238.toInt() else 0xFFFFFFFF.toInt()
    val BORDER: Int get() = if (dark) 0xFF2A3150.toInt() else 0xFFD9DFEE.toInt()
    val TEXT: Int get() = if (dark) 0xFFEEF1FB.toInt() else 0xFF1A1F33.toInt()
    val MUTED: Int get() = if (dark) 0xFFA3AAC8.toInt() else 0xFF5E6887.toInt()
    val ACCENT2: Int get() = if (dark) 0xFF7FD3FF.toInt() else 0xFF2E73E8.toInt()
    // پس‌زمینهٔ ورودی‌ها/قاب‌های تودرتو و دکمهٔ ثانویه (هم‌تراز با تم)
    val INPUT: Int get() = if (dark) 0xFF141829.toInt() else 0xFFF1F4FA.toInt()
    val INSET: Int get() = if (dark) 0xFF10131F.toInt() else 0xFFEDF0F7.toInt()
    val BTN: Int get() = if (dark) 0xFF262C47.toInt() else 0xFFEAEEF7.toInt()

    const val ACCENT = 0xFF6B7CFF.toInt()
    const val GREEN = 0xFF2FBF71.toInt()
    const val RED = 0xFFE5484D.toInt()
    const val AMBER = 0xFFF5A524.toInt()
    val GRAD = intArrayOf(0xFF4F7CFF.toInt(), 0xFF8B5CF6.toInt())

    // رنگ‌های «همیشه‌تیره» برای overlay و حباب (روی بازی‌ها) — مستقل از تمِ برنامه،
    // چون پس‌زمینهٔ آن‌ها همیشه تیره/شفاف است و متن باید روشن بماند.
    const val OTEXT = 0xFFEEF1FB.toInt()
    const val OMUTED = 0xFFA3AAC8.toInt()
    const val OACCENT2 = 0xFF7FD3FF.toInt()
    const val OBORDER = 0xFF2A3150.toInt()

    private var regular: Typeface? = null
    private var bold: Typeface? = null

    fun font(c: Context, b: Boolean = false): Typeface {
        if (regular == null) {
            regular = try { Typeface.createFromAsset(c.assets, "fonts/Vazirmatn-Regular.ttf") } catch (e: Exception) { Typeface.DEFAULT }
            bold = try { Typeface.createFromAsset(c.assets, "fonts/Vazirmatn-Bold.ttf") } catch (e: Exception) { Typeface.DEFAULT_BOLD }
        }
        return (if (b) bold else regular)!!
    }

    fun dp(c: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics).toInt()

    fun round(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = BORDER): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = radius
        if (stroke > 0) g.setStroke(stroke, strokeColor)
        return g
    }

    fun gradient(radius: Float): GradientDrawable {
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, GRAD)
        g.cornerRadius = radius
        return g
    }

    fun text(c: Context, s: CharSequence, size: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView {
        val t = TextView(c)
        t.text = L.s(s) ?: s                         // ترجمهٔ خودکارِ رابط (انگلیسی/…)، فارسی بدون تغییر
        t.textSize = size
        t.setTextColor(color)
        t.typeface = font(c, bold)
        t.textDirection = if (L.rtl) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
        t.textAlignment = View.TEXT_ALIGNMENT_TEXT_START
        t.setLineSpacing(0f, 1.25f)
        return t
    }

    fun alpha(color: Int, a: Int): Int = (a.coerceIn(0, 255) shl 24) or (color and 0xFFFFFF)

    /** بازخورد لمس (موج) روی هر پس‌زمینه */
    fun ripple(bg: Drawable?, radius: Float, color: Int = 0x33FFFFFF): RippleDrawable {
        val mask = round(Color.WHITE, radius)
        return RippleDrawable(ColorStateList.valueOf(color), bg, mask)
    }

    fun button(c: Context, s: String, primary: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(c)
        b.text = L.s(s) ?: s
        b.isAllCaps = false
        b.textSize = if (primary) 16f else 14f
        b.typeface = font(c, primary)
        b.setTextColor(if (primary) Color.WHITE else TEXT)   // دکمهٔ ثانویه در لایت‌مود باید متنِ تیره داشته باشد
        val r = dp(c, if (primary) 14f else 12f).toFloat()
        b.background = ripple(if (primary) gradient(r) else round(BTN, r, 1, BORDER), r)
        b.stateListAnimator = null
        if (primary) b.elevation = dp(c, 3f).toFloat()
        val p = dp(c, if (primary) 14f else 11f)
        b.setPadding(p, p, p, p)
        b.minHeight = dp(c, if (primary) 52f else 44f)
        b.setOnClickListener { onClick() }
        return b
    }

    /** دکمه کم‌رنگ (برای کارهای خطرناک یا فرعی) */
    fun ghost(c: Context, s: String, color: Int = TEXT, onClick: () -> Unit): Button {
        val b = button(c, s, false, onClick)
        val r = dp(c, 12f).toFloat()
        b.background = ripple(round(alpha(color, 26), r, 1, alpha(color, 90)), r)
        b.setTextColor(color)
        return b
    }

    fun chip(c: Context, s: String, color: Int): TextView {
        val t = text(c, s, 12f, color, true)
        t.background = round(alpha(color, 36), dp(c, 20f).toFloat(), 1, alpha(color, 110))
        val p = dp(c, 10f)
        t.setPadding(p, dp(c, 3f), p, dp(c, 3f))
        return t
    }

    fun section(c: Context, icon: String, title: String, sub: String? = null): LinearLayout {
        val box = LinearLayout(c)
        box.orientation = LinearLayout.VERTICAL
        val r = row(c)
        val ic = TextView(c)
        ic.text = icon
        ic.textSize = 15f
        ic.setTextColor(ACCENT)   // آیکون‌های متنی (Aa/⟳/…) در هر دو تم خوانا بمانند
        ic.gravity = Gravity.CENTER
        ic.background = round(alpha(ACCENT, 40), dp(c, 10f).toFloat())
        r.addView(ic, LinearLayout.LayoutParams(dp(c, 32f), dp(c, 32f)))
        val t = text(c, title, 16f, TEXT, true)
        t.setPadding(dp(c, 10f), 0, dp(c, 10f), 0)
        r.addView(t)
        box.addView(r)
        if (sub != null) {
            val st = text(c, sub, 13f, MUTED)
            st.setPadding(0, dp(c, 6f), 0, 0)
            box.addView(st)
        }
        return box
    }

    /** انتخاب یکی از چند گزینه به شکل دکمه‌های کنار هم */
    fun segmented(c: Context, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit): LinearLayout {
        val box = row(c)
        val r = dp(c, 12f).toFloat()
        box.background = round(INPUT, r, 1, BORDER)
        val p = dp(c, 4f)
        box.setPadding(p, p, p, p)
        for ((id, label) in options) {
            val on = id == selected
            val t = text(c, label, 14f, if (on) Color.WHITE else MUTED, on)
            t.gravity = Gravity.CENTER
            t.textAlignment = View.TEXT_ALIGNMENT_CENTER
            t.maxLines = 1
            // متنِ بلند (مثل «Microsoft (پیشنهادی)») خودش کوچک می‌شود تا داخل دکمه جا شود و سرریز نکند.
            try { t.setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, TypedValue.COMPLEX_UNIT_SP) } catch (_: Throwable) {}
            val pp = dp(c, 8f)
            t.setPadding(pp, dp(c, 10f), pp, dp(c, 10f))
            t.background = ripple(if (on) gradient(r - 2) else null, r - 2)
            t.setOnClickListener { if (!on) onPick(id) }
            box.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return box
    }

    fun switchRow(c: Context, title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val r = row(c)
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        col.addView(text(c, title, 14f, TEXT))
        if (sub != null) col.addView(text(c, sub, 12f, MUTED))
        r.addView(weight(col, 1f, 0f))
        val sw = Switch(c)
        sw.isChecked = checked
        sw.thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(ACCENT2, 0xFFB0B6CC.toInt()))
        sw.trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(alpha(ACCENT, 170), 0xFF3A4060.toInt()))
        sw.setOnCheckedChangeListener { _, b -> onChange(b) }
        r.addView(sw)
        r.setOnClickListener { sw.toggle() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(c, 10f)
        r.layoutParams = lp
        return r
    }

    fun card(c: Context): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.VERTICAL
        l.background = round(CARD, dp(c, 18f).toFloat(), 1, BORDER)
        l.elevation = dp(c, 1f).toFloat()
        val p = dp(c, 16f)
        l.setPadding(p, p, p, p)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(c, 12f)
        l.layoutParams = lp
        return l
    }

    fun row(c: Context): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        return l
    }

    fun gap(c: Context, h: Float): View {
        val v = View(c)
        v.layoutParams = LinearLayout.LayoutParams(1, dp(c, h))
        return v
    }

    fun weight(v: View, w: Float = 1f, marginDp: Float = 4f): View {
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)
        val m = dp(v.context, marginDp)
        lp.setMargins(m, 0, m, 0)
        v.layoutParams = lp
        return v
    }

    fun full(v: View, topDp: Float = 8f): View {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(v.context, topDp)
        v.layoutParams = lp
        return v
    }

    /** اسکرول‌بارِ باریک، گِرد و هم‌رنگِ برنامه (به‌جای اسکرول‌بارِ پیش‌فرضِ زمختِ اندروید). */
    fun styleScroll(v: View) {
        val c = v.context
        v.isVerticalScrollBarEnabled = true
        v.isScrollbarFadingEnabled = true
        v.scrollBarDefaultDelayBeforeFade = 1200
        v.scrollBarFadeDuration = 500
        try { v.scrollBarSize = dp(c, 5f) } catch (_: Throwable) {}
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val thumb = round(alpha(ACCENT2, 150), dp(c, 2.5f).toFloat())
                v.verticalScrollbarThumbDrawable = thumb
                v.verticalScrollbarTrackDrawable = round(0x00000000, dp(c, 2.5f).toFloat())
            } catch (_: Throwable) {}
        }
    }
}
