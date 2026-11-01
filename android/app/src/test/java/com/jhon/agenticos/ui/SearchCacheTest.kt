package com.jhon.agenticos.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchCacheTest {
    /** The matcher as it was before labels were prepared once: everything worked out on every call. */
    private fun uncached(q: String, name: String): Int {
        val k = SmartSearch.normalize(q).trim()
        if (k.isEmpty()) return -1
        val n = SmartSearch.normalize(name)
        val at = n.indexOf(k)
        if (at == 0) return 0
        if (at > 0) {
            var i = at
            while (i >= 0) { if (i == 0 || !n[i - 1].isLetterOrDigit()) return 1; i = n.indexOf(k, i + 1) }
            return 2
        }
        val tight = k.replace(" ", "")
        if (SmartSearch.subsequence(tight, n)) return 3
        val pool = n.split(' ', '-', '_', '.', ':').filter { it.isNotEmpty() } + n.replace(" ", "").take(tight.length + 1)
        if (tight.length >= 3 && pool.any { SmartSearch.jaroWinkler(tight, it) >= 0.85 }) return 4
        return -1
    }

    private val labels = listOf("Café Bustelo", "YT Music", "Spotify", "Instagram", "Google Maps", "Gmail", "Maps Lite",
        "Smart Manager", "Bitmap Maps", "Calendar", "Wi-Fi", "Do not disturb", "New message", "Settings: Battery", "my_files.app")
    private val queries = listOf("m", "ma", "MAP", "cafe", "ytm", "spotfy", "sptoify", "instagarm", "gm", "lend", "wifi",
        "wi fi", "dnd", "batery", "zzz", "  ", "new mesage", "files")

    @Test fun cachedScoresMatchUncached() {
        SmartSearch.prepare(labels.take(5))  // some prepared ahead, the rest filled in on first use
        repeat(2) {  // second pass reads only from the cache
            for (q in queries) for (l in labels) assertEquals("$q / $l", uncached(q, l), SmartSearch.score(q, l))
        }
    }

    @Test fun cachedRankMatchesUncached() {
        for (q in queries) {
            val want = labels.withIndex().map { (i, l) -> Triple(l, uncached(q, l), i) }.filter { it.second >= 0 }
                .sortedWith(compareBy({ it.second }, { it.third })).map { it.first }
            assertEquals(q, want, SmartSearch.rank(q, labels) { it })
        }
    }
}
