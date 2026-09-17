package com.ares.analytics.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopInstanceLockTest {
    @Test
    fun `instance lock prevents concurrent acquisition and releases cleanly`() {
        val first = DesktopInstanceLock.tryAcquire()
        assertNotNull(first, "First lock acquisition must succeed")
        assertTrue(first.isHeld, "First lock must be valid and held")

        val second = DesktopInstanceLock.tryAcquire()
        assertNull(second, "Second concurrent lock acquisition must return null")

        first.close()
        assertFalse(first.isHeld, "Lock must no longer be held after close")

        val third = DesktopInstanceLock.tryAcquire()
        assertNotNull(third, "Re-acquisition after close must succeed")
        assertTrue(third.isHeld)
        third.close()
    }
}
