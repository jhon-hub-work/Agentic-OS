package com.jhon.agenticos.ui

import android.appwidget.AppWidgetProviderInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSetupTest {
    @Test fun setupRunsOnlyWhenNeeded() {
        assertTrue(widgetNeedsSetup(true, 0))
        assertTrue(widgetNeedsSetup(true, AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE))  // reconfigurable alone still sets up
        assertFalse(widgetNeedsSetup(true, AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL))
        assertFalse(widgetNeedsSetup(true, AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL or AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE))
        assertFalse(widgetNeedsSetup(false, 0))
    }
}
