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

/** Converts the shared flywheel contract from RPM to the SysId standard of radians/second. */
class FlywheelSysIdAdapter(private val flywheel: FlywheelIO) : SysIdMechanismIO {
    override val mechanism: SysIdMechanism = SysIdMechanism.FLYWHEEL
    override val velocity: Double
        get() = flywheel.velocityRpm * (2.0 * Math.PI / 60.0)
    override val measurementValid: Boolean
        get() = flywheel.velocityValid && velocity.isFinite()

    override fun setCharacterizationVoltage(volts: Double) {
        flywheel.setAppliedVoltage(volts.takeIf { it.isFinite() }?.coerceIn(0.0, 12.0) ?: 0.0)
    }
}
