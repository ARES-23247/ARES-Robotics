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
    /** Canonical FTC hub transport selected by generated project metadata. */
    @Volatile var ftcHubCommandTransport: String = "STANDARD_SDK"
    /** True only when Photon successfully wrapped at least one real REV hub for this OpMode. */
    @Volatile var ftcPhotonActive: Boolean = false
    /** Whether this project requested the bounded Limelight HTTP proxy. */
    @Volatile var ftcLimelightProxyConfigured: Boolean = false
    /** Whether the requested Limelight proxy currently owns its listeners. */
    @Volatile var ftcLimelightProxyActive: Boolean = false
}
