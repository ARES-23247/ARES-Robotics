package com.ares.analytics.ui.screens.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncStepTest {
    @Test
    fun `path placeholders preserve native conventions`() {
        assertEquals("C:\\Users\\...\\Robots", platformPathPlaceholder("Robots", '\\'))
        assertEquals("/path/to/Robots", platformPathPlaceholder("Robots", '/'))
        assertEquals("C:\\Users\\...\\my-robot-project", platformPathPlaceholder("my-robot-project", '\\'))
        assertEquals("/path/to/my-robot-project", platformPathPlaceholder("my-robot-project", '/'))
    }
}
