package com.areslib.state

import com.areslib.control.tuning.PIDFCoefficients
import com.areslib.control.tuning.SimpleFeedforwardCoeffs

// ── FTC-Specific Hardware Tuning Sub-States ──

/**
 * FTC-specific odometry hardware tuning (GoBilda Pinpoint Computer).
 */
data class FtcPinpointTuningState(
    val xOffsetMm: Double = 0.0,
    val yOffsetMm: Double = 0.0,
    /** Positive values override the pod calibration; zero retains the SDK's named 4-Bar preset. */
    val encoderResolution: Double = 0.0
)

/**
 * FTC-specific motor encoder resolution and SDK DcMotorEx gains.
 */
data class FtcDriveTuningState(
    val ticksPerMeter: Double = 2000.0,
    val motorGains: PIDFCoefficients? = null
)

/**
 * FTC mechanism presets (intake nominal voltage, flywheel target RPM presets).
 */
data class FtcSubsystemTuningState(
    val intakeNominalVoltage: Double = 12.0,
    val flywheelTargetRpmPreset: Double = 2000.0
)

// ── Cross-Platform / Shared Tuning Sub-States ──

/**
 * Platform-independent EKF state-space estimation process noise.
 */
data class EkfProcessNoiseTuningState(
    val qX: Double = 0.01,
    val qY: Double = 0.01,
    val qTheta: Double = 0.01
)

/**
 * Shared Drivetrain closed-loop gains, feedforwards, and acceleration limits.
 */
data class DriveTuningState(
    val trackWidthMeters: Double = 0.45,
    val wheelBaseMeters: Double = 0.45,
    val pathTranslationGains: PIDFCoefficients = PIDFCoefficients(2.0, 0.0, 0.02),
    val pathRotationGains: PIDFCoefficients = PIDFCoefficients(2.5, 0.0, 0.05),
    val headingGains: PIDFCoefficients = PIDFCoefficients(1.8, 0.0, 0.08),
    val headingDeadzoneDeg: Double = 2.5,
    val headingMaxOutputLimit: Double = 0.40,
    val positionHoldGains: PIDFCoefficients = PIDFCoefficients(1.5, 0.0, 0.1),
    val positionHoldDeadzoneMeters: Double = 0.02,
    val positionHoldMaxOutputLimit: Double = 0.50,
    val teleOpTurnScale: Double = 0.60,
    val driveFeedforward: SimpleFeedforwardCoeffs = SimpleFeedforwardCoeffs(kS = 0.05, kV = 0.638, kA = 0.02),
    val angularFeedforward: SimpleFeedforwardCoeffs = SimpleFeedforwardCoeffs(kS = 0.0, kV = 0.0, kA = 0.0),
    val driveSlewRateLimit: Double? = null,
    val pathVelocityScale: Double = 0.85,
    val pathAccelerationLimit: Double = 3.0,
    val ftc: FtcDriveTuningState = FtcDriveTuningState()
) {
    val ticksPerMeter: Double get() = ftc.ticksPerMeter
    val motorGains: PIDFCoefficients? get() = ftc.motorGains
}

/**
 * Shared Vision measurement noise standard deviations and outlier rejection thresholds.
 */
data class VisionTuningState(
    val stdDevsX: Double = 0.35,
    val stdDevsY: Double = 0.35,
    val stdDevsHeading: Double = 0.80,
    val maxDistanceMeters: Double = 6.0,
    val maxAmbiguity: Double = 0.2,
    val mahalanobisThreshold: Double = 18.0
)

/**
 * Shared Vision-based AprilTag closed-loop alignment gains and sweep timing.
 */
data class VisionAlignTuningState(
    val targetDistanceMeters: Double = 2.4384, // 8 feet
    val maxHeadingChangeRad: Double = 0.2618, // 15 degrees in rad
    val alphaTranslation: Double = 0.4,
    val alphaHeading: Double = 0.5,
    val kpTranslation: Double = 1.0,
    val kpRotation: Double = 1.1,
    val kdRotation: Double = 0.35,
    val ksRotational: Double = 0.06,
    val translationDeadbandMeters: Double = 0.04,
    val headingErrorDeadbandRad: Double = 0.0175, // ~1 degree in rad
    val clampTranslationX: Double = 0.5,
    val clampTranslationY: Double = 0.3,
    val clampRotation: Double = 0.65,
    val searchFirstSweepMs: Long = 1200,
    val searchSecondSweepMs: Long = 2400,
    val searchSpeed: Double = 0.85
)

