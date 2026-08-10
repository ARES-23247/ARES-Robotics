package com.areslib.logging

import java.io.File

/** Process-level location used by robot log writers and the local log download server. */
object RobotLogEnvironment {
    /** `true` when running on an Android-based FTC Robot Controller. */
    val isAndroid: Boolean by lazy {
        val javaVendor = System.getProperty("java.vendor").orEmpty()
        javaVendor.contains("Android", ignoreCase = true) || File("/sdcard").exists()
    }

    /** Shared directory for completed and `.active` robot logs. */
    val logDirectory: File by lazy {
        if (isAndroid) File("/sdcard/FIRST/telemetry_logs/") else File("./logs/")
    }
}
