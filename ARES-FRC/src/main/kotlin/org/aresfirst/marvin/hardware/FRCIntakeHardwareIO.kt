package org.aresfirst.marvin.hardware

import com.areslib.hardware.actuator.IntakeIO
import com.ctre.phoenix6.BaseStatusSignal
import com.ctre.phoenix6.controls.PositionVoltage
import com.ctre.phoenix6.controls.VoltageOut
import com.ctre.phoenix6.hardware.TalonFX

/**
 * TalonFX IO for the Marvin XIX intake pivot and roller.
 *
 * The public pivot contract is degrees; commands are converted to mechanism rotations
 * after CTRE's configured 4:1 sensor ratio. Roller commands use RPS. [refresh] is the
 * sole sensor-read phase, so position/current getters remain cached and CAN-free.
 */
class FRCIntakeHardwareIO(
    private val pivotMotor: TalonFX,
    private val rollerMotor: TalonFX
) : IntakeIO, FrcMechanismConfigurationStatus, FrcMechanismHomingStatus, AutoCloseable {
    private val startupConfigurationValid: Boolean
    @Volatile private var resetDetected = false
    override val configurationValid: Boolean
        get() = startupConfigurationValid && !resetDetected
    @Volatile override var homed: Boolean = false
        private set
    @Volatile private var cachedPivotAngleValid = false
    @Volatile private var cachedCurrentValid = false

    private val positionRequest = PositionVoltage(0.0)
    private val voltageRequest = VoltageOut(0.0)
    private val velocityRequest = com.ctre.phoenix6.controls.VelocityVoltage(0.0)

    private val pivotPosition = pivotMotor.position
    private val pivotCurrent = pivotMotor.statorCurrent
    private val rollerCurrent = rollerMotor.statorCurrent

    init {
        pivotMotor.optimizeBusUtilization()
        rollerMotor.optimizeBusUtilization()

        pivotPosition.setUpdateFrequency(50.0)
        pivotCurrent.setUpdateFrequency(10.0)
        rollerCurrent.setUpdateFrequency(10.0)

        val pivotConfigured = listOf(pivotMotor).applyMechanismConfigChecked("Intake pivot") {
            Slot0.kP = 24.0
            Slot0.kI = 0.0
            Slot0.kD = 0.0
            Slot0.kV = 0.38247 // 12.0 / 31.375 (Max speed: 7530 RPM / 4 = 1882.5 RPM = 31.375 RPS)

            MotorOutput.NeutralMode = com.ctre.phoenix6.signals.NeutralModeValue.Brake
            MotorOutput.Inverted = com.ctre.phoenix6.signals.InvertedValue.Clockwise_Positive
            Feedback.SensorToMechanismRatio = 4.0 // 4:1 pivot gear reduction

            CurrentLimits.SupplyCurrentLimitEnable = true
            CurrentLimits.SupplyCurrentLimit = 40.0
            CurrentLimits.StatorCurrentLimitEnable = true
            CurrentLimits.StatorCurrentLimit = 80.0

            // Software soft limits. Pivot travel is 0.0 (stowed) to ~0.25 mechanism rotations
            // (90° deploy; setPivotAngle commands degrees/360). Forward threshold of 0.30 gives
            // a 0.05-rotation margin above the full-deploy command, mirroring the cowl/climber.
            SoftwareLimitSwitch.ForwardSoftLimitEnable = true
            SoftwareLimitSwitch.ForwardSoftLimitThreshold = 0.30
            SoftwareLimitSwitch.ReverseSoftLimitEnable = true
            SoftwareLimitSwitch.ReverseSoftLimitThreshold = 0.0
        }

        val rollerConfigured = listOf(rollerMotor).applyMechanismConfigChecked("Intake roller") {
            Slot0.kP = 0.5
            Slot0.kI = 2.0
            Slot0.kD = 0.0
            Slot0.kV = 0.0956 // 12.0 / 125.5 (Max speed: 7530 RPM = 125.5 RPS)

            MotorOutput.NeutralMode = com.ctre.phoenix6.signals.NeutralModeValue.Coast
            MotorOutput.Inverted = com.ctre.phoenix6.signals.InvertedValue.Clockwise_Positive
            Feedback.SensorToMechanismRatio = 1.0

            CurrentLimits.SupplyCurrentLimitEnable = true
            CurrentLimits.SupplyCurrentLimit = 30.0
            CurrentLimits.StatorCurrentLimitEnable = true
            CurrentLimits.StatorCurrentLimit = 40.0
        }
        startupConfigurationValid = pivotConfigured && rollerConfigured
    }

    override fun refresh() {
        if (anyTalonResetOccurred(pivotMotor, rollerMotor)) {
            resetDetected = true
            homed = false
        }
        val positionRefreshOk = BaseStatusSignal.refreshAll(pivotPosition).isOK
        cachedPivotAngleValid = homed && positionRefreshOk &&
            pivotPosition.valueAsDouble.isFinite()
        cachedCurrentValid = BaseStatusSignal.refreshAll(pivotCurrent, rollerCurrent).isOK &&
            pivotCurrent.valueAsDouble.isFinite() && pivotCurrent.valueAsDouble >= 0.0 &&
            rollerCurrent.valueAsDouble.isFinite() && rollerCurrent.valueAsDouble >= 0.0
    }

    override fun homeAtKnownZero(): Boolean {
        val pivotStopped = pivotMotor.setControl(voltageRequest.withOutput(0.0)).isOK
        val rollerStopped = rollerMotor.setControl(voltageRequest.withOutput(0.0)).isOK
        val zeroed = pivotMotor.setPosition(0.0).isOK
        homed = configurationValid && pivotStopped && rollerStopped && zeroed
        cachedPivotAngleValid = false
        if (!homed) reportConfigurationFailure("Intake pivot zeroing failed")
        return homed
    }

    private fun setPivotAngleFullEffort(degrees: Double) {
        if (!configurationValid || !homed || !pivotAngleValid) {
            setPivotVoltage(0.0)
            return
        }
        // Convert degrees to mechanism rotations (1 degree = (1.0 / 360.0) rotations)
        // Feedback.SensorToMechanismRatio handles the internal 4:1 scaling in TalonFX
        val safeDegrees = degrees.takeIf { it.isFinite() }?.coerceIn(
            org.aresfirst.marvin.marvin.MarvinConfig.MechanismLimits.intakeStowedDegrees,
            org.aresfirst.marvin.marvin.MarvinConfig.MechanismLimits.intakeDeployedDegrees
        ) ?: org.aresfirst.marvin.marvin.MarvinConfig.MechanismLimits.intakeStowedDegrees
        val rotations = safeDegrees / 360.0
        pivotMotor.setControl(positionRequest.withPosition(rotations))
    }

    override fun setPivotAngle(degrees: Double, maxEffortScale: Double) {
        val effortScale = maxEffortScale.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        if (!configurationValid || !homed || !pivotAngleValid) {
            setPivotVoltage(0.0)
            return
        }
        if (effortScale >= FULL_EFFORT_THRESHOLD) {
            setPivotAngleFullEffort(degrees)
            return
        }
        val safeDegrees = degrees.takeIf { it.isFinite() }?.coerceIn(
            org.aresfirst.marvin.marvin.MarvinConfig.MechanismLimits.intakeStowedDegrees,
            org.aresfirst.marvin.marvin.MarvinConfig.MechanismLimits.intakeDeployedDegrees
        ) ?: org.aresfirst.marvin.marvin.MarvinConfig.MechanismLimits.intakeStowedDegrees
        val targetRotations = safeDegrees / 360.0
        val errorRotations = targetRotations - pivotPosition.valueAsDouble
        val maxVolts = NOMINAL_VOLTAGE * effortScale
        setPivotVoltage((POSITION_KP_VOLTS_PER_ROTATION * errorRotations).coerceIn(-maxVolts, maxVolts))
    }

    override fun setPivotVoltage(volts: Double) {
        val requestedVolts = if (configurationValid && homed) volts else 0.0
        pivotMotor.setControl(voltageRequest.withOutput(requestedVolts.takeIf { it.isFinite() }?.coerceIn(-12.0, 12.0) ?: 0.0))
    }

    override fun setRollerVoltage(volts: Double) {
        val requestedVolts = if (configurationValid && homed) volts else 0.0
        rollerMotor.setControl(voltageRequest.withOutput(requestedVolts.takeIf { it.isFinite() }?.coerceIn(-12.0, 12.0) ?: 0.0))
    }

    override fun setRollerVelocityRps(rps: Double) {
        val requestedRps = if (configurationValid && homed) rps else 0.0
        rollerMotor.setControl(velocityRequest.withVelocity(requestedRps.takeIf { it.isFinite() } ?: 0.0))
    }

    override val pivotAngleDegrees: Double
        get() = pivotPosition.valueAsDouble * 360.0

    override val pivotAngleValid: Boolean
        get() = cachedPivotAngleValid

    override val pivotCurrentAmps: Double
        get() = pivotCurrent.valueAsDouble

    override val rollerCurrentAmps: Double
        get() = rollerCurrent.valueAsDouble

    override val rollerCurrentValid: Boolean
        get() = cachedCurrentValid

    override fun isCurrentReadingValid(readingAmps: Double): Boolean =
        cachedCurrentValid && readingAmps.isFinite() && readingAmps >= 0.0 &&
            pivotCurrentAmps.isFinite() && pivotCurrentAmps >= 0.0 &&
            rollerCurrentAmps.isFinite() && rollerCurrentAmps >= 0.0

    override fun close() = closeTalons(pivotMotor, rollerMotor)

    private companion object {
        const val POSITION_KP_VOLTS_PER_ROTATION = 24.0
        const val NOMINAL_VOLTAGE = 12.0
        const val FULL_EFFORT_THRESHOLD = 0.999
    }
}
