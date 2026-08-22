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

/**
 * Marks an FTC OpMode as an explicit user of the experimental Photon Lynx fast path.
 *
 * Photon inspects this marker during the SDK's pre-init notification, before the OpMode's
 * `runOpMode()` method starts and before team hardware is constructed. Implementing it on a
 * shared TeleOp or autonomous base opts every derived OpMode into Photon while leaving unrelated
 * diagnostics and third-party OpModes on the standard FTC SDK transport.
 */
@Deprecated(
    message = "Use AresFtcRuntimeOptionsProvider backed by canonical generated project metadata",
    replaceWith = ReplaceWith("AresFtcRuntimeOptionsProvider"),
)
interface PhotonEnabledOpMode : AresFtcRuntimeOptionsProvider {
    override val aresFtcRuntimeOptions: AresFtcRuntimeOptions
        get() = AresFtcRuntimeOptions(hubCommandTransport = FtcHubCommandTransport.ARES_PHOTON)
}
