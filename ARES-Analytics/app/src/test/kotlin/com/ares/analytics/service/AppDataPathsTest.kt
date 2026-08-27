package com.ares.analytics.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppDataPathsTest {
    @Test
    fun `explicit property wins over environment and user home`() {
        val result = AppDataPaths.resolveRootDirectory(
            configuredProperty = " C:/isolated/ares-test ",
            configuredEnvironment = "C:/environment/ares-test",
            userHome = "C:/Users/student",
        )

        assertEquals(File("C:/isolated/ares-test").absoluteFile, result)
    }

    @Test
    fun `environment is used when property is blank`() {
        val result = AppDataPaths.resolveRootDirectory(
            configuredProperty = " ",
            configuredEnvironment = "C:/environment/ares-test",
            userHome = "C:/Users/student",
        )

        assertEquals(File("C:/environment/ares-test").absoluteFile, result)
    }

    @Test
    fun `normal installation remains under user home`() {
        val result = AppDataPaths.resolveRootDirectory(
            configuredProperty = null,
            configuredEnvironment = null,
            userHome = "C:/Users/student",
        )

        assertEquals(File("C:/Users/student/.ares-analytics"), result)
    }

    @Test
    fun `child paths cannot escape the data directory`() {
        assertFailsWith<IllegalArgumentException> { AppDataPaths.file("../outside.json") }
    }
}
