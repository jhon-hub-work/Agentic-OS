package com.jhon.agenticos.ui

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MotionGlassTest {
    @Test fun launchStartsFromAScreenShapedRectCentredOnTheIcon() {
        val r = LaunchMath.startRect(100, 100, 1080, 2340)
        assertEquals(0, r[0]); assertEquals(100, r[2])
        assertEquals(2340f / 1080f, r[3] / r[2].toFloat(), 0.02f)       // the screen's aspect ratio, so nothing squashes
        assertEquals(50f, r[1] + r[3] / 2f, 1f)                          // centred on the icon
        assertArrayEquals(intArrayOf(0, 0, 0, 0), LaunchMath.startRect(0, 0, 1080, 2340))
        assertArrayEquals(intArrayOf(0, 0, 100, 80), LaunchMath.startRect(100, 80, 0, 0))  // no window size yet: the view itself
    }

    @Test fun rateGateLetsOneThroughPerGap() {
        val g = RateGate(40)
        assertTrue(g.pass(1000)); assertFalse(g.pass(1039)); assertTrue(g.pass(1040)); assertFalse(g.pass(1041))
        assertTrue(RateGate(40).pass(0))  // the first one always plays
    }

    @Test fun blurRadiusSnapsToHalfDpSteps() {
        val step = 1.3125f  // half a dp at 2.625 density
        assertEquals(0, BlurMath.steps(0f, step))
        assertEquals(0, BlurMath.steps(-4f, step))
        assertEquals(0, BlurMath.steps(Float.NaN, step))
        assertEquals(0, BlurMath.steps(0.5f, step))
        assertEquals(8, BlurMath.steps(10.4f, step))
        assertEquals(BlurMath.steps(10.4f, step), BlurMath.steps(10.6f, step))  // nearby radii share one cached effect
        assertEquals(0, BlurMath.steps(10f, 0f))
    }

    @Test fun blurLevelsThinOutAsTheyGrow() {
        assertEquals(0, BlurMath.coarse(0)); assertEquals(0, BlurMath.coarse(1))  // under a dp: no blur at all
        for (n in 2..8) assertEquals(n, BlurMath.coarse(n))
        assertEquals(10, BlurMath.coarse(9)); assertEquals(60, BlurMath.coarse(56)); assertEquals(50, BlurMath.coarse(54))
        val levels = (0..56).map { BlurMath.coarse(it) }
        assertEquals(levels.sorted(), levels)  // never steps back while a blur grows
        assertEquals(19, levels.distinct().size)  // a 28 dp ramp (56 half-dp steps): 18 blur levels plus none
    }

    @Test fun boxBlurKeepsFlatColourAndSpreadsAPoint() {
        val w = 21; val h = 15
        val flat = IntArray(w * h) { 0xFF336699.toInt() }
        BoxBlur.blur(flat, w, h, 3)
        assertTrue(flat.all { it == 0xFF336699.toInt() })

        val px = IntArray(w * h) { 0xFF000000.toInt() }
        val mid = 7 * w + 10
        px[mid] = 0xFFFFFFFF.toInt()
        BoxBlur.blur(px, w, h, 2)
        fun red(i: Int) = px[i] shr 16 and 255
        assertTrue(red(mid) in 1..254)                           // the point spread out
        assertEquals(red(mid - 2), red(mid + 2))                 // evenly left and right
        assertEquals(red(mid - 2 * w), red(mid + 2 * w))         // and up and down
        assertTrue(red(mid) >= red(mid + 3))                     // brightest at the centre
        assertTrue(px.all { it ushr 24 == 255 })                 // stays opaque

        val same = IntArray(w * h) { it * 997 or 0xFF000000.toInt() }
        val copy = same.copyOf()
        BoxBlur.blur(same, w, h, 0)
        assertArrayEquals(copy, same)
    }

    @Test fun glassBandIsAQuarterOfTheShortSideCapped() {
        val cap = 42f
        assertEquals(25f, GlassMath.band(400f, 100f, cap), 1e-4f)
        assertEquals(42f, GlassMath.band(1000f, 600f, cap), 1e-4f)
        assertEquals(84f, GlassMath.amount(42f), 1e-4f)
        assertEquals(84 + 24, GlassMath.margin(84f, 8f))
        assertEquals(0, GlassMath.level(-1f)); assertEquals(GlassMath.LEVELS, GlassMath.level(2f)); assertEquals(4, GlassMath.level(0.5f))
    }

    @Test fun refractionBendsMostAtTheRimAndNotInside() {
        val band = 20f; val amount = 40f
        assertEquals(amount, GlassMath.displacement(0f, band, amount), 1e-4f)          // at the rim, the full reach
        assertEquals(0f, GlassMath.displacement(-20f, band, amount), 1e-4f)            // past the band: clear glass
        assertEquals(0f, GlassMath.displacement(-30f, band, amount), 1e-4f)
        assertEquals(0f, GlassMath.displacement(5f, band, amount), 1e-4f)              // outside the panel: untouched
        assertEquals(0f, GlassMath.displacement(-1f, band, 0f), 1e-4f)                 // reveal 0: no refraction yet
        var prev = Float.MAX_VALUE
        for (k in 0..20) { val d = GlassMath.displacement(-k.toFloat(), band, amount); assertTrue(d <= prev); prev = d }
        assertTrue(abs(GlassMath.displacement(-10f, band, amount) - amount * (1f - kotlin.math.sqrt(0.75f))) < 1e-3f)
    }

    private fun item(pkg: String, app: String, title: String, time: Long, count: Int? = null) =
        JSONObject().put("pkg", pkg).put("app", app).put("title", title).put("line", "hi").put("time", time).apply { count?.let { put("count", it) } }

    @Test fun digestSummaryCountsChatsAndMessages() {
        assertEquals("Nothing new", PanelLogic.digestSummary(emptyList()))
        val five = (1..5).map { item("a", "WhatsApp", "c$it", it.toLong()) }
        assertEquals("New messages in 5 chats", PanelLogic.digestSummary(five))
        assertEquals("New messages in 1 chat", PanelLogic.digestSummary(five.take(1)))
        val counted = listOf(item("a", "WhatsApp", "Mum", 1, 9), item("b", "Messenger", "Team", 2, 5))
        assertEquals("14 messages from 2 chats", PanelLogic.digestSummary(counted))
        assertEquals(1, PanelLogic.digestChats(listOf(item("a", "W", "Mum", 1), item("a", "W", "Mum", 2))))
    }

    @Test fun digestGroupsNewestFirstByApp() {
        val items = listOf(item("w", "WhatsApp", "Mum", 1), item("m", "Messenger", "Team", 5), item("w", "WhatsApp", "Dad", 9),
            item("w", "WhatsApp", "Mum", 3), item("x", "", "Bank", 2))
        val g = PanelLogic.digestGroups(items, 40)
        assertEquals(listOf("WhatsApp", "Messenger", "x"), g.map { it.first })         // app of the newest chat first; no name: its package
        assertEquals(listOf("Dad", "Mum"), g[0].second.map { it.optString("title") })
        assertEquals(3L, g[0].second[1].optLong("time"))                               // a chat appears once, its latest line
        assertEquals(2, PanelLogic.digestGroups(items, 2).sumOf { it.second.size })    // capped
    }
}
