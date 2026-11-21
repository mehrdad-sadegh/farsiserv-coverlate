package ir.farsisaz

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var lm: LicenseManager
    private lateinit var content: FrameLayout
    private val navButtons = LinkedHashMap<String, TextView>()
    private var page = "home"
    private var downloadId = -1L
    @Volatile private var shellBuilt = false   // تا قاب اصلی ساخته نشده، onResume نباید go() را صدا بزند

    private fun dir() = if (L.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

    /** رنگ و روشن/تیره‌بودنِ آیکون‌های نوار وضعیت و ناوبری متناسب با تم. */
    private fun applySystemBars() {
        window.statusBarColor = Ui.PANEL
        window.navigationBarColor = Ui.PANEL
        @Suppress("DEPRECATION")
        var flags = window.decorView.systemUiVisibility
        val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        flags = if (!Ui.dark) flags or light else flags and light.inv()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
    }

    private val pages = listOf(
        Triple("home", "🌐", "ترجمه"), Triple("duo", "🔄", "دوطرفه"), Triple("history", "🕘", "تاریخچه"),
        Triple("license", "🔑", "لایسنس"), Triple("help", "📖", "راهنما"), Triple("about", "ℹ️", "درباره"),
        Triple("settings", "⚙️", "تنظیمات"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashGuard.install(this)
        try {
            Prefs.init(this)
            Monetize.channel = Monetize.detectChannel(this)   // استورِ نصب (bazaar/myket/ir/global)
            L.init()
            Ui.applyTheme()
            lm = LicenseManager.get(this)
            lm.state = lm.offlineState()
            applySystemBars()
            var gateShown = false
            if (BuildConfig.SHOW_LANG_GATE && !Prefs.langChosen) {
                // اگر صفحهٔ انتخاب زبان خطا داد، به‌جای کرش به صفحهٔ اصلی برمی‌گردیم.
                gateShown = try { showLangGate(); true } catch (e: Throwable) { CrashGuard.save(this, e); false }
            }
            if (!gateShown) {
                buildShell()
                go(intent?.getStringExtra("page") ?: "home")
                refreshOnline()
                maybeShowIntro()
            }
        } catch (e: Throwable) {
            CrashGuard.save(this, e)
            // آخرین تلاش: اگر قاب اصلی ساخته نشده، یک‌بار بسازش تا برنامه بی‌صفحه نماند.
            if (!shellBuilt) try { buildShell(); go("home") } catch (_: Throwable) {}
        }
        // نمایش گزارش خطای اجرای قبلی (اگر بود) روی صفحهٔ فعلی — بدون قطع‌کردن ساختِ برنامه.
        try { CrashGuard.showIfAny(this) } catch (_: Throwable) {}
    }

    /** صفحهٔ اولِ انتخاب زبان (فقط نسخهٔ جهانی): زبان رابط + زبان مقصد ترجمه. نسخهٔ سادهٔ امن. */
    private fun showLangGate() {
        var ui = Prefs.uiLang
        var target = if (Prefs.targetLang.isNotEmpty()) Prefs.targetLang else ui

        val sv = ScrollView(this)
        sv.setBackgroundColor(Ui.BG)
        sv.isFillViewport = true
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val pad = Ui.dp(this, 24f)
        box.setPadding(pad, Ui.dp(this, 56f), pad, pad)
        sv.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        box.addView(Ui.text(this, "Coverlate", 26f, Ui.TEXT, true))
        box.addView(Ui.text(this, "Welcome · خوش آمدید", 14f, Ui.MUTED))
        box.addView(Ui.gap(this, 24f))

        // زبان رابط کاربری — از میان زبان‌های پشتیبانی‌شده
        box.addView(Ui.text(this, "App language · زبان برنامه", 13f, Ui.MUTED))
        val uiBtn = Ui.ghost(this, L.uiLangName(ui), Ui.ACCENT2) {}
        uiBtn.setOnClickListener {
            pickUiLangDialog(ui) { ui = it; uiBtn.text = L.uiLangName(it) }
        }
        box.addView(Ui.full(uiBtn, 8f))

        // زبان مقصد ترجمه
        box.addView(Ui.text(this, "Translate into · ترجمه به", 13f, Ui.MUTED).also { it.setPadding(0, Ui.dp(this, 12f), 0, 0) })
        val tgtBtn = Ui.ghost(this, Langs.name(target), Ui.ACCENT2) {}
        tgtBtn.setOnClickListener { langPicker("Translate into · ترجمه به", target, false) { target = it; tgtBtn.text = Langs.name(it) } }
        box.addView(Ui.full(tgtBtn, 8f))

        box.addView(Ui.full(Ui.button(this, "Continue  ·  ادامه", true) {
            // اگر زبانِ رابط نیاز به بستهٔ ترجمه دارد، یک‌بار ساخته می‌شود؛ بعد وارد برنامه می‌شویم.
            ensureUiPack(ui) {
                Prefs.uiLang = ui
                Prefs.targetLang = target
                Prefs.duoTarget = target
                Prefs.langChosen = true
                L.init()
                buildShell()
                go("home")
                refreshOnline()
                maybeShowIntro()
            }
        }, 24f))

        setContentView(sv)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("page")?.let { go(it) }
    }

    override fun onResume() {
        super.onResume()
        if (shellBuilt && ::lm.isInitialized && page == "home") go("home")
    }

    /** اولین باز شدن برنامه: صفحهٔ راهنما را باز می‌کند و با یک پیام از کاربر می‌خواهد آن را بخواند. */
    private fun maybeShowIntro() {
        if (Prefs.seenIntro) return
        Prefs.seenIntro = true
        try { go("help") } catch (_: Throwable) { return }
        window.decorView.postDelayed({
            try {
                AlertDialog.Builder(this)
                    .setTitle(L.sx("لطفاً این صفحه را بخوانید"))
                    .setMessage(L.sx("به برنامه خوش آمدید! لطفاً این راهنما را یک‌بار کامل بخوانید تا بهترین نتیجه را بگیرید.") + "\n\n" +
                            L.sx("نکتهٔ مهم: حالتِ «ترجمهٔ خودکار» باید زبانِ روی صفحه را خودش حدس بزند و همین کار را کندتر می‌کند. برای ترجمهٔ بهتر و سریع‌تر، در «تنظیمات» زبانِ متنِ روی صفحه را خودتان انتخاب کنید."))
                    .setPositiveButton(L.sx("باشه"), null)
                    .show()
            } catch (_: Throwable) {}
        }, 400)
    }

    private fun refreshOnline(after: (() -> Unit)? = null) {
        Thread {
            lm.checkOnline()
            // اگر info تازه از سرور نیامد (اولین اجرا/قطعیِ لحظه‌ای)، چند بار دوباره تلاش کن تا
            // تنظیماتِ سرور (مثل خاموش‌بودن خرید) بدون نیاز به ری‌استارتِ برنامه اعمال شود.
            var tries = 0
            while (!lm.gotInfo && tries < 3) {
                try { Thread.sleep(1500) } catch (_: Throwable) {}
                if (lm.fetchInfo()) runOnUiThread { go(page) }
                tries++
            }
            runOnUiThread {
                go(page)
                after?.invoke()
                val up = lm.updateAvailable()
                if (up != null && Prefs.notifiedVersion != up.optString("version")) {
                    Prefs.notifiedVersion = up.optString("version")
                    toast("نسخه جدید فارسی ساز (${up.optString("version")}) منتشر شد؛ از بخش «درباره» دانلود کنید.")
                }
            }
        }.start()
    }

    // ================================================================ قاب اصلی
    private lateinit var headChip: TextView

    private fun buildShell() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        root.layoutDirection = dir()

        val head = Ui.row(this)
        head.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(if (Ui.dark) 0xFF1B2040.toInt() else 0xFFF7F9FE.toInt(), Ui.PANEL))
        val hp = Ui.dp(this, 14f)
        head.setPadding(hp, hp, hp, hp)
        val logo = TextView(this)
        logo.text = BuildConfig.BUBBLE_GLYPH; logo.textSize = 21f; logo.gravity = Gravity.CENTER
        logo.typeface = Ui.font(this, true); logo.setTextColor(Color.WHITE)
        logo.background = Ui.gradient(Ui.dp(this, 13f).toFloat())
        logo.elevation = Ui.dp(this, 4f).toFloat()
        head.addView(logo, LinearLayout.LayoutParams(Ui.dp(this, 44f), Ui.dp(this, 44f)))
        val tv = LinearLayout(this)
        tv.orientation = LinearLayout.VERTICAL
        tv.setPadding(Ui.dp(this, 12f), 0, Ui.dp(this, 10f), 0)
        val appName = try { packageManager.getApplicationLabel(applicationInfo).toString() } catch (_: Exception) { "Coverlate" }
        tv.addView(Ui.text(this, appName, 19f, Ui.TEXT, true))
        val ver = if (L.rtl) Persian.faDigits(APP_VERSION) else APP_VERSION
        tv.addView(Ui.text(this, "${L.t("tagline")} · ${if (L.rtl) "نسخه " else "v"}$ver", 12f, Ui.MUTED))
        head.addView(Ui.weight(tv, 1f, 0f))
        headChip = Ui.chip(this, "", Ui.MUTED)
        head.addView(headChip)
        root.addView(head)

        content = FrameLayout(this)
        content.setBackgroundColor(Ui.BG)   // پس‌زمینهٔ ثابت تا در هیچ لحظه‌ای (مثل گذار بین صفحه‌ها) پس‌زمینهٔ اشتباه دیده نشود
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val nav = Ui.row(this)
        nav.setBackgroundColor(Ui.PANEL)
        nav.elevation = Ui.dp(this, 8f).toFloat()
        val np = Ui.dp(this, 6f)
        nav.setPadding(np, np, np, np)
        for ((id, icon, label) in pages) {
            if (id == "license" && !Monetize.licenseTab(lm.info)) continue   // تب لایسنس از پنل قابل‌مخفی‌شدن است
            val b = Ui.text(this, "$icon\n${L.t("nav_$id")}", 12f, Ui.MUTED)
            b.gravity = Gravity.CENTER
            b.textAlignment = View.TEXT_ALIGNMENT_CENTER
            b.setLineSpacing(0f, 1.1f)
            b.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 6f))
            b.setOnClickListener { go(id) }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(Ui.dp(this, 3f), 0, Ui.dp(this, 3f), 0)
            nav.addView(b, lp)
            navButtons[id] = b
        }
        root.addView(nav)
        setContentView(root)
        shellBuilt = true
    }

    private fun updateHeadChip() {
        if (!::headChip.isInitialized) return
        val on = CaptureService.running
        headChip.text = if (on) "● ${L.t("on")}" else "○ ${L.t("off")}"
        val col = if (on) Ui.GREEN else Ui.MUTED
        headChip.setTextColor(col)
        headChip.background = Ui.round(Ui.alpha(col, 36), Ui.dp(this, 20f).toFloat(), 1, Ui.alpha(col, 110))
    }

    private fun go(p: String) {
        val prev = page
        page = if (pages.any { it.first == p }) p else "home"
        val r = Ui.dp(this, 12f).toFloat()
        for ((id, b) in navButtons) {
            val on = id == page
            b.setTextColor(if (on) Color.WHITE else Ui.MUTED)
            b.typeface = Ui.font(this, on)
            b.background = Ui.ripple(if (on) Ui.round(Ui.alpha(Ui.ACCENT, 70), r) else null, r)
        }
        updateHeadChip()
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        sv.setBackgroundColor(Ui.BG)   // هر صفحه پس‌زمینهٔ خودش را دارد تا گذار بین صفحه‌ها تمیز باشد
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.layoutDirection = dir()
        val p16 = Ui.dp(this, 16f)
        body.setPadding(p16, p16, p16, p16 * 2)
        sv.addView(body)
        when (page) {
            "home" -> pageHome(body)
            "duo" -> pageDuo(body)
            "history" -> pageHistory(body)
            "license" -> pageLicense(body)
            "help" -> pageHelp(body)
            "about" -> pageAbout(body)
            "settings" -> pageSettings(body)
        }
        // بازسازیِ همان صفحه (مثلاً بعد از تغییر یک تنظیم) نباید انیمیشن/پرشِ سیاه داشته باشد:
        // اول نمای جدید را اضافه می‌کنیم، بعد نمای قبلی را برمی‌داریم تا هیچ لحظه‌ای صفحه خالی/سیاه نشود.
        val samePage = content.childCount > 0 && prev == page
        val old = if (content.childCount > 0) content.getChildAt(0) else null
        content.addView(sv)
        if (old != null) content.removeView(old)
        if (!samePage) {
            sv.alpha = 0f
            sv.translationY = Ui.dp(this, 8f).toFloat()
            sv.animate().alpha(1f).translationY(0f).setDuration(160).start()
        }
        maybeCoach(page)
    }

    // ============================================================ راهنمای پاپ‌آپ اولین‌بار هر بخش
    private val coachText = mapOf(
        "home" to Pair("بخش ترجمه", "از اینجا فارسی ساز را روشن می‌کنید. «روش ترجمه» را انتخاب کنید و «شروع» را بزنید. بعد از دادن اجازه‌ها، حباب «ف» روی صفحه ظاهر می‌شود و در هر بازی یا برنامه‌ای متن‌ها را فارسی می‌کند."),
        "duo" to Pair("ترجمهٔ دوطرفه", "اینجا خودتان متن تایپ می‌کنید و به هر زبانی ترجمه می‌شود (مثلاً فارسی به انگلیسی برای چت). زبان مبدأ و مقصد را انتخاب کنید، «ترجمه» را بزنید و با دکمهٔ کپی متن را بردارید. این بخش از روی حباب، بیرون از برنامه هم در دسترس است."),
        "history" to Pair("بخش تاریخچه", "هر متنی که ترجمه شده اینجا ذخیره می‌شود. برای کپی کردن هر ترجمه، انگشت‌تان را روی آن نگه دارید."),
        "license" to Pair("بخش لایسنس", "۷ روز اول رایگان است. «کد دستگاه» را برای پشتیبانی بفرستید تا لایسنس بگیرید، بعد کد لایسنس را همین‌جا وارد و «فعال‌سازی» را بزنید. هر لایسنس روی ۲ دستگاه کار می‌کند."),
        "help" to Pair("راهنمای کامل", "توضیح کامل همه بخش‌ها و مخصوصاً «حباب شناور» اینجاست. اگر جایی گیر کردید، اول این بخش را بخوانید."),
        "about" to Pair("درباره و به‌روزرسانی", "نسخه برنامه و آخرین اخبار سازنده اینجاست. هر وقت نسخه جدیدی منتشر شود، از همین‌جا خبردار می‌شوید."),
        "settings" to Pair("بخش تنظیمات", "روش ترجمه، موتور ترجمه، اندازه و پررنگی متن ترجمه، واژه‌نامه و پاک کردن حافظه را اینجا تنظیم کنید. همه تغییرات خودکار ذخیره می‌شوند."),
    )

    /** بار اولی که کاربر وارد هر بخش می‌شود، توضیح آن بخش به‌صورت یک کارت پاپ‌آپ بالای صفحه ظاهر می‌شود. */
    private fun maybeCoach(id: String) {
        if (Prefs.coachSeen(id)) return
        val (title, text) = coachText[id] ?: return
        Prefs.markCoach(id)
        content.postDelayed({ showCoach(title, text) }, 260)
    }

    private var coachView: View? = null

    private fun showCoach(title: String, text: String) {
        dismissCoach()
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.layoutDirection = dir()
        card.background = Ui.round(0xF21B2450.toInt(), Ui.dp(this, 16f).toFloat(), Ui.dp(this, 2f), Ui.OACCENT2)
        card.elevation = Ui.dp(this, 12f).toFloat()
        val p = Ui.dp(this, 16f)
        card.setPadding(p, Ui.dp(this, 12f), p, Ui.dp(this, 12f))
        val head = Ui.row(this)
        head.addView(Ui.text(this, "💡  ${L.sx(title)}", 16f, Color.WHITE, true).also { Ui.weight(it, 1f, 0f) })
        val x = Ui.text(this, "✕", 16f, Ui.MUTED)
        x.setPadding(Ui.dp(this, 8f), 0, 0, 0)
        x.setOnClickListener { dismissCoach() }
        head.addView(x)
        card.addView(head)
        card.addView(Ui.text(this, text, 13.5f, 0xFFD7DcF5.toInt()).also { it.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 4f)) })
        val hint = Ui.text(this, "راهنمای کامل در بخش «راهنما» است.", 11.5f, Ui.OMUTED)
        card.addView(hint)
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        val m = Ui.dp(this, 10f); lp.setMargins(m, m, m, m)
        content.addView(card, lp)
        coachView = card
        card.alpha = 0f; card.translationY = -Ui.dp(this, 16f).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(220).start()
        card.setOnClickListener { dismissCoach() }
        content.postDelayed({ if (coachView === card) dismissCoach() }, 9000)
    }

    private fun dismissCoach() {
        val c = coachView ?: return
        coachView = null
        c.animate().alpha(0f).translationY(-Ui.dp(this, 16f).toFloat()).setDuration(180)
            .withEndAction { try { content.removeView(c) } catch (_: Exception) {} }.start()
    }

    private fun title(body: LinearLayout, t: String, sub: String) {
        body.addView(Ui.text(this, t, 23f, Ui.TEXT, true))
        body.addView(Ui.text(this, sub, 13f, Ui.MUTED))
        body.addView(Ui.gap(this, 16f))
    }

    // ================================================================ ترجمه
    private fun pageHome(body: LinearLayout) {
        val st = lm.state
        // تا وقتی وضعیت از سرور تازه نیامده (اولین اجرا)، هیچ پیام لایسنس/خریدی نشان نده (رفعِ چشمکِ اولِ نصب).
        if (lm.gotInfo && !Monetize.freeMode(lm.info) && (!st.allowed || st.status == "trial" || st.daysLeft <= 5)) {
            val b = Ui.card(this)
            // اجزای ترجمه‌پذیر جدا با L.sx ترجمه می‌شوند تا در نسخهٔ جهانی هم درست دربیایند
            val days = Persian.faDigits(st.daysLeft.toString())
            val (msg, color) = when {
                st.status == "trial" -> Pair("🎁 " + L.sx("دوره آزمایشی رایگان:") + " " + days + " " + L.sx("روز مانده"), Ui.AMBER)
                st.status == "licensed" -> Pair("⏳ " + L.sx("لایسنس شما") + " " + days + " " + L.sx("روز دیگر تمام می‌شود."), Ui.AMBER)
                st.status == "unknown" -> Pair(L.sx("در حال بررسی لایسنس…"), Ui.MUTED)
                else -> Pair("🔒 " + L.sx(statusTitle(st.status)) + (if (st.message.isNotEmpty()) " — " + SrvText.now(st.message) else ""), Ui.RED)
            }
            b.background = Ui.round(Ui.alpha(color, 26), Ui.dp(this, 16f).toFloat(), 1, Ui.alpha(color, 100))
            b.addView(Ui.text(this, msg, 14f, color, true))
            if (!st.allowed && st.status != "unknown")
                b.addView(Ui.full(Ui.button(this, "🔑 تهیه لایسنس", true) { go("license") }))
            else if (st.status == "trial" || st.status == "licensed") b.setOnClickListener { go("license") }
            body.addView(b)
        }

        // تبلیغ (از پنل مدیریت سرور)
        try { Ads.banner(this, lm.info, st.status)?.let { body.addView(it) } } catch (_: Exception) {}
        // پاپ‌آپِ تبلیغِ تمام‌پنجره (یک‌بار در هر اجرا) — هم‌تراز نسخهٔ ویندوز: متن/عکس/ویدیو + عکسِ تمام‌صفحه
        try { AdOverlay.maybeShow(this, lm.info, st.status) } catch (_: Exception) {}

        // کارت اصلی
        val hero = Ui.card(this)
        val on = CaptureService.running
        val heroColors = if (Ui.dark)
            (if (on) intArrayOf(0xFF123A35.toInt(), 0xFF16213E.toInt()) else intArrayOf(0xFF26205A.toInt(), 0xFF16213E.toInt()))
        else
            (if (on) intArrayOf(0xFFDDF3EC.toInt(), 0xFFEAF0FF.toInt()) else intArrayOf(0xFFE7E2FF.toInt(), 0xFFEAF1FF.toInt()))
        hero.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR, heroColors
        ).also { it.cornerRadius = Ui.dp(this, 20f).toFloat(); it.setStroke(1, if (on) 0x802FBF71.toInt() else 0x806B7CFF.toInt()) }
        val hp = Ui.dp(this, 20f)
        hero.setPadding(hp, hp, hp, hp)
        if (on) {
            hero.addView(Ui.chip(this, L.t("home_on_chip"), Ui.GREEN))
            hero.addView(Ui.gap(this, 10f))
            hero.addView(Ui.text(this, L.t("home_ready"), 22f, Ui.TEXT, true))
            hero.addView(Ui.text(this, if (Prefs.mode == "auto") L.t("home_auto_hint") else L.t("home_tap_hint"), 14f, Ui.MUTED))
            hero.addView(Ui.full(Ui.ghost(this, L.t("home_turnoff"), Ui.RED) {
                startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
                window.decorView.postDelayed({ go("home") }, 300)
            }, 16f))
        } else {
            hero.addView(Ui.text(this, L.t("home_title_off"), 22f, Ui.TEXT, true))
            hero.addView(Ui.text(this, L.t("home_sub_off"), 14f, Ui.MUTED))
            hero.addView(Ui.gap(this, 14f))
            hero.addView(Ui.text(this, L.t("home_mode"), 13f, Ui.MUTED, true))
            hero.addView(modeCard("tap", "👆", L.t("mode_tap_t"), L.t("mode_tap_s")))
            hero.addView(modeCard("auto", "⟳", L.t("mode_auto_t"), L.t("mode_auto_s")))
            hero.addView(Ui.full(Ui.button(this, L.t("home_start"), true) { startFlow() }, 16f))
        }
        body.addView(hero)

        val g = Ui.card(this)
        g.addView(Ui.section(this, "💡", L.t("quick_guide")))
        g.addView(Ui.gap(this, 8f))
        val nums = if (L.rtl) listOf("۱", "۲", "۳", "۴") else listOf("1", "2", "3", "4")
        val steps = nums.zip(listOf(L.t("step1"), L.t("step2"), L.t("step3"), L.t("step4")))
        for ((n, s) in steps) {
            val r = Ui.row(this)
            val num = Ui.text(this, n, 13f, Ui.ACCENT2, true)
            num.gravity = Gravity.CENTER; num.textAlignment = View.TEXT_ALIGNMENT_CENTER
            num.background = Ui.round(Ui.alpha(Ui.ACCENT, 40), Ui.dp(this, 14f).toFloat())
            r.addView(num, LinearLayout.LayoutParams(Ui.dp(this, 28f), Ui.dp(this, 28f)))
            val t = Ui.text(this, s, 14f, Ui.MUTED)
            t.setPadding(Ui.dp(this, 10f), 0, 0, 0)
            r.addView(Ui.weight(t, 1f, 0f))
            g.addView(Ui.full(r, 8f))
        }
        g.addView(Ui.full(Ui.ghost(this, L.t("full_guide"), Ui.ACCENT2) { go("help") }, 10f))
        body.addView(g)

        val perm = Ui.card(this)
        perm.addView(Ui.section(this, "🛡", L.t("perms")))
        val ov = Settings.canDrawOverlays(this)
        perm.addView(Ui.full(Ui.text(this, if (ov) L.t("perm_overlay_on") else L.t("perm_overlay_off"), 14f, if (ov) Ui.GREEN else Ui.AMBER), 10f))
        perm.addView(Ui.text(this, L.t("perm_capture"), 13f, Ui.MUTED))
        body.addView(perm)
    }

    private fun modeCard(id: String, icon: String, t: String, sub: String): View {
        val sel = Prefs.mode == id
        val r = Ui.row(this)
        val rad = Ui.dp(this, 14f).toFloat()
        val unselBg = if (Ui.dark) 0x33000000 else 0x0F1A1F33
        r.background = Ui.ripple(Ui.round(if (sel) Ui.alpha(Ui.ACCENT, 46) else unselBg, rad, if (sel) Ui.dp(this, 2f) else 1,
            if (sel) Ui.ACCENT2 else Ui.BORDER), rad)
        val p = Ui.dp(this, 12f)
        r.setPadding(p, p, p, p)
        val ic = Ui.text(this, icon, 18f, if (sel) Color.WHITE else Ui.MUTED, true)
        ic.gravity = Gravity.CENTER; ic.textAlignment = View.TEXT_ALIGNMENT_CENTER
        ic.background = if (sel) Ui.gradient(Ui.dp(this, 12f).toFloat()) else Ui.round(if (Ui.dark) 0xFF262C47.toInt() else 0xFFE6EAF6.toInt(), Ui.dp(this, 12f).toFloat())
        r.addView(ic, LinearLayout.LayoutParams(Ui.dp(this, 42f), Ui.dp(this, 42f)))
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(this, 12f), 0, Ui.dp(this, 8f), 0)
        col.addView(Ui.text(this, t, 15f, Ui.TEXT, true))
        col.addView(Ui.text(this, sub, 12.5f, Ui.MUTED))
        r.addView(Ui.weight(col, 1f, 0f))
        r.addView(Ui.text(this, if (sel) "◉" else "○", 18f, if (sel) Ui.ACCENT2 else Ui.MUTED))
        r.setOnClickListener { Prefs.mode = id; go("home") }
        return Ui.full(r, 10f)
    }

    private fun startFlow() {
        val st = lm.state
        val free = Monetize.freeMode(lm.info)
        if (!free && (st.status == "unknown" || st.status == "need_online")) {
            toast("در حال بررسی لایسنس…")
            refreshOnline { if (lm.state.allowed) startFlow() else go("license") }
            return
        }
        if (!free && !st.allowed) { toast(statusTitle(st.status)); go("license"); return }
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle(L.sx("اجازه نمایش روی برنامه‌ها"))
                .setMessage(L.sx("برای اینکه ترجمه روی بازی نمایش داده شود، در صفحه بعد «فارسی ساز» را پیدا و گزینه را روشن کنید، بعد به برنامه برگردید."))
                .setPositiveButton(L.sx("باشه")) { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
                .setNegativeButton(L.sx("انصراف"), null)
                .show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        if (!Prefs.seenGuide) {
            Prefs.seenGuide = true
            AlertDialog.Builder(this)
                .setTitle(L.sx("طرز کار حباب «ف»"))
                .setMessage(
                    L.sx("بعد از این، حباب گرد «ف» روی صفحه ظاهر می‌شود و همیشه روی همه برنامه‌ها می‌ماند:\n\n") +
                    L.sx("•‌ یک‌بار زدن روی حباب: صفحه ترجمه می‌شود (یا در حالت خودکار، روشن/خاموش می‌شود).\n") +
                    L.sx("•‌ نگه داشتن حباب: منو باز می‌شود — ترجمه یک قسمت، ترجمه زنده دیالوگ و خاموش کردن.\n") +
                    L.sx("•‌ کشیدن حباب: جابه‌جا می‌شود و به لبه می‌چسبد.\n\n") +
                    L.sx("در پنجره بعد «کل صفحه / Entire screen» را انتخاب و «شروع» را بزنید.\n\n") +
                    L.sx("تصویر صفحه فقط روی خود گوشی خوانده می‌شود و فقط متن برای ترجمه فرستاده می‌شود.\n") +
                    L.sx("توضیح کامل‌تر در بخش «راهنما» هست.")
                )
                .setPositiveButton(L.sx("ادامه")) { _, _ ->
                    @Suppress("DEPRECATION") startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
                }
                .setNegativeButton(L.sx("راهنما"), { _, _ -> go("help") })
                .show()
            return
        }
        @Suppress("DEPRECATION") startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION") startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) { toast("بدون اجازه ضبط صفحه، ترجمه ممکن نیست."); return }
        val i = Intent(this, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_DATA, data)
            .putExtra(CaptureService.EXTRA_AUTO, Prefs.mode == "auto")
        startForegroundService(i)
        toast(if (Prefs.mode == "auto") "ترجمه خودکار روشن شد؛ حالا بازی را باز کنید." else "حباب «ف» روشن شد؛ حالا بازی را باز کنید.")
        window.decorView.postDelayed({ moveTaskToBack(true) }, 600)
    }

    // ================================================================ تاریخچه
    private fun pageHistory(body: LinearLayout) {
        title(body, "تاریخچه", "آخرین متن‌هایی که ترجمه شده‌اند · برای کپی، انگشت را روی هر مورد نگه دارید")
        val items = Db.get(this).history()
        if (items.isEmpty()) {
            val c = Ui.card(this)
            c.gravity = Gravity.CENTER_HORIZONTAL
            val big = Ui.text(this, "☰", 34f, Ui.alpha(Ui.MUTED, 150))
            big.textAlignment = View.TEXT_ALIGNMENT_CENTER
            c.addView(big)
            val t = Ui.text(this, "هنوز چیزی ترجمه نشده.\nبعد از اولین ترجمه، متن‌ها اینجا می‌آیند.", 14f, Ui.MUTED)
            t.textAlignment = View.TEXT_ALIGNMENT_CENTER
            c.addView(t)
            body.addView(c)
            return
        }
        for ((_, src, dst) in items) {
            val c = Ui.card(this)
            val en = Ui.text(this, src, 13f, Ui.MUTED)
            en.textDirection = View.TEXT_DIRECTION_LTR
            en.textAlignment = View.TEXT_ALIGNMENT_TEXT_START
            c.addView(en)
            c.addView(Ui.gap(this, 4f))
            c.addView(Ui.text(this, Persian.display(dst), 15f))
            c.background = Ui.ripple(Ui.round(Ui.CARD, Ui.dp(this, 18f).toFloat(), 1, Ui.BORDER), Ui.dp(this, 18f).toFloat())
            c.setOnLongClickListener { copy(dst); true }
            body.addView(c)
        }
    }

    // ================================================================ ترجمهٔ دوطرفه
    private val duoTranslator by lazy { Translator(this) }

    private fun pageDuo(body: LinearLayout) {
        title(body, "ترجمهٔ دوطرفه", "هر متنی را تایپ کنید و به زبان دلخواه ترجمه بگیرید · برای چت با زبان‌های دیگر")

        val card = Ui.card(this)

        // نوار انتخاب زبان: مبدأ ⇄ مقصد
        val bar = Ui.row(this)
        val srcBtn = Ui.ghost(this, Langs.name(Prefs.duoSource), Ui.ACCENT2) {}
        val dstBtn = Ui.ghost(this, Langs.name(Prefs.duoTarget), Ui.ACCENT2) {}
        val swap = Ui.text(this, "⇄", 20f, Ui.ACCENT2, true)
        swap.gravity = Gravity.CENTER; swap.textAlignment = View.TEXT_ALIGNMENT_CENTER
        srcBtn.setOnClickListener {
            langPicker("زبان مبدأ", Prefs.duoSource, true) { Prefs.duoSource = it; srcBtn.text = Langs.name(it) }
        }
        dstBtn.setOnClickListener {
            langPicker("زبان مقصد", Prefs.duoTarget, false) { Prefs.duoTarget = it; dstBtn.text = Langs.name(it) }
        }
        swap.setOnClickListener {
            if (Prefs.duoSource != "auto") {
                val s = Prefs.duoSource; Prefs.duoSource = Prefs.duoTarget; Prefs.duoTarget = s
                srcBtn.text = Langs.name(Prefs.duoSource); dstBtn.text = Langs.name(Prefs.duoTarget)
            } else toast("مبدأ روی «تشخیص خودکار» است؛ برای جابه‌جایی یک زبان مشخص انتخاب کنید.")
        }
        bar.addView(Ui.weight(srcBtn, 1f))
        bar.addView(swap, LinearLayout.LayoutParams(Ui.dp(this, 40f), ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(Ui.weight(dstBtn, 1f))
        card.addView(Ui.full(bar, 0f))

        // کادر ورودی
        val input = EditText(this)
        input.setText("")
        input.hint = L.sx("متن را اینجا بنویسید…")
        input.setTextColor(Ui.TEXT); input.setHintTextColor(Ui.MUTED)
        input.minLines = 3; input.gravity = Gravity.TOP or Gravity.START
        input.typeface = Ui.font(this)
        input.background = Ui.round(Ui.INPUT, Ui.dp(this, 12f).toFloat(), 1, Ui.BORDER)
        val ip = Ui.dp(this, 12f); input.setPadding(ip, ip, ip, ip)
        card.addView(Ui.full(input, 12f))

        // خروجی
        val outWrap = FrameLayout(this)
        outWrap.background = Ui.round(Ui.INSET, Ui.dp(this, 12f).toFloat(), 1, Ui.alpha(Ui.ACCENT, 90))
        outWrap.setPadding(ip, ip, ip, ip)
        val out = Ui.text(this, "ترجمه اینجا نمایش داده می‌شود.", 16f, Ui.MUTED)
        out.setTextIsSelectable(true)
        outWrap.addView(out)

        val copyBtn = Ui.ghost(this, "📋 کپی", Ui.ACCENT2) { copy(out.text.toString()) }
        copyBtn.visibility = View.GONE

        fun showResult(text: String, targetCode: String) {
            val rtl = Langs.isRtl(targetCode)
            out.text = if (rtl) Persian.display(text) else text
            out.setTextColor(Color.WHITE)
            out.textDirection = if (rtl) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
            out.textAlignment = View.TEXT_ALIGNMENT_TEXT_START
            copyBtn.visibility = View.VISIBLE
        }

        val goBtn = Ui.button(this, "ترجمه", true) {
            val text = input.text.toString().trim()
            if (text.isEmpty()) { toast("اول متنی بنویسید."); return@button }
            val src = Prefs.duoSource; val dst = Prefs.duoTarget
            out.text = L.sx("در حال ترجمه…"); out.setTextColor(Ui.MUTED); copyBtn.visibility = View.GONE
            Thread {
                val res = try { duoTranslator.translateText(text, src, dst) }
                catch (e: Exception) { "⚠ " + Translator.short(e) }
                runOnUiThread {
                    if (res.startsWith("⚠")) { out.text = res; out.setTextColor(Ui.AMBER) }
                    else showResult(res, dst)
                }
            }.start()
        }
        card.addView(Ui.full(goBtn, 12f))
        card.addView(Ui.full(outWrap, 12f))
        card.addView(Ui.full(copyBtn, 8f))
        body.addView(card)

        val tip = Ui.card(this)
        tip.addView(Ui.section(this, "💬", "برای چت با زبان‌های دیگر", "همین بخش از روی حباب شناور، بیرون از برنامه هم باز می‌شود؛ وقتی در تلگرام یا هر برنامه‌ای هستید، منوی حباب → «ترجمهٔ دوطرفه» را بزنید، متن را بنویسید، کپی کنید و در چت بچسبانید."))
        body.addView(tip)
    }

    /** انتخابگر زبان با جست‌وجو (برای مبدأ/مقصد) */
    private fun langPicker(title: String, current: String, includeAuto: Boolean, onPick: (String) -> Unit) {
        val list = if (includeAuto) Langs.sources else Langs.targets
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.layoutDirection = dir()
        val search = EditText(this)
        search.hint = L.sx("جست‌وجوی زبان…")
        search.setTextColor(Ui.TEXT); search.setHintTextColor(Ui.MUTED)
        search.typeface = Ui.font(this)
        search.background = Ui.round(Ui.INPUT, Ui.dp(this, 10f).toFloat(), 1, Ui.BORDER)
        val sp = Ui.dp(this, 10f); search.setPadding(sp, sp, sp, sp)
        content.addView(search)
        val sv = ScrollView(this); Ui.styleScroll(sv)
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.layoutDirection = dir()
        sv.addView(col)
        content.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 340f)).also { it.topMargin = Ui.dp(this, 10f) })

        val dialog = panelDialog(title, content)

        fun render(q: String) {
            col.removeAllViews()
            val query = q.trim()
            for (lg in list) {
                if (query.isNotEmpty() && !lg.name.contains(query) && !L.sx(lg.name).contains(query, true) && !lg.code.contains(query, true)) continue
                val on = lg.code == current
                val row = Ui.text(this, lg.name, 15f, if (on) Color.WHITE else Ui.TEXT, on)
                val rp = Ui.dp(this, 12f); row.setPadding(rp, rp, rp, rp)
                row.background = Ui.ripple(if (on) Ui.round(Ui.alpha(Ui.ACCENT, 50), Ui.dp(this, 10f).toFloat()) else null, Ui.dp(this, 10f).toFloat())
                row.setOnClickListener { onPick(lg.code); dialog.dismiss() }
                col.addView(Ui.full(row, 4f))
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { render(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        render("")
        dialog.show()
    }

    // ================================================================ راهنما
    /** یک بند راهنما: عنوان + متن، داخل یک کارت */
    private fun helpCard(body: LinearLayout, icon: String, title: String, vararg lines: String) {
        val c = Ui.card(this)
        c.addView(Ui.section(this, icon, title))
        c.addView(Ui.gap(this, 6f))
        for (ln in lines) {
            if (ln.isEmpty()) { c.addView(Ui.gap(this, 6f)); continue }
            val bullet = ln.startsWith("•") || ln.startsWith("۱") || ln.startsWith("۲") || ln.startsWith("۳") ||
                ln.startsWith("۴") || ln.startsWith("۵")
            val t = Ui.text(this, ln, 13.5f, if (bullet) Ui.MUTED else Ui.TEXT)
            t.setPadding(0, Ui.dp(this, 3f), 0, Ui.dp(this, 3f))
            c.addView(t)
        }
        body.addView(c)
    }

    private fun pageHelp(body: LinearLayout) {
        title(body, "راهنمای کامل", "همه‌چیز درباره فارسی ساز و طرز کار حباب")

        // نکتهٔ مهم و برجسته: حالت خودکار کندتر است؛ انتخاب دستیِ زبان، ترجمه را بهتر و سریع‌تر می‌کند.
        val tip = Ui.card(this)
        tip.background = Ui.round(Ui.alpha(Ui.AMBER, 28), Ui.dp(this, 18f).toFloat(), Ui.dp(this, 2f), Ui.AMBER)
        tip.addView(Ui.text(this, "⚡ برای ترجمهٔ بهتر و سریع‌تر", 16f, Ui.AMBER, true))
        tip.addView(Ui.text(this, "حالتِ «ترجمهٔ خودکار» باید زبانِ روی صفحه را خودش حدس بزند و همین کار را کندتر و گاهی کم‌دقت‌تر می‌کند. برای نتیجهٔ بهتر، در «تنظیمات» زبانِ متنِ روی صفحه را خودتان انتخاب کنید.", 13.5f, Ui.TEXT))
        body.addView(tip)

        helpCard(body, "▶", "فارسی ساز چه می‌کند؟",
            "متن انگلیسی هر بازی یا برنامه‌ای را همان‌جا روی صفحه، فارسی نشان می‌دهد.",
            "کار در سه مرحله انجام می‌شود: از صفحه عکس گرفته می‌شود، متن روی خود گوشی خوانده می‌شود (بدون اینترنت)، بعد فقط متن برای ترجمه فرستاده و ترجمه‌اش روی صفحه نشان داده می‌شود.",
            "برای شروع نیازی به روت کردن گوشی نیست.")

        helpCard(body, "🚀", "شروع کار",
            "۱. در بخش «ترجمه»، روش ترجمه را انتخاب کنید و دکمه «شروع فارسی ساز» را بزنید.",
            "۲. اجازه «نمایش روی برنامه‌های دیگر» را بدهید (یک‌بار).",
            "۳. اجازه «ضبط صفحه» را بدهید؛ گزینه «کل صفحه / Entire screen» را انتخاب کنید. این اجازه هر بار پرسیده می‌شود.",
            "۴. حالا حباب «ف» کنار صفحه ظاهر می‌شود. به بازی یا برنامه بروید.")

        helpCard(body, "⇄", "ترجمهٔ دوطرفه (تایپ دستی)",
            "در بخش «دوطرفه» خودتان متن می‌نویسید و به هر زبانی ترجمه می‌گیرید — برعکسِ ترجمهٔ صفحه که فقط می‌خوانَد.",
            "۱. زبان مبدأ و مقصد را انتخاب کنید (مبدأ می‌تواند «تشخیص خودکار» باشد).",
            "۲. متن را بنویسید و «ترجمه» را بزنید.",
            "۳. با دکمهٔ «کپی» متن ترجمه‌شده را بردارید و هر جا خواستید بچسبانید.",
            "برای چت: وقتی در تلگرام یا هر برنامه‌ای هستید، حباب را نگه دارید و «ترجمهٔ دوطرفه» را بزنید؛ همین کادر روی همان برنامه باز می‌شود.")

        helpCard(body, "🗣", "زبان‌ها",
            "زبان مقصدِ ترجمهٔ صفحه را در «تنظیمات → زبان‌های ترجمهٔ صفحه» انتخاب کنید (پیش‌فرض فارسی).",
            "خطِ متنِ روی صفحه (OCR): اگر بازی/برنامه به چینی، ژاپنی، کره‌ای یا هندی است، خط مناسب را از همان‌جا انتخاب کنید. خط لاتین شامل انگلیسی و بیشتر زبان‌های اروپایی است.")

        // --- حباب: توضیح کامل و تکمیلی ---
        val bub = Ui.card(this)
        bub.background = Ui.round(Ui.alpha(Ui.ACCENT, 30), Ui.dp(this, 18f).toFloat(), Ui.dp(this, 2f), Ui.ACCENT2)
        bub.addView(Ui.section(this, BuildConfig.BUBBLE_GLYPH, "حباب شناور «ف» — قلب برنامه"))
        bub.addView(Ui.gap(this, 6f))
        val bl = listOf(
            "حباب «ف» یک دکمه گرد شناور است که همیشه روی همه برنامه‌ها می‌ماند تا هر وقت خواستید ترجمه کنید.",
            "",
            "• یک‌بار زدن روی حباب:",
            "   – در حالت «ترجمه با لمس»: همان لحظه از صفحه عکس گرفته و ترجمه روی متن‌ها نشان داده می‌شود.",
            "   – در حالت «خودکار مداوم»: ترجمه خودکار را روشن یا خاموش می‌کند.",
            "   – اگر ترجمه‌ای روی صفحه باز باشد، با یک‌بار زدن بسته می‌شود.",
            "",
            "• نگه داشتن حباب (لمس طولانی): منوی حباب باز می‌شود با این گزینه‌ها:",
            "   – ترجمه کل صفحه (یک‌بار)",
            "   – ترجمه یک قسمت: با انگشت دور متن کادر می‌کشید تا فقط همان قسمت ترجمه شود.",
            "   – ترجمه زنده دیالوگ: برای زیرنویس و دیالوگ بازی؛ ترجمه در یک پنل جدا و پشت‌سرهم نشان داده می‌شود.",
            "   – ترجمه خودکار مداوم: روشن/خاموش.",
            "   – باز کردن برنامه و خاموش کردن فارسی ساز.",
            "",
            "• کشیدن حباب: با انگشت جابه‌جا می‌شود و به لبه صفحه می‌چسبد تا جلوی دید نباشد.",
            "",
            "• رنگ حباب یعنی:",
            "   – بنفش: آماده.",
            "   – سبز: ترجمه زنده دیالوگ روشن است.",
            "   – سبز پررنگ: ترجمه خودکار مداوم روشن است.",
            "   – «…» یعنی در حال ترجمه است.",
            "",
            "• برای خاموش کردن کامل: روی حباب نگه دارید و «خاموش کردن فارسی ساز» را بزنید، یا از اعلان بالای گوشی «خاموش کردن» را بزنید.",
        )
        for (s in bl) {
            if (s.isEmpty()) { bub.addView(Ui.gap(this, 5f)); continue }
            val t = Ui.text(this, s, 13.5f, if (s.startsWith("•")) Ui.TEXT else Ui.MUTED, s.startsWith("•"))
            t.setPadding(0, Ui.dp(this, 2f), 0, Ui.dp(this, 2f))
            bub.addView(t)
        }
        body.addView(bub)

        helpCard(body, "👆", "دو روش ترجمه",
            "• ترجمه با لمس (پیشنهادی): هر بار خودتان با زدن روی حباب ترجمه می‌گیرید. دقیق‌ترین حالت، بدون چشمک و با کمترین مصرف باتری.",
            "• خودکار مداوم: هر متنی که روی صفحه بیاید خودش ترجمه می‌شود، تا وقتی خاموشش کنید. برای بازی‌هایی که متنشان مدام عوض می‌شود خوب است ولی باتری بیشتری می‌برد.",
            "روش را در بخش «ترجمه» یا «تنظیمات» عوض کنید.")

        helpCard(body, "🌐", "موتور ترجمه",
            "دو موتور Microsoft و Google موجود است (بخش تنظیمات). اگر موتور انتخابی دیر جواب بدهد، موتور دیگر هم هم‌زمان امتحان می‌شود تا سریع‌تر باشد.",
            "متن انگلیسی برای ترجمه به سرور این موتورها فرستاده می‌شود؛ عکس صفحه هیچ‌وقت فرستاده نمی‌شود.")

        helpCard(body, "📖", "واژه‌نامه",
            "اسم شخصیت‌ها و اصطلاحات بازی را در تنظیمات وارد کنید تا همیشه یکسان ترجمه شوند.",
            "هر خط: کلمه انگلیسی = ترجمه. مثلاً: Mana = مانا")

        helpCard(body, "🔑", "لایسنس",
            "۷ روز اول رایگان است. بعد از آن «کد دستگاه» را از بخش لایسنس برای پشتیبانی بفرستید، لایسنس بگیرید و کد را همان‌جا وارد کنید.",
            "هر لایسنس روی ۲ دستگاه (گوشی یا کامپیوتر) کار می‌کند. برای فعال شدن به اینترنت نیاز است.")

        helpCard(body, "🛡", "حریم خصوصی",
            "عکس صفحه فقط روی خود گوشی و توسط خود برنامه خوانده می‌شود و هیچ‌جا فرستاده نمی‌شود.",
            "فقط متن انگلیسی برای ترجمه به سرور موتور ترجمه می‌رود.",
            "ضبط صفحه فقط وقتی فعال است که خودتان فارسی ساز را روشن کرده باشید و هر بار از شما اجازه گرفته می‌شود.")

        helpCard(body, "❓", "مشکل دارید؟",
            "• حباب دیده نمی‌شود: اجازه «نمایش روی برنامه‌های دیگر» را از تنظیمات گوشی بدهید.",
            "• صفحه سیاه ترجمه نمی‌شود: بعضی برنامه‌ها (مثل برنامه‌های بانکی) اجازه عکس گرفتن نمی‌دهند.",
            "• ترجمه دقیق روی متن نیفتاد: در حالت «ترجمه با لمس» معمولاً دقیق‌تر است.",
            "• کند بود: در تنظیمات، «سرعت بررسی صفحه» را کمتر یا موتور ترجمه را عوض کنید.")

        body.addView(Ui.full(Ui.button(this, "▶  رفتن به بخش ترجمه", true) { go("home") }, 6f))
    }

    // ================================================================ لایسنس
    private fun statusTitle(s: String) = when (s) {
        "trial" -> "دوره آزمایشی رایگان"
        "licensed" -> "لایسنس فعال"
        "expired" -> "لایسنس تمام شده"
        "trial_over" -> "دوره آزمایشی تمام شده"
        "blocked" -> "این دستگاه مسدود شده"
        "need_online" -> "نیاز به اتصال اینترنت"
        else -> "در حال بررسی لایسنس…"
    }

    private fun statusColor(s: String) = when (s) {
        "licensed" -> Ui.GREEN; "trial", "need_online" -> Ui.AMBER; "unknown" -> Ui.MUTED; else -> Ui.RED
    }

    // --- پرداختِ خودکار از طریق درگاه (کلاینت فقط لینک را باز می‌کند و وضعیت را می‌پرسد؛
    //     هر درگاهی که در پنل اضافه شود، بدون آپدیت برنامه همین‌جا کار می‌کند) ---
    private fun startPurchase(plan: JSONObject) {
        val provs = lm.providers()
        if (provs.length() == 0) return
        if (provs.length() == 1) { beginPay(plan, provs.getJSONObject(0)); return }
        val names = Array(provs.length()) {
            val p = provs.getJSONObject(it); L.sx(p.optString("title").ifEmpty { p.optString("type") })
        }
        AlertDialog.Builder(this).setTitle(L.sx("روش پرداخت را انتخاب کنید"))
            .setItems(names) { _, w -> beginPay(plan, provs.getJSONObject(w)) }.show()
    }

    private fun beginPay(plan: JSONObject, prov: JSONObject) {
        toast(L.sx("در حال اتصال به درگاه…"))
        Thread {
            val r = lm.payStart(plan.optString("id"), prov.optString("id"))
            runOnUiThread {
                if (r.optBoolean("ok") && r.optString("pay_url").isNotEmpty()) {
                    openUrl(r.optString("pay_url"))
                    pollPayment(r.optString("payment_id"))
                } else toast(r.optString("message").ifEmpty { L.sx("شروع پرداخت ناموفق بود.") })
            }
        }.start()
    }

    private fun pollPayment(pid: String, tries: Int = 0) {
        if (pid.isEmpty()) return
        if (tries == 0) toast(L.sx("بعد از پرداخت، چند لحظه صبر کنید…"))
        window.decorView.postDelayed({
            Thread {
                val s = lm.payStatus(pid)
                runOnUiThread {
                    when (s.optString("status")) {
                        "paid" -> { toast(L.sx("پرداخت موفق بود؛ لایسنس فعال شد 🎉")); refreshOnline { go("license") } }
                        "failed" -> toast(L.sx("پرداخت انجام نشد."))
                        else -> if (tries < 60) pollPayment(pid, tries + 1)
                    }
                }
            }.start()
        }, if (tries == 0) 6000 else 5000)
    }

    private fun pageLicense(body: LinearLayout) {
        val st = lm.state
        val info = lm.info

        // اولین اجرا و هنوز خبری از سرور نداریم → به‌جای نمایش اشتباهِ بخش خرید، «در حال بررسی» نشان بده.
        if (!lm.gotInfo && (st.status == "unknown" || st.status == "need_online")) {
            title(body, "لایسنس", "")
            val lc = Ui.card(this)
            lc.addView(Ui.text(this, "در حال بررسی وضعیت…", 14f, Ui.MUTED))
            body.addView(lc)
            return
        }

        // نسخهٔ رایگان (خرید از پنل خاموش است): بدون دوره آزمایشی و بدون محدودیت.
        if (Monetize.freeMode(info)) {
            title(body, "نسخه", "")
            val fc = Ui.card(this)
            fc.addView(Ui.text(this, "نسخهٔ رایگان", 19f, Ui.GREEN, true))
            fc.addView(Ui.text(this, "استفادهٔ کامل و بدون محدودیت. همهٔ امکانات رایگان در دسترس است.", 13f, Ui.MUTED))
            body.addView(fc)
            return
        }

        title(body, "لایسنس", "۷ روز رایگان · بعد از آن با خرید لایسنس ادامه دهید")

        val c = Ui.card(this)
        val r = Ui.row(this)
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(Ui.text(this, statusTitle(st.status), 19f, statusColor(st.status), true))
        // بخش‌های ثابت از I18N_EN ترجمه می‌شوند؛ تاریخ/کلید جداگانه اضافه می‌شود تا ترجمه خراب نشود.
        when (st.status) {
            "trial" -> col.addView(Ui.text(this, L.sx("اعتبار تا") + " " + Persian.jalali(st.until), 13f, Ui.MUTED))
            "licensed" -> {
                col.addView(Ui.text(this, L.sx("اعتبار تا") + " " + Persian.jalali(st.until), 13f, Ui.MUTED))
                if (st.key.isNotEmpty()) {
                    val kt = Ui.text(this, "", 13f, Ui.MUTED)
                    kt.text = L.sx("لایسنس:") + " " + st.key
                    col.addView(kt)
                }
            }
            "expired", "trial_over" -> col.addView(Ui.text(this, "برای ادامه، از پشتیبانی لایسنس بگیرید و کد آن را پایین وارد کنید.", 13f, Ui.MUTED))
            else -> if (st.message.isNotEmpty()) {
                val mt = Ui.text(this, "", 13f, Ui.MUTED)
                SrvText.load(duoTranslator, st.message) { x -> mt.post { mt.text = x } }
                col.addView(mt)
            }
        }
        r.addView(Ui.weight(col, 1f, 0f))
        if (st.status == "trial" || st.status == "licensed") {
            val d = Ui.text(this, Persian.faDigits(st.daysLeft.toString()) + "\nروز", 22f, statusColor(st.status), true)
            d.textAlignment = View.TEXT_ALIGNMENT_CENTER
            r.addView(d)
        }
        c.addView(r)
        body.addView(c)

        val h = Ui.card(this)
        h.addView(Ui.section(this, "📱", "کد دستگاه شما"))
        h.addView(Ui.text(this, "برای خرید لایسنس این کد را برای پشتیبانی بفرستید. هر لایسنس روی ۲ دستگاه (کامپیوتر یا گوشی) کار می‌کند.", 13f, Ui.MUTED))
        val code = Ui.text(this, lm.hwid, 18f, Ui.ACCENT2, true)
        code.textDirection = View.TEXT_DIRECTION_LTR
        code.textAlignment = View.TEXT_ALIGNMENT_CENTER
        code.typeface = android.graphics.Typeface.MONOSPACE
        code.background = Ui.round(Ui.INPUT, Ui.dp(this, 10f).toFloat(), 1, 0xFF3A4370.toInt())
        val cp = Ui.dp(this, 10f)
        code.setPadding(cp, cp, cp, cp)
        h.addView(Ui.full(code))
        h.addView(Ui.full(Ui.button(this, "⧉ کپی کد دستگاه") { copy(lm.hwid) }))
        body.addView(h)

        if (Monetize.purchaseOn(info)) {
        val pl = Ui.card(this)
        pl.addView(Ui.section(this, "💎", "پلن‌ها"))
        val plans = info.optJSONArray("plans")
        val maxDev = info.optInt("max_devices", 2)
        if (plans != null) for (i in 0 until plans.length()) {
            val p = plans.getJSONObject(i)
            val row = Ui.row(this)
            // پس‌زمینهٔ کارت پلن theme-aware تا عنوان در لایت‌مود خوانا باشد (عکس ۲)
            row.background = Ui.round(if (Ui.dark) 0xFF171C31.toInt() else 0xFFF1F4FA.toInt(), Ui.dp(this, 12f).toFloat(), 1, Ui.BORDER)
            val pp = Ui.dp(this, 12f)
            row.setPadding(pp, pp, pp, pp)
            val left = LinearLayout(this); left.orientation = LinearLayout.VERTICAL
            left.addView(Ui.text(this, p.optString("title"), 15f, Ui.TEXT, true))
            val dd = Ui.text(this, "", 12f, Ui.MUTED)
            dd.text = Persian.faDigits(p.optInt("days").toString()) + " " + L.sx("روز") + " · " +
                    Persian.faDigits(maxDev.toString()) + " " + L.sx("دستگاه")
            left.addView(dd)
            row.addView(Ui.weight(left, 1f, 0f))
            row.addView(Ui.text(this, Persian.money(p.optDouble("price", 0.0), lm.currency), 15f, Ui.ACCENT2, true))
            // دکمهٔ خرید فقط وقتی درگاهِ پرداختی برای این نسخه روشن باشد (پرداختِ خودکار)
            if (lm.providers().length() > 0) {
                val buy = Ui.button(this, L.sx("خرید"), true) { startPurchase(p) }
                buy.minHeight = 0; buy.minimumHeight = 0
                buy.setPadding(Ui.dp(this, 14f), Ui.dp(this, 7f), Ui.dp(this, 14f), Ui.dp(this, 7f))
                row.addView(buy, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.marginStart = Ui.dp(this, 8f) })
            }
            pl.addView(Ui.full(row))
        }
        body.addView(pl)

        // روش‌های پرداخت (از پنل سرور؛ اگر چیزی تنظیم نشده باشد، فقط بخش پشتیبانی پایین می‌ماند)
        val methods = Monetize.payments(info)
        if (methods.isNotEmpty()) {
            val pay = Ui.card(this)
            pay.addView(Ui.section(this, "💳", "خرید اشتراک"))
            for (m in methods) {
                val icon = when (m.type) { "crypto" -> "₿"; "googleplay" -> "▶"; "support" -> "✈"; else -> "💳" }
                val btn = Ui.button(this, "$icon  ${m.title}", m.primary) {
                    when (m.type) {
                        "googleplay" -> {
                            toast("در حال باز کردن Google Play…")
                            Billing(this, lm.hwid) { ok, msg ->
                                runOnUiThread { toast(msg); if (ok) refreshOnline { go("license") } }
                            }.buy(m.product)
                        }
                        "support" -> { copy(lm.hwid); if (m.url.isNotEmpty()) openUrl(m.url) }
                        else -> { copy(lm.hwid); openUrl(Monetize.fillUrl(m.url, lm.hwid)) }   // url | crypto
                    }
                }
                pay.addView(Ui.full(btn))
                if (m.sub.isNotEmpty()) pay.addView(Ui.text(this, m.sub, 12f, Ui.MUTED).also { it.setPadding(0, Ui.dp(this, 4f), 0, Ui.dp(this, 2f)) })
            }
            pay.addView(Ui.text(this, "کد دستگاه شما هنگام پرداخت ثبت می‌شود تا لایسنس روی همین دستگاه فعال شود.", 12f, Ui.MUTED).also { it.setPadding(0, Ui.dp(this, 8f), 0, 0) })
            body.addView(pay)
        }

        val sup = info.optJSONObject("support") ?: JSONObject()
        val s = Ui.card(this)
        s.addView(Ui.section(this, "🛒", "خرید لایسنس"))
        // متن و برچسبِ پشتیبانی از سرور می‌آیند (متنِ آزاد) → روی دستگاه به زبانِ کاربر ترجمه می‌شوند
        val supTv = Ui.text(this, "", 14f, Ui.MUTED)
        s.addView(supTv)
        SrvText.load(duoTranslator, sup.optString("text")) { x -> supTv.post { supTv.text = x } }
        val tg = sup.optString("telegram")
        if (tg.isNotEmpty()) {
            val supLabel = sup.optString("label", "پیام به پشتیبانی")
            val supBtn = Ui.button(this, "✈  " + supLabel, true) {
                copy(lm.hwid)
                openUrl(tg)
            }
            SrvText.load(duoTranslator, supLabel) { x -> supBtn.post { supBtn.text = "✈  " + x } }
            s.addView(Ui.full(supBtn))
        }
        body.addView(s)
        }   // پایان بخش خرید (purchase_on)

        val a = Ui.card(this)
        a.addView(Ui.section(this, "🔑", "فعال‌سازی لایسنس"))
        val key = EditText(this)
        key.hint = "FS-XXXX-XXXX-XXXX-XXXX"
        key.setHintTextColor(Ui.MUTED)
        key.setTextColor(Ui.TEXT)
        key.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        key.textDirection = View.TEXT_DIRECTION_LTR
        key.typeface = android.graphics.Typeface.MONOSPACE
        key.background = Ui.round(Ui.INPUT, Ui.dp(this, 10f).toFloat(), 1, Ui.BORDER)
        key.setPadding(cp, cp, cp, cp)
        a.addView(Ui.full(key))
        a.addView(Ui.full(Ui.button(this, "✓ فعال‌سازی", true) {
            val k = key.text.toString().trim()
            if (k.length < 10) { toast("کد لایسنس را وارد کنید."); return@button }
            toast("در حال فعال‌سازی…")
            Thread {
                val (ok, msg) = lm.activate(k)
                lm.checkOnline()
                runOnUiThread {
                    toast(if (ok) "لایسنس فعال شد 🎉" else msg.ifEmpty { "فعال‌سازی ناموفق بود." })
                    go("license")
                }
            }.start()
        }))
        a.addView(Ui.full(Ui.button(this, "⟳ بررسی دوباره وضعیت") {
            refreshOnline { toast(lm.state.message.ifEmpty { "به‌روز شد." }) }
        }))
        body.addView(a)
    }

    // ================================================================ درباره و به‌روزرسانی
    private fun pageAbout(body: LinearLayout) {
        title(body, "درباره و به‌روزرسانی", L.sx("نسخه فعلی شما:") + " " + Persian.faDigits(APP_VERSION))
        val info = lm.info
        val up = lm.updateAvailable()
        val u = Ui.card(this)
        if (up != null) {
            u.addView(Ui.text(this, "🎉 نسخه جدید آماده است!", 18f, Ui.ACCENT2, true))
            u.addView(Ui.text(this, L.sx("نسخهٔ جدید منتشر شد؛ نسخهٔ فعلی شما:") + " " + Persian.faDigits(APP_VERSION) + " → " + Persian.faDigits(up.optString("version")), 13f, Ui.MUTED))
            val notes = up.optString("notes")
            if (notes.isNotEmpty()) {
                val n = Ui.text(this, Persian.display(notes), 14f)
                n.background = Ui.round(Ui.INPUT, Ui.dp(this, 10f).toFloat(), 1, Ui.BORDER)
                val p = Ui.dp(this, 10f); n.setPadding(p, p, p, p)
                u.addView(Ui.full(n))
            }
            u.addView(Ui.full(Ui.button(this, "⬇  دانلود و نصب نسخه جدید", true) { downloadUpdate(up) }))
        } else {
            u.addView(Ui.text(this, "✓ برنامه شما به‌روز است", 18f, Ui.GREEN, true))
            u.addView(Ui.text(this, "هر وقت نسخه جدید منتشر شود، همین‌جا خبر می‌دهیم.", 13f, Ui.MUTED))
        }
        u.addView(Ui.full(Ui.button(this, "⟳ بررسی نسخه جدید") {
            Thread {
                val ok = lm.fetchInfo()
                runOnUiThread {
                    go("about")
                    if (!ok) toast("اتصال برقرار نشد.") else if (lm.updateAvailable() == null) toast("شما آخرین نسخه را دارید.")
                }
            }.start()
        }))
        body.addView(u)

        val about = info.optJSONObject("about")
        if (about != null && (about.optString("text").isNotEmpty() || about.optString("name").isNotEmpty())) {
            val a = Ui.card(this)
            a.addView(Ui.text(this, about.optString("title", "درباره من").ifEmpty { "درباره من" }, 18f, Ui.TEXT, true))
            if (about.optString("name").isNotEmpty()) a.addView(Ui.text(this, about.optString("name"), 13f, Ui.MUTED))
            a.addView(Ui.gap(this, 6f))
            a.addView(Ui.text(this, Persian.display(about.optString("text")), 14f))
            val links = about.optJSONArray("links")
            if (links != null) for (i in 0 until links.length()) {
                val l = links.getJSONObject(i)
                val url = l.optString("url")
                if (url.isEmpty()) continue
                a.addView(Ui.full(Ui.button(this, "🔗  " + l.optString("label", url)) { openUrl(url) }))
            }
            body.addView(a)
        }
    }

    /**
     * دانلود نسخه جدید به پوشه Downloads با DownloadManager (مکانیزم استاندارد اندروید).
     * برنامه خودش چیزی را silently نصب نمی‌کند: کاربر بعد از پایان دانلود، از اعلان یا از پوشه Downloads
     * روی فایل می‌زند و خود سیستم نصب را با اجازه کاربر انجام می‌دهد. به همین دلیل مجوز REQUEST_INSTALL_PACKAGES لازم نیست.
     */
    private fun downloadUpdate(up: JSONObject) {
        var url = up.optString("url")
        if (url.startsWith("/")) url = SERVER_URL + url
        if (up.optBoolean("external") || !url.lowercase().contains(".apk")) { openUrl(url); return }
        try {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val name = "FarsiSaz-${up.optString("version")}.apk"
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(L.sx("فارسی ساز") + " ${up.optString("version")}")
                .setDescription(L.sx("برای نصب، بعد از پایان دانلود روی این اعلان بزنید."))
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            downloadId = dm.enqueue(req)
            AlertDialog.Builder(this)
                .setTitle(L.sx("دانلود نسخه جدید"))
                .setMessage(L.sx("نسخه جدید در حال دانلود است و در پوشه Downloads ذخیره می‌شود.\n\nبعد از پایان دانلود، از نوار اعلان یا پوشه Downloads روی فایل بزنید تا نصب شود. نصب با اجازه خودتان و توسط خود اندروید انجام می‌شود."))
                .setPositiveButton(L.sx("باشه"), null)
                .show()
        } catch (e: Exception) {
            openUrl(url)
        }
    }

    // ================================================================ زبان متن روی صفحه (OCR)
    /** برچسب دکمهٔ انتخاب زبانِ OCR. */
    private fun ocrSourceLabel(): String {
        val s = Prefs.sourceScript
        return when {
            s == "auto" -> "خودکار (تشخیص زبان)"
            s.startsWith("tess:") -> OcrTess.packName(s.removePrefix("tess:"))
            else -> Langs.scriptName(s)
        }
    }

    /** فهرستِ انتخابِ زبانِ رابط. فقط انتخاب را برمی‌گرداند (اعمال/ساختِ بسته با فراخواننده). */
    private fun pickUiLangDialog(current: String, onPick: (String) -> Unit) {
        val content = LinearLayout(this); content.orientation = LinearLayout.VERTICAL; content.layoutDirection = dir()
        content.addView(Ui.text(this, L.t("lang_internet_note"), 12f, Ui.MUTED))
        val sv = ScrollView(this); Ui.styleScroll(sv)
        val colv = LinearLayout(this); colv.orientation = LinearLayout.VERTICAL; colv.layoutDirection = dir()
        sv.addView(colv)
        content.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 360f)).also { it.topMargin = Ui.dp(this, 10f) })
        val dlg = panelDialog(L.t("gate_ui"), content)
        val rad = Ui.dp(this, 12f).toFloat()
        for ((code, native) in L.uiLangs) {
            val on = code == current
            val row = LinearLayout(this); row.layoutDirection = dir(); row.gravity = Gravity.CENTER_VERTICAL
            val rp = Ui.dp(this, 12f); row.setPadding(rp, rp, rp, rp)
            row.background = Ui.ripple(if (on) Ui.round(Ui.alpha(Ui.ACCENT, 50), rad) else null, rad)
            row.addView(Ui.weight(Ui.text(this, native, 15.5f, if (on) Color.WHITE else Ui.TEXT, on), 1f, 0f))
            when {
                on -> row.addView(Ui.text(this, "✓", 16f, Ui.ACCENT2))
                Prefs.hasUiPack(code) -> row.addView(Ui.text(this, "✓", 13f, Ui.MUTED))   // بستهٔ آماده (آفلاین)
            }
            row.setOnClickListener { dlg.dismiss(); onPick(code) }
            colv.addView(Ui.full(row, 4f))
        }
        dlg.show()
    }

    private fun chooseUiLang() = pickUiLangDialog(Prefs.uiLang) { applyUiLang(it) }

    private fun applyUiLang(code: String) {
        if (code == Prefs.uiLang) return
        ensureUiPack(code) { Prefs.uiLang = code; L.init(); recreate() }
    }

    /** مطمئن می‌شود بستهٔ زبانِ رابط برای این زبان آماده است (en/fa نیاز ندارند)؛ در صورت نیاز یک‌بار می‌سازد. */
    private fun ensureUiPack(code: String, onReady: () -> Unit) {
        if (Prefs.hasUiPack(code)) { onReady(); return }
        val prog = buildPrepDialog(L.uiLangName(code)); prog.show()
        Thread {
            val tr = Translator(this)
            val ok = try {
                val en = L.uiEnglishStrings()
                val tx = tr.translateUi(en, code)
                val map = LinkedHashMap<String, String>(en.size * 2)
                en.forEachIndexed { i, s -> map[s] = tx.getOrNull(i) ?: s }
                Prefs.setUiPack(code, map); true
            } catch (_: Throwable) { false } finally { try { tr.close() } catch (_: Throwable) {} }
            runOnUiThread {
                try { prog.dismiss() } catch (_: Throwable) {}
                if (ok) onReady()
                else toast("ترجمهٔ زبان انجام نشد؛ اینترنت را بررسی کنید.")
            }
        }.start()
    }

    /** دیالوگِ هم‌رنگِ برنامه: پنجرهٔ شفاف + کارتِ گِرد و حاشیه‌دار (بدون مستطیل‌های طوسیِ پیش‌فرضِ اندروید).
     *  withClose=true یک دکمهٔ «بستن» خوش‌فرم هم پایین اضافه می‌کند. */
    private fun panelDialog(titleText: String?, content: View, contentHeightDp: Int? = null, withClose: Boolean = true): AlertDialog {
        val rootc = LinearLayout(this); rootc.orientation = LinearLayout.VERTICAL; rootc.layoutDirection = dir()
        rootc.background = Ui.round(Ui.PANEL, Ui.dp(this, 20f).toFloat(), Ui.dp(this, 1f), Ui.BORDER)
        val p = Ui.dp(this, 18f); rootc.setPadding(p, Ui.dp(this, 16f), p, Ui.dp(this, 12f))
        if (titleText != null) {
            rootc.addView(Ui.text(this, titleText, 17.5f, Ui.TEXT, true))
            rootc.addView(Ui.gap(this, 12f))
        }
        val clp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            contentHeightDp?.let { Ui.dp(this, it.toFloat()) } ?: ViewGroup.LayoutParams.WRAP_CONTENT)
        rootc.addView(content, clp)
        val dlg = AlertDialog.Builder(this).setView(rootc).create()
        if (withClose) {
            rootc.addView(Ui.full(Ui.ghost(this, L.sx("بستن"), Ui.MUTED) { dlg.dismiss() }, 14f))
        }
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        return dlg
    }

    private fun buildPrepDialog(nativeName: String): AlertDialog {
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL; box.layoutDirection = dir()
        val row = Ui.row(this)
        val pb = android.widget.ProgressBar(this)
        val ps = Ui.dp(this, 26f); row.addView(pb, LinearLayout.LayoutParams(ps, ps))
        row.addView(Ui.text(this, "${L.t("preparing_lang")} · $nativeName", 15f, Ui.TEXT, true).also { it.setPaddingRelative(Ui.dp(this, 12f), 0, 0, 0) })
        box.addView(row)
        box.addView(Ui.text(this, L.t("preparing_lang_sub"), 12.5f, Ui.MUTED).also { it.setPadding(0, Ui.dp(this, 12f), 0, 0) })
        return panelDialog(null, box, withClose = false).also { it.setCancelable(false) }
    }

    private val downloadingPacks = HashSet<String>()

    /** انتخابگرِ زبان/خطِ OCR: خط‌های داخلی ML Kit + زبان‌های دانلودیِ Tesseract.
     *  دانلودِ پک همین‌جا با انیمیشن انجام می‌شود و پنجره باز می‌ماند تا کاربر زبان‌های دیگر را هم نصب کند. */
    private fun openOcrPicker() {
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.layoutDirection = dir()
        val pp = Ui.dp(this, 2f); col.setPadding(pp, 0, pp, 0)
        val sv = ScrollView(this); sv.addView(col); Ui.styleScroll(sv)
        val dlg = panelDialog(L.sx("زبان متن روی صفحه"), sv, contentHeightDp = 440)
        dlg.setOnDismissListener { go("settings") }
        val base = (lm.info.optString("tessdata_url", "").takeIf { it.isNotBlank() }) ?: "$SERVER_URL/tess"
        val rad = Ui.dp(this, 14f).toFloat()

        fun sectionLabel(text: String) {
            col.addView(Ui.text(this, text, 12.5f, Ui.ACCENT2, true).also {
                it.setPadding(Ui.dp(this, 4f), Ui.dp(this, 14f), Ui.dp(this, 4f), Ui.dp(this, 6f))
            })
        }

        // یک کارتِ قابل‌انتخاب: ایموجی + عنوان (+ نشان) + زیرنویس، با وضعیت در سمتِ انتها.
        fun optionCard(parent: LinearLayout, emoji: String, title: String, sub: String?, on: Boolean, badge: String?, trailing: View?, onClick: () -> Unit) {
            val card = LinearLayout(this); card.orientation = LinearLayout.HORIZONTAL
            card.gravity = Gravity.CENTER_VERTICAL; card.layoutDirection = dir()
            val cpH = Ui.dp(this, 12f); val cpV = Ui.dp(this, 10f); card.setPadding(cpH, cpV, cpH, cpV)
            card.background = Ui.ripple(Ui.round(if (on) Ui.alpha(Ui.ACCENT, 60) else Ui.CARD, rad, 1, if (on) Ui.ACCENT2 else Ui.BORDER), rad)
            if (emoji.isNotEmpty()) {
                val ic = Ui.text(this, emoji, 17f, Ui.TEXT)
                ic.setPaddingRelative(0, 0, Ui.dp(this, 10f), 0)
                card.addView(ic)
            }
            val txt = LinearLayout(this); txt.orientation = LinearLayout.VERTICAL
            txt.addView(Ui.text(this, title, 15f, if (on) Color.WHITE else Ui.TEXT, true))
            if (badge != null) {
                // نشان در یک خطِ جداگانهٔ افقی، زیرِ عنوان (نه کنارش) تا عمودی/شکسته نشود.
                val bg = Ui.text(this, badge, 10.5f, Ui.GREEN, true)
                bg.maxLines = 1
                val bp = Ui.dp(this, 8f); bg.setPadding(bp, Ui.dp(this, 2f), bp, Ui.dp(this, 2f))
                bg.background = Ui.round(Ui.alpha(Ui.GREEN, 40), Ui.dp(this, 10f).toFloat())
                bg.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = Ui.dp(this, 6f) }
                txt.addView(bg)
            }
            if (sub != null) txt.addView(Ui.text(this, sub, 11.5f, Ui.MUTED).also { it.setPadding(0, Ui.dp(this, 2f), 0, 0); it.setLineSpacing(0f, 1.1f) })
            card.addView(Ui.weight(txt, 1f, 0f))
            if (trailing != null) card.addView(trailing)
            card.setOnClickListener { onClick() }
            parent.addView(Ui.full(card, 6f))
        }

        fun pill(text: String, color: Int): TextView {
            val t = Ui.text(this, text, 12f, color, true)
            val hp = Ui.dp(this, 9f); t.setPadding(hp, Ui.dp(this, 4f), hp, Ui.dp(this, 4f))
            t.background = Ui.round(Ui.alpha(color, 34), Ui.dp(this, 10f).toFloat())
            return t
        }

        // ---- پیشنهادی: خودکار
        sectionLabel(L.sx("پیشنهادی"))
        optionCard(col, "🌐", L.sx("خودکار (تشخیص زبانِ صفحه)"),
            L.sx("زبانِ روی صفحه را خودش پیدا می‌کند."),
            Prefs.sourceScript == "auto", L.sx("پیشنهادی"),
            if (Prefs.sourceScript == "auto") Ui.text(this, "✓", 16f, Ui.ACCENT2) else null
        ) { Prefs.sourceScript = "auto"; dlg.dismiss() }
        col.addView(Ui.text(this, L.sx("حالتِ خودکار کمی کندتر است؛ برای سرعتِ بیشتر، زبان/خطِ متن را مستقیم انتخاب کنید."), 11.5f, Ui.AMBER).also {
            it.setPadding(Ui.dp(this, 6f), Ui.dp(this, 4f), Ui.dp(this, 6f), 0)
        })

        // ---- داخلی (بدون دانلود)
        sectionLabel(L.sx("داخلی (بدون دانلود)"))
        for (s in Langs.scripts) {
            val on = s.id == Prefs.sourceScript
            optionCard(col, "🔤", s.name, null, on, null,
                if (on) Ui.text(this, "✓", 17f, Ui.ACCENT2) else null
            ) { Prefs.sourceScript = s.id; dlg.dismiss() }
        }

        // ---- دانلودی (Tesseract)
        sectionLabel(L.sx("دانلودی"))
        col.addView(Ui.text(this, L.sx("یک‌بار دانلود کنید؛ بعد در حالتِ «خودکار» هم استفاده می‌شود."), 12f, Ui.MUTED).also {
            it.setPadding(Ui.dp(this, 4f), 0, Ui.dp(this, 4f), Ui.dp(this, 4f))
        })

        // لیستِ پک‌ها در یک ظرفِ جدا تا زنده به‌روز شود (بدون بستنِ پنجره).
        val packCol = LinearLayout(this); packCol.orientation = LinearLayout.VERTICAL
        col.addView(packCol)

        fun renderPacks() {
            packCol.removeAllViews()
            for (p in OcrTess.packs) {
                val installed = OcrTess.isInstalled(this, p.code)
                val on = Prefs.sourceScript == "tess:${p.code}"
                val busyDl = p.code in downloadingPacks
                val trailing: View = when {
                    busyDl -> {
                        val rowT = Ui.row(this)
                        val pb = android.widget.ProgressBar(this)
                        val ps = Ui.dp(this, 18f); rowT.addView(pb, LinearLayout.LayoutParams(ps, ps))
                        rowT.addView(Ui.text(this, L.sx("در حال دانلود…"), 12f, Ui.ACCENT2).also { it.setPaddingRelative(Ui.dp(this, 6f), 0, 0, 0) })
                        rowT
                    }
                    on -> Ui.text(this, "✓", 17f, Ui.GREEN)
                    installed -> pill(L.sx("نصب‌شده"), Ui.GREEN)
                    else -> pill("⬇ ${p.mb} MB", Ui.ACCENT2)
                }
                optionCard(packCol, "", L.sx(p.name), null, on, null, trailing) {
                    if (p.code in downloadingPacks) return@optionCard
                    if (OcrTess.isInstalled(this, p.code)) {
                        Prefs.sourceScript = "tess:${p.code}"; renderPacks(); toast(L.sx("انتخاب شد:") + " " + L.sx(p.name))
                    } else {
                        downloadingPacks.add(p.code); renderPacks()
                        Thread {
                            val ok = try { OcrTess.download(this, p.code, base) } catch (_: Throwable) { false }
                            runOnUiThread {
                                downloadingPacks.remove(p.code)
                                if (ok) { Prefs.sourceScript = "tess:${p.code}"; toast(L.sx(p.name) + " " + L.sx("نصب شد ✓")) }
                                else toast(L.sx(p.name) + " — " + L.sx("دانلود نشد؛ اینترنت یا آدرس سرور را بررسی کنید."))
                                renderPacks()
                            }
                        }.start()
                    }
                }
            }
        }
        renderPacks()
        dlg.show()
    }

    // ================================================================ تنظیمات
    private fun pageSettings(body: LinearLayout) {
        title(body, "تنظیمات", "همه تغییرات خودکار ذخیره می‌شوند")

        // تمِ روشن/تیره (هر دو نسخه)
        val th = Ui.card(this)
        th.addView(Ui.section(this, "🎨", "ظاهر برنامه", "تم روشن یا تیره"))
        th.addView(Ui.full(Ui.segmented(this, listOf("dark" to "تیره", "light" to "روشن"),
            if (Prefs.darkMode) "dark" else "light") {
            val d = it == "dark"
            if (d != Prefs.darkMode) {
                // بازسازیِ درجا (بدون recreate) تا چشمک/سیاهی رخ ندهد.
                Prefs.darkMode = d; Ui.applyTheme(); applySystemBars()
                buildShell(); go("settings")
            }
        }, 12f))
        body.addView(th)

        // زبانِ رابطِ برنامه — فقط نسخهٔ جهانی (FarsiServ فارسی می‌ماند).
        if (BuildConfig.EDITION == "global") {
            val lg = Ui.card(this)
            lg.addView(Ui.section(this, "🌐", L.t("gate_ui"), L.t("settings_ui_lang_sub")))
            val langBtn = Ui.ghost(this, L.uiLangName(Prefs.uiLang), Ui.ACCENT2) {}
            langBtn.setOnClickListener { chooseUiLang() }
            lg.addView(Ui.full(langBtn, 12f))
            body.addView(lg)
        }

        val md = Ui.card(this)
        md.addView(Ui.section(this, "⟳", "روش ترجمه", "با زدن روی حباب «ف» کدام اتفاق بیفتد؟"))
        md.addView(Ui.full(Ui.segmented(this, listOf("tap" to "با هر لمس (پیشنهادی)", "auto" to "خودکار مداوم"), Prefs.mode) {
            Prefs.mode = it; go("settings")
        }, 12f))
        md.addView(slider("سرعت بررسی صفحه در حالت خودکار (میلی‌ثانیه؛ کمتر = سریع‌تر، باتری بیشتر)", 400, 2500, Prefs.autoInterval) { Prefs.autoInterval = it })
        body.addView(md)

        val e = Ui.card(this)
        e.addView(Ui.section(this, "🌐", "موتور ترجمه", "اگر موتور انتخابی دیر جواب بدهد، موتور دیگر هم هم‌زمان استفاده می‌شود."))
        e.addView(Ui.full(Ui.segmented(this, listOf("microsoft" to "Microsoft (پیشنهادی)", "google" to "Google"), Prefs.provider) {
            Prefs.provider = it; go("settings")
        }, 12f))
        body.addView(e)

        // زبان‌های ترجمهٔ صفحه
        val lg = Ui.card(this)
        lg.addView(Ui.section(this, "🗣", "زبان‌های ترجمهٔ صفحه", "متن صفحه به چه زبانی ترجمه شود و زبان متنِ روی صفحه چیست."))
        // مقصدِ ترجمه
        lg.addView(Ui.text(this, "به چه زبانی ترجمه کنم؟", 13.5f, Ui.MUTED).also { it.setPadding(0, Ui.dp(this, 12f), 0, Ui.dp(this, 6f)) })
        val tBtn = Ui.ghost(this, Langs.name(Prefs.targetLang), Ui.ACCENT2) {}
        tBtn.setOnClickListener { langPicker("زبان مقصد ترجمهٔ صفحه", Prefs.targetLang, false) { Prefs.targetLang = it; go("settings") } }
        lg.addView(Ui.full(tBtn, 0f))
        // زبانِ متنِ روی صفحه (OCR)
        lg.addView(Ui.text(this, "زبان روی صفحه چیست؟", 13.5f, Ui.MUTED).also { it.setPadding(0, Ui.dp(this, 14f), 0, Ui.dp(this, 6f)) })
        val scBtn = Ui.ghost(this, ocrSourceLabel(), Ui.ACCENT2) {}
        scBtn.setOnClickListener { openOcrPicker() }
        lg.addView(Ui.full(scBtn, 0f))
        lg.addView(Ui.text(this, "حالتِ «خودکار» پیشنهاد می‌شود: زبانِ روی صفحه را خودش تشخیص می‌دهد. خط لاتین (انگلیسی/فرانسه/آلمانی/اسپانیایی…) و چینی/ژاپنی/کره‌ای/هندی داخلی‌اند. برای تجربهٔ بهتر با روسی، عربی، یونانی، تایلندی و… یک‌بار پک آن زبان را از دکمهٔ بالا دانلود کن؛ بعد خودکار استفاده می‌شود.", 12f, Ui.MUTED).also { it.setPadding(0, Ui.dp(this, 10f), 0, 0) })
        lg.addView(Ui.text(this, "حالتِ خودکار کمی کندتر است؛ برای سرعتِ بیشتر، زبان/خطِ متن را مستقیم انتخاب کنید.", 12f, Ui.AMBER).also { it.setPadding(0, Ui.dp(this, 6f), 0, 0) })
        body.addView(lg)

        val v = Ui.card(this)
        v.addView(Ui.section(this, "Aa", "نمایش ترجمه"))
        val sampleShown = L.sx("این یک نمونه از ترجمه است.")
        val preview = Ui.text(this, if (L.rtl) Persian.display(sampleShown) else sampleShown, Prefs.fontSize.toFloat(), Color.WHITE)
        fun paintPreview() {
            val alpha = (Prefs.opacity * 255 / 100).coerceIn(80, 255)
            preview.textSize = Prefs.fontSize.toFloat()
            // جعبهٔ نمونه همیشه مثل خودِ overlay تیره است (متنِ سفید روی پس‌زمینهٔ تیرهٔ شفاف).
            preview.background = Ui.round((alpha shl 24) or 0x10131F, Ui.dp(this, 8f).toFloat(), 1, 0x886B7CFF.toInt())
        }
        val pp = Ui.dp(this, 10f)
        preview.setPadding(pp, pp / 2, pp, pp / 2)
        val pv = FrameLayout(this)
        pv.background = Ui.round(if (Ui.dark) 0xFF3A4A6B.toInt() else 0xFFCED6E8.toInt(), Ui.dp(this, 12f).toFloat())
        pv.setPadding(pp, pp * 2, pp, pp * 2)
        pv.addView(preview)
        paintPreview()
        v.addView(Ui.full(pv, 12f))
        v.addView(slider("اندازه متن", 11, 28, Prefs.fontSize) { Prefs.fontSize = it; paintPreview() })
        v.addView(slider("پررنگی پس‌زمینه ترجمه (٪)", 40, 100, Prefs.opacity) { Prefs.opacity = it; paintPreview() })
        v.addView(slider("فاصله بررسی ترجمه زنده دیالوگ (میلی‌ثانیه)", 300, 2000, Prefs.liveInterval) { Prefs.liveInterval = it })
        // «اعداد فارسی» فقط در نسخهٔ ایران معنی دارد؛ در نسخهٔ جهانی حذف شده.
        if (BuildConfig.EDITION != "global") {
            v.addView(Ui.switchRow(this, "اعداد فارسی", "۱۲۳ به‌جای 123", Prefs.persianDigits) { Prefs.persianDigits = it })
        }
        body.addView(v)

        val g = Ui.card(this)
        g.addView(Ui.section(this, "📖", "واژه‌نامه", "اسم شخصیت‌ها و اصطلاحات بازی را اینجا بنویسید تا همیشه یکسان ترجمه شوند. هر خط: کلمه انگلیسی = ترجمه\nمثلاً: Mana = مانا"))
        val ed = EditText(this)
        ed.setText(Prefs.glossary)
        ed.setTextColor(Ui.TEXT)
        ed.setHintTextColor(Ui.MUTED)
        ed.hint = L.sx("Mana = مانا")
        ed.minLines = 4
        ed.gravity = Gravity.TOP or Gravity.START
        ed.typeface = Ui.font(this)
        ed.background = Ui.round(Ui.INPUT, Ui.dp(this, 10f).toFloat(), 1, Ui.BORDER)
        val p = Ui.dp(this, 10f); ed.setPadding(p, p, p, p)
        g.addView(Ui.full(ed, 12f))
        g.addView(Ui.full(Ui.button(this, "💾 ذخیره واژه‌نامه") {
            Prefs.glossary = ed.text.toString(); Db.get(this).clearAll(); toast("ذخیره شد.")
        }))
        body.addView(g)

        val m = Ui.card(this)
        m.addView(Ui.section(this, "🗂", "حافظه ترجمه", "ترجمه‌های قبلی ذخیره می‌شوند تا دفعه بعد فوری نمایش داده شوند."))
        m.addView(Ui.full(Ui.ghost(this, "🗑 پاک کردن حافظه و تاریخچه", Ui.RED) {
            AlertDialog.Builder(this).setMessage(L.sx("همه ترجمه‌های ذخیره‌شده و تاریخچه پاک شوند؟"))
                .setPositiveButton(L.sx("پاک شود")) { _, _ -> Db.get(this).clearAll(); toast("پاک شد.") }
                .setNegativeButton(L.sx("انصراف"), null).show()
        }, 12f))
        body.addView(m)
    }

    private fun slider(label: String, min: Int, max: Int, value: Int, onChange: (Int) -> Unit): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val lbl = L.sx(label)
        val t = Ui.text(this, "$lbl: ${Persian.faDigits(value.toString())}", 13.5f, Ui.MUTED)
        box.addView(t)
        val sb = SeekBar(this)
        sb.max = max - min
        sb.progress = value - min
        sb.layoutDirection = dir()
        sb.progressTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
        sb.thumbTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT2)
        sb.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF3A4060.toInt())
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                t.text = "$lbl: ${Persian.faDigits((progress + min).toString())}"
                if (fromUser) onChange(progress + min)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        box.addView(sb)
        return Ui.full(box, 10f)
    }

    // ================================================================ ابزار
    private fun copy(s: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("farsisaz", s))
        toast(L.t("copied"))
    }

    private fun openUrl(u: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) { toast("باز کردن لینک ممکن نشد.") }
    }

    private fun toast(s: String) = Toast.makeText(this, L.s(s) ?: s, Toast.LENGTH_LONG).show()

    companion object {
        const val REQ_CAPTURE = 41
        const val REQ_NOTIF = 42
    }
}
