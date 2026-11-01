package com.jhon.agenticos.core

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.atomic.AtomicBoolean

/** Everything the phone tells the PC goes through here: one file per item, so it survives a kill or a reboot, and
 *  it's sent oldest first when the PC answers again. Both processes add; whoever holds the lock sends. */
class SendQueue(private val dir: File) {
    /** ttl: a chat alert an hour late is noise, an approval still counts (the PC says 409 if it expired meanwhile). */
    fun add(path: String, json: String, ttlMs: Long = 24 * 3600_000L, method: String = "POST") {
        val item = JSONObject().put("path", path).put("method", method).put("body", json)
            .put("at", System.currentTimeMillis()).put("ttl", ttlMs)
        atomicWrite(File(dir, "${System.currentTimeMillis()}-${System.nanoTime()}.json"), item.toString())
    }

    fun size() = items().size

    // checked before .lock is opened: closing any handle on it drops this process's lock (POSIX), so a second thread
    // here must never open it while the first is sending. One instance per dir per process (Link.queue).
    private val busy = AtomicBoolean()

    /** Paths still waiting, so a tap made before a restart still shows as made. */
    fun waiting(): List<String> = items().mapNotNull { try { JSONObject(it.readText()).getString("path") } catch (_: Exception) { null } }

    private fun items() = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .sortedWith(compareBy({ it.name.substringBefore('-').toLongOrNull() ?: 0 }, { it.name }))

    /** Sends what it can. Stops at the first item the PC can't take yet (offline, 5xx) so order holds; drops items the
     *  PC refused (409: an approval already answered; not 401, which waits for pairing) and items past their ttl. Returns how many are left. */
    fun flush(send: (path: String, method: String, body: ByteArray) -> Unit = { p, m, b -> osHttp(p, m, "application/json", b, 15000) }): Int {
        if (!busy.compareAndSet(false, true)) return size()  // another thread here is sending
        try {
            dir.mkdirs()
            RandomAccessFile(File(dir, ".lock"), "rw").use { raf ->
                // null: the other process is sending
                val lock = try { raf.channel.tryLock() } catch (_: OverlappingFileLockException) { null } ?: return size()
                try {
                    for (f in items()) {
                        val item = try { JSONObject(f.readText()) } catch (_: Exception) { f.delete(); continue }  // half-written by a crash
                        if (System.currentTimeMillis() - item.optLong("at") > item.optLong("ttl", 24 * 3600_000L)) { f.delete(); continue }
                        try {
                            send(item.getString("path"), item.optString("method", "POST"), item.getString("body").toByteArray())
                            f.delete()
                        } catch (e: Refused) {
                            if (e.code == 401 || e.code == 403) break  // not paired yet (cookie not handed over): wait, don't drop
                            f.delete()
                        }
                        catch (_: IOException) { break }
                    }
                } finally { lock.release() }
            }
        } finally { busy.set(false) }
        return size()
    }
}
