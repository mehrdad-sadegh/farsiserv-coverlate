package ir.farsisaz

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * خواندن متن از تصویر (ML Kit، روی خود گوشی و بدون اینترنت) + تحلیل چیدمان.
 *
 * ML Kit گاهی یک جمله را در چند «بلوک» جدا برمی‌گرداند و اگر هر تکه جدا ترجمه شود، معنی از بین می‌رود.
 * اینجا همه خط‌ها دوباره بر اساس فاصله، اندازه و هم‌ترازی به پاراگراف تبدیل می‌شوند (مثل نسخه ویندوز)،
 * ستون‌های کنار هم جدا می‌مانند و متن‌های بی‌معنی که OCR از روی خط فارسی یا آیکون‌ها ساخته حذف می‌شوند.
 */
object Ocr {
    data class Block(val text: String, val rect: Rect, val lineH: Int = 0, val lines: Int = 1, val lineRects: List<Rect> = emptyList(), val isList: Boolean = false)

    private class Word(val text: String, val r: Rect)
    private class Seg(val words: List<Word>) {
        val x = words.minOf { it.r.left }
        val y = words.minOf { it.r.top }
        val x2 = words.maxOf { it.r.right }
        val y2 = words.maxOf { it.r.bottom }
        val h get() = max(1, y2 - y)
        val w get() = max(1, x2 - x)
        val text get() = words.joinToString(" ") { it.text }
    }

    // یک تشخیص‌دهنده برای هر خط (لاتین پیش‌فرض؛ بقیه فقط وقتی کاربر انتخاب کند ساخته می‌شوند)
    private val recognizers = HashMap<String, TextRecognizer>()

