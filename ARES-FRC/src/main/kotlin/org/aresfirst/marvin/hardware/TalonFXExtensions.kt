package org.aresfirst.marvin.hardware

import com.areslib.frc.hardware.applyConfigChecked
import com.ctre.phoenix6.configs.TalonFXConfiguration
import com.ctre.phoenix6.hardware.TalonFX
import edu.wpi.first.wpilibj.DriverStation

/** Exposes whether one-time mechanism configuration completed successfully. */
interface FrcMechanismConfigurationStatus {
    val configurationValid: Boolean
}

/** Position mechanisms without an absolute reference remain inhibited until zeroed deliberately. */
interface FrcMechanismHomingStatus {
    val homed: Boolean

    /**
     * Records the mechanism's current, physically verified safe position as zero.
     * The robot lifecycle only calls this while Disabled or Test-enabled.
     */
    fun homeAtKnownZero(): Boolean
}

/** Reports whether the most recent live controller update reached every owned motor. */
interface FrcFlywheelTuningStatus {
    val lastTuningApplySuccessful: Boolean
}

/** Hardware-only readiness contract that prevents an average from hiding one lagging motor. */
interface FrcFlywheelPerMotorReadiness {
    fun allMotorsAtTarget(targetRpm: Double, toleranceRpm: Double): Boolean
}

/**
 * Adds mechanism context to ARESLib's checked Talon configuration result.
 */
internal fun Iterable<TalonFX>.applyMechanismConfigChecked(
    mechanismName: String,
    block: TalonFXConfiguration.() -> Unit
): Boolean {
    val applied = applyConfigChecked(block = block)
    if (!applied) reportConfigurationFailure("$mechanismName configuration failed")
    return applied
}

internal fun reportConfigurationFailure(message: String) {
    runCatching { DriverStation.reportError("ARES: $message; mechanism outputs inhibited", false) }
        .onFailure { System.err.println("ARES: $message; mechanism outputs inhibited") }
}

/** Safe-first, best-effort close for one hardware owner. */
internal fun closeTalons(vararg motors: TalonFX) {
    var firstFailure: Throwable? = null
    for (motor in motors) {
        try {
            motor.close()
        } catch (failure: Throwable) {
            if (firstFailure == null) firstFailure = failure else firstFailure.addSuppressed(failure)
        }
    }
    firstFailure?.let { throw it }
}

/** Samples every Phoenix reset indicator so no device reboot is hidden by short-circuiting. */
internal fun anyTalonResetOccurred(vararg motors: TalonFX): Boolean {
    var resetOccurred = false
    for (motor in motors) {
        if (motor.hasResetOccurred()) resetOccurred = true
    }
    return resetOccurred
}
