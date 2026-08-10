package com.areslib.logging

import java.io.File

/**
 * Object implementation for Cloud Exporter.
 *
 * Real-time telemetry streaming, diagnostic logging, and NetworkTables 4 communication handler.
 */
object CloudExporter {
    val isAndroid: Boolean by lazy {
        val javaVendor = System.getProperty("java.vendor") ?: ""
        javaVendor.contains("Android", ignoreCase = true) || File("/sdcard").exists()
    }

    val logDir: File by lazy {
        if (isAndroid) File("/sdcard/FIRST/telemetry_logs/") else File("./logs/")
    }

    /** Base URL retained for desktop/simulator replay downloads; it is never used for robot uploads. */
    @Volatile
    var areswebServerUrl: String = System.getenv("ARESWEB_API_URL")
        ?: "https://ares-analytics-gateway-staging-205869391101.us-central1.run.app/api"

    /**
     * Direct robot-to-cloud upload is intentionally disabled. Robot logs are served by
     * [LogManagerServer] on the local network; ARES Analytics pulls and syncs them from
     * the laptop so robot loops never depend on WAN access or cloud credentials.
     */
    @Deprecated("Use the desktop pull/sync pipeline; robots must remain offline-first")
    fun uploadFile(file: File): String? {
        return if (file.exists()) {
            "Direct robot cloud upload is disabled; import this log through ARES Analytics"
        } else {
            "File not found"
        }
    }
}