/**
 * Master Localization Tuning State grouping shared EKF noise and FTC Pinpoint tuning.
 */
data class LocalizationTuningState(
    val ekfNoise: EkfProcessNoiseTuningState = EkfProcessNoiseTuningState(),
    val ftcPinpoint: FtcPinpointTuningState = FtcPinpointTuningState()
) {
    val odomQx: Double get() = ekfNoise.qX
    val odomQy: Double get() = ekfNoise.qY
    val odomQtheta: Double get() = ekfNoise.qTheta
    val pinpointXOffsetMm: Double get() = ftcPinpoint.xOffsetMm
    val pinpointYOffsetMm: Double get() = ftcPinpoint.yOffsetMm
    val pinpointEncoderResolution: Double get() = ftcPinpoint.encoderResolution
}

/**
 * Shared Driver profile exponential curves and rate limits.
 */
data class DriverTuningState(
    val deadbandExponent: Double = 1.0,
    val slewRateLimit: Double = 999.0,
    val triggerThreshold: Double = 0.5
)

/**
 * Shared Kidnapped / Stolen Robot Recovery thresholds.
 */
data class RecoveryTuningState(
    val stolenRobotRejectionThreshold: Double = 45.0,
    val stolenRobotVelocityThreshold: Double = 0.1,
    val stolenRobotAngularVelocityThreshold: Double = 0.25
)

/**
 * Shared Telemetry and CAN / I2C polling intervals.
 */
data class TelemetryTuningState(
    val telemetryRateDivisor: Int = 3,
    val motorCurrentPollingIntervalMs: Long = 50
)

/**
 * Master Subsystem Tuning State holding FTC mechanism presets.
 */
data class SubsystemTuningState(
    val ftc: FtcSubsystemTuningState = FtcSubsystemTuningState(),
    val flywheel: MechanismTuningState = MechanismTuningState()
) {
    val intakeNominalVoltage: Double get() = ftc.intakeNominalVoltage
    val flywheelTargetRpmPreset: Double get() = ftc.flywheelTargetRpmPreset
}

/** Shared identified plant and feedback parameters for a velocity-controlled mechanism. */
data class MechanismTuningState(
    val feedforward: SimpleFeedforwardCoeffs = SimpleFeedforwardCoeffs(0.0, 0.0, 0.0),
    val velocityGains: PIDFCoefficients = PIDFCoefficients(0.0, 0.0, 0.0, 0.0)
)

/**
 * Immutable Redux state holding all dynamically tunable constants for the robot,
 * clearly compartmentalized into FTC-specific, FRC-specific, and shared cross-platform sub-states.
 */
