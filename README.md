<div align="center">

# FarsiServ &nbsp;·&nbsp; Coverlate

**ترجمهٔ زندهٔ متنِ بازی‌ها روی صفحه — مستقیم، بی‌دردسر، همان لحظه.**

A live, on-screen game translator for Android — read any game's text in your own language, in real time.

</div>

---

## این پروژه چیست؟

 دو نسخه از یک برنامهٔ واحد هستند که متنِ روی صفحهٔ بازی‌ها را با OCR می‌خواند، ترجمه می‌کند و همان‌جا — روی خودِ بازی — نشان می‌دهد. نیازی به تغییرِ فایل‌های بازی یا روت نیست؛ یک حبابِ شناور روی صفحه می‌نشیند و با یک لمس، متنِ جلوی چشمت ترجمه می‌شود.


## امکانات

- **ترجمهٔ زنده روی صفحه** — حباب شناور + پنلِ ترجمه، بدونِ خروج از بازی.
- **دو حالت:** «ترجمه با لمس» (تک‌ضرب) و «ترجمهٔ خودکار و پیوسته».
- **OCR روی خودِ دستگاه** — تشخیصِ متنِ لاتین کاملاً آفلاین؛ خط‌های دیگر (روسی، عربی، یونانی و…) با بسته‌های زبانیِ قابل‌دانلود.
- **چند موتورِ ترجمه** — موتورهای وبِ رایگان (Microsoft / Google)، یا در صورت تمایل کلیدِ شخصیِ خودت (Gemini / Claude / سرورِ اختصاصی) برای ترجمهٔ باکیفیت‌ترِ اصطلاح‌ها.
- **کاملاً رایگان** — بدونِ لایسنس و بدونِ خرید؛ نگه‌داری از طریقِ تبلیغاتِ درون‌برنامه‌ای.
- **سبک و مستقل** — بدونِ کتابخانه‌های سنگینِ اضافه؛ کار با حافظهٔ کم.

> **حریمِ خصوصی و آفلاین:** هستهٔ ترجمه (OCR + موتورهای ترجمه) به سرورِ ما وابسته نیست و آفلاین کار می‌کند. ارتباط با سرور فقط برای شمارشِ کاربر و دریافتِ تبلیغات است؛ اگر سرور در دسترس نباشد، برنامه همچنان کار می‌کند.

---

## نصب

آخرین APK هر دو نسخه را از بخشِ [**Releases**](../../releases) دانلود کن:

- `farsiserv-release.apk` — برای کاربران فارسی زبان
- `coverlate-release.apk` — نسخهٔ جهانی

برای عملکردِ درست، به برنامه اجازهٔ **«نمایش روی برنامه‌های دیگر»** و **«ضبطِ صفحه»** را بده.

## لایسنس
این پروژه تحتِ لایسنسِ **MIT** منتشر شده است —  [LICENSE](LICENSE).

© 2026 Mehrdad Sadegh

---

<div align="center" dir="ltr">

### English (short)

**FarsiServ** and **Coverlate** are two editions of one app that reads on-screen game text with OCR and overlays the translation live, right on top of the game — no game-file edits, no root. FarsiServ targets Persian users; Coverlate targets a global (English) audience. Both are built from a single codebase via product flavors (`assembleIrRelease` / `assembleGlobalRelease`).

The translation core (on-device OCR + free web engines, with optional personal API keys) works offline; the server is used only for user counts and in-app ads, and the app keeps working without it. Free to use, ad-supported.

Download APKs from [Releases](../../releases). Licensed under MIT — see [LICENSE](LICENSE).

</div>
