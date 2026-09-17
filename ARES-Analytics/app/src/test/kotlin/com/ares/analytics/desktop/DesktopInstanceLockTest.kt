package com.ares.analytics.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopInstanceLockTest {
    @org.junit.Rule @JvmField
    val temporary = org.junit.rules.TemporaryFolder()

    @Test
    fun `unusable data directory reports an IO error instead of another running instance`() {
        kotlin.test.assertFailsWith<java.io.IOException> {
            DesktopInstanceLock.tryAcquire(temporary.newFile("not-a-directory"))
        }
    }

    @Test
    fun `instance lock prevents concurrent acquisition and releases cleanly`() {
        val directory = temporary.newFolder("instance")
        val first = DesktopInstanceLock.tryAcquire(directory)
        assertNotNull(first, "First lock acquisition must succeed")
        assertTrue(first.isHeld, "First lock must be valid and held")

        val second = DesktopInstanceLock.tryAcquire(directory)
        assertNull(second, "Second concurrent lock acquisition must return null")

        first.close()
        assertFalse(first.isHeld, "Lock must no longer be held after close")

        val third = DesktopInstanceLock.tryAcquire(directory)
        assertNotNull(third, "Re-acquisition after close must succeed")
        assertTrue(third.isHeld)
        third.close()
    }
}
