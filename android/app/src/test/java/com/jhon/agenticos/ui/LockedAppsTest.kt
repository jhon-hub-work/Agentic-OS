package com.jhon.agenticos.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockedAppsTest {
    @Test fun matchesByPackageEitherWay() {
        // locked in the orbit as a two-activity key, asked by the dock with the plain package
        assertTrue(LockedApps.lockedIn(setOf("com.bank/.Main#0"), "com.bank"))
        // locked from the dock, asked by the orbit's component key
        assertTrue(LockedApps.lockedIn(setOf("com.bank"), "com.bank/.Other#10"))
        assertTrue(LockedApps.lockedIn(setOf("com.bank"), "com.bank"))
    }

    @Test fun otherPackagesStayOpen() {
        assertFalse(LockedApps.lockedIn(emptySet(), "com.bank"))
        assertFalse(LockedApps.lockedIn(setOf("com.bank"), "com.bankx"))
        assertFalse(LockedApps.lockedIn(setOf("com.bankx/.Main#0"), "com.bank"))
    }
}
