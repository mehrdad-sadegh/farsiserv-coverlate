package ir.farsisaz

/**
 * چندزبانه‌کردن رابط کاربری.
 * - فارسی: متنِ اصلیِ کد (بدون ترجمه).
 * - انگلیسی: از نقشهٔ آمادهٔ I18N_EN (فارسی → انگلیسی).
 * - بقیهٔ زبان‌ها: متنِ انگلیسی یک‌بار روی خود گوشی به آن زبان ترجمه و در Prefs ذخیره می‌شود
 *   (بسته‌ی زبان)؛ پس هر زبانی پشتیبانی می‌شود و بعد از ساختِ بسته، رابط فوری و آفلاین است.
 * اگر ترجمهٔ یک رشته در بستهٔ زبانِ جاری نبود، به انگلیسی برمی‌گردد.
 */
object L {
    @Volatile var lang: String = "en"
    // نقشهٔ انگلیسی → زبانِ جاری (برای زبان‌های غیر از en/fa). با انتخابِ زبان بارگذاری می‌شود.
    @Volatile private var extra: Map<String, String>? = null

    fun init() {
        lang = Prefs.uiLang
        extra = if (lang == "en" || lang == "fa") null else Prefs.uiPack(lang)
    }

    val rtl: Boolean get() = Langs.isRtl(lang)

    /** زبان‌های رابط که کاربر می‌تواند انتخاب کند (نسخهٔ جهانی) — با نامِ بومیِ هر زبان. */
    val uiLangs: List<Pair<String, String>> = listOf(
        "en" to "English",
        "es" to "Español",
        "fr" to "Français",
        "de" to "Deutsch",
        "pt" to "Português",
        "it" to "Italiano",
        "ru" to "Русский",
        "tr" to "Türkçe",
        "ar" to "العربية",
        "zh" to "中文",
        "ja" to "日本語",
        "ko" to "한국어",
        "hi" to "हिन्दी",
        "id" to "Bahasa Indonesia",
        "fa" to "فارسی",
    )

    fun uiLangName(code: String): String = uiLangs.firstOrNull { it.first == code }?.second ?: code

    /** انگلیسیِ یک رشتهٔ فارسیِ رابط (پایهٔ ترجمه به زبان‌های دیگر). */
    private fun toEnglish(x: String): String = I18N_EN[x] ?: x

    /** ترجمهٔ خودکارِ یک رشتهٔ فارسیِ رابط به زبانِ جاری. فارسی بدون تغییر برمی‌گردد.
     *  همهٔ متن‌ها از همین‌جا (Ui.text/toast/…) رد می‌شوند، پس بدون دست‌زدن به صدها فراخوانی، کلِ رابط ترجمه می‌شود. */
    fun s(x: CharSequence?): CharSequence? {
        if (x == null || lang == "fa") return x
        val en = toEnglish(x.toString())
        if (lang == "en") return en
        val m = extra ?: return en            // بستهٔ زبان هنوز ساخته نشده → انگلیسی
        return m[en] ?: en                     // ترجمهٔ ذخیره‌شده، وگرنه انگلیسی
    }

    /** مثل s() ولی خروجیِ String (برای دیالوگ‌ها و hintها). */
    fun sx(x: String): String = s(x)?.toString() ?: x

    /** همهٔ رشته‌های انگلیسیِ یکتای رابط — ورودیِ ساختِ بستهٔ زبان. */
    fun uiEnglishStrings(): List<String> {
        val set = LinkedHashSet<String>()
        set.addAll(I18N_EN.values)
        set.addAll(EN.values)
        return set.filter { it.isNotBlank() }
    }

    fun t(key: String): String {
        val base = EN[key] ?: key
        return when (lang) {
            "en" -> base
            "fa" -> FA[key] ?: base
            else -> extra?.get(base) ?: base
        }
    }

