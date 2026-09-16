package com.areslib.control.safety

/**
 * Universal mechanism configuration status contract across FTC and FRC.
 */
interface MechanismConfigurationStatus {
    /** True when device configuration has been successfully applied and verified. */
    val configurationValid: Boolean
}

/**
 * Common homing status contract for relative-only position mechanisms.
 */
interface MechanismHomingStatus {
    /** True when the mechanism has established a safe physical reference zero. */
    val homed: Boolean
}

/**
 * Platform-agnostic safety interlocks and authorization predicates for mechanism homing,
 * configuration verification, and multi-operator confirmation chords.
 *
 * All functions are pure, allocation-free, and safe for high-rate robot loops.
 */
object HomingInterlock {
    /**
     * Verifies that every mechanism in [devices] reports valid configuration.
     * Returns true if [devices] is empty.
     */
    fun isAllConfigured(devices: Array<out MechanismConfigurationStatus>): Boolean {
        for (device in devices) {
            if (!device.configurationValid) return false
        }
        return true
    }

    /**
     * Verifies that every mechanism in [devices] has completed its homing routine.
     * Returns true if [devices] is empty.
     */
    fun isAllHomed(devices: Array<out MechanismHomingStatus>): Boolean {
        for (device in devices) {
            if (!device.homed) return false
        }
        return true
    }

    /**
     * Verifies that mechanism safety is healthy:
     * valid configuration, completed homing, and no unhandled fatal exception.
     */
    fun isSafetyHealthy(
        configurationValid: Boolean,
        homingValid: Boolean,
        fatalUpdateFailure: Throwable?
    ): Boolean = configurationValid && homingValid && fatalUpdateFailure == null

    /**
     * Mechanism homing request authorization predicate.
     *
     * Homing can only be commanded while the robot is disabled and not running
     * an active test sequence.
     */
    fun isHomingRequestAllowed(isDisabled: Boolean, isTestEnabled: Boolean): Boolean =
        isDisabled && !isTestEnabled

    /**
     * Dual-operator four-button chord authorization predicate.
     *
     * Requires simultaneous Back + Start inputs on both primary driver
     * and co-pilot / operator gamepads before homing or calibration can initiate.
     */
    fun isHomingChordPressed(
        driverBack: Boolean,
        driverStart: Boolean,
        operatorBack: Boolean,
        operatorStart: Boolean
    ): Boolean = driverBack && driverStart && operatorBack && operatorStart
}
