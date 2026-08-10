package com.areslib.ftc.photon

/**
 * Marks an FTC OpMode as an explicit user of the experimental Photon Lynx fast path.
 *
 * Photon inspects this marker during the SDK's pre-init notification, before the OpMode's
 * `runOpMode()` method starts and before team hardware is constructed. Implementing it on a
 * shared TeleOp or autonomous base opts every derived OpMode into Photon while leaving unrelated
 * diagnostics and third-party OpModes on the standard FTC SDK transport.
 */
interface PhotonEnabledOpMode
