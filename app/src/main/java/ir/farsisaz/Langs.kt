package ir.farsisaz

/**
 * فهرست زبان‌های موتور ترجمه (Microsoft/Google) با نام فارسی، و نگاشت کد هر موتور.
 * کد پایه بیشترِ زبان‌ها برای هر دو موتور یکسان است؛ فقط چند مورد (مثل چینی) نگاشت جدا دارند.
 */
object Langs {
    data class Lang(val code: String, val name: String)

    /** زبان‌های مقصد (ترجمه به این زبان‌ها). «auto» اینجا نیست چون مقصد باید مشخص باشد. */
    val targets: List<Lang> = listOf(
        Lang("fa", "فارسی"),
        Lang("en", "انگلیسی"),
        Lang("ar", "عربی"),
        Lang("tr", "ترکی استانبولی"),
        Lang("az", "ترکی آذربایجانی"),
        Lang("ru", "روسی"),
        Lang("de", "آلمانی"),
        Lang("fr", "فرانسوی"),
        Lang("es", "اسپانیایی"),
        Lang("it", "ایتالیایی"),
        Lang("pt", "پرتغالی"),
        Lang("nl", "هلندی"),
        Lang("zh", "چینی (ساده‌شده)"),
        Lang("zh-TW", "چینی (سنتی)"),
        Lang("ja", "ژاپنی"),
        Lang("ko", "کره‌ای"),
        Lang("hi", "هندی"),
        Lang("ur", "اردو"),
        Lang("ps", "پشتو"),
        Lang("ckb", "کردی سورانی"),
        Lang("ku", "کردی کرمانجی"),
        Lang("he", "عبری"),
        Lang("id", "اندونزیایی"),
        Lang("ms", "مالایی"),
        Lang("th", "تایلندی"),
        Lang("vi", "ویتنامی"),
        Lang("hy", "ارمنی"),
        Lang("ka", "گرجی"),
        Lang("uk", "اوکراینی"),
        Lang("pl", "لهستانی"),
        Lang("ro", "رومانیایی"),
        Lang("el", "یونانی"),
        Lang("sv", "سوئدی"),
        Lang("no", "نروژی"),
        Lang("da", "دانمارکی"),
        Lang("fi", "فنلاندی"),
        Lang("cs", "چکی"),
        Lang("sk", "اسلواکی"),
        Lang("hu", "مجاری"),
        Lang("bg", "بلغاری"),
        Lang("sr", "صربی"),
        Lang("hr", "کرواتی"),
        Lang("bs", "بوسنیایی"),
        Lang("sl", "اسلوونیایی"),
        Lang("sq", "آلبانیایی"),
        Lang("bn", "بنگالی"),
        Lang("ta", "تامیلی"),
        Lang("te", "تلوگو"),
        Lang("ml", "مالایالام"),
        Lang("kn", "کانّادا"),
        Lang("mr", "مراتی"),
        Lang("gu", "گجراتی"),
        Lang("pa", "پنجابی"),
        Lang("ne", "نپالی"),
        Lang("si", "سینهالی"),
        Lang("my", "برمه‌ای"),
        Lang("km", "خمری"),
        Lang("lo", "لائوسی"),
        Lang("mn", "مغولی"),
        Lang("kk", "قزاقی"),
        Lang("uz", "ازبکی"),
        Lang("ky", "قرقیزی"),
        Lang("tg", "تاجیکی"),
        Lang("tk", "ترکمنی"),
        Lang("sw", "سواحیلی"),
        Lang("am", "امهری"),
        Lang("so", "سومالیایی"),
        Lang("ha", "هوسایی"),
        Lang("yo", "یوروبایی"),
        Lang("zu", "زولو"),
        Lang("af", "آفریکانس"),
        Lang("tl", "فیلیپینی"),
        Lang("ca", "کاتالان"),
        Lang("eu", "باسکی"),
        Lang("gl", "گالیسیایی"),
        Lang("cy", "ولزی"),
        Lang("ga", "ایرلندی"),
        Lang("is", "ایسلندی"),
        Lang("lt", "لیتوانیایی"),
        Lang("lv", "لتونیایی"),
        Lang("et", "استونیایی"),
        Lang("mk", "مقدونی"),
        Lang("mt", "مالتی"),
        Lang("lb", "لوگزامبورگی"),
        Lang("la", "لاتین"),
        Lang("eo", "اسپرانتو"),
    )

    /** زبان‌های مبدأ: «تشخیص خودکار» به‌علاوهٔ همان فهرست بالا. */
    val sources: List<Lang> = listOf(Lang("auto", "تشخیص خودکار")) + targets

    private val byCode = (listOf(Lang("auto", "تشخیص خودکار")) + targets).associateBy { it.code }

    fun name(code: String): String = byCode[code]?.name ?: code

    /** زبان‌های راست‌به‌چپ (برای جهت نمایش و اصلاح نگارش) */
    private val RTL = setOf("fa", "ar", "he", "ur", "ps", "ckb", "sd", "ug", "yi")
    fun isRtl(code: String): Boolean = code in RTL

    /** نگاشت کد به زبان مقصد/مبدأ برای Microsoft (Bing) */
    fun bing(code: String): String = when (code) {
        "auto" -> "auto-detect"
        "zh" -> "zh-Hans"
        "zh-TW" -> "zh-Hant"
        "tl" -> "fil"
        else -> code
    }

    /** نگاشت کد برای Google */
    fun google(code: String): String = when (code) {
        "auto" -> "auto"
        "zh" -> "zh-CN"
        "zh-TW" -> "zh-TW"
        "he" -> "iw"
        "tl" -> "fil"
        else -> code
    }

    // ---------------------------------------------------------------- خط‌های OCR (اندروید)
    data class Script(val id: String, val name: String)

    /** خط متنی که روی صفحه خوانده می‌شود (ML Kit مدل جداگانه برای هر خط دارد). */
    val scripts: List<Script> = listOf(
        Script("latin", "لاتین (انگلیسی، فرانسه، آلمانی، اسپانیایی…)"),
        Script("chinese", "چینی"),
        Script("japanese", "ژاپنی"),
        Script("korean", "کره‌ای"),
        Script("devanagari", "دوناگری (هندی، مراتی…)"),
    )

    fun scriptName(id: String): String = scripts.firstOrNull { it.id == id }?.name ?: id
}
