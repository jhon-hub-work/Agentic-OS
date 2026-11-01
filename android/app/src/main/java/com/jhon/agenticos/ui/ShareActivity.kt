package com.jhon.agenticos.ui

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.OpenableColumns
import android.text.TextUtils
import android.text.format.Formatter
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher
import com.jhon.agenticos.R
import com.jhon.agenticos.core.Link
import com.jhon.agenticos.core.Share
import com.jhon.agenticos.core.ShareJob
import com.jhon.agenticos.core.atomicWrite
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/** Share -> Agentic OS: a glass sheet over the app he's in. It shows what came in and three agents; one that can't
 *  take it says why in words. Files are copied first (the share's read grant ends with this screen), then handed to
 *  ShareJob in :core; text for Jharvis goes through the send queue. Works with the PC off: everything waits. */
class ShareActivity : Activity() {
    private class Item(val uri: Uri?, @Volatile var file: File?, val name: String, val mime: String, val size: Long, val durMs: Long) {
        val video get() = mime.startsWith("video/")
        val image get() = mime.startsWith("image/")
        val audio get() = mime.startsWith("audio/")
        fun bytes() = file?.length() ?: size.coerceAtLeast(0)
    }

    private lateinit var look: Look
    private val main = Handler(Looper.getMainLooper())
    private var id = System.currentTimeMillis().toString(36)
    private var resumed = false     // reopened from "Similar film exists. Tap to upload anyway"
    private var still = false       // Remove animations: no blur, no scale, things just appear
    private var blurBehind = false  // the app behind goes soft (cross-window blur; off in power saving)
    private lateinit var scrim: View
    private lateinit var shell: SheetFrame
    private lateinit var body: LinearLayout
    private lateinit var stage: FrameLayout
    private val thumbs = ArrayList<Pair<Item, ImageView>>()
    private var note: TextView? = null
    // dim: scrim + blur behind; show: the sheet materializing; drag: finger offset (px); shift: height change (px); back: predictive Back
    private lateinit var dim: Spring
    private lateinit var show: Spring
    private lateinit var drag: Spring
    private lateinit var shift: Spring
    private lateinit var back: Spring
    private var lastBlur = -1
    private var lastSheetBlur = -1f
    private var app = "another app"
    private var appIcon: Drawable? = null
    private var text: String? = null
    private var subject: String? = null
    private var items: List<Item> = emptyList()
    @Volatile private var closing = false
    @Volatile private var handedOff = false  // a job or the queue owns it now: don't delete the copies
    @Volatile private var copying = false
    private var copied = false
    private var copyError: String? = null
    private var afterCopy: (() -> Unit)? = null
    private var busy = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        look = Look(this); still = reducedMotion(this)
        if (Build.VERSION.SDK_INT >= 34) {  // our springs do the entrance and exit, not the window animation
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0); overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        val resume = intent.getStringExtra("resume")
        if (resume != null) { if (!Share.validId(resume)) { finish(); return }; id = resume; resumed = true }
        window.setDimAmount(0f)  // our own scrim dims with the sheet instead of the theme's fixed dim
        window.setDecorFitsSystemWindows(false)
        window.isNavigationBarContrastEnforced = false
        blurBehind = !still && windowManager.isCrossWindowBlurEnabled
        if (blurBehind) window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        setContentView(frame())
        registerBack()
        if (still) dim.snap(1f) else dim.to(1f, response = 0.5f)  // the app behind dims first, the sheet arrives a beat later
        Thread { load() }.start()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !closing) { closing = true; cleanup(); finish() }  // Home or Recents: the sheet goes
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (::dim.isInitialized) listOf(dim, show, drag, shift, back).forEach { it.onRest = null; it.stop() }
        if (!closing) { closing = true; cleanup() }
        super.onDestroy()
    }

    // ---------- what was shared (off the main thread: provider queries, metadata, thumbnails are all IPC or disk)

    private fun load() {
        if (resumed) {
            val dir = Share.dir(this, id)
            val meta = try { JSONObject(File(dir, ".meta.json").readText()) } catch (_: Exception) { null }
            if (meta == null) { runOnUiThread { finish() }; return }  // the upload already finished or was cleared
            app = meta.optString("app", app)
            val a = meta.optJSONArray("items") ?: JSONArray()
            items = (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { o ->
                File(dir, o.optString("file")).takeIf { it.isFile && it.parentFile == dir }?.let { f ->
                    Item(null, f, o.optString("name", f.name), o.optString("mime"), f.length(), o.optLong("dur")) }
            }
            copied = true
        } else {
            val pkg = referrer?.takeIf { it.scheme == "android-app" }?.host ?: callingPackage
            if (pkg != null && pkg != packageName && pkg != "android") try {
                val ai = packageManager.getApplicationInfo(pkg, 0)
                app = packageManager.getApplicationLabel(ai).toString(); appIcon = packageManager.getApplicationIcon(ai)
            } catch (_: Exception) {}
            text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()?.ifBlank { null }
            subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.ifBlank { null }
            items = uris().mapNotNull { meta(it) }
        }
        runOnUiThread {
            if (closing) return@runOnUiThread
            fill()
            if (items.isNotEmpty() && !copied) { copying = true; Thread { copy() }.start() }
            if (thumbs.isNotEmpty()) Thread { thumbnails() }.start()
        }
    }

    private fun uris(): List<Uri> {
        val out = ArrayList<Uri>()
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { out += it }
        else intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { out += it }
        if (out.isEmpty()) intent.clipData?.let { cd -> for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { out += it } }
        // content:// only: what another app hands over, never a file:// path that points into this app's own files
        return out.filter { it.scheme == ContentResolver.SCHEME_CONTENT }.distinct().take(21)
    }

    private fun meta(u: Uri): Item? = try {
        var name = "shared"; var size = -1L
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0) c.getString(n)?.let { name = it }
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
            }
        }
        val mime = contentResolver.getType(u) ?: intent.type ?: "application/octet-stream"
        var dur = 0L
        if (mime.startsWith("video/") || mime.startsWith("audio/")) {
            val r = MediaMetadataRetriever()
            try { r.setDataSource(this, u); dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
            catch (_: Exception) {} finally { r.release() }
        }
        Item(u, null, name, mime, size, dur)
    } catch (_: Exception) { null }

    private fun thumbnails() {
        val s = Size(look.px(144), look.px(144))
        for ((it, view) in thumbs) {
            if (closing) return
            val bmp: Bitmap? = try {
                val f = it.file
                when {
                    it.uri != null -> contentResolver.loadThumbnail(it.uri, s, null)
                    f != null && it.video -> ThumbnailUtils.createVideoThumbnail(f, s, null)
                    f != null -> ThumbnailUtils.createImageThumbnail(f, s, null)
                    else -> null
                }
            } catch (_: Exception) { null }
            if (bmp != null) runOnUiThread {
                view.setImageBitmap(bmp)
                if (!still) { view.alpha = 0f; view.animate().alpha(1f).setDuration(280).setInterpolator(SpringCurve(1f)).start() }
            }
        }
    }

    /** Copies into filesDir/share/<id>/ right away: the read grant ends when this screen does, the job may run hours later. */
    private fun copy() {
        val dir = Share.dir(this, id)
        val err = try {
            dir.mkdirs()
            val need = items.sumOf { it.size.coerceAtLeast(0) }
            if (StatFs(filesDir.path).availableBytes < need + 256L * 1024 * 1024) "Not enough free space for a copy"
            else {
                val names = HashSet<String>()
                val buf = ByteArray(256 * 1024)
                for (it in items) {
                    var n = Share.safeName(it.name, MimeTypeMap.getSingleton().getExtensionFromMimeType(it.mime))
                    var k = 1
                    while (!names.add(n)) n = "${k++}_$n"
                    val f = File(dir, n)
                    (contentResolver.openInputStream(it.uri!!) ?: throw IOException("no stream")).use { i -> f.outputStream().use { o ->
                        while (true) {
                            if (closing && !handedOff) throw IOException("closed")
                            val r = i.read(buf); if (r < 0) break
                            o.write(buf, 0, r)
                        }
                    } }
                    it.file = f
                }
                atomicWrite(File(dir, ".meta.json"), JSONObject().put("app", app).put("items", JSONArray(items.map {
                    JSONObject().put("file", it.file!!.name).put("name", it.name).put("mime", it.mime).put("dur", it.durMs) })).toString())
                null
            }
        } catch (_: SecurityException) { "Couldn't read what was shared" }
          catch (_: Exception) { "Couldn't keep a copy" }
        copying = false
        if (err != null || (closing && !handedOff)) dir.deleteRecursively()
        runOnUiThread {
            if (closing) return@runOnUiThread
            copied = err == null; copyError = err
            val next = afterCopy; afterCopy = null
            if (err == null) { next?.invoke(); return@runOnUiThread }
            note?.apply { text = err; visibility = View.VISIBLE }
            if (next != null) fail(err) else if (!busy) swap(targets(false))
        }
    }

    // ---------- the sheet

    private fun frame(): View {
        dim = Spring(0f) { place() }; show = Spring(0f) { place() }; back = Spring(0f) { place() }
        drag = Spring(0f, 0.5f) { place() }; shift = Spring(0f, 0.5f) { place() }
        scrim = View(this).apply {
            setBackgroundColor(look.shade(102)); alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener { close() }
        }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(look.px(20), look.px(10), look.px(20), look.px(20)) }
        // double bezel: a clear glass shell holding an ice core (ice, not see-through, so text reads over any app)
        val core = FrameLayout(this).apply {
            background = look.round(look.ground, 22); clipToOutline = true
            foreground = look.round(Color.TRANSPARENT, 22, look.glassEdge)
            addView(FrostView(this@ShareActivity, look), FrameLayout.LayoutParams(-1, -1))
            addView(body, FrameLayout.LayoutParams(-1, -2))
        }
        shell = SheetFrame().apply {
            background = look.shell()
            setPadding(look.px(6), look.px(6), look.px(6), look.px(6))
            elevation = look.px(24).toFloat()
            outlineSpotShadowColor = look.ink; outlineAmbientShadowColor = look.ink
            outlineProvider = object : ViewOutlineProvider() {  // the fill is clear glass, so give the shadow its own soft outline
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, look.px(28).toFloat()); o.alpha = 0.55f }
            }
            alpha = 0f
            accessibilityPaneTitle = "Send to an agent"
            addView(core, FrameLayout.LayoutParams(-1, -2))
        }
        return FrameLayout(this).apply {
            addView(scrim, FrameLayout.LayoutParams(-1, -1))
            addView(shell, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            setOnApplyWindowInsetsListener { _, ins ->  // edge to edge: clear of the bars, the cutout and the keyboard
                val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                (shell.layoutParams as FrameLayout.LayoutParams).setMargins(look.px(10) + i.left, i.top + look.px(10), look.px(10) + i.right, look.px(10) + i.bottom)
                shell.requestLayout(); ins
            }
        }
    }

    /** Every spring lands here: materialize (scale 0.92, blur 8dp, rising from below) and the dim behind, as one. */
    private fun place() {
        val h = max(shell.height, 1).toFloat()
        val d = dim.value.coerceIn(0f, 1f) * (1 - (drag.value / h).coerceIn(0f, 1f))
        scrim.alpha = d
        if (blurBehind) {
            val r = (look.px(22) * d).toInt()
            if (r != lastBlur) { lastBlur = r; window.attributes = window.attributes.apply { blurBehindRadius = r } }
        }
        val p = show.value
        shell.alpha = p.coerceIn(0f, 1f)
        if (still) { shell.translationY = drag.value + shift.value; return }
        shell.translationY = drag.value + shift.value + (1 - p) * look.px(56) + back.value * look.px(28)
        val s = (0.92f + 0.08f * p) * (1 - 0.06f * back.value)
        shell.pivotX = shell.width / 2f; shell.pivotY = h
        shell.scaleX = s; shell.scaleY = s
        val b = look.px(8) * (1 - p).coerceIn(0f, 1f)
        if (abs(b - lastSheetBlur) >= 0.5f) { lastSheetBlur = b; shell.blur(b) }
    }

    private fun fill() {
        body.removeAllViews(); thumbs.clear()
        body.addView(View(this).apply { background = look.round(look.inkA(64), 3); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(look.px(36), look.px(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = look.px(12) })
        body.addView(header())
        body.addView(preview(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = look.px(14) })
        stage = FrameLayout(this)
        body.addView(stage, LinearLayout.LayoutParams(-1, -2).apply { topMargin = look.px(12) })
        stage.addView(targets(!still))
        shell.post { if (!closing) { if (still) show.snap(1f) else show.to(1f, response = 0.55f) } }  // after layout: scale from the real bottom edge
    }

    private fun header() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        appIcon?.let { addView(ImageView(context).apply { setImageDrawable(it); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(look.px(36), look.px(36)).apply { rightMargin = look.px(12) }) }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(look.text("Send to an agent", 20f, look.ink, true))
            addView(look.text("From $app", 14f, look.ink2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_share_close); imageTintList = android.content.res.ColorStateList.valueOf(look.ink2)
            background = InsetDrawable(look.round(look.inkA(20), 16), look.px(8))
            setPadding(look.px(15), look.px(15), look.px(15), look.px(15))
            contentDescription = "Close"
            look.press(this, 0.9f); setOnClickListener { close() }
        }, LinearLayout.LayoutParams(look.px(48), look.px(48)))
    }

    private fun preview(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = look.glassCard(18)
            setPadding(look.px(14), look.px(12), look.px(14), look.px(12))
        }
        val media = items.filter { it.video || it.image }
        val voice = items.filter { it.audio }
        when {
            items.isEmpty() -> {
                val t = text.orEmpty()
                val link = Regex("""https?://\S+""").find(t)?.value
                if (link != null && t.length - link.length < 4) {  // just a link: show where it goes
                    card.addView(LinearLayout(this).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(tile(R.drawable.ic_share_link, true), LinearLayout.LayoutParams(look.px(40), look.px(40)).apply { rightMargin = look.px(12) })
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(look.text(subject ?: (Uri.parse(link).host ?: link).removePrefix("www."), 16f, look.ink, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                            addView(look.text(link, 13f, look.ink3).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE })
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                    })
                } else {
                    subject?.takeIf { !t.contains(it) }?.let { card.addView(look.text(it, 16f, look.ink, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }) }
                    card.addView(look.text(t, 15f, look.ink).apply { maxLines = 5; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.15f) })
                }
            }
            media.isEmpty() && voice.size == 1 -> card.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(tile(R.drawable.ic_share_voice, true), LinearLayout.LayoutParams(look.px(40), look.px(40)).apply { rightMargin = look.px(12) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(look.text("Voice note", 16f, look.ink, true))
                    addView(look.text(listOfNotNull(clock(voice[0].durMs), size(voice[0].bytes())).joinToString(", "), 14f, look.ink2))
                })
            })
            else -> {
                if (media.isNotEmpty()) card.addView(LinearLayout(this).apply {
                    val first = media.take(4)
                    first.forEachIndexed { k, it ->
                        addView(thumb(it, if (k == first.lastIndex && media.size > 4) media.size - 4 else 0),
                            LinearLayout.LayoutParams(look.px(68), look.px(68)).apply { if (k > 0) leftMargin = look.px(8) })
                    }
                }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = look.px(10) })
                card.addView(look.text(summary(), 14f, look.ink2))
            }
        }
        note = look.text(copyError.orEmpty(), 14f, look.bad).apply { visibility = if (copyError == null) View.GONE else View.VISIBLE }
        card.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = look.px(6) })
        return card
    }

    private fun thumb(it: Item, more: Int) = FrameLayout(this).apply {
        val img = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; background = look.round(look.inkA(28), 14); clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        thumbs += it to img
        addView(img, FrameLayout.LayoutParams(-1, -1))
        val label = when { more > 0 -> "+$more"; it.video -> clock(it.durMs); else -> null }
        label?.let { s -> addView(look.text(s, 13f, Color.WHITE, true).apply {
            background = look.round(look.shade(120), 8); setPadding(look.px(6), look.px(1), look.px(6), look.px(1))
        }, FrameLayout.LayoutParams(-2, -2, if (more > 0) Gravity.CENTER else Gravity.BOTTOM or Gravity.END).apply { setMargins(look.px(4), look.px(4), look.px(4), look.px(4)) }) }
    }

    private fun tile(icon: Int, on: Boolean) = ImageView(this).apply {
        setImageResource(icon)
        imageTintList = android.content.res.ColorStateList.valueOf(if (on) look.onNavy else look.ink3)
        background = look.round(if (on) look.navy else look.inkA(26), 12)
        setPadding(look.px(10), look.px(10), look.px(10), look.px(10))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // ---------- targets

    private val names = listOf("Jharvis", "Job search", "DYE editor")
    private val icons = listOf(R.drawable.ic_share_jharvis, R.drawable.ic_share_jobs, R.drawable.ic_share_dye)

    private fun reasons(): List<String?> {
        val audio = items.filter { it.audio }.map { it.bytes() }
        val r = Share.reasons(if (items.isEmpty()) text else null, audio, items.count { it.video }, items.count { it.image },
            items.count { !it.video && !it.image && !it.audio }).toMutableList()
        if (items.isNotEmpty() && copyError != null) for (k in r.indices) if (r[k] == null) r[k] = copyError
        return r
    }

    private fun targets(cascade: Boolean): View {
        val images = items.count { it.image }
        val ok = listOf(
            if (items.any { it.audio }) "Transcribes it, then sends it" else "Sends it as a message",
            "Adds the job to the board",
            if (images > 0) "Starts an edit with ${count(images, "photo")}" else "Starts an edit of this video")
        val why = reasons()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            for (k in 0..2) {
                val row = row(k, why[k] ?: ok[k], why[k] == null)
                addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (k > 0) topMargin = look.px(8) })
                if (cascade) {  // the three arrive together, so they cascade once; never again on the way back
                    row.alpha = 0f; row.translationY = look.px(18).toFloat()
                    row.animate().alpha(1f).translationY(0f).setStartDelay(90L + 55L * k).setDuration(560).setInterpolator(SpringCurve(1f)).start()
                }
            }
        }
    }

    private fun row(k: Int, sub: String, on: Boolean) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; minimumHeight = look.px(64)
        background = look.glassCard(18)
        setPadding(look.px(12), look.px(10), look.px(12), look.px(10))
        val icon = tile(icons[k], on)
        addView(icon, LinearLayout.LayoutParams(look.px(44), look.px(44)).apply { rightMargin = look.px(14) })
        val title = look.text(names[k], 17f, look.ink, true)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(look.text(sub, 14f, if (on) look.ink2 else look.ink3))  // the reason stays at full contrast: it's the answer
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (on) {
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_set_chevron); imageTintList = android.content.res.ColorStateList.valueOf(look.ink3)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(look.px(18), look.px(18)))
            contentDescription = "${names[k]}. $sub"
            look.press(this, 0.97f)
            setOnClickListener { if (!busy && !closing) { busy = true; pick(k) } }
        } else {
            icon.alpha = 0.5f; title.alpha = 0.5f
            contentDescription = "${names[k]}, unavailable. $sub"
        }
    }

    private fun pick(k: Int) = when (k) {
        0 -> if (items.isEmpty()) sendText() else whenCopied { sendVoice() }
        1 -> sendJob()
        else -> checkFilm()
    }

    private fun whenCopied(then: () -> Unit) {
        when {
            copied -> then()
            copyError != null -> fail(copyError!!)
            else -> { afterCopy = then; swap(waiting("Getting it ready")) }
        }
    }

    private fun pcUp() = System.currentTimeMillis() - Link.lastBeat("pc") < 20_000  // :core reads the outbox every 3 s

    private fun sendText() {
        val t = listOfNotNull(subject?.takeIf { text?.contains(it) != true }, text).joinToString("\n")
        swap(waiting("Sending"))
        val answered = AtomicBoolean(false)
        fun result(sent: Boolean?) {
            if (answered.compareAndSet(false, true)) runOnUiThread {
                if (sent == null) fail("Couldn't save it. Try again.")
                else done(sent, if (sent) "Sent" else "Sends when the PC is back", if (sent) "Jharvis has it" else null)
            }
        }
        Thread {
            try { Link.queue.add("/api/jharvis/messages", Share.message(app, t)) } catch (_: IOException) { result(null); return@Thread }
            handedOff = true
            main.postDelayed({ result(false) }, 3500)  // the PC is slow or away: it's queued, say so and get out of the way
            result(try { Link.queue.flush() == 0 } catch (_: Exception) { false })
        }.start()
    }

    private fun sendVoice() {
        val v = items.first { it.audio }
        Thread {
            val ok = ShareJob.voice(this, id, v.file!!.name, app); val up = pcUp()
            runOnUiThread {
                if (!ok) { fail("Couldn't queue it. Try again."); return@runOnUiThread }
                handedOff = true
                done(up, if (up) "Sent" else "Sends when the PC is back", if (up) "Jharvis transcribes it first" else null)
            }
        }.start()
    }

    private fun sendJob() {
        val url = Share.indeedLink(text) ?: return
        Thread {
            val ok = ShareJob.indeed(this, id, url); val up = pcUp()
            runOnUiThread {
                if (!ok) { fail("Couldn't queue it. Try again."); return@runOnUiThread }
                handedOff = true
                done(up, if (up) "Sent" else "Sends when the PC is back", "It shows on the board in a few minutes")
            }
        }.start()
    }

    // DYE: name check first (never remake a film that exists), then always ask, because finishing starts a paid edit.
    private fun checkFilm() {
        val v = items.first { it.video }
        swap(waiting("Checking the board for a similar film"))
        Thread {
            val sim = try { Share.check(v.name.substringBeforeLast('.'), v.bytes(), v.durMs / 1000.0) } catch (_: Exception) { null }
            runOnUiThread {
                if (closing) return@runOnUiThread
                when {
                    sim == null -> confirm(false, false, "Couldn't reach the board to check. The upload checks first and stops if a similar film is there.")
                    sim.isNotEmpty() -> similar(sim)
                    else -> confirm(false, true, null)
                }
            }
        }.start()
    }

    private fun similar(found: List<String>) {
        shell.performHapticFeedback(HapticFeedbackConstants.REJECT)
        swap(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(look.text("Something similar is on the board", 20f, look.ink, true))
            addView(look.text("Remaking a film that exists costs a long edit.", 14f, look.ink2), lp(4))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; background = look.glassCard(18)
                setPadding(look.px(14), look.px(10), look.px(14), look.px(10))
                found.take(3).forEach { addView(look.text(it, 15f, look.ink).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }) }
                if (found.size > 3) addView(look.text("and ${found.size - 3} more", 14f, look.ink3))
            }, lp(12))
            addView(buttons("Make it anyway", { confirm(true, true, null) }, "Cancel", { close() }), lp(14))
        })
    }

    private fun confirm(force: Boolean, checked: Boolean, why: String?) {
        val v = items.first { it.video }
        swap(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(look.text("Start the edit now?", 22f, look.ink, true))
            addView(look.text("This uploads ${v.name} (${size(items.filter { it.video || it.image }.sumOf { it.bytes() }) ?: "size unknown"}) and starts a paid edit on the PC.",
                15f, look.ink2).apply { setLineSpacing(0f, 1.15f) }, lp(6))
            why?.let { addView(look.text(it, 14f, look.ink3).apply { setLineSpacing(0f, 1.15f) }, lp(8)) }
            addView(buttons("Not now", { busy = false; swap(targets(false)) }, "Start edit", { whenCopied { startFilm(force, checked) } }), lp(16))
        })
    }

    private fun startFilm(force: Boolean, checked: Boolean) {
        val v = items.first { it.video }
        val files = items.filter { it.video || it.image }.map { it.file!! }
        swap(waiting("Starting the upload"))
        Thread {
            val ok = ShareJob.dye(this, id, v.name.substringBeforeLast('.'), v.file!!.name, files.map { it.name }.toTypedArray(),
                files.sumOf { it.length() }, v.durMs / 1000.0, force, checked)
            val up = pcUp()
            runOnUiThread {
                if (!ok) { fail("Couldn't start the upload. Try again."); return@runOnUiThread }
                handedOff = true
                done(up, if (up) "Uploading" else "Uploads when the PC is back", "Progress is in your notifications")
            }
        }.start()
    }

    // ---------- states

    private fun waiting(what: String) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; minimumHeight = look.px(64)
        addView(ProgressBar(context).apply {
            isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(look.glacier)
        }, LinearLayout.LayoutParams(look.px(22), look.px(22)).apply { rightMargin = look.px(12) })
        addView(look.text(what, 16f, look.ink2))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    /** "Sent" or "Sends when the PC is back", then the sheet dissolves. */
    private fun done(sent: Boolean, title: String, sub: String?) {
        val badge = ImageView(this).apply {
            setImageResource(if (sent) R.drawable.ic_share_check else R.drawable.ic_share_clock)
            imageTintList = android.content.res.ColorStateList.valueOf(if (sent) Color.WHITE else look.ink2)
            background = if (sent) look.round(look.glacier, 28) else look.round(if (look.night) look.glass else Color.argb(217, 255, 255, 255), 28, look.glassEdge)
            setPadding(look.px(14), look.px(14), look.px(14), look.px(14))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        swap(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, look.px(6), 0, look.px(4))
            addView(badge, LinearLayout.LayoutParams(look.px(56), look.px(56)))
            addView(look.text(title, 22f, look.ink, true).apply { gravity = Gravity.CENTER }, lp(12))
            sub?.let { addView(look.text(it, 14f, look.ink2).apply { gravity = Gravity.CENTER }, lp(2)) }
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
        shell.performHapticFeedback(HapticFeedbackConstants.CONFIRM)  // same frame as the badge arrives
        if (!still) { badge.scaleX = 0.6f; badge.scaleY = 0.6f
            badge.animate().scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(SpringCurve(1f)).start() }
        main.postDelayed({ close() }, 1300)
    }

    private fun fail(why: String) {
        shell.performHapticFeedback(HapticFeedbackConstants.REJECT)
        swap(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(look.text(why, 17f, look.ink, true))
            addView(buttons(null, null, "Back", { busy = false; swap(targets(false)) }), lp(14))
        })
    }

    private fun buttons(a: String?, onA: (() -> Unit)?, b: String, onB: () -> Unit) = LinearLayout(this).apply {
        if (a != null && onA != null) addView(look.button(a, false, onA), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = look.px(8) })
        addView(look.button(b, true, onB), LinearLayout.LayoutParams(0, -2, 1f))
    }

    /** New content materializes in place; the sheet's top edge glides to its new height instead of jumping. */
    private fun swap(v: View) {
        val before = shell.height
        stage.removeAllViews(); stage.addView(v)
        if (still) return
        v.alpha = 0f
        Spring(0f) { p -> v.alpha = p; val s = 0.97f + 0.03f * p; v.scaleX = s; v.scaleY = s; v.blur(look.px(6) * (1 - p)) }.to(1f, response = 0.42f)
        shell.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                shell.viewTreeObserver.removeOnPreDrawListener(this)
                val delta = shell.height - before
                if (before > 0 && delta != 0) { shift.snap(shift.value + delta); shift.to(0f, response = 0.5f) }
                return true
            }
        })
    }

    // ---------- leaving

    /** Out the same way it came in: sinks, shrinks and blurs while the app behind comes back into focus. */
    private fun close() {
        if (closing) return
        closing = true
        main.removeCallbacksAndMessages(null)
        cleanup()
        if (still) { finish(); return }
        show.to(0f, response = 0.4f)
        dim.onRest = { finish() }
        dim.to(0f, response = 0.42f)
    }

    private fun flung(v: Float) {
        if (closing) return
        closing = true
        main.removeCallbacksAndMessages(null)
        cleanup()
        drag.onRest = { finish() }
        drag.to(shell.height + look.px(80).toFloat(), response = 0.35f, velocity = v)
        dim.to(0f, response = 0.35f)
    }

    /** Nothing was sent: drop the copies (the copy thread drops them itself if it's still running). */
    private fun cleanup() {
        if (!handedOff && !copying) { val dir = Share.dir(this, id); Thread { dir.deleteRecursively() }.start() }
    }

    private fun registerBack() {
        if (Build.VERSION.SDK_INT >= 34) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            object : OnBackAnimationCallback {  // predictive Back: the sheet leans away with the gesture, springs back if cancelled
                override fun onBackProgressed(e: BackEvent) { if (!still && !closing) back.snap(e.progress) }
                override fun onBackCancelled() { back.to(0f, response = 0.35f) }
                override fun onBackInvoked() { close() }
            })
        else onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { close() }
    }

    // ---------- small things

    private fun lp(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = look.px(top) }
    private fun count(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"
    private fun clock(ms: Long) = if (ms <= 0) null else "%d:%02d".format(ms / 60000, ms / 1000 % 60)
    private fun size(b: Long) = if (b <= 0) null else Formatter.formatShortFileSize(this, b)
    private fun summary(): String {
        val v = items.count { it.video }; val i = items.count { it.image }; val o = items.size - v - i
        val what = listOfNotNull(if (v > 0) count(v, "video") else null, if (i > 0) count(i, "photo") else null, if (o > 0) count(o, "file") else null)
            .joinToString(" and ")
        return listOfNotNull(what, size(items.sumOf { it.bytes() })).joinToString(", ")
    }

    /** The sheet follows the finger down 1:1 (rubber band going up), a flick throws it away, a short pull springs back.
     *  Grabbing it mid-spring catches it where it is. */
    private inner class SheetFrame : FrameLayout(this@ShareActivity) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var y0 = 0f
        private var start = 0f
        private var dragging = false
        private var over = false
        private var vt: VelocityTracker? = null

        override fun onMeasure(w: Int, h: Int) {  // a phone-width sheet even on a wide screen
            val max = look.px(560)
            super.onMeasure(if (MeasureSpec.getSize(w) > max) MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY) else w, h)
        }

        private fun track(e: MotionEvent) { val m = MotionEvent.obtain(e); m.setLocation(e.rawX, e.rawY); vt?.addMovement(m); m.recycle() }

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                y0 = e.rawY; dragging = false; over = false
                vt?.recycle(); vt = VelocityTracker.obtain()
                if (!closing) { drag.stop(); start = drag.value }
            }
            track(e)
            val r = super.dispatchTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (dragging) release() else if (drag.value != 0f && !closing) drag.to(0f, response = 0.4f)
                dragging = false
            }
            return r
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            if (!dragging && !closing && e.actionMasked == MotionEvent.ACTION_MOVE && e.rawY - y0 > slop) begin(e)
            return dragging
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_MOVE && !closing) {
                if (!dragging && abs(e.rawY - y0) > slop) begin(e)
                if (dragging) {
                    val dy = start + e.rawY - y0
                    drag.snap(if (dy < 0) -rubberband(-dy, height.toFloat()) else dy)
                    val now = drag.value > height * 0.45f
                    if (now != over && Build.VERSION.SDK_INT >= 34) performHapticFeedback(
                        if (now) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE)
                    over = now
                }
            }
            return true  // the sheet's own background keeps its taps: they never fall through to the scrim
        }

        private fun begin(e: MotionEvent) { dragging = true; y0 = e.rawY; parent.requestDisallowInterceptTouchEvent(true) }

        private fun release() {
            val t = vt ?: return
            t.computeCurrentVelocity(1000)
            val v = t.yVelocity
            if (drag.value + project(v) > height * 0.45f) flung(v)  // where the flick was heading, not where it let go
            else drag.to(0f, response = 0.4f, damping = if (abs(v) > 600) 0.8f else 1f, velocity = v)  // bounce only after a real flick
        }
    }
}
