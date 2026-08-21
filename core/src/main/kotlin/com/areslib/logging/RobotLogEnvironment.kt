package com.areslib.logging

import java.io.File

/** Process-level location used by robot log writers and the local log download server. */
object RobotLogEnvironment {
    /** `true` when running on an Android-based FTC Robot Controller. */
    val isAndroid: Boolean by lazy {
        val javaVendor = System.getProperty("java.vendor").orEmpty()
        javaVendor.contains("Android", ignoreCase = true) || File("/sdcard").exists()
    }

    /** `true` for a normal roboRIO filesystem; desktop Linux simulations do not have this root. */
    private val isRoboRio: Boolean by lazy {
        System.getProperty("os.name").orEmpty().contains("Linux", ignoreCase = true) &&
            File("/home/lvuser").isDirectory
    }

    /** Shared directory for completed and `.active` robot logs. */
    val logDirectory: File by lazy {
        if (isAndroid) File("/sdcard/FIRST/telemetry_logs/") else File("./logs/")
    }

    /**
     * Resolves the process logging profile once per logger construction.
     *
     * `ares.logging.profile` or `ARES_LOGGING_PROFILE` may explicitly select COMPETITION,
     * SIMULATION, or FORENSIC. Physical robot runtimes default to COMPETITION; desktop processes
     * default to SIMULATION so ordinary development cannot grow unthrottled logs indefinitely.
     */
    internal fun loggingPolicy(): LoggingPolicy {
        val configured = System.getProperty("ares.logging.profile")
            ?.takeIf(String::isNotBlank)
            ?: System.getenv("ARES_LOGGING_PROFILE")?.takeIf(String::isNotBlank)
        val profile = configured?.let(LoggingProfile::parse) ?: when {
            isAndroid || isRoboRio -> LoggingProfile.COMPETITION
            else -> LoggingProfile.SIMULATION
        }
        return LoggingPolicy.forProfile(profile)
    }

    /**
     * Allows maintenance and validation processes to suppress automatic pruning without changing
     * the selected logging profile. Production defaults to enabled.
     */
    internal fun isRetentionEnabled(): Boolean {
        val configured = System.getProperty("ares.logging.retention.enabled")
            ?.takeIf(String::isNotBlank)
            ?: System.getenv("ARES_LOG_RETENTION_ENABLED")?.takeIf(String::isNotBlank)
            ?: return true
        return when (configured.trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> throw IllegalArgumentException(
                "Invalid ARES log-retention flag '$configured'; expected true or false"
            )
        }
    }
}
