# --- ML Kit (OCR روی گوشی) ---
# ML Kit با reflection مدل‌ها را بارگذاری می‌کند؛ کلاس‌هایش نباید حذف/مبهم شوند.
-keep class com.google.mlkit.** { *; }
-keep interface com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# نگه داشتن annotationها (ML Kit و Play services به آن‌ها متکی‌اند)
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# نقطه‌های ورودی برنامه (از Manifest صدا زده می‌شوند) خودکار نگه داشته می‌شوند.
# کد خود برنامه reflection ندارد، پس نیازی به keep دستی نیست و R8 آزادانه مبهم/کوچک می‌کند.

# --- تبلیغات و خرید (AdMob/Tapsell/Adivery/Play Billing) ---
-keep class ir.tapsell.** { *; }
-dontwarn ir.tapsell.**
-keep class com.adivery.** { *; }
-dontwarn com.adivery.**
-keep class com.android.billingclient.** { *; }
-dontwarn com.android.billingclient.**
# AdMob زیر com.google.android.gms.** است که از قبل نگه داشته شده.

# نگه داشتن شماره خط برای گزارش خطای قابل‌فهم، ولی مبهم‌سازی نام فایل
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
