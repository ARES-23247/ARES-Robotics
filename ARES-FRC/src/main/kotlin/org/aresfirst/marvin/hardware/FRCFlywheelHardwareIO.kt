package org.aresfirst.marvin.hardware

import com.areslib.frc.hardware.setUpdateFrequencies
import com.areslib.hardware.actuator.FlywheelIO
import com.ctre.phoenix6.BaseStatusSignal
import com.ctre.phoenix6.controls.Follower
import com.ctre.phoenix6.controls.VelocityVoltage
import com.ctre.phoenix6.controls.VoltageOut
import com.ctre.phoenix6.hardware.TalonFX
import com.ctre.phoenix6.configs.TalonFXConfiguration

internal const val MAX_ALLOWED_FLYWHEEL_MOTOR_RPM_SPREAD = 250.0
private const val FULL_EFFORT_THRESHOLD = 0.999
private const val NOMINAL_VOLTAGE = 12.0
private const val MAX_FLYWHEEL_RPM = 6000.0
private const val VELOCITY_KP_VOLTS_PER_RPM = 0.002

/** Pure four-sensor readiness predicate; no averaged pair may hide a stopped or stale motor. */
internal fun flywheelVelocitySnapshotValid(
    leftMasterRpm: Double,
    leftFollowerRpm: Double,
    rightMasterRpm: Double,
    rightFollowerRpm: Double
): Boolean {
    if (!leftMasterRpm.isFinite() || leftMasterRpm < 0.0 ||
        !leftFollowerRpm.isFinite() || leftFollowerRpm < 0.0 ||
        !rightMasterRpm.isFinite() || rightMasterRpm < 0.0 ||
        !rightFollowerRpm.isFinite() || rightFollowerRpm < 0.0) return false
    val minRpm = minOf(minOf(leftMasterRpm, leftFollowerRpm), minOf(rightMasterRpm, rightFollowerRpm))
    val maxRpm = maxOf(maxOf(leftMasterRpm, leftFollowerRpm), maxOf(rightMasterRpm, rightFollowerRpm))
    return maxRpm - minRpm <= MAX_ALLOWED_FLYWHEEL_MOTOR_RPM_SPREAD
}

internal fun flywheelVelocitySnapshotReadyForTarget(
    targetRpm: Double,
    toleranceRpm: Double,
    leftMasterRpm: Double,
    leftFollowerRpm: Double,
    rightMasterRpm: Double,
    rightFollowerRpm: Double
): Boolean {
    if (!targetRpm.isFinite() || targetRpm < 0.0 ||
        !toleranceRpm.isFinite() || toleranceRpm <= 0.0) return false
    return flywheelVelocitySnapshotValid(
        leftMasterRpm,
        leftFollowerRpm,
        rightMasterRpm,
        rightFollowerRpm
    ) && kotlin.math.abs(leftMasterRpm - targetRpm) < toleranceRpm &&
        kotlin.math.abs(leftFollowerRpm - targetRpm) < toleranceRpm &&
        kotlin.math.abs(rightMasterRpm - targetRpm) < toleranceRpm &&
        kotlin.math.abs(rightFollowerRpm - targetRpm) < toleranceRpm
}

/**
 * Four-motor TalonFX flywheel IO on `CAN2`, arranged as opposed master/follower pairs.
 *
 * Public speed units are RPM; CTRE closed-loop requests use rotations per second at this
 * boundary. [refresh] jointly refreshes every master and follower velocity signal and records
 * whether all four agree closely enough to trust. Consumers must require [velocityValid] before
 * using cached RPM to authorize feeding. Reverse voltage is disabled by configuration.
 */
