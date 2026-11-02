package com.jhon.agenticos.core

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The phone's line to the OS on the PC. Lives in :core, so it never touches WebView: the UI process copies the
 *  pairing cookie into a file (WebView refuses to run in two processes on one data folder). */
object Link {
    const val ORIGIN = "https://desktop-tge4frg.tailcb1411.ts.net:8810"
    lateinit var dir: File; private set
    val queue by lazy { SendQueue(File(dir, "queue")) }

    fun init(c: Context) { dir = c.filesDir }

    fun saveCookie(cookie: String?) {
        if (cookie.isNullOrBlank() || cookie == cookie()) return
        try { atomicWrite(File(dir, "pair_cookie"), cookie) } catch (_: IOException) {}  // next page load tries again
    }
    fun cookie(): String? = File(dir, "pair_cookie").takeIf { it.exists() }?.readText()

    /** Heartbeats between processes: :core writes them, the home screen reads their age. */
    fun beat(name: String) = atomicWrite(File(dir, "beat_$name"), System.currentTimeMillis().toString())
    fun lastBeat(name: String): Long = File(dir, "beat_$name").takeIf { it.exists() }?.readText()?.toLongOrNull() ?: 0L
}

/** A write that a reader in another process sees whole or not at all. */
fun atomicWrite(f: File, text: String) {
    f.parentFile?.mkdirs()
    val tmp = File(f.parentFile, f.name + ".tmp" + System.nanoTime())
    tmp.writeText(text)
    try { Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE) }  // replaces f in one step, on Android and on Windows (tests)
    catch (e: Exception) { tmp.delete(); throw IOException("could not write ${f.name}", e) }
}

/** Thrown when the OS answered no (4xx, or 500 for a bad request): sending it again won't help. */
class Refused(val code: Int, path: String) : IOException("$path $code")

/** A call to the OS on the PC with the phone's pairing cookie. Marks "pc" on every answer, so the home screen
 *  can say "PC off since 9:40". */
fun osHttp(path: String, method: String = "GET", type: String? = null, body: ByteArray? = null, timeoutMs: Int = 60000): ByteArray {
    val c = URL(Link.ORIGIN + path).openConnection() as HttpURLConnection
    c.requestMethod = method; c.connectTimeout = minOf(10000, timeoutMs); c.readTimeout = timeoutMs
    Link.cookie()?.let { c.setRequestProperty("Cookie", it) }
    if (body != null && body.isNotEmpty()) { c.doOutput = true; c.setRequestProperty("Content-Type", type); c.outputStream.use { it.write(body) } }
    val code = c.responseCode
    if (code < 500) try { Link.beat("pc") } catch (_: IOException) {}  // Serve answers 502 when the OS is down: that's not the PC being up
    // 500 is the OS itself failing on this request: sending it again can't help (and would block the queue)
    if (code in 400..500) throw Refused(code, path)
    if (code !in 200..299) throw IOException("$path $code")
    return c.inputStream.use { it.readBytes() }
}

/** A multipart/form-data body: the text fields, then one file. Held in memory, so keep files small (photos, voice notes). */
fun multipart(boundary: String, fields: List<Pair<String, String>>, file: String, type: String, data: ByteArray): ByteArray {
    val head = StringBuilder()
    for ((k, v) in fields) head.append("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
    head.append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$file\"\r\nContent-Type: $type\r\n\r\n")
    return head.toString().toByteArray() + data + "\r\n--$boundary--\r\n".toByteArray()
}
