package org.aresfirst.marvin.hardware

import com.areslib.frc.hardware.setUpdateFrequencies
import com.areslib.hardware.actuator.FeederIO
import com.ctre.phoenix6.BaseStatusSignal
import com.ctre.phoenix6.controls.VoltageOut
import com.ctre.phoenix6.hardware.TalonFX

/**
 * Open-loop TalonFX feeder IO on `CAN2`.
 *
 * Marvin XIX has no beam-break sensor, so [pieceDetectionValid] is deliberately false;
 * callers must not interpret [isBeamBroken] as a fresh “no piece” observation. Current
 * feedback is refreshed once per robot loop and read from the cached status signal.
 */
class FRCFeederHardwareIO(
    private val motor: TalonFX
) : FeederIO, FrcMechanismConfigurationStatus, AutoCloseable {
    private val startupConfigurationValid: Boolean
    @Volatile private var resetDetected = false
    override val configurationValid: Boolean
        get() = startupConfigurationValid && !resetDetected
    @Volatile private var cachedCurrentValid = false

    private val voltageRequest = VoltageOut(0.0)

    private val feederCurrent = motor.statorCurrent

    init {
        motor.optimizeBusUtilization()
        setUpdateFrequencies(10.0, feederCurrent)

        startupConfigurationValid = listOf(motor).applyMechanismConfigChecked("Feeder") {
            MotorOutput.NeutralMode = com.ctre.phoenix6.signals.NeutralModeValue.Coast
            MotorOutput.Inverted = com.ctre.phoenix6.signals.InvertedValue.Clockwise_Positive
            Feedback.SensorToMechanismRatio = 4.0 // 4:1 feeder gear reduction

            CurrentLimits.SupplyCurrentLimitEnable = true
            CurrentLimits.SupplyCurrentLimit = 60.0
            CurrentLimits.StatorCurrentLimitEnable = true
            CurrentLimits.StatorCurrentLimit = 100.0
        }
    }

    override fun refresh() {
        if (anyTalonResetOccurred(motor)) resetDetected = true
        cachedCurrentValid = BaseStatusSignal.refreshAll(feederCurrent).isOK &&
            feederCurrent.valueAsDouble.isFinite() && feederCurrent.valueAsDouble >= 0.0
    }

    /**
     * Drives the feeder open-loop via raw voltage. This is deliberate: the feeder
     * is a simple transfer roller with no closed-loop velocity requirement, so no
     * TalonFX Slot0 PID gains are configured. Voltage scaling is applied upstream
     * by [org.aresfirst.marvin.marvin.MarvinSuperstructure] (FEEDER_KV * rps * brownoutScale).
     */
    override fun setAppliedVoltage(volts: Double) {
        val requestedVolts = if (configurationValid) volts else 0.0
        motor.setControl(voltageRequest.withOutput(
            requestedVolts.takeIf { it.isFinite() }?.coerceIn(-12.0, 12.0) ?: 0.0
        ))
    }

    override val isBeamBroken: Boolean
        get() = false

    override val pieceDetectionValid: Boolean
        get() = false

    override val currentAmps: Double
        get() = feederCurrent.valueAsDouble

    override fun isCurrentReadingValid(readingAmps: Double): Boolean =
        cachedCurrentValid && readingAmps.isFinite() && readingAmps >= 0.0

    override fun close() = closeTalons(motor)
}
