package com.jhon.agenticos.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class WaveCorePanelsTest {
    @Test fun orbIsAUnitSphereWithRowsAsBands() {
        val out = FloatArray(WaveMath.ROWS * WaveMath.COLS * 3)
        WaveMath.orb(WaveMath.ROWS, WaveMath.COLS, out)
        for (i in 0 until out.size / 3) {
            val len = sqrt(out[i * 3] * out[i * 3] + out[i * 3 + 1] * out[i * 3 + 1] + out[i * 3 + 2] * out[i * 3 + 2])
            assertEquals(1f, len, 1e-4f)
        }
        // the nearest grid row wraps the bottom of the orb, the farthest the top (averaged, the tilt mixes y and z)
        fun meanY(row: Int) = (0 until WaveMath.COLS).map { out[(row * WaveMath.COLS + it) * 3 + 1] }.average()
        assertTrue(meanY(0) < meanY(WaveMath.ROWS / 2))
        assertTrue(meanY(WaveMath.ROWS / 2) < meanY(WaveMath.ROWS - 1))
        // the middle column faces the viewer
        val mid = (WaveMath.ROWS / 2 * WaveMath.COLS + WaveMath.COLS / 2) * 3
        assertTrue(out[mid + 2] > 0.5f)
    }

    @Test fun touchMapsBackToTheFieldItLandsOn() {
        val f = 300f; val cx = 540f; val horizon = 200f
        for ((x, z) in listOf(0f to 0f, 4f to 3f, -9f to 10f)) {
            val sx = WaveMath.screenX(x, z, cx, f); val sy = WaveMath.screenY(0f, z, horizon, f)
            val gz = WaveMath.groundZ(sy, horizon, f)
            assertEquals(z, gz, 1e-3f)
            assertEquals(x, WaveMath.groundX(sx, gz, cx, f), 1e-3f)
        }
        assertEquals(WaveMath.DEPTH, WaveMath.groundZ(horizon - 10f, horizon, f), 0f)  // above the horizon: the far edge
    }

    @Test fun swellAndRipplesDieOut() {
        assertEquals(0f, WaveMath.swell(1f, 3f), 0f)
        assertEquals(0f, WaveMath.ripple(1f, 2.5f), 0f)
        var peak = 0f
        for (k in 0 until 200) peak = maxOf(peak, abs(WaveMath.height(k * 0.11f - 11f, k * 0.06f, k * 0.03f, 1f)))
        assertTrue(peak < 1.3f)
    }

    @Test fun toolNamesReadAsWords() {
        assertEquals("Run agent", PanelLogic.humanise("os_run_agent"))
        assertEquals("Send message", PanelLogic.humanise("sendMessage"))
        assertEquals("Os", PanelLogic.humanise("os"))
        assertEquals("Something", PanelLogic.humanise(""))
    }

    @Test fun argsShowInFull() {
        assertEquals("rm -rf build/", PanelLogic.argsText(JSONObject().put("command", "rm -rf build/")))
        assertEquals("", PanelLogic.argsText(JSONObject()))
        assertEquals("", PanelLogic.argsText(null))
        val t = PanelLogic.argsText(JSONObject().put("path", "a/b").put("n", 2))
        assertTrue(t.contains("\"path\": \"a/b\"") && t.contains("\n"))
        assertEquals("", PanelLogic.argsText(JSONArray()))
    }

    @Test fun answerStatusPrefersTheQueue() {
        assertNull(PanelLogic.status("7", null, emptySet()))
        assertEquals("Approved. Sending.", PanelLogic.status("7", true, emptySet()))
        assertEquals("Declined. Sending.", PanelLogic.status("7", false, emptySet()))
        assertEquals("Answered. Sends when the PC is back.", PanelLogic.status("7", true, setOf("/api/jharvis/approvals/7")))
        assertEquals("Answered. Sends when the PC is back.", PanelLogic.status("7", null, setOf("/api/jharvis/approvals/7")))
    }

    @Test fun signaturesChangeOnlyWithContent() {
        val a = JSONObject().put("id", "1").put("tool", "t").put("args", JSONObject().put("command", "ls"))
        assertEquals(PanelLogic.askSig(a, null), PanelLogic.askSig(JSONObject(a.toString()), null))
        assertTrue(PanelLogic.askSig(a, null) != PanelLogic.askSig(a, "Approved. Sending."))
        val p = JSONObject().put("title", "Jobs").put("lines", JSONArray().put("one"))
        assertTrue(PanelLogic.panelSig(p) != PanelLogic.panelSig(JSONObject(p.toString()).put("lines", JSONArray().put("two"))))
    }
}
