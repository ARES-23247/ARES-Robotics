package com.areslib.control.assist

import com.areslib.hardware.actuator.FlywheelIO

/** Hardware-neutral actuator boundary used by FTC and FRC SysId executors. */
interface SysIdMechanismIO {
    val mechanism: SysIdMechanism
    val velocity: Double
    val measurementValid: Boolean
    fun setCharacterizationVoltage(volts: Double)
    fun stop() = setCharacterizationVoltage(0.0)
}

/**
 * Converts cached RPM to radians/second. The existing flywheel characterization routines
 * run forward only: voltage is bounded to 0..12 V and nonfinite requests neutralize.
 * The owner must refresh feedback and authorize hardware before using this adapter.
 */
class FlywheelSysIdAdapter(private val flywheel: FlywheelIO) : SysIdMechanismIO {
    override val mechanism: SysIdMechanism = SysIdMechanism.FLYWHEEL
    override val velocity: Double
        get() = flywheel.velocityRpm * (2.0 * Math.PI / 60.0)
    override val measurementValid: Boolean
        // The conversion factor is finite and less than one, so finite RPM cannot overflow.
        get() = flywheel.velocityValid && flywheel.velocityRpm.isFinite()

    override fun setCharacterizationVoltage(volts: Double) {
        flywheel.setAppliedVoltage(volts.takeIf { it.isFinite() }?.coerceIn(0.0, 12.0) ?: 0.0)
    }
}
