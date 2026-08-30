package com.areslib.ftc.photon

/** Hub command path selected before an FTC OpMode constructs hardware. */
enum class FtcHubCommandTransport {
    /** Supported FTC SDK path with ARES manual bulk reads. */
    STANDARD_SDK,

    /** Experimental ARES direct motor-write path with per-command SDK fallback. */
    ARES_PHOTON,
}

/** Immutable runtime policy generated from the selected robot project's canonical metadata. */
data class AresFtcRuntimeOptions(
    val hubCommandTransport: FtcHubCommandTransport = FtcHubCommandTransport.STANDARD_SDK,
    val limelightProxyEnabled: Boolean = false,
)

/**
 * Supplies the complete FTC runtime policy early enough for SDK pre-init hooks.
 *
 * Implementations should return a stable value backed by generated project constants. The
 * simulator reads the same policy but leaves hardware-only acceleration inactive.
 */
interface AresFtcRuntimeOptionsProvider {
    val aresFtcRuntimeOptions: AresFtcRuntimeOptions
}
