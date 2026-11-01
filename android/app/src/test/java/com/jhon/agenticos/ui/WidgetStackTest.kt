package com.jhon.agenticos.ui

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetStackTest {
    @Test fun stacksParseAndOldFormatStillReads() {
        assertEquals(listOf(listOf(12, 15), listOf(9)), WidgetStore.groupsOf("12+15,9"))
        assertEquals(listOf(listOf(12), listOf(15), listOf(9)), WidgetStore.groupsOf("12,15,9"))
        assertEquals(listOf(listOf(12), listOf(9)), WidgetStore.groupsOf(" 12+x+12,,+, 9+12"))  // junk and repeats dropped
        assertEquals(listOf(12, 15, 9), WidgetStore.parse("12+15,9"))
        assertEquals("12+15,9", WidgetStore.joinGroups(listOf(listOf(12, 15), emptyList(), listOf(9))))
    }

    @Test fun stackUnstackAddRemove() {
        val g = listOf(listOf(1), listOf(2), listOf(3))
        assertEquals(listOf(listOf(1, 2), listOf(3)), WidgetStore.stacked(g, 1))
        assertEquals(g, WidgetStore.stacked(g, 3))  // nothing after the last
        assertEquals(listOf(listOf(1, 2, 3)), WidgetStore.stacked(WidgetStore.stacked(g, 1), 2))
        assertEquals(g, WidgetStore.unstacked(listOf(listOf(1, 2), listOf(3)), 2))
        assertEquals(listOf(listOf(1), listOf(3)), WidgetStore.without(listOf(listOf(1, 2), listOf(3)), 2))
        assertEquals(listOf(listOf(3)), WidgetStore.without(listOf(listOf(2), listOf(3)), 2))
        assertEquals(listOf(listOf(1), listOf(3), listOf(2)), WidgetStore.added(g, 2))
    }

    @Test fun sizesSnapToTheProvidersLimits() {
        assertEquals(1, WidgetSizes.rowsFor(74)); assertEquals(2, WidgetSizes.rowsFor(75)); assertEquals(1, WidgetSizes.rowsFor(0))
        for (dp in listOf(40, 110, 180, 300)) assert(WidgetSizes.heightDp(WidgetSizes.rowsFor(dp)) >= dp)
        assertEquals(listOf(2), WidgetSizes.choices(110, 40, 0, 0, resizable = false))
        assertEquals(listOf(3), WidgetSizes.choices(110, 40, 0, 3, resizable = false))           // target wins
        assertEquals(listOf(1, 2, 3, 4), WidgetSizes.choices(110, 40, 0, 0, resizable = true))
        assertEquals(listOf(2, 3, 4), WidgetSizes.choices(110, 0, 0, 0, resizable = true))       // no min resize: the min
        assertEquals(listOf(2, 3, 4), WidgetSizes.choices(110, 200, 0, 0, resizable = true))     // min resize above min: ignored
        assertEquals(listOf(1, 2), WidgetSizes.choices(110, 40, 200, 0, resizable = true))       // max 200 dp: 2 cells
        assertEquals(listOf(2, 5), WidgetSizes.choices(110, 0, 120, 5, resizable = true))        // odd provider keeps its target
        assertEquals(3, WidgetSizes.pick(3, listOf(1, 2, 3), 2))
        assertEquals(2, WidgetSizes.pick(0, listOf(1, 2, 3), 2))
        assertEquals(2, WidgetSizes.pick(4, listOf(1, 2, 3), 2))
    }

    @Test fun stackLandsOnePageAtATime() {
        assertEquals(0, StackMath.settle(0.4f, 0f, 0, 3))
        assertEquals(1, StackMath.settle(0.6f, 0f, 0, 3))
        assertEquals(1, StackMath.settle(0.3f, 2f, 0, 3))    // a flick carries it over
        assertEquals(1, StackMath.settle(0.3f, 40f, 0, 3))   // but never past the next page
        assertEquals(0, StackMath.settle(-0.2f, -5f, 0, 3))  // nor past the first
        assertEquals(2, StackMath.settle(2.1f, 9f, 2, 3))    // nor past the last
        assertEquals(1, StackMath.settle(1.7f, -3f, 2, 3))
        assertEquals("2 of 3", StackMath.label(1, 3))
    }

    @Test fun pinsAppendAndReplace() {
        val one = Pins.added(null, "com.android.chrome", "s1", "Example")
        val two = Pins.added(one, "com.whatsapp", "c9", "Mum")
        val again = JSONArray(Pins.added(two, "com.android.chrome", "s1", "Example news"))
        assertEquals(2, again.length())
        assertEquals("com.whatsapp", again.getJSONObject(0).getString("pkg"))
        assertEquals("Example news", again.getJSONObject(1).getString("label"))
        assertEquals("s1", again.getJSONObject(1).getString("id"))
        assertEquals(1, JSONArray(Pins.added("not json", "p", "i", "l")).length())
    }
}