class FRCFlywheelHardwareIO(
    private val leftMaster: TalonFX,
    private val leftFollower: TalonFX,
    private val rightMaster: TalonFX,
    private val rightFollower: TalonFX
) : FlywheelIO, FrcMechanismConfigurationStatus, FrcFlywheelTuningStatus,
    FrcFlywheelPerMotorReadiness, AutoCloseable {
    private val startupConfigurationValid: Boolean
    @Volatile private var resetDetected = false
    @Volatile override var lastTuningApplySuccessful: Boolean = true
        private set
    override val configurationValid: Boolean
        get() = startupConfigurationValid && !resetDetected && lastTuningApplySuccessful
    @Volatile private var cachedVelocityValid = false
    @Volatile private var cachedVelocityRpm = 0.0
    @Volatile private var cachedCurrentValid = false
    @Volatile private var cachedLeftMasterRpm = 0.0
    @Volatile private var cachedLeftFollowerRpm = 0.0
    @Volatile private var cachedRightMasterRpm = 0.0
    @Volatile private var cachedRightFollowerRpm = 0.0

    private val velocityRequest = VelocityVoltage(0.0)
    private val voltageRequest = VoltageOut(0.0)

    private val leftMasterVelocity = leftMaster.velocity
    private val leftFollowerVelocity = leftFollower.velocity
    private val rightMasterVelocity = rightMaster.velocity
    private val rightFollowerVelocity = rightFollower.velocity
    private val leftMasterCurrent = leftMaster.statorCurrent
    private val leftFollowerCurrent = leftFollower.statorCurrent
    private val rightMasterCurrent = rightMaster.statorCurrent
    private val rightFollowerCurrent = rightFollower.statorCurrent
    private val leftMasterTemp = leftMaster.deviceTemp
    private val rightMasterTemp = rightMaster.deviceTemp

    init {
        leftMaster.optimizeBusUtilization()
        leftFollower.optimizeBusUtilization()
        rightMaster.optimizeBusUtilization()
        rightFollower.optimizeBusUtilization()

        setUpdateFrequencies(
            50.0,
            leftMasterVelocity,
            leftFollowerVelocity,
            rightMasterVelocity,
            rightFollowerVelocity
        )
        setUpdateFrequencies(20.0, leftMasterCurrent, leftFollowerCurrent, rightMasterCurrent, rightFollowerCurrent)
        setUpdateFrequencies(4.0, leftMasterTemp, rightMasterTemp)

        // Configure followers as opposed to their respective masters
        val leftFollowerConfigured = leftFollower.setControl(
            Follower(leftMaster.deviceID, com.ctre.phoenix6.signals.MotorAlignmentValue.Opposed)
        ).isOK
        val rightFollowerConfigured = rightFollower.setControl(
            Follower(rightMaster.deviceID, com.ctre.phoenix6.signals.MotorAlignmentValue.Opposed)
        ).isOK
        if (!leftFollowerConfigured) reportConfigurationFailure("Flywheel left follower request failed")
        if (!rightFollowerConfigured) reportConfigurationFailure("Flywheel right follower request failed")

        // Enforce exact physical configurations matching SystemConstants.java
        val leftConfigured = listOf(leftMaster, leftFollower).applyMechanismConfigChecked("Flywheel left pair") {
            Slot0.kP = 0.5
            Slot0.kI = 0.0
            Slot0.kD = 0.0
            Slot0.kV = 0.12 // 12.0 / 100.0 (Max speed: 6000 RPM / 60 = 100 RPS)
            Slot0.kS = 0.15 // Conservative static friction compensation, should be tuned via sysid

            MotorOutput.NeutralMode = com.ctre.phoenix6.signals.NeutralModeValue.Coast
            MotorOutput.Inverted = com.ctre.phoenix6.signals.InvertedValue.CounterClockwise_Positive

            Feedback.SensorToMechanismRatio = 1.0

            Voltage.PeakReverseVoltage = 0.0 // Software lock reversal of flywheel
            CurrentLimits.SupplyCurrentLimitEnable = true
            CurrentLimits.SupplyCurrentLimit = 70.0
            CurrentLimits.StatorCurrentLimitEnable = true
            CurrentLimits.StatorCurrentLimit = 120.0
        }
        
        val rightConfigured = listOf(rightMaster, rightFollower).applyMechanismConfigChecked("Flywheel right pair") {
            Slot0.kP = 0.5
            Slot0.kI = 0.0
            Slot0.kD = 0.0
            Slot0.kV = 0.12 // 12.0 / 100.0 (Max speed: 6000 RPM / 60 = 100 RPS)
            Slot0.kS = 0.15 // Conservative static friction compensation, should be tuned via sysid

            MotorOutput.NeutralMode = com.ctre.phoenix6.signals.NeutralModeValue.Coast
            MotorOutput.Inverted = com.ctre.phoenix6.signals.InvertedValue.Clockwise_Positive

            Feedback.SensorToMechanismRatio = 1.0

            Voltage.PeakReverseVoltage = 0.0 // Software lock reversal of flywheel
            CurrentLimits.SupplyCurrentLimitEnable = true
            CurrentLimits.SupplyCurrentLimit = 70.0
            CurrentLimits.StatorCurrentLimitEnable = true
            CurrentLimits.StatorCurrentLimit = 120.0
        }
        startupConfigurationValid = leftFollowerConfigured && rightFollowerConfigured &&
            leftConfigured && rightConfigured
    }



    override fun refresh() {
        if (anyTalonResetOccurred(leftMaster, leftFollower, rightMaster, rightFollower)) {
            resetDetected = true
        }
        val velocityRefreshOk = BaseStatusSignal.refreshAll(
            leftMasterVelocity,
            leftFollowerVelocity,
            rightMasterVelocity,
            rightFollowerVelocity
        ).isOK
        val leftMasterRpm = kotlin.math.abs(leftMasterVelocity.valueAsDouble * 60.0)
        val leftFollowerRpm = kotlin.math.abs(leftFollowerVelocity.valueAsDouble * 60.0)
        val rightMasterRpm = kotlin.math.abs(rightMasterVelocity.valueAsDouble * 60.0)
        val rightFollowerRpm = kotlin.math.abs(rightFollowerVelocity.valueAsDouble * 60.0)
        cachedLeftMasterRpm = leftMasterRpm
        cachedLeftFollowerRpm = leftFollowerRpm
        cachedRightMasterRpm = rightMasterRpm
        cachedRightFollowerRpm = rightFollowerRpm
        cachedVelocityValid = configurationValid && velocityRefreshOk && flywheelVelocitySnapshotValid(
            leftMasterRpm,
            leftFollowerRpm,
            rightMasterRpm,
            rightFollowerRpm
        )
        cachedVelocityRpm = if (cachedVelocityValid) {
            (leftMasterRpm + leftFollowerRpm + rightMasterRpm + rightFollowerRpm) / 4.0
        } else {
            0.0
        }
        cachedCurrentValid = BaseStatusSignal.refreshAll(
            leftMasterCurrent, leftFollowerCurrent,
            rightMasterCurrent, rightFollowerCurrent
        ).isOK && leftMasterCurrent.valueAsDouble.isFinite() && leftMasterCurrent.valueAsDouble >= 0.0 &&
            leftFollowerCurrent.valueAsDouble.isFinite() && leftFollowerCurrent.valueAsDouble >= 0.0 &&
            rightMasterCurrent.valueAsDouble.isFinite() && rightMasterCurrent.valueAsDouble >= 0.0 &&
            rightFollowerCurrent.valueAsDouble.isFinite() && rightFollowerCurrent.valueAsDouble >= 0.0
        BaseStatusSignal.refreshAll(leftMasterTemp, rightMasterTemp)
    }

    override fun setVelocityRpm(rpm: Double, maxEffortScale: Double) {
        if (!configurationValid) {
            leftMaster.setControl(voltageRequest.withOutput(0.0))
            rightMaster.setControl(voltageRequest.withOutput(0.0))
            return
        }
        val targetRpm = rpm.takeIf { it.isFinite() && it >= 0.0 }
            ?.coerceAtMost(MAX_FLYWHEEL_RPM) ?: 0.0
        val effortScale = maxEffortScale.takeIf { it.isFinite() }
            ?.coerceIn(0.0, 1.0) ?: 0.0
        if (effortScale <= 0.0) {
            setAppliedVoltage(0.0)
        } else if (effortScale >= FULL_EFFORT_THRESHOLD) {
            val rps = targetRpm / 60.0
            leftMaster.setControl(velocityRequest.withVelocity(rps))
            rightMaster.setControl(velocityRequest.withVelocity(rps))
        } else {
            val feedforwardVolts = targetRpm / MAX_FLYWHEEL_RPM * NOMINAL_VOLTAGE
            val feedbackVolts = (targetRpm - cachedVelocityRpm) * VELOCITY_KP_VOLTS_PER_RPM
            val voltageLimit = NOMINAL_VOLTAGE * effortScale
            setAppliedVoltage((feedforwardVolts + feedbackVolts).coerceIn(0.0, voltageLimit))
        }
    }

    override fun setAppliedVoltage(volts: Double) {
        val requestedVolts = if (configurationValid) volts else 0.0
        val safeVolts = requestedVolts.takeIf { it.isFinite() }?.coerceIn(0.0, 12.0) ?: 0.0
        leftMaster.setControl(voltageRequest.withOutput(safeVolts))
        rightMaster.setControl(voltageRequest.withOutput(safeVolts))
    }

    override fun configureVelocityController(
        gains: com.areslib.control.tuning.PIDFCoefficients,
        feedforward: com.areslib.control.tuning.SimpleFeedforwardCoeffs
    ) {
        val radiansPerRotation = 2.0 * Math.PI
        val kP = gains.kP * radiansPerRotation
        val kI = gains.kI * radiansPerRotation
        val kD = gains.kD * radiansPerRotation
        val kV = feedforward.kV * radiansPerRotation
        val kA = feedforward.kA * radiansPerRotation
        if (!listOf(kP, kI, kD, kV, kA, feedforward.kS).all { it.isFinite() && it >= 0.0 }) {
            lastTuningApplySuccessful = false
            reportConfigurationFailure("Flywheel live tuning rejected invalid gains")
            return
        }
        val motors = arrayOf(leftMaster, leftFollower, rightMaster, rightFollower)
        val configs = Array(motors.size) { TalonFXConfiguration() }
        if (motors.indices.any { !motors[it].configurator.refresh(configs[it]).isOK }) {
            lastTuningApplySuccessful = false
            reportConfigurationFailure("Flywheel live tuning could not read every motor configuration")
            return
        }
        for (config in configs) {
            config.Slot0.kP = kP
            config.Slot0.kI = kI
            config.Slot0.kD = kD
            config.Slot0.kS = feedforward.kS
            config.Slot0.kV = kV
            config.Slot0.kA = kA
        }
        var allApplied = true
        for (index in motors.indices) {
            if (!motors[index].configurator.apply(configs[index]).isOK) allApplied = false
        }
        lastTuningApplySuccessful = allApplied
        if (!lastTuningApplySuccessful) {
            setAppliedVoltage(0.0)
            reportConfigurationFailure("Flywheel live tuning did not reach every motor")
        }
    }

    override val velocityRpm: Double
        get() = cachedVelocityRpm

    override val velocityValid: Boolean
        get() = cachedVelocityValid

    override fun allMotorsAtTarget(targetRpm: Double, toleranceRpm: Double): Boolean =
        cachedVelocityValid && flywheelVelocitySnapshotReadyForTarget(
            targetRpm,
            toleranceRpm,
            cachedLeftMasterRpm,
            cachedLeftFollowerRpm,
            cachedRightMasterRpm,
            cachedRightFollowerRpm
        )

    override val currentAmps: Double
        get() = leftMasterCurrent.valueAsDouble +
                leftFollowerCurrent.valueAsDouble +
                rightMasterCurrent.valueAsDouble +
                rightFollowerCurrent.valueAsDouble

    override fun isCurrentReadingValid(readingAmps: Double): Boolean =
        cachedCurrentValid && readingAmps.isFinite() && readingAmps >= 0.0

    override val tempCelsius: Double
        get() = Math.max(leftMasterTemp.valueAsDouble, rightMasterTemp.valueAsDouble)

    override fun close() = closeTalons(leftMaster, leftFollower, rightMaster, rightFollower)

}
