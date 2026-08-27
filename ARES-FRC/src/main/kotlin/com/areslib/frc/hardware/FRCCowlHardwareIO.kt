package com.areslib.frc.hardware

import com.areslib.frc.marvin.MarvinConfig
import com.ctre.phoenix6.BaseStatusSignal
import com.ctre.phoenix6.controls.PositionVoltage
import com.ctre.phoenix6.controls.VoltageOut
import com.ctre.phoenix6.hardware.TalonFX
import com.ctre.phoenix6.signals.InvertedValue
import com.ctre.phoenix6.signals.NeutralModeValue

/**
 * TalonFX IO for the adjustable cowl, expressed entirely in mechanism rotations.
 *
 * Shot-table values such as `0.50..1.75` are rotations, not degrees. [refresh] owns
 * CAN reads and getters expose cached observations. The target remains geometric when
 * brownout scaling changes; the overload accepting `maxEffortScale` limits only output
 * voltage. Software and TalonFX limits share [MarvinConfig.cowlMaxRotations].
 */
class FRCCowlHardwareIO(
    private val motor: TalonFX
) : CowlIO, FrcMechanismConfigurationStatus, FrcMechanismHomingStatus, AutoCloseable {
    private val startupConfigurationValid: Boolean
    @Volatile private var resetDetected = false
    override val configurationValid: Boolean
        get() = startupConfigurationValid && !resetDetected
    @Volatile override var homed: Boolean = false
        private set
    @Volatile private var cachedAngleValid = false
    @Volatile private var cachedCurrentValid = false

    private val positionRequest = PositionVoltage(0.0)
    private val voltageRequest = VoltageOut(0.0)

    private val cowlPosition = motor.position
    private val cowlCurrent = motor.statorCurrent

    init {
        motor.optimizeBusUtilization()
        setUpdateFrequencies(50.0, cowlPosition)
        setUpdateFrequencies(10.0, cowlCurrent)

        startupConfigurationValid = listOf(motor).applyMechanismConfigChecked("Cowl") {
            // Neutral mode and inversions
            MotorOutput.NeutralMode = NeutralModeValue.Brake
            MotorOutput.Inverted = InvertedValue.Clockwise_Positive

            // Gearing and sensor ratio
            Feedback.SensorToMechanismRatio = 1.0

            // Software soft limits
            SoftwareLimitSwitch.ForwardSoftLimitEnable = true
            SoftwareLimitSwitch.ForwardSoftLimitThreshold = MarvinConfig.cowlMaxRotations
            SoftwareLimitSwitch.ReverseSoftLimitEnable = true
            SoftwareLimitSwitch.ReverseSoftLimitThreshold = 0.0

            // Position closed-loop PID gains
            Slot0.kP = 20.0
            Slot0.kI = 0.0
            Slot0.kD = 0.0
            Slot0.kS = 2.0

            // Current limits
            CurrentLimits.SupplyCurrentLimit = 30.0
            CurrentLimits.SupplyCurrentLimitEnable = true
            CurrentLimits.StatorCurrentLimit = 50.0
            CurrentLimits.StatorCurrentLimitEnable = true
        }
    }

    override fun refresh() {
        if (anyTalonResetOccurred(motor)) {
            resetDetected = true
            homed = false
        }
        val positionRefreshOk = BaseStatusSignal.refreshAll(cowlPosition).isOK
        cachedAngleValid = homed && positionRefreshOk &&
            cowlPosition.valueAsDouble.isFinite()
        cachedCurrentValid = BaseStatusSignal.refreshAll(cowlCurrent).isOK &&
            cowlCurrent.valueAsDouble.isFinite() && cowlCurrent.valueAsDouble >= 0.0
    }

    override fun homeAtKnownZero(): Boolean {
        val stopped = motor.setControl(voltageRequest.withOutput(0.0)).isOK
        val zeroed = motor.setPosition(0.0).isOK
        homed = configurationValid && stopped && zeroed
        cachedAngleValid = false
        if (!homed) reportConfigurationFailure("Cowl zeroing failed")
        return homed
    }

    override fun setTargetAngle(rotations: Double) {
        if (!configurationValid || !homed || !angleValid) {
            setAppliedVoltage(0.0)
            return
        }
        motor.setControl(positionRequest.withPosition(safeTarget(rotations)))
    }

    override fun setTargetAngle(rotations: Double, maxEffortScale: Double) {
        val effortScale = maxEffortScale.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        if (!configurationValid || !homed || !angleValid) {
            setAppliedVoltage(0.0)
            return
        }
        if (effortScale >= FULL_EFFORT_THRESHOLD) {
            setTargetAngle(rotations)
            return
        }
        val error = safeTarget(rotations) - angleRotations
        val staticVolts = when {
            error > POSITION_EPSILON_ROTATIONS -> POSITION_KS_VOLTS
            error < -POSITION_EPSILON_ROTATIONS -> -POSITION_KS_VOLTS
            else -> 0.0
        }
        val maxVolts = NOMINAL_VOLTAGE * effortScale
        setAppliedVoltage((POSITION_KP_VOLTS_PER_ROTATION * error + staticVolts).coerceIn(-maxVolts, maxVolts))
    }

    override fun setAppliedVoltage(volts: Double) {
        val requestedVolts = if (configurationValid && homed) volts else 0.0
        motor.setControl(voltageRequest.withOutput(
            requestedVolts.takeIf { it.isFinite() }?.coerceIn(-NOMINAL_VOLTAGE, NOMINAL_VOLTAGE) ?: 0.0
        ))
    }

    override val angleRotations: Double
        get() = cowlPosition.valueAsDouble

    override val angleValid: Boolean
        get() = cachedAngleValid

    override val currentAmps: Double
        get() = cowlCurrent.valueAsDouble

    override fun isCurrentReadingValid(readingAmps: Double): Boolean =
        cachedCurrentValid && readingAmps.isFinite() && readingAmps >= 0.0

    override fun close() = closeTalons(motor)

    private fun safeTarget(rotations: Double): Double =
        rotations.takeIf { it.isFinite() }?.coerceIn(0.0, MarvinConfig.cowlMaxRotations) ?: 0.0

    private companion object {
        const val POSITION_KP_VOLTS_PER_ROTATION = 20.0
        const val POSITION_KS_VOLTS = 2.0
        const val POSITION_EPSILON_ROTATIONS = 0.002
        const val NOMINAL_VOLTAGE = 12.0
        const val FULL_EFFORT_THRESHOLD = 0.999
    }
}
