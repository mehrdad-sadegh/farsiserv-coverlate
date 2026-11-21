package ir.farsisaz

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast

/**
 * سرویس اصلی: عکس صفحه (با اجازه کاربر)، حباب شناور، ترجمه روی متن و ترجمه زنده.
 * عکس‌ها فقط روی خود گوشی خوانده می‌شوند (ML Kit)؛ فقط متن برای ترجمه فرستاده می‌شود.
 */
class CaptureService : Service() {
    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private lateinit var workThread: HandlerThread
    private lateinit var work: Handler

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capW = 0
    private var capH = 0
    private var density = 0

    private var bubble: Bubble? = null
    private var menu: BubbleMenu? = null
    private var result: ResultOverlay? = null
    private var panel: LivePanel? = null
    private var duo: DuoPanel? = null
    private lateinit var translator: Translator

    @Volatile private var busy = false
    @Volatile private var liveRegion: Rect? = null
    private var lastSig: IntArray? = null
    private var stableSig: IntArray? = null
    private var liveHistory = ArrayList<String>()
    private var lastLiveText = ""

    // ترجمه خودکار مداوم
    @Volatile private var autoOn = false
    private var autoSeen: IntArray? = null      // آخرین تصویر (با ترجمه‌ها روی صفحه، بدون محدوده ترجمه‌ها)
    private var autoPending: IntArray? = null   // تصویری که در حال ثابت شدن است
    private var autoLastClean = 0L
    private var autoLastError = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        L.init()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        workThread = HandlerThread("farsisaz-capture").also { it.start() }
        work = Handler(workThread.looper)
        translator = Translator(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopEverything(); return START_NOT_STICKY }
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                else intent.getParcelableExtra(EXTRA_DATA)
                goForeground()
                if (data == null || !startProjection(code, data)) {
                    toast("اجازه ضبط صفحه داده نشد.")
                    stopEverything()
                    return START_NOT_STICKY
                }
                showBubble()
                running = true
                if (intent.getBooleanExtra(EXTRA_AUTO, false)) main.postDelayed({ startAuto() }, 900)
            }
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- اعلان (برای کار در پس‌زمینه لازم است)
    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel("farsisaz", L.sx("فارسی ساز"), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "farsisaz")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(L.sx("فارسی ساز روشن است"))
            .setContentText(L.sx("روی حباب «ف» بزنید؛ برای ترجمه خودکار مداوم، انگشت را روی حباب نگه دارید."))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, L.sx("خاموش کردن"), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(7, n)
    }

    // ---------------------------------------------------------------- ضبط صفحه
    private fun startProjection(code: Int, data: Intent): Boolean {
        return try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(code, data) ?: return false
            projection = mp
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { main.post { stopEverything() } }
            }, main)
            createDisplay()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun createDisplay() {
        val (w, h) = realSize(this)
        capW = w; capH = h
        density = resources.displayMetrics.densityDpi
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val old = reader
        reader = r
        val d = display
        if (d == null) {
            display = projection?.createVirtualDisplay(
                "farsisaz", w, h, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, work
            )
        } else {
            d.resize(w, h, density)
            d.surface = r.surface
        }
        old?.close()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (projection != null) work.post { try { createDisplay() } catch (_: Exception) {} }
    }

    /** آخرین فریم صفحه به‌صورت Bitmap */
    private fun grab(): Bitmap? {
        val img = reader?.acquireLatestImage() ?: return null
        try {
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val padding = rowStride - pixelStride * img.width
            val bmp = Bitmap.createBitmap(img.width + padding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            return if (padding > 0) Bitmap.createBitmap(bmp, 0, 0, img.width, img.height) else bmp
        } finally {
            img.close()
        }
    }

    private fun crop(bmp: Bitmap, r: Rect): Bitmap {
        val x = r.left.coerceIn(0, bmp.width - 1)
        val y = r.top.coerceIn(0, bmp.height - 1)
        val w = r.width().coerceAtMost(bmp.width - x).coerceAtLeast(1)
        val h = r.height().coerceAtMost(bmp.height - y).coerceAtLeast(1)
        return Bitmap.createBitmap(bmp, x, y, w, h)
    }

    private fun isBlack(bmp: Bitmap): Boolean {
        val small = Bitmap.createScaledBitmap(bmp, 16, 16, false)
        for (x in 0 until 16) for (y in 0 until 16) if ((small.getPixel(x, y) and 0xFFFFFF) > 0x101010) return false
        return true
    }

    private fun signature(bmp: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(bmp, 32, 16, true)
        val out = IntArray(32 * 16)
        for (y in 0 until 16) for (x in 0 until 32) {
            val p = s.getPixel(x, y)
            out[y * 32 + x] = ((p shr 16 and 255) * 3 + (p shr 8 and 255) * 6 + (p and 255)) / 10
        }
        return out
    }

    /** امضای کوچک تصویر؛ خانه‌هایی که زیر ترجمه‌ها یا حباب هستند حساب نمی‌شوند */
    private fun maskedSignature(bmp: Bitmap, mask: List<Rect>): IntArray {
        val gw = 48; val gh = 28
        val s = Bitmap.createScaledBitmap(bmp, gw, gh, true)
        val out = IntArray(gw * gh)
        val fx = bmp.width.toFloat() / gw; val fy = bmp.height.toFloat() / gh
        for (y in 0 until gh) for (x in 0 until gw) {
            val cx = ((x + 0.5f) * fx).toInt(); val cy = ((y + 0.5f) * fy).toInt()
            if (mask.any { it.contains(cx, cy) }) { out[y * gw + x] = -1; continue }
            val p = s.getPixel(x, y)
            out[y * gw + x] = ((p shr 16 and 255) * 3 + (p shr 8 and 255) * 6 + (p and 255)) / 10
        }
        return out
    }

    /** بیشتر از ۱۵٪ صفحه عوض شده؟ */
    private fun bigChange(a: IntArray?, b: IntArray): Boolean {
        if (a == null || a.size != b.size) return false
        var n = 0; var tot = 0
        for (i in b.indices) {
            if (a[i] < 0 || b[i] < 0) continue
            tot++
            if (kotlin.math.abs(a[i] - b[i]) > 16) n++
        }
        return tot > 0 && n > tot * 0.15
    }

    private fun maskedDiffers(a: IntArray?, b: IntArray): Boolean {
        if (a == null || a.size != b.size) return true
        var n = 0
        for (i in b.indices) {
            if (a[i] < 0 || b[i] < 0) continue
            if (kotlin.math.abs(a[i] - b[i]) > 16) n++
        }
        return n > 5
    }

    private fun differs(a: IntArray?, b: IntArray): Boolean {
        if (a == null) return true
        var n = 0
        for (i in b.indices) if (kotlin.math.abs(a[i] - b[i]) > 18) n++
        return n > 6
    }

    // ---------------------------------------------------------------- حباب و منو
    private fun showBubble() {
        if (bubble != null) return
        menu = BubbleMenu(this, wm)
        result = ResultOverlay(this, wm)
        bubble = Bubble(this, wm, onTap = { onBubbleTap() }, onLong = { showMenu() }).also { it.show() }
    }

    private fun onBubbleTap() {
        if (autoOn) { stopAuto(); toast("ترجمه خودکار خاموش شد."); return }
        if (result?.isShown2 == true) { result?.dismiss(); return }
        if (liveRegion != null) { stopLive(); return }
        if (Prefs.mode == "auto") { startAuto(); return }
        quickTranslate(null)
    }

    private fun showMenu() {
        val b = bubble ?: return
        val items = ArrayList<MenuItem>()
        if (autoOn) items.add(MenuItem("⏸", "توقف ترجمه خودکار", "ترجمه‌ها دیگر به‌روز نمی‌شوند", active = true) { stopAuto() })
        else items.add(MenuItem("⟳", "ترجمه خودکار مداوم", "هر متنی که روی صفحه بیاید خودش ترجمه می‌شود") { startAuto() })
        items.add(MenuItem("🌐", "ترجمه کل صفحه (یک‌بار)", null) { stopAuto(); quickTranslate(null) })
        items.add(MenuItem("▭", "ترجمه یک قسمت", "دور متن کادر بکشید") { stopAuto(); pickRegion { r -> quickTranslate(r) } })
        if (liveRegion == null) items.add(MenuItem("▶", "ترجمه زنده دیالوگ", "ترجمه در پنل جدا، مخصوص زیرنویس و دیالوگ") {
            stopAuto(); pickRegion { r -> startLive(r) }
        })
        else items.add(MenuItem("⏸", "توقف ترجمه زنده", null, active = true) { stopLive() })
        items.add(MenuItem("⇄", "ترجمهٔ دوطرفه", "متن تایپ کنید و به زبان دلخواه ترجمه و کپی کنید") { toggleDuo() })
        items.add(MenuItem("⚙", "باز کردن برنامه", null) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        items.add(MenuItem("✕", "خاموش کردن فارسی ساز", null, danger = true) { stopEverything() })
        menu?.show(b.params.x, b.params.y, items)
    }

    /** پنل ترجمهٔ دوطرفه (تایپ دستی) روی هر برنامه‌ای باز/بسته می‌شود */
    private fun toggleDuo() {
        if (duo?.isShown == true) { duo?.remove(); duo = null; return }
        duo = DuoPanel(this, wm,
            translate = { text, src, dst -> translator.translateText(text, src, dst) },
            onClose = { main.post { duo?.remove(); duo = null } }
        ).also { it.show() }
    }

    private fun pickRegion(done: (Rect) -> Unit) {
        result?.dismiss()
        RegionSelector(this, wm) { r -> if (r != null) main.postDelayed({ done(r) }, 120) }.show()
    }

    private fun licenseOk(): Boolean {
        if (!Integrity.ok(this)) {
            toast("این نسخهٔ برنامه معتبر نیست. لطفاً نسخهٔ رسمی را نصب کنید.")
            return false
        }
        val lm = LicenseManager.get(this)
        if (Monetize.freeMode(lm.info)) return true   // نسخهٔ رایگان: بدون محدودیت
        var st = lm.state
        if (st.status == "unknown") { st = lm.offlineState(); lm.state = st }
        if (st.allowed) return true
        toast(if (st.message.isNotEmpty()) st.message else "دوره آزمایشی یا لایسنس تمام شده؛ از بخش «لایسنس» برنامه تمدید کنید.")
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("page", "license"))
        return false
    }

    // ---------------------------------------------------------------- ترجمه یک‌باره (روی خود متن)
    private fun quickTranslate(region: Rect?) {
        if (busy || !licenseOk()) return
        busy = true
        result?.dismiss()
        bubble?.busy(true)
        bubble?.hideForCapture(true)
        // کمی صبر تا حباب و ترجمه قبلی از تصویر حذف شوند
        work.postDelayed({
            var err: String? = null
            var items: List<TrItem> = emptyList()
            try {
                var bmp = grab()
                if (bmp == null) { Thread.sleep(250); bmp = grab() }
                main.post { bubble?.hideForCapture(false) }
                if (bmp == null) throw RuntimeException("تصویر صفحه در دسترس نیست؛ یک بار دیگر امتحان کنید.")
                if (isBlack(bmp)) throw RuntimeException("این برنامه اجازه عکس گرفتن از صفحه را نمی‌دهد (صفحه سیاه است).")
                val src = if (region != null) crop(bmp, region) else bmp
                val blocks = Ocr.read(src)
                if (blocks.isEmpty()) throw RuntimeException("متنی پیدا نشد.")
                val tr = translator.translateMany(blocks.map { it.text })
                val db = Db.get(this)
                items = blocks.flatMapIndexed { i, b ->
                    db.addHistory(b.text, tr[i])
                    Ocr.items(b, tr[i], region?.left ?: 0, region?.top ?: 0)
                }
            } catch (e: OutOfMemoryError) {
                err = "حافظه گوشی کم است؛ برنامه‌های دیگر را ببندید و دوباره امتحان کنید."
            } catch (e: Exception) {
                err = e.message ?: "خطا"
            }
            main.post {
                busy = false
                bubble?.busy(false)
                bubble?.hideForCapture(false)
                if (err != null) toast(err) else result?.show(items)
            }
        }, 180)
    }

    // ---------------------------------------------------------------- ترجمه زنده
    private fun startLive(region: Rect) {
        if (!licenseOk()) return
        stopAuto()
        liveRegion = region
        lastSig = null; stableSig = null; lastLiveText = ""
        liveHistory.clear()
        bubble?.live(true)
        panel = LivePanel(this, wm) { stopLive() }.also {
            it.show(region)
            it.setStatus("● ترجمه زنده روشن است — منتظر متن…")
        }
        work.post(liveTick)
    }

    private fun stopLive() {
        liveRegion = null
        work.removeCallbacks(liveTick)
        panel?.remove(); panel = null
        bubble?.live(false)
    }

    private val liveTick = object : Runnable {
        override fun run() {
            val region = liveRegion ?: return
            try {
                val bmp = grab()
                if (bmp != null && !busy) {
                    val part = crop(bmp, region)
                    val sig = signature(part)
                    if (differs(lastSig, sig)) {
                        lastSig = sig      // هنوز در حال تغییر است (مثلاً متن تایپ‌شونده)؛ صبر می‌کنیم
                    } else if (differs(stableSig, sig)) {
                        stableSig = sig    // تصویر ثابت شد و با آخرین ترجمه فرق دارد
                        translateLive(part)
                    }
                }
            } catch (_: Exception) {}
            if (liveRegion != null) work.postDelayed(this, Prefs.liveInterval.toLong().coerceIn(300, 3000))
        }
    }

    private fun translateLive(part: Bitmap) {
        val blocks = Ocr.read(part)
        val text = blocks.joinToString("\n") { it.text }.trim()
        if (text.isEmpty() || text == lastLiveText) return
        lastLiveText = text
        main.post { panel?.setStatus("● در حال ترجمه…", Ui.OACCENT2) }
        try {
            val tr = translator.translateMany(blocks.map { it.text })
            val joined = tr.joinToString("\n")
            val db = Db.get(this)
            blocks.forEachIndexed { i, b -> db.addHistory(b.text, tr[i]) }
            liveHistory.add(joined)
            while (liveHistory.size > 4) liveHistory.removeAt(0)
            val copy = ArrayList(liveHistory)
            main.post { panel?.push(copy); panel?.setStatus("● ترجمه زنده") }
        } catch (e: Exception) {
            main.post { panel?.setStatus("⚠ " + Translator.short(e), Ui.AMBER) }
        }
    }

    // ---------------------------------------------------------------- ترجمه خودکار مداوم
    /**
     * هر چند صدم ثانیه یک عکس کوچک از صفحه با قبلی مقایسه می‌شود (بدون OCR و بدون پنهان کردن چیزی):
     *  - بیرون ترجمه‌ها: هر تغییری دیده می‌شود.
     *  - زیر ترجمه‌ها: پس‌زمینه ترجمه کمی شفاف است، پس عوض شدن متن اصلی زیر آن هم دیده می‌شود.
     * فقط وقتی چیزی عوض شد و ثابت ماند، ترجمه‌ها یک لحظه کوتاه پنهان می‌شوند، متن اصلی خوانده و ترجمه می‌شود.
     * اگر متن همان متن قبلی بود، هیچ چیز دوباره کشیده نمی‌شود (بدون پرش).
     */
    private var autoSeenIn: IntArray? = null
    private var autoPendingIn: IntArray? = null
    private var autoKey = ""
    private var autoEmpty = 0

    private fun startAuto() {
        if (autoOn) return
        if (!licenseOk()) return
        stopLive()
        result?.dismiss()
        autoOn = true
        autoSeen = null; autoPending = null; autoSeenIn = null; autoPendingIn = null
        autoKey = ""; autoEmpty = 0; autoLastClean = 0L
        bubble?.mode("auto")
        toast("ترجمه خودکار روشن شد. برای خاموش کردن روی حباب بزنید.")
        work.post(autoTick)
    }

    private fun stopAuto() {
        if (!autoOn) return
        autoOn = false
        work.removeCallbacks(autoTick)
        main.post {
            result?.dismiss()
            bubble?.busy(false)
            bubble?.hideForCapture(false)
            bubble?.mode(if (liveRegion != null) "live" else "idle")
        }
    }

    private val autoTick = object : Runnable {
        override fun run() {
            if (!autoOn) return
            try {
                autoStep()
            } catch (_: OutOfMemoryError) {
                System.gc()
            } catch (t: Throwable) {
                // خطا را بی‌صدا رد نکن تا اگر ترجمهٔ خودکار کار نکرد، علتش دیده شود.
                val now = System.currentTimeMillis()
                if (now - autoLastError > 20000) {
                    autoLastError = now
                    val m = t.message?.take(80) ?: t.javaClass.simpleName
                    main.post { toast("⚠ ترجمهٔ خودکار: $m") }
                }
            }
            if (autoOn) work.postDelayed(this, Prefs.autoInterval.toLong().coerceIn(400, 3000))
        }
    }

    private fun overlayMask(): List<Rect> {
        val m = ArrayList<Rect>(result?.rects() ?: emptyList())
        bubble?.let { m.add(it.rect()) }
        return m
    }

    /** امضای ریز محدوده ترجمه‌ها (برای دیدن تغییر متن زیر ترجمه‌ها) */
    private fun boxSignature(bmp: Bitmap, boxes: List<Rect>): IntArray? {
        if (boxes.isEmpty()) return null
        val out = ArrayList<Int>()
        for (r0 in boxes) {
            val r = Rect(r0)
            if (!r.intersect(0, 0, bmp.width, bmp.height) || r.width() < 4 || r.height() < 4) continue
            val gw = (r.width() / 6).coerceIn(4, 120)
            val gh = (r.height() / 6).coerceIn(2, 60)
            val part = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
            val s = Bitmap.createScaledBitmap(part, gw, gh, true)
            for (y in 0 until gh) for (x in 0 until gw) {
                val p = s.getPixel(x, y)
                out.add(((p shr 16 and 255) * 3 + (p shr 8 and 255) * 6 + (p and 255)) / 10)
            }
            if (s !== part) s.recycle()
            part.recycle()
        }
        return out.toIntArray()
    }

    private fun boxDiffers(a: IntArray?, b: IntArray?): Boolean {
        if (b == null) return false
        if (a == null || a.size != b.size) return true
        var n = 0
        for (i in b.indices) if (kotlin.math.abs(a[i] - b[i]) > 4) n++
        return n > maxOf(3, b.size / 60)
    }

    /** اجرا در رشته پس‌زمینه */
    private fun autoStep() {
        if (busy) return
        if (runOnMain { menu?.isOpen } == true) return
        val frame = grab() ?: return
        try {
            if (isBlack(frame)) return
            val mask = runOnMain { overlayMask() } ?: return
            val boxes = runOnMain { result?.rects() } ?: emptyList()
            val sig = maskedSignature(frame, mask)
            val sigIn = boxSignature(frame, boxes)
            val now = System.currentTimeMillis()
            val outChanged = maskedDiffers(autoSeen, sig)
            val inChanged = boxDiffers(autoSeenIn, sigIn)
            if (!outChanged && !inChanged) { autoPending = null; autoPendingIn = null; return }
            // تغییر بزرگ (اسکرول، صفحه جدید): ترجمه‌های قبلی دیگر روی متن درست نیستند؛ فوراً برداشته می‌شوند
            if (boxes.isNotEmpty() && bigChange(autoSeen, sig)) {
                autoKey = ""
                main.post { if (autoOn) result?.dismiss() }
            }
            // صبر تا تصویر ثابت شود (انیمیشن، متن تایپ‌شونده، اسکرول)
            val settled = autoPending != null && !maskedDiffers(autoPending, sig) && !boxDiffers(autoPendingIn, sigIn)
            autoPending = sig; autoPendingIn = sigIn
            if (!settled && autoSeen != null) return
            // اگر فقط زیر ترجمه‌ها تغییر کرد (مثلاً پس‌زمینه متحرک)، زیاد پشت سر هم بررسی نمی‌شود
            if (!outChanged && now - autoLastClean < 2500) return
            // پنهان کردن لحظه‌ای ترجمه‌ها حداکثر هر ۱٫۵ ثانیه یک بار (بدون چشمک زدن در بازی‌های پرتحرک)
            if (boxes.isNotEmpty() && now - autoLastClean < 1500) return
            autoSeen = sig; autoSeenIn = sigIn
            autoPending = null; autoPendingIn = null
        } finally {
            frame.recycle()
        }
        cleanTranslate()
    }

    private fun cleanTranslate() {
        val hasOverlay = runOnMain { result?.isShown2 } == true
        var clean: Bitmap? = null
        if (hasOverlay) {
            // ترجمه‌ها و حباب یک لحظه پنهان می‌شوند تا متن اصلی دیده شود
            runOnMain { result?.hideForCapture(true); bubble?.hideForCapture(true) }
            try {
                // تصویر صفحه فقط وقتی چیزی عوض شود ساخته می‌شود؛ پنهان کردن ترجمه یک فریم تازه می‌سازد
                Thread.sleep(95)
                clean = grab()
                var tries = 0
                while (clean == null && tries++ < 4) { Thread.sleep(40); clean = grab() }
            } finally {
                runOnMain { result?.hideForCapture(false); bubble?.hideForCapture(false) }
            }
        } else {
            clean = grab()
        }
        autoLastClean = System.currentTimeMillis()
        if (clean == null) return
        val bubbleRect = runOnMain { bubble?.rect() }
        val blocks: List<Ocr.Block>
        try {
            if (isBlack(clean) || !autoOn) return
            blocks = Ocr.read(clean).filter { b -> bubbleRect == null || !Rect.intersects(b.rect, bubbleRect) }
        } finally {
            clean.recycle()
        }
        if (!autoOn) return
        if (blocks.isEmpty()) {
            // یک بار خالی بودن کافی نیست (ممکن است وسط تغییر صفحه باشد)
            if (++autoEmpty >= 2 && autoKey.isNotEmpty()) { autoKey = ""; main.post { if (autoOn) result?.dismiss() } }
            refreshSeen()
            return
        }
        autoEmpty = 0
        val key = Ocr.key(blocks)
        if (key == autoKey && hasOverlay) { refreshSeen(); return }     // همان متن قبلی؛ چیزی عوض نمی‌شود
        main.post { bubble?.busy(true) }
        try {
            val tr = translator.translateMany(blocks.map { it.text })
            if (!autoOn) return
            autoKey = key
            val db = Db.get(this)
            val items = blocks.flatMapIndexed { i, b -> db.addHistory(b.text, tr[i]); Ocr.items(b, tr[i]) }
            runOnMain { if (autoOn) result?.show(items, passiveMode = true) }
        } catch (e: Exception) {
            val t = System.currentTimeMillis()
            if (t - autoLastError > 20000) { autoLastError = t; toast("⚠ " + Translator.short(e)) }
        } finally {
            main.post { bubble?.busy(false) }
        }
        refreshSeen()
    }

    /** بعد از نمایش ترجمه، تصویر فعلی صفحه (با ترجمه‌ها) مبنای مقایسه بعدی می‌شود */
    private fun refreshSeen() {
        Thread.sleep(220)
        val after = grab() ?: return
        try {
            val m = runOnMain { overlayMask() } ?: return
            val boxes = runOnMain { result?.rects() } ?: emptyList()
            autoSeen = maskedSignature(after, m)
            autoSeenIn = boxSignature(after, boxes)
            autoPending = null; autoPendingIn = null
        } finally {
            after.recycle()
        }
    }

    /** اجرای کار روی رشته اصلی و گرفتن نتیجه (حداکثر ۱ ثانیه صبر) */
    private fun <T> runOnMain(f: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return f()
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: T? = null
        main.post { try { out = f() } finally { latch.countDown() } }
        latch.await(1, java.util.concurrent.TimeUnit.SECONDS)
        return out
    }

    // ---------------------------------------------------------------- پایان
    private fun stopEverything() {
        running = false
        autoOn = false
        work.removeCallbacks(autoTick)
        stopLive()
        duo?.remove(); duo = null
        menu?.dismiss()
        result?.dismiss()
        bubble?.remove(); bubble = null
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        try { display?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        bubble?.remove()
        panel?.remove()
        duo?.remove()
        result?.dismiss()
        workThread.quitSafely()
        super.onDestroy()
    }

    private fun toast(s: String) = main.post { Toast.makeText(this, L.s(s) ?: s, Toast.LENGTH_LONG).show() }

    companion object {
        const val ACTION_START = "ir.farsisaz.START"
        const val ACTION_STOP = "ir.farsisaz.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_AUTO = "auto"
        @Volatile var running = false
    }
}
