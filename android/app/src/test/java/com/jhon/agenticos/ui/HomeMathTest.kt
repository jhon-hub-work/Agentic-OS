package com.jhon.agenticos.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HomeMathTest {
    // Jhon, 2 Nov: pull down for search on Home, press Back, and Orbit showed although his layout is Honeycomb
    @Test fun backFromSearchGoesWhereHeCameFrom() {
        assertEquals(HomeMath.BACK_TO_RINGS, HomeMath.searchBack(layoutIsOrbit = true, fromLayout = false))  // Orbit: its rings
        assertEquals(HomeMath.BACK_TO_RINGS, HomeMath.searchBack(layoutIsOrbit = true, fromLayout = true))
        assertEquals(HomeMath.BACK_HOME, HomeMath.searchBack(layoutIsOrbit = false, fromLayout = false))      // pulled down on Home
        assertEquals(HomeMath.BACK_TO_LAYOUT, HomeMath.searchBack(layoutIsOrbit = false, fromLayout = true))  // the layout's search pill
    }

    @Test fun releaseGoesWhereTheFlickWasHeading() {
        assertEquals(1f, HomeMath.settle(0.6f, 0f))     // past halfway, let go still: opens
        assertEquals(0f, HomeMath.settle(0.4f, 0f))     // short of halfway: shuts
        assertEquals(1f, HomeMath.settle(0.2f, 2f))     // short, but flicked up hard: opens
        assertEquals(0f, HomeMath.settle(0.8f, -2f))    // far, but flicked back: shuts
    }

    @Test fun needsCountsApprovalsUrgentAndNext() {
        val home = JSONObject().put("urgent", JSONObject().put("title", "a")).put("next", JSONArray().put(JSONObject()).put(JSONObject()))
        assertEquals(4, HomeMath.needsCount(home, JSONArray().put(JSONObject())))
        assertEquals(0, HomeMath.needsCount(JSONObject(), JSONArray()))
    }

    @Test fun sigIgnoresQuietRefreshesButSeesChanges() {
        val home = JSONObject().put("needs_sig", "x").put("online", true).put("now", 1)
        val appr = JSONArray().put(JSONObject().put("id", "1"))
        val panels = JSONArray().put(JSONObject().put("id", "p").put("title", "Build").put("lines", JSONArray().put("ok")))
        val a = HomeMath.sig(home, appr, panels)
        home.put("now", 2); appr.getJSONObject(0).put("answered", "Approved. Sending.")  // timestamps and local marks
        assertEquals(a, HomeMath.sig(home, appr, panels))
        panels.getJSONObject(0).put("lines", JSONArray().put("failed"))
        assertNotEquals(a, HomeMath.sig(home, appr, panels))
    }
}