    @Synchronized
    private fun recognizerFor(script: String): TextRecognizer = recognizers.getOrPut(script) {
        when (script) {
            "chinese" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            "japanese" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            "korean" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            "devanagari" -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    /**
     * اول OCR معمولی؛ اگر نتیجه پر از کلمه بی‌معنی بود (فونت ایتالیک، بولد، نازک یا تزئینی)،
     * تصویر با روش‌های دیگر هم خوانده می‌شود و بهترین نتیجه انتخاب می‌شود.
     * برای خطوط غیرلاتین (چینی/ژاپنی/…) مسیر ساده‌تری استفاده می‌شود چون فیلترهای بالا مخصوص لاتین‌اند.
     */
    fun read(bmp: Bitmap, script: String = Prefs.sourceScript): List<Block> {
        if (script == "auto") return readAuto(bmp)
        // خط‌هایی که ML Kit ندارد (روسی/عربی/…) با Tesseract خوانده می‌شوند.
        if (script.startsWith("tess:")) return try { OcrTess.read(bmp, script.removePrefix("tess:")) } catch (_: Throwable) { emptyList() }
        val latin = script == "latin"
        if (!latin) return readNonLatin(bmp, script)
        return readLatin(bmp)
    }

    /** مسیر کاملِ لاتین: OCR با چند واریانت (ایتالیک/بولد/کنتراست) و انتخابِ بهترین. */
    private fun readLatin(bmp: Bitmap): List<Block> {
        var best = recognize(bmp, 0, "latin")
        var q = quality(best)
        if (best.isNotEmpty() && q.first < 0.8f) {
            for (v in 1..2) {
                val alt = try { recognize(bmp, v, "latin") } catch (_: Throwable) { continue }
                val aq = quality(alt)
                if (aq.first > q.first + 0.08f && aq.second >= q.second * 0.8f) { best = alt; q = aq }
                if (q.first >= 0.9f) break
            }
        }
        val segs = ArrayList<Seg>()
        for (ln in best) if (ln.isNotEmpty()) segs.addAll(splitSegments(ln))
        return group(segs)
    }

    // ------------------------------------------------------------ تشخیص خودکارِ زبان/خط (رایگان، روی گوشی)
    private fun ascii(s: String): Float { var n = 0; for (c in s) if (c in 'A'..'Z' || c in 'a'..'z') n++; return n.toFloat() }
    // حروفِ خطوطِ غیرلاتین (یونانی/سیریلیک/عبری/عربی/دوناگری/تایلندی/چینی/ژاپنی/کره‌ای)
    private fun nonAscii(s: String): Float { var n = 0; for (c in s) if (c.isLetter() && c.code >= 0x0370) n++; return n.toFloat() }

    /** بلوک‌های یک خط/موتور را می‌دهد (برای شمارشِ حروف در تشخیصِ خودکار). */
    private fun scriptBlocks(bmp: Bitmap, script: String): List<Block> {
        if (script.startsWith("tess:")) return try { OcrTess.read(bmp, script.removePrefix("tess:")) } catch (_: Throwable) { emptyList() }
        val lines = try { recognize(bmp, 0, script) } catch (_: Throwable) { return emptyList() }
        val segs = ArrayList<Seg>()
        for (ln in lines) if (ln.isNotEmpty()) segs.addAll(splitSegments(ln))
        return if (script == "latin") group(segs) else groupSimple(segs)
    }

    /**
     * تشخیصِ خودکار: لاتین + چینی/ژاپنی/کره‌ای/هندی (داخلی) + پک‌های نصب‌شدهٔ تِسِرکت را می‌خواند و
     * تعدادِ حروفِ «همان خط» را می‌شمارد. اگر یک خطِ غیرلاتین مقدارِ قابل‌توجهی حرف داشته باشد (نسبت به
     * لاتین) همان انتخاب می‌شود؛ وگرنه لاتین. این‌طور متنِ روسی/عربی/… دیگر اشتباهاً لاتین خوانده نمی‌شود.
     */
    // برندهٔ خطِ تشخیصِ خودکار از فریمِ قبل؛ تا وقتی زبانِ صفحه عوض نشده، دوباره همه را نمی‌خوانیم (سرعت).
    @Volatile private var autoWinner: String? = null
    @Volatile private var autoFrames = 0

    private fun readAuto(bmp: Bitmap): List<Block> {
        val latBlocks = scriptBlocks(bmp, "latin")
        val latCount = ascii(latBlocks.joinToString(" ") { it.text })

        // هر چند فریم یک‌بار کشفِ کامل انجام می‌شود تا تغییرِ زبانِ صفحه گرفته شود؛ بقیهٔ فریم‌ها از «برندهٔ» قبلی استفاده می‌کنند.
        autoFrames++
        val forceFull = autoFrames % RECHECK_EVERY == 0
        val w = autoWinner
        if (!forceFull && w != null) {
            if (w == "latin") {
                // صفحهٔ واضحِ لاتین → فقط همین؛ خواندنِ چینی/ژاپنی/… لازم نیست.
                if (latCount >= 25f) return latBlocks
            } else {
                // برندهٔ غیرلاتینِ قبلی را اول امتحان کن؛ اگر هنوز پررنگ است، سریع برگرد.
                val b = scriptBlocks(bmp, w)
                val n = nonAscii(b.joinToString(" ") { it.text })
                if (n >= maxOf(10f, latCount * 0.6f)) return b
            }
        }

        // کشفِ کامل: همهٔ خط‌های نامزد خوانده و بهترین انتخاب می‌شود.
        val cands = ArrayList<String>().apply { add("chinese"); add("japanese"); add("korean"); add("devanagari") }
        val tess = try { OcrTess.installedCodes(FarsiApp.ctx) } catch (_: Throwable) { emptySet<String>() }
        if (tess.isNotEmpty()) cands.add("tess:" + tess.toList().take(4).joinToString("+"))

        var bestBlocks: List<Block> = emptyList(); var bestNon = 0f; var bestScript = "latin"
        for (sc in cands) {
            val b = scriptBlocks(bmp, sc)
            val n = nonAscii(b.joinToString(" ") { it.text })
            if (n > bestNon) { bestNon = n; bestBlocks = b; bestScript = sc }
        }
        // خطِ غیرلاتین وقتی برنده است که حروفِ خطِ خودش به‌قدرِ کافی زیاد باشد (حداقل ۱۰ و بیش از ۶۰٪ِ لاتین).
        return if (bestNon >= maxOf(10f, latCount * 0.6f)) {
            autoWinner = bestScript; bestBlocks
        } else {
            autoWinner = "latin"; readLatin(bmp)
        }
    }

    private const val RECHECK_EVERY = 8

    /**
     * مسیر خطوط غیرلاتین: فیلترهای لاتین (تشخیص کلمهٔ بی‌معنی، حروف صدادار، اصلاح حروف بزرگ) اعمال نمی‌شوند؛
     * خط‌ها بر اساس نزدیکی عمودی به پاراگراف تبدیل و بدون تمیزکاری لاتین ترجمه می‌شوند.
     */
    private fun readNonLatin(bmp: Bitmap, script: String): List<Block> {
        val lines = try { recognize(bmp, 0, script) } catch (_: Throwable) { return emptyList() }
        val segs = ArrayList<Seg>()
        for (ln in lines) if (ln.isNotEmpty()) segs.addAll(splitSegments(ln))
        return groupSimple(segs)
    }

    /** گروه‌بندی سادهٔ خط‌ها به پاراگراف برای خطوط غیرلاتین (بدون حذف نویز لاتین). */
    private fun groupSimple(all: List<Seg>): List<Block> {
        val segs = all.sortedWith(compareBy({ it.y }, { it.x }))
        val groups = ArrayList<ArrayList<Seg>>()
        for (s in segs) {
            var placed = false
            for (g in groups) {
                val last = g.last()
                val gx0 = g.minOf { it.x }; val gx1 = g.maxOf { it.x2 }
                val lh = max(1, median(g.map { it.h }))
                val vgap = s.y - last.y2
                val aligned = hOverlap(s.x, s.x2, gx0, gx1) > 0.2f || abs(s.x - gx0) < 2f * lh
                if (vgap > -0.5f * lh && vgap < 1.2f * lh && aligned) { g.add(s); placed = true; break }
            }
            if (!placed) groups.add(arrayListOf(s))
        }
        val out = ArrayList<Block>()
        for (g in groups) {
            val ls = g.filter { it.text.trim().isNotEmpty() }
            if (ls.isEmpty()) continue
            val text = ls.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+"), " ").trim()
            if (text.count { it.isLetterOrDigit() } < 1) continue
            val r = Rect(ls.minOf { it.x }, ls.minOf { it.y }, ls.maxOf { it.x2 }, ls.maxOf { it.y2 })
            out.add(Block(text, r, median(ls.map { it.h }), ls.size, ls.map { Rect(it.x, it.y, it.x2, it.y2) }, false))
        }
        return out
    }

    /** گروه‌بندی کلماتِ خامِ Tesseract (متن + کادر) به بلوک‌های پاراگرافی — برای مسیر Tesseract. */
    fun groupWords(rawWords: List<Pair<String, Rect>>): List<Block> {
        if (rawWords.isEmpty()) return emptyList()
        val ws = rawWords.map { Word(it.first, it.second) }.sortedWith(compareBy({ it.r.top }, { it.r.left }))
        // اول کلمات را بر اساس نزدیکیِ عمودی به خط تبدیل می‌کنیم
        val lines = ArrayList<ArrayList<Word>>()
        for (w in ws) {
            val cy = (w.r.top + w.r.bottom) / 2
            var placed = false
            for (ln in lines) {
                val last = ln.last()
                val lcy = (last.r.top + last.r.bottom) / 2
                val lh = max(1, max(w.r.height(), last.r.height()))
                if (abs(cy - lcy) < 0.6f * lh && w.r.left >= last.r.left - lh) { ln.add(w); placed = true; break }
            }
            if (!placed) lines.add(arrayListOf(w))
        }
        val segs = ArrayList<Seg>()
        for (ln in lines) segs.addAll(splitSegments(ln.sortedBy { it.r.left }))
        return groupSimple(segs)
    }

    private fun quality(lines: List<List<Word>>): Pair<Float, Int> {
        val toks = lines.flatten().map { it.text }.filter { t -> t.any { it.isLetter() } }
        if (toks.isEmpty()) return Pair(0f, 0)
        val good = toks.count { wordOk(it) }
        return Pair(good.toFloat() / toks.size, good)
    }

    private const val SHEAR = 0.21f      // شیب معمول فونت‌های ایتالیک (حدود ۱۲ درجه)

    /** variant 0: تصویر اصلی · 1: بزرگ‌تر، خاکستری و پرکنتراست (فونت نازک/بولد/تزئینی) · 2: صاف کردن شیب ایتالیک */
    private fun recognize(src: Bitmap, variant: Int, script: String = "latin"): List<List<Word>> {
        var scale = 1f
        var shear = 0f
        val img: Bitmap = when (variant) {
            1 -> {
                scale = if (src.height < 900) 1.6f else 1.25f
                val w = (src.width * scale).toInt(); val h = (src.height * scale).toInt()
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val cv = android.graphics.Canvas(out)
                val cm = android.graphics.ColorMatrix()
                cm.setSaturation(0f)
                val c = 1.6f; val t = (-0.5f * c + 0.5f) * 255f
                cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
                val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
                cv.drawBitmap(src, null, android.graphics.Rect(0, 0, w, h), paint)
                out
            }
            2 -> {
                shear = SHEAR
                val extra = (shear * src.height).toInt() + 2
                val out = Bitmap.createBitmap(src.width + extra, src.height, Bitmap.Config.ARGB_8888)
                val cv = android.graphics.Canvas(out)
                cv.drawColor(android.graphics.Color.BLACK)
                val m = android.graphics.Matrix()
                m.setSkew(shear, 0f)          // x' = x + k·y : حروف کج صاف می‌شوند
                cv.drawBitmap(src, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                out
            }
            else -> src
        }
        try {
            val res = Tasks.await(recognizerFor(script).process(InputImage.fromBitmap(img, 0)), 20, TimeUnit.SECONDS)
            val lines = ArrayList<List<Word>>()
            fun map(r: Rect): Rect {
                val cy = (r.top + r.bottom) / 2f
                val dx = shear * cy
                return Rect(((r.left - dx) / scale).toInt(), (r.top / scale).toInt(), ((r.right - dx) / scale).toInt(), (r.bottom / scale).toInt())
            }
            for (b in res.textBlocks) for (ln in b.lines) {
                val words = ArrayList<Word>()
                for (e in ln.elements) {
                    val r = e.boundingBox ?: continue
                    val t = e.text.trim()
                    if (t.isNotEmpty()) words.add(Word(t, map(r)))
                }
                if (words.isEmpty()) {
                    val r = ln.boundingBox ?: continue
                    val t = ln.text.trim()
                    if (t.isNotEmpty()) words.add(Word(t, map(r)))
                }
                if (words.isNotEmpty()) lines.add(words)
            }
            return lines
        } finally {
            if (img !== src) img.recycle()
        }
    }

    // ------------------------------------------------------------ خط‌ها و ستون‌ها
    /** جایی که فاصله کلمه‌ها خیلی زیاد است (دو ستون کنار هم) خط جدا می‌شود */
    private fun splitSegments(words: List<Word>): List<Seg> {
        val ws = words.sortedBy { it.r.left }
        if (ws.size < 2) return listOf(Seg(ws))
        val hs = ws.map { it.r.height() }.sorted()
        val gapLimit = max(1.5f * hs[hs.size / 2], 14f)   // دکمه‌ها و ستون‌های کنار هم یکی نشوند
        val out = ArrayList<Seg>()
        var cur = ArrayList<Word>()
        cur.add(ws[0])
        for (i in 1 until ws.size) {
            if (ws[i].r.left - ws[i - 1].r.right > gapLimit) { out.add(Seg(cur)); cur = ArrayList() }
            cur.add(ws[i])
        }
        out.add(Seg(cur))
        return out
    }

    private fun hOverlap(a0: Int, a1: Int, b0: Int, b1: Int): Float {
        val inter = min(a1, b1) - max(a0, b0)
        return inter.toFloat() / max(1, min(a1 - a0, b1 - b0))
    }

    private fun median(v: List<Int>): Int = v.sorted()[v.size / 2]

    private fun group(all: List<Seg>): List<Block> {
        val segs = all.sortedWith(compareBy({ it.y }, { it.x }))
        val groups = ArrayList<ArrayList<Seg>>()
        for (s in segs) {
            var placed = false
            for (g in groups) {
                val last = g.last()
                val gx0 = g.minOf { it.x }
                val gx1 = g.maxOf { it.x2 }
                val lh = max(1, median(g.map { it.h }))
                val vgap = s.y - last.y2
                val ratio = s.h.toFloat() / last.h
                val sameSize = ratio > 0.6f && ratio < 1.65f
                val aligned = hOverlap(s.x, s.x2, gx0, gx1) > 0.25f || abs(s.x - gx0) < 1.5f * lh
                val nameBox = g.size == 1 && nameLike(last.text) && s.w > 2.2f * last.w
                if (vgap > -0.4f * lh && vgap < 1.1f * lh && sameSize && aligned && !nameBox) {
                    g.add(s); placed = true; break
                }
            }
            if (!placed) groups.add(arrayListOf(s))
        }

        val out = ArrayList<Block>()
        for (g in groups) {
            val keep = g.map { Pair(it, cleanLine(it.text)) }.filter { it.second.isNotEmpty() && !isNoise(it.second) }
            if (keep.isEmpty()) continue
            val ls = keep.map { it.first }
            val ts = keep.map { it.second }
            val isList = ts.size >= 2 && ts.all { t -> t.split(" ").size <= 3 && !Regex("[.!?,]$").containsMatchIn(t) }
            var text = if (isList) ts.joinToString("\n") else joinLines(ts, ls.map { it.w }, ls.maxOf { it.x2 } - ls.minOf { it.x })
            text = Persian.fixCaps(text).replace(Regex("(?<![\\w'’])i(?![\\w'’])"), "I")
            if (!Persian.needsTranslation(text)) continue
            val r = Rect(ls.minOf { it.x }, ls.minOf { it.y }, ls.maxOf { it.x2 }, ls.maxOf { it.y2 })
            out.add(Block(text, r, median(ls.map { it.h }), ls.size, ls.map { Rect(it.x, it.y, it.x2, it.y2) }, isList))
        }
        return out
    }

    private fun nameLike(t: String): Boolean {
        val s = t.trim()
        val n = s.split(" ").size
        return n in 1..3 && s.length <= 24 && s.firstOrNull()?.isUpperCase() == true && !Regex("[.!?,:]$").containsMatchIn(s)
    }

    // ------------------------------------------------------------ تمیزکاری
    private val JUNK = Regex("[®©™•●◆◇■□▲▼►◄▶◀★☆♦♠♣♥~_¦¬`^«»|]+")

    fun cleanLine(src: String): String {
        var s = src.trim()
        if (Regex("[a-z]").containsMatchIn(s)) s = s.replace(Regex("(?<![\\w'’])[l1](?![\\w'’])"), "I")
        s = JUNK.replace(s, " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private val WORD_OK = Regex(
        "^[\"'“‘(\\[]*(" +
            "[A-Za-z][a-z]*(?:['’][a-z]+)?(?:-[A-Za-z][a-z]*)*" +   // Hello · don't · well-known
            "|[A-Z]+(?:['’][A-Z]+)?(?:-[A-Z]+)*" +                   // HELLO
            "|\\d+(?:[.,:/]\\d+)*%?" +                               // 12 · 3.5 · 10%
            "|[A-Za-z]\\d+|\\d+[A-Za-z]{1,3}" +                       // A1 · 2nd
            ")[\"'”’)\\].,!?;:…]*$"
    )
    private val VOWEL = Regex("[aeiouyAEIOUY]")

    private fun wordOk(tok: String): Boolean {
        if (!WORD_OK.matches(tok)) return false
        val core = tok.filter { it in 'a'..'z' || it in 'A'..'Z' }
        if (core.length >= 4 && !VOWEL.containsMatchIn(core) && core != core.uppercase()) return false
        return true
    }

    /** متن بی‌معنی (OCR از روی خط فارسی، آیکون یا فونت تزئینی) */
    fun isNoise(s: String): Boolean {
        if (s.any { it in '؀'..'ۿ' }) return true       // خودش فارسی است
        val compact = s.replace(" ", "")
        val letters = s.count { it.isLetter() }
        if (letters < 2 || letters < compact.length * 0.5) return true
        val toks = s.split(" ").filter { t -> t.any { it.isLetter() } }
        if (toks.isEmpty()) return true
        val good = toks.count { wordOk(it) }
        if (toks.size == 1) {
            val t = toks[0].filter { it in 'a'..'z' || it in 'A'..'Z' }
            return !(good == 1 && t.length >= 2 && (VOWEL.containsMatchIn(t) || t == t.uppercase()))
        }
        return good < toks.size * 0.6
    }

    /** خط‌های شکسته یک جمله با فاصله وصل می‌شوند؛ فهرست‌ها و تیترها خط جدا می‌مانند */
    private fun joinLines(lines: List<String>, widths: List<Int>, blockW: Int): String {
        val sb = StringBuilder()
        for (i in lines.indices) {
            val ln = lines[i]
            if (sb.isEmpty()) { sb.append(ln); continue }
            val prev = lines[i - 1]
            val shortPrev = widths[i - 1] < 0.6 * blockW
            val first = ln.firstOrNull() ?: continue
            when {
                sb.endsWith("-") && first.isLowerCase() -> { sb.setLength(sb.length - 1); sb.append(ln) }
                first.isUpperCase() && !Regex("[,\\-;:]$").containsMatchIn(prev) &&
                    (shortPrev || (prev.split(" ").size <= 5 && !Regex("[.!?]$").containsMatchIn(prev))) -> sb.append("\n").append(ln)
                else -> sb.append(" ").append(ln)
            }
        }
        var t = sb.toString()
        t = t.replace(Regex("\\s+([,.!?;:])"), "$1")
        t = t.replace(Regex("([,.!?;:])(?=[A-Za-z])"), "$1 ")
        t = t.replace(Regex("\\.{4,}"), "...")
        return t.trim()
    }

    /**
     * کادرهای نمایش ترجمه: متن‌های فهرستی (منو، گزینه‌ها) هر خط جدا روی خط خودش؛
     * پاراگراف‌ها یک کادر (چون کل جمله با هم ترجمه شده).
     */
    fun items(b: Block, tr: String, dx: Int = 0, dy: Int = 0): List<TrItem> {
        val parts = tr.split("\n").map { it.trim() }
        if (b.isList && b.lineRects.size > 1 && parts.size == b.lineRects.size) {
            return b.lineRects.mapIndexed { i, r -> TrItem(Rect(r).also { it.offset(dx, dy) }, parts[i], r.height()) }
        }
        return listOf(TrItem(Rect(b.rect).also { it.offset(dx, dy) }, tr, b.lineH))
    }

    /** کلید مقایسه دو نتیجه (برای اینکه ترجمه تکراری دوباره کشیده نشود) */
    fun key(blocks: List<Block>): String = blocks.joinToString("|") { it.text.lowercase().filter { c -> c.isLetterOrDigit() } }
}
