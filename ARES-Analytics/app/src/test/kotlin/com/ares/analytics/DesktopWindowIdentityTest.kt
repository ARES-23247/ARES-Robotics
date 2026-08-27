package com.ares.analytics

import com.ares.analytics.desktop.NativeWindowProbe
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopWindowIdentityTest {
    @Test
    fun `accepts only the exact visible valid AWT handle owned by this process`() {
        assertTrue(
            NativeWindowProbe.isExpectedNativeWindow(
                expectedHandle = 101L,
                candidateHandle = 101L,
                ownerPid = 23,
                currentPid = 23,
                valid = true,
                visible = true,
            )
        )
    }

    @Test
    fun `rejects a helper window even when it belongs to this process`() {
        assertFalse(
            NativeWindowProbe.isExpectedNativeWindow(
                expectedHandle = 101L,
                candidateHandle = 202L,
                ownerPid = 23,
                currentPid = 23,
                valid = true,
                visible = true,
            )
        )
    }

    @Test
    fun `rejects stale hidden invalid and foreign handles`() {
        assertFalse(NativeWindowProbe.isExpectedNativeWindow(0L, 0L, 23, 23, valid = true, visible = true))
        assertFalse(NativeWindowProbe.isExpectedNativeWindow(101L, 101L, 23, 23, valid = false, visible = true))
        assertFalse(NativeWindowProbe.isExpectedNativeWindow(101L, 101L, 23, 23, valid = true, visible = false))
        assertFalse(NativeWindowProbe.isExpectedNativeWindow(101L, 101L, 99, 23, valid = true, visible = true))
    }
}
