package com.jhon.agenticos.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetsTest {
    @Test fun idsSurviveJunkAndDuplicates() {
        assertEquals(listOf(12, 15, 9), WidgetStore.parse(" 12,15,x,,12, 9"))
        assertEquals(emptyList<Int>(), WidgetStore.parse(null))
        assertEquals(emptyList<Int>(), WidgetStore.parse(""))
        assertEquals("12,15", WidgetStore.join(listOf(12, 15)))
        assertEquals(listOf(3, 1), WidgetStore.parse(WidgetStore.join(listOf(3, 1))))
    }

    @Test fun cellsFollowAndroidsRule() {  // n cells = 70n - 30 dp
        assertEquals(1, widgetCells(0))
        assertEquals(1, widgetCells(40))
        assertEquals(2, widgetCells(110))
        assertEquals(3, widgetCells(111))
        assertEquals(4, widgetCells(250))
    }
}