    private val EN = mapOf(
        "tagline" to "Live screen translation",
        "nav_home" to "Translate", "nav_duo" to "Two-way", "nav_history" to "History",
        "nav_license" to "License", "nav_help" to "Help", "nav_about" to "About", "nav_settings" to "Settings",
        "on" to "On", "off" to "Off",
        // language gate
        "gate_welcome" to "Welcome", "gate_choose" to "Choose your language",
        "gate_ui" to "App language", "gate_target" to "Translate into",
        "gate_continue" to "Continue",
        "settings_ui_lang_sub" to "Choose any language; the app's interface is translated for you.",
        "preparing_lang" to "Preparing language…",
        "preparing_lang_sub" to "Translating the app's interface into your language. This may take a little while — please wait.",
        "lang_internet_note" to "The first time you pick a language it is translated once using the internet, then saved for offline use.",
        // home
        "home_on_chip" to "● Running", "home_ready" to "Ready to translate",
        "home_auto_hint" to "Open any app; text is translated automatically. Tap the bubble to stop.",
        "home_tap_hint" to "Open any game or app and tap the «C» bubble. Hold the bubble for continuous translation.",
        "home_turnoff" to "■  Turn off",
        "home_title_off" to "See any screen in your language",
        "home_sub_off" to "Translation shows right over the original text — no root needed.",
        "home_mode" to "Translation mode",
        "mode_tap_t" to "Tap to translate  ·  recommended",
        "mode_tap_s" to "Tap the bubble to translate the screen instantly. Most accurate, no flicker, low battery.",
        "mode_auto_t" to "Continuous auto-translate",
        "mode_auto_s" to "Any text on screen is translated automatically; turn it on once.",
        "home_start" to "▶  Start",
        "quick_guide" to "Quick guide",
        "step1" to "Tap «Start» and grant the permissions (display over apps and screen capture).",
        "step2" to "Open the app; the bubble sits at the edge and can be dragged.",
        "step3" to "Tap the bubble: translate (or toggle auto-translate).",
        "step4" to "Hold the bubble: menu — translate a region, live dialog, two-way, and stop.",
        "full_guide" to "📖  Full guide",
        "perms" to "Permissions",
        "perm_overlay_on" to "✓ Display over other apps", "perm_overlay_off" to "✗ Display over other apps",
        "perm_capture" to "• Screen capture: asked each time you start. The image is read only on your device.",
        "copied" to "Copied",
    )

    private val FA = mapOf(
        "tagline" to "ترجمه زنده روی صفحه",
        "nav_home" to "ترجمه", "nav_duo" to "دوطرفه", "nav_history" to "تاریخچه",
        "nav_license" to "لایسنس", "nav_help" to "راهنما", "nav_about" to "درباره", "nav_settings" to "تنظیمات",
        "on" to "روشن", "off" to "خاموش",
        "gate_welcome" to "خوش آمدید", "gate_choose" to "زبان خود را انتخاب کنید",
        "gate_ui" to "زبان برنامه", "gate_target" to "ترجمه به",
        "gate_continue" to "ادامه",
        "settings_ui_lang_sub" to "هر زبانی را انتخاب کنید؛ رابط برنامه برایتان ترجمه می‌شود.",
        "preparing_lang" to "در حال آماده‌سازی زبان…",
        "preparing_lang_sub" to "در حال ترجمهٔ رابطِ برنامه به زبانِ شما. ممکن است کمی طول بکشد — لطفاً صبر کنید.",
        "lang_internet_note" to "بار اول که زبانی را انتخاب می‌کنید یک‌بار با اینترنت ترجمه و برای استفادهٔ آفلاین ذخیره می‌شود.",
        "home_on_chip" to "● روشن است", "home_ready" to "آماده ترجمه",
        "home_auto_hint" to "به بازی بروید؛ متن‌ها خودکار ترجمه می‌شوند. برای توقف، روی حباب بزنید.",
        "home_tap_hint" to "به بازی یا برنامه بروید و روی حباب «ف» بزنید. برای ترجمه خودکار، انگشت را روی حباب نگه دارید.",
        "home_turnoff" to "■  خاموش کردن",
        "home_title_off" to "متن هر صفحه را به زبان خود ببینید",
        "home_sub_off" to "ترجمه درست روی همان متن‌ها نمایش داده می‌شود؛ بدون نیاز به روت.",
        "home_mode" to "روش ترجمه",
        "mode_tap_t" to "ترجمه با لمس  ·  پیشنهادی",
        "mode_tap_s" to "هر بار روی حباب بزنید، همان لحظه صفحه ترجمه می‌شود. دقیق‌ترین حالت، بدون چشمک و کم‌مصرف.",
        "mode_auto_t" to "ترجمه خودکار مداوم",
        "mode_auto_s" to "هر متنی روی صفحه بیاید خودش ترجمه می‌شود؛ یک بار روشن کنید.",
        "home_start" to "▶  شروع",
        "quick_guide" to "راهنمای سریع",
        "step1" to "«شروع» را بزنید و اجازه‌ها را بدهید (نمایش روی برنامه‌ها و ضبط صفحه).",
        "step2" to "برنامه را باز کنید؛ حباب کنار صفحه است و با کشیدن جابه‌جا می‌شود.",
        "step3" to "زدن روی حباب: ترجمه (یا روشن/خاموش کردن ترجمه خودکار).",
        "step4" to "نگه داشتن حباب: منو — ترجمه یک قسمت، دیالوگ زنده، دوطرفه و خاموش کردن.",
        "full_guide" to "📖  راهنمای کامل",
        "perms" to "اجازه‌ها",
        "perm_overlay_on" to "✓ نمایش روی برنامه‌های دیگر", "perm_overlay_off" to "✗ نمایش روی برنامه‌های دیگر",
        "perm_capture" to "• ضبط صفحه: هر بار که روشن می‌کنید پرسیده می‌شود. تصویر فقط روی خود گوشی خوانده می‌شود.",
        "copied" to "کپی شد",
    )
}
