package com.ares.analytics.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DatabaseResourceSettingsTest {
    @Test
    fun `defaults scale below the former fixed one gigabyte and four threads`() {
        val settings = resolveDuckDbResourceSettings(
            requestedMemoryLimit = null,
            requestedWorkerThreads = null,
            maxJvmMemoryBytes = 4L * 1024L * 1024L * 1024L,
            availableProcessors = 8,
        )

        assertEquals("768MB", settings.memoryLimit)
        assertEquals(4, settings.workerThreads)
    }

    @Test
    fun `validated overrides support constrained pit laptops`() {
        val settings = resolveDuckDbResourceSettings(
            requestedMemoryLimit = "384mb",
            requestedWorkerThreads = 2,
            maxJvmMemoryBytes = 8L * 1024L * 1024L * 1024L,
            availableProcessors = 16,
        )

        assertEquals("384MB", settings.memoryLimit)
        assertEquals(2, settings.workerThreads)
    }

    @Test
    fun `invalid SQL setting fragments are rejected before database startup`() {
        assertFailsWith<IllegalArgumentException> {
            resolveDuckDbResourceSettings(
                requestedMemoryLimit = "1GB'; DROP TABLE sessions; --",
                requestedWorkerThreads = 2,
                maxJvmMemoryBytes = 4L * 1024L * 1024L * 1024L,
                availableProcessors = 8,
            )
        }
    }
}
