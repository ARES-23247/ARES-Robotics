package org.aresfirst.marvin.hardware

import com.areslib.frc.hardware.setUpdateFrequencies
import com.areslib.hardware.actuator.ClimberIO
import com.ctre.phoenix6.BaseStatusSignal
import com.ctre.phoenix6.controls.PositionVoltage
import com.ctre.phoenix6.controls.VoltageOut
import com.ctre.phoenix6.hardware.TalonFX
import com.ctre.phoenix6.signals.InvertedValue
import com.ctre.phoenix6.signals.NeutralModeValue

/**
 * CTRE TalonFX climber IO for CAN ID 19 on `CAN2`.
 *
 * Position feedback and commands are mechanism rotations after the configured 80:1
 * sensor ratio. [refresh] is the only sensor-read phase; property getters return the
 * cached status-signal values. Closed-loop effort is bounded independently of the
 * geometric target, and TalonFX soft limits provide the final `0.0..1.73` boundary.
 */
class FRCClimberHardwareIO(
    private val motor: TalonFX
) : ClimberIO, FrcMechanismConfigurationStatus, FrcMechanismHomingStatus, AutoCloseable {
    private val startupConfigurationValid: Boolean
    @Volatile private var resetDetected = false
    override val configurationValid: Boolean
        get() = startupConfigurationValid && !resetDetected
    @Volatile override var homed: Boolean = false
        private set
    @Volatile private var cachedPositionValid = false
    @Volatile private var cachedCurrentValid = false

    private val positionRequest = PositionVoltage(0.0)
    private val voltageRequest = VoltageOut(0.0)

    private val climberPosition = motor.position
    private val climberCurrent = motor.statorCurrent

    init {
        motor.optimizeBusUtilization()
        setUpdateFrequencies(50.0, climberPosition)
        setUpdateFrequencies(10.0, climberCurrent)

        startupConfigurationValid = listOf(motor).applyMechanismConfigChecked("Climber") {
            // Neutral mode and inversions
            MotorOutput.NeutralMode = NeutralModeValue.Brake
            MotorOutput.Inverted = InvertedValue.CounterClockwise_Positive
            
            // Gearing / Sensor scaling
            Feedback.SensorToMechanismRatio = 80.0

            // Supply and Stator current limits matching SystemConstants.java
            CurrentLimits.SupplyCurrentLimit = 70.0
            CurrentLimits.SupplyCurrentLimitEnable = true
            CurrentLimits.StatorCurrentLimit = 120.0
            CurrentLimits.StatorCurrentLimitEnable = true

            // Position closed-loop PID/feedforward gains
            Slot0.kP = 12.0
            Slot0.kI = 0.0
            Slot0.kD = 0.0
            Slot0.kV = 9.6 // 12.0 / 1.25 RPS (Max speed: 6000 RPM / 80 = 75 RPM = 1.25 RPS)

            // Software soft limits
            SoftwareLimitSwitch.ForwardSoftLimitThreshold = 1.73
            SoftwareLimitSwitch.ForwardSoftLimitEnable = true
            SoftwareLimitSwitch.ReverseSoftLimitThreshold = 0.0
            SoftwareLimitSwitch.ReverseSoftLimitEnable = true
        }
    }

    override fun refresh() {
        if (anyTalonResetOccurred(motor)) {
            resetDetected = true
            homed = false
        }
        val positionRefreshOk = BaseStatusSignal.refreshAll(climberPosition).isOK
        cachedPositionValid = homed && positionRefreshOk &&
            climberPosition.valueAsDouble.isFinite()
        cachedCurrentValid = BaseStatusSignal.refreshAll(climberCurrent).isOK &&
            climberCurrent.valueAsDouble.isFinite() && climberCurrent.valueAsDouble >= 0.0
    }

    override fun homeAtKnownZero(): Boolean {
        val stopped = motor.setControl(voltageRequest.withOutput(0.0)).isOK
        val zeroed = motor.setPosition(0.0).isOK
        homed = configurationValid && stopped && zeroed
        cachedPositionValid = false
        if (!homed) reportConfigurationFailure("Climber zeroing failed")
        return homed
    }

    private fun setTargetPositionFullEffort(rotations: Double) {
        if (!configurationValid || !homed || !positionValid) {
            setAppliedVoltage(0.0)
            return
        }
        val safeRotations = rotations.takeIf { it.isFinite() }?.coerceIn(
            org.aresfirst.marvin.marvin.MarvinConfig.MechanismLimits.climberMinRotations,
            org.aresfirst.marvin.marvin.MarvinConfig.MechanismLimits.climberMaxRotations
        ) ?: org.aresfirst.marvin.marvin.MarvinConfig.MechanismLimits.climberMinRotations
        motor.setControl(positionRequest.withPosition(safeRotations))
    }

    override fun setTargetPositionRotations(rotations: Double, maxEffortScale: Double) {
        val effortScale = maxEffortScale.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        if (!configurationValid || !homed || !positionValid) {
            setAppliedVoltage(0.0)
            return
        }
        if (effortScale >= FULL_EFFORT_THRESHOLD) {
            setTargetPositionFullEffort(rotations)
            return
        }
        val safeRotations = rotations.takeIf { it.isFinite() }?.coerceIn(
            org.aresfirst.marvin.marvin.MarvinConfig.MechanismLimits.climberMinRotations,
            org.aresfirst.marvin.marvin.MarvinConfig.MechanismLimits.climberMaxRotations
        ) ?: org.aresfirst.marvin.marvin.MarvinConfig.MechanismLimits.climberMinRotations
        val error = safeRotations - positionRotations
        val maxVolts = NOMINAL_VOLTAGE * effortScale
        setAppliedVoltage((POSITION_KP_VOLTS_PER_ROTATION * error).coerceIn(-maxVolts, maxVolts))
    }

    override fun setAppliedVoltage(volts: Double) {
        val safeRequest = volts.takeIf { it.isFinite() }?.coerceIn(-12.0, 12.0) ?: 0.0
        val requestedVolts = if (
            configurationValid && homed &&
            (kotlin.math.abs(safeRequest) <= ZERO_OUTPUT_EPSILON || positionValid)
        ) safeRequest else 0.0
        val safeVolts = requestedVolts.coerceIn(-12.0, 12.0)
        motor.setControl(voltageRequest.withOutput(safeVolts))
    }

    override val positionRotations: Double
        get() = climberPosition.valueAsDouble

    override val positionValid: Boolean
        get() = cachedPositionValid

    override val currentAmps: Double
        get() = climberCurrent.valueAsDouble

    override fun isCurrentReadingValid(readingAmps: Double): Boolean =
        cachedCurrentValid && readingAmps.isFinite() && readingAmps >= 0.0

    override fun close() = closeTalons(motor)

    private companion object {
        const val POSITION_KP_VOLTS_PER_ROTATION = 12.0
        const val NOMINAL_VOLTAGE = 12.0
        const val FULL_EFFORT_THRESHOLD = 0.999
        const val ZERO_OUTPUT_EPSILON = 1e-9
    }
}
