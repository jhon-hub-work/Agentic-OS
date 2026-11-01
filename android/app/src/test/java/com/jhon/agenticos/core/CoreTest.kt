package com.jhon.agenticos.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class CoreTest {
    private fun tmp() = Files.createTempDirectory("aos").toFile()

    @Test fun queueSendsInOrderAndKeepsWhatFailed() {
        val q = SendQueue(tmp())
        q.add("/a", "{}"); q.add("/b", "{}"); q.add("/c", "{}")
        val sent = ArrayList<String>()
        // PC down after the first item: the rest stay, in order
        assertEquals(2, q.flush { p, _, _ -> if (p == "/b") throw IOException("offline"); sent += p })
        assertEquals(listOf("/a"), sent)
        assertEquals(0, q.flush { p, _, _ -> sent += p })
        assertEquals(listOf("/a", "/b", "/c"), sent)
    }

    @Test fun queueDropsRefusedAndExpired() {
        val q = SendQueue(tmp())
        q.add("/done", "{}")             // PC says 409: drop, keep going
        q.add("/old", "{}", ttlMs = -1)  // already past its ttl: never sent
        q.add("/ok", "{}")
        val sent = ArrayList<String>()
        assertEquals(0, q.flush { p, _, _ -> if (p == "/done") throw Refused(409, p); sent += p })
        assertEquals(listOf("/ok"), sent)
    }

    @Test fun queueWaitsWhileUnpaired() {
        val q = SendQueue(tmp())
        q.add("/approve", "{}")
        assertEquals(1, q.flush { p, _, _ -> throw Refused(401, p) })  // cookie not handed over yet: keep it
        assertEquals(listOf("/approve"), q.waiting())
        assertEquals(0, q.flush { _, _, _ -> })
    }

    @Test fun queueFlushWhileSendingSendsNothingTwice() {  // a second flush here must back off before touching .lock
        val q = SendQueue(tmp())
        q.add("/a", "{}")
        val sent = ArrayList<String>()
        assertEquals(0, q.flush { p, _, _ -> sent += p; assertEquals(1, q.flush { p2, _, _ -> sent += p2 }) })
        assertEquals(listOf("/a"), sent)
    }

    @Test fun queueSurvivesAHalfWrittenFile() {
        val dir = tmp()
        val q = SendQueue(dir)
        q.add("/ok", "{}")
        java.io.File(dir, "1-1.json").writeText("{\"path\":")  // a crash mid-write
        assertEquals(0, q.flush { _, _, _ -> })
    }
}
