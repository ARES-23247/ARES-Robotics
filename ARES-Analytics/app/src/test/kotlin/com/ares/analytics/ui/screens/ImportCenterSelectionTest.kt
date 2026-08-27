package com.ares.analytics.ui.screens

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportCenterSelectionTest {
    @Test
    fun `test log selection is fail closed without loopback control opt in`() {
        assertTrue(controlledLogSelection(null, "student-run.csv").isEmpty())
        assertTrue(controlledLogSelection("", "student-run.csv").isEmpty())
    }

    @Test
    fun `test log selection supports the platform path separator`() {
        val encoded = listOf("first.csv", "second.wpilog").joinToString(File.pathSeparator)

        assertEquals(
            listOf("first.csv", "second.wpilog"),
            controlledLogSelection("49321", encoded).map(File::getPath),
        )
    }
}
