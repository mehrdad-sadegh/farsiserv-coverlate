plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ir.farsisaz"
    compileSdk = 34

    defaultConfig {
        applicationId = "ir.farsisaz.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
    }

    flavorDimensions += "edition"
    productFlavors {
        create("ir") {
            dimension = "edition"
            applicationId = "ir.farsisaz.app"      // بدون تغییر: آپدیت روی نصب‌های فعلی می‌نشیند
            resValue("string", "app_name", "FarsiServ")
            buildConfigField("String", "EDITION", "\"fa\"")
            buildConfigField("String", "DEF_LANG", "\"fa\"")
            buildConfigField("boolean", "SHOW_LANG_GATE", "false")
            buildConfigField("String", "BUBBLE_GLYPH", "\"ف\"")
        }
        create("global") {
            dimension = "edition"
            applicationId = "com.coverlate.app"
            resValue("string", "app_name", "Coverlate")
            buildConfigField("String", "EDITION", "\"global\"")
            buildConfigField("String", "DEF_LANG", "\"en\"")
            buildConfigField("boolean", "SHOW_LANG_GATE", "false")   // موقتاً خاموش تا علت کرش نسخهٔ جهانی روشن شود
            buildConfigField("String", "BUBBLE_GLYPH", "\"C\"")
        }
    }

    buildFeatures { buildConfig = true }

    // امضا از متغیرهای محیطی خوانده می‌شود (در CI از GitHub Secrets تأمین می‌شوند).
    // کلید و رمز هیچ‌وقت داخل کد نیستند. اگر کلید موجود نباشد، نسخهٔ release بدون‌امضا ساخته می‌شود.
    signingConfigs {
        create("release") {
            val ks = file(System.getenv("KEYSTORE_FILE") ?: "farsisaz.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("STORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // بعضی SDKها (تپسل/ادیوری) با Kotlin جدیدتر ساخته شده‌اند؛ این فلگ اجازه می‌دهد کامپایلر فعلی آن‌ها را بخواند.
        freeCompilerArgs = freeCompilerArgs + listOf("-Xskip-metadata-version-check", "-Xskip-prerelease-check")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // OCR روی خود گوشی (رایگان، بدون اینترنت) — خط لاتین
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // مدل‌های خطوط دیگر (هر کدام چند مگ به حجم برنامه اضافه می‌کند)
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    // Tesseract (برای خط‌هایی که ML Kit ندارد: روسی/سیریلیک، عربی، یونانی، تایلندی…)؛
    // فایل زبان‌ها در برنامه نیست و کاربر هر زبان را یک‌بار از سرور دانلود می‌کند.
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.7.0")

    // تبلیغات (همه اختیاری و با کنترل سرور؛ پیش‌فرض خاموش)
    implementation("com.google.android.gms:play-services-ads:23.6.0")   // AdMob (گوگل)
    implementation("ir.tapsell.plus:tapsell-plus-sdk-android:2.3.3")    // تپسل
    implementation("com.adivery:sdk:4.9.0")                             // ادیوری

    // خرید اشتراک از Google Play (برای آینده)
    implementation("com.android.billingclient:billing:7.1.1")
}
