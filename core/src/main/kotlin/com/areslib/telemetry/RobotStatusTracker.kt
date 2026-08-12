package com.areslib.telemetry

/** Process-local robot status shared by platform telemetry publishers. */
object RobotStatusTracker {
    @Volatile var isEnabled: Boolean = false
    @Volatile var activeOpMode: String = "Disabled"
    @Volatile var visionConnected: Boolean = false
    @Volatile var visionStatus: String = "OFFLINE"
    @Volatile var odometrySource: String = "UNINITIALIZED"
    @Volatile var odometryStatus: String = "UNKNOWN"
    @Volatile var resolvedLimelightIp: String? = null
    @Volatile var activeLimelightIps: List<String> = emptyList()
    @Volatile var uploadProgress: Double = 0.0
    @Volatile var activeUploadFile: String? = null
}
