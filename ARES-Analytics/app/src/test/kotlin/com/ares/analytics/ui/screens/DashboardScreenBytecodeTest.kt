package com.ares.analytics.ui.screens

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse

class DashboardScreenBytecodeTest {
    @Test
    fun `replay toolbar does not depend on a top-level synthetic enum mapping`() {
        val classResource = DashboardScreenBytecodeTest::class.java.classLoader
            .getResource("com/ares/analytics/ui/screens/DashboardScreenKt.class")
            ?: error("Dashboard screen class resource is unavailable")
        if (classResource.protocol != "file") return

        val screensDirectory = File(classResource.toURI()).parentFile
        assertFalse(
            File(screensDirectory, "DashboardScreenKt\$WhenMappings.class").exists(),
            "Replay toolbar must not rely on an incrementally fragile synthetic mapping class"
        )
    }
}
