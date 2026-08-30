package com.areslib.frc.hardware

import com.areslib.hardware.actuator.FloorIO
import com.ctre.phoenix6.BaseStatusSignal
import com.ctre.phoenix6.controls.VoltageOut
import com.ctre.phoenix6.hardware.TalonFX

/**
 * Open-loop floor-roller TalonFX IO for CAN ID 16 on `CAN2`.
 *
 * Redux expresses the requested roller speed in RPS, but [setAppliedVoltage] is the
 * hardware boundary because this mechanism intentionally has no velocity loop. Velocity
 * and current getters return signals cached by [refresh].
 */
class FRCFloorHardwareIO(
    private val motor: TalonFX
) : FloorIO, FrcMechanismConfigurationStatus, AutoCloseable {
    private val startupConfigurationValid: Boolean
    @Volatile private var resetDetected = false
    override val configurationValid: Boolean
        get() = startupConfigurationValid && !resetDetected
    @Volatile private var cachedCurrentValid = false

    private val voltageRequest = VoltageOut(0.0)

    private val floorVelocity = motor.velocity
    private val floorCurrent = motor.statorCurrent

    init {
        motor.optimizeBusUtilization()
        setUpdateFrequencies(20.0, floorVelocity)
        setUpdateFrequencies(10.0, floorCurrent)

        startupConfigurationValid = listOf(motor).applyMechanismConfigChecked("Floor roller") {
            MotorOutput.NeutralMode = com.ctre.phoenix6.signals.NeutralModeValue.Coast
            MotorOutput.Inverted = com.ctre.phoenix6.signals.InvertedValue.CounterClockwise_Positive
            Feedback.SensorToMechanismRatio = 1.0

            CurrentLimits.SupplyCurrentLimitEnable = true
            CurrentLimits.SupplyCurrentLimit = 60.0
            CurrentLimits.StatorCurrentLimitEnable = true
            CurrentLimits.StatorCurrentLimit = 100.0
        }
    }

    override fun refresh() {
        if (anyTalonResetOccurred(motor)) resetDetected = true
        BaseStatusSignal.refreshAll(floorVelocity)
        cachedCurrentValid = BaseStatusSignal.refreshAll(floorCurrent).isOK &&
            floorCurrent.valueAsDouble.isFinite() && floorCurrent.valueAsDouble >= 0.0
    }

    /**
     * Drives the floor rollers open-loop via raw voltage. This is deliberate: the
     * floor is a high-speed intake roller governed by voltage feed-forward only
     * (FLOOR_KV * rps * brownoutScale), so no TalonFX Slot0 PID gains are configured.
     */
    override fun setAppliedVoltage(volts: Double) {
        val requestedVolts = if (configurationValid) volts else 0.0
        motor.setControl(voltageRequest.withOutput(
            requestedVolts.takeIf { it.isFinite() }?.coerceIn(-12.0, 12.0) ?: 0.0
        ))
    }

    override val velocityRps: Double
        get() = floorVelocity.valueAsDouble

    override val currentAmps: Double
        get() = floorCurrent.valueAsDouble

    override fun isCurrentReadingValid(readingAmps: Double): Boolean =
        cachedCurrentValid && readingAmps.isFinite() && readingAmps >= 0.0

    override fun close() = closeTalons(motor)
}
