package org.aresfirst.marvin

import com.areslib.hardware.HardwareRegistry

/** Retain completed IO before fallible composition; normal device registration shares ownership. */
internal fun HardwareRegistry.retainFrcHardware(vararg devices: Any?) {
    for (device in devices) {
        if (device is AutoCloseable) registerCloseable(device)
    }
}

/** Run partial-initialization teardown immediately while retaining the original startup failure. */
internal inline fun initializeFrcRobot(initialize: () -> Unit, cleanup: () -> Unit) {
    try { initialize() } catch (failure: Throwable) {
        try { cleanup() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
        throw failure
    }
}

/** Returns false when any real mechanism adapter reports failed or reset configuration. */
internal fun mechanismsConfigured(
    devices: Array<out org.aresfirst.marvin.hardware.FrcMechanismConfigurationStatus>
): Boolean {
    for (device in devices) {
        if (!device.configurationValid) return false
    }
    return true
}

/** Returns false until every relative-only position mechanism has a deliberate safe zero. */
internal fun mechanismsHomed(
    devices: Array<out org.aresfirst.marvin.hardware.FrcMechanismHomingStatus>
): Boolean {
    for (device in devices) {
        if (!device.homed) return false
    }
    return true
}

internal fun mechanismSafetyHealthy(
    configurationValid: Boolean,
    homingValid: Boolean,
    fatalUpdateFailure: Throwable?
): Boolean = configurationValid && homingValid && fatalUpdateFailure == null

internal fun mechanismHomingComboPressed(
    driverBack: Boolean,
    driverStart: Boolean,
    operatorBack: Boolean,
    operatorStart: Boolean
): Boolean = driverBack && driverStart && operatorBack && operatorStart

internal fun mechanismHomingRequestAllowed(isDisabled: Boolean, isTestEnabled: Boolean): Boolean =
    isDisabled && !isTestEnabled

/** Rejects an unavailable or suspicious enabled PDH total so shared fallback remains truthful. */
internal fun validatedPdhCurrent(readingAmps: Double, robotEnabled: Boolean): Double =
    if (readingAmps.isFinite() && readingAmps >= 0.0 && (!robotEnabled || readingAmps > 0.0)) {
        readingAmps
    } else {
        Double.NaN
    }

internal fun marvinCanTopology(
    displayName: String,
    primaryCanId: Int,
    vararg memberCanIds: Int
): com.areslib.telemetry.schema.TopologyNode = com.areslib.telemetry.schema.TopologyNode(
    id = displayName,
    type = com.areslib.telemetry.schema.TopologyNodeType.CAN_MOTOR_CONTROLLER,
    displayName = displayName,
    canId = primaryCanId,
    canBus = org.aresfirst.marvin.generated.drivebase.GeneratedAresDrivebaseConfig.CTRE_CAN_BUS,
    busPosition = primaryCanId,
    connectionType = "CAN-FD",
    metadata = mapOf(
        "canIds" to memberCanIds.joinToString(","),
        "controllerModel" to "TalonFX"
    )
)
