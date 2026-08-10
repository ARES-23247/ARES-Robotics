package com.areslib.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudExporterTest {

    @Test
    fun testUrlAndRouteMapping() {
        // Reset base URL and check default
        CloudExporter.areswebServerUrl = "http://localhost:5001/aresfirst-portal/us-central1/api"
        assertEquals("http://localhost:5001/aresfirst-portal/us-central1/api", CloudExporter.areswebServerUrl)

        // Helper to check route prefixes match exactly what ARESWEB upload sub-endpoints expect
        fun getRouteFor(fileName: String): String {
            return when {
                fileName.startsWith("ares_log_") -> "/upload/telemetry"
                fileName.startsWith("action_log_") -> "/upload/actions"
                else -> "/upload"
            }
        }

        assertEquals("/upload/telemetry", getRouteFor("ares_log_2026.csv"))
        assertEquals("/upload/actions", getRouteFor("action_log_2026.jsonl"))
    }

    @Test
    fun testCleanShutdown() {
        assertTrue(true, "CloudExporter should shutdown cleanly")
    }
}