data class TuningState(
    val drive: DriveTuningState = DriveTuningState(),
    val vision: VisionTuningState = VisionTuningState(),
    val visionAlign: VisionAlignTuningState = VisionAlignTuningState(),
    val localization: LocalizationTuningState = LocalizationTuningState(),
    val driver: DriverTuningState = DriverTuningState(),
    val recovery: RecoveryTuningState = RecoveryTuningState(),
    val telemetry: TelemetryTuningState = TelemetryTuningState(),
    val subsystem: SubsystemTuningState = SubsystemTuningState()
) {

    // ── Legacy / Flat Delegator Getters for 100% Backward Compatibility ──

    // Drivetrain
    val trackWidthMeters: Double get() = drive.trackWidthMeters
    val wheelBaseMeters: Double get() = drive.wheelBaseMeters
    val pathTranslationGains: PIDFCoefficients get() = drive.pathTranslationGains
    val pathRotationGains: PIDFCoefficients get() = drive.pathRotationGains
    val headingGains: PIDFCoefficients get() = drive.headingGains
    val headingDeadzoneDeg: Double get() = drive.headingDeadzoneDeg
    val headingMaxOutputLimit: Double get() = drive.headingMaxOutputLimit
    val positionHoldGains: PIDFCoefficients get() = drive.positionHoldGains
    val positionHoldDeadzoneMeters: Double get() = drive.positionHoldDeadzoneMeters
    val positionHoldMaxOutputLimit: Double get() = drive.positionHoldMaxOutputLimit
    val teleOpTurnScale: Double get() = drive.teleOpTurnScale
    val driveFeedforward: SimpleFeedforwardCoeffs get() = drive.driveFeedforward
    val angularFeedforward: SimpleFeedforwardCoeffs get() = drive.angularFeedforward
    val driveSlewRateLimit: Double? get() = drive.driveSlewRateLimit
    val motorGains: PIDFCoefficients? get() = drive.motorGains
    val ticksPerMeter: Double get() = drive.ticksPerMeter
    val pathVelocityScale: Double get() = drive.pathVelocityScale
    val pathAccelerationLimit: Double get() = drive.pathAccelerationLimit

    // Vision
    val visionStdDevsX: Double get() = vision.stdDevsX
    val visionStdDevsY: Double get() = vision.stdDevsY
    val visionStdDevsHeading: Double get() = vision.stdDevsHeading
    val visionMaxDistanceMeters: Double get() = vision.maxDistanceMeters
    val visionMaxAmbiguity: Double get() = vision.maxAmbiguity
    val visionMahalanobisThreshold: Double get() = vision.mahalanobisThreshold

    // Vision Align
    val visionAlignTargetDistance: Double get() = visionAlign.targetDistanceMeters
    val visionAlignMaxHeadingChangeRad: Double get() = visionAlign.maxHeadingChangeRad
    val visionAlignAlphaTranslation: Double get() = visionAlign.alphaTranslation
    val visionAlignAlphaHeading: Double get() = visionAlign.alphaHeading
    val visionAlignKpTranslation: Double get() = visionAlign.kpTranslation
    val visionAlignKpRotation: Double get() = visionAlign.kpRotation
    val visionAlignKdRotation: Double get() = visionAlign.kdRotation
    val visionAlignKsRotational: Double get() = visionAlign.ksRotational
    val visionAlignTranslationDeadband: Double get() = visionAlign.translationDeadbandMeters
    val visionAlignHeadingErrorDeadband: Double get() = visionAlign.headingErrorDeadbandRad
    val visionAlignClampTranslationX: Double get() = visionAlign.clampTranslationX
    val visionAlignClampTranslationY: Double get() = visionAlign.clampTranslationY
    val visionAlignClampRotation: Double get() = visionAlign.clampRotation
    val visionAlignSearchFirstSweepMs: Long get() = visionAlign.searchFirstSweepMs
    val visionAlignSearchSecondSweepMs: Long get() = visionAlign.searchSecondSweepMs
    val visionAlignSearchSpeed: Double get() = visionAlign.searchSpeed

    // Localization
    val odomQx: Double get() = localization.odomQx
    val odomQy: Double get() = localization.odomQy
    val odomQtheta: Double get() = localization.odomQtheta
    val pinpointXOffsetMm: Double get() = localization.pinpointXOffsetMm
    val pinpointYOffsetMm: Double get() = localization.pinpointYOffsetMm
    val pinpointEncoderResolution: Double get() = localization.pinpointEncoderResolution

    // Driver
    val driverDeadbandExponent: Double get() = driver.deadbandExponent
    val driverSlewRateLimit: Double get() = driver.slewRateLimit
    val driverTriggerThreshold: Double get() = driver.triggerThreshold

    // Recovery
    val stolenRobotRejectionThreshold: Double get() = recovery.stolenRobotRejectionThreshold
    val stolenRobotVelocityThreshold: Double get() = recovery.stolenRobotVelocityThreshold
    val stolenRobotAngularVelocityThreshold: Double get() = recovery.stolenRobotAngularVelocityThreshold

    // Telemetry
    val telemetryRateDivisor: Int get() = telemetry.telemetryRateDivisor
    val motorCurrentPollingIntervalMs: Long get() = telemetry.motorCurrentPollingIntervalMs

    // Subsystem
    val intakeNominalVoltage: Double get() = subsystem.intakeNominalVoltage
    val flywheelTargetRpmPreset: Double get() = subsystem.flywheelTargetRpmPreset
    val flywheelTuning: MechanismTuningState get() = subsystem.flywheel
}
