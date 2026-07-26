package com.areslib.state

import com.areslib.control.tuning.PIDFCoefficients
import com.areslib.control.tuning.SimpleFeedforwardCoeffs

/**
 * Immutable Redux state holding all dynamically tunable constants for the robot.
 * Values can be updated via the dashboard and dispatched as an [UpdateTuningState] action.
 */
data class TuningState(
    // Drivetrain Kinematics
    val trackWidthMeters: Double = 0.45,
    val wheelBaseMeters: Double = 0.45,

    // Drivetrain Path-following Translation PID
    val pathTranslationGains: PIDFCoefficients = PIDFCoefficients(2.0, 0.0, 0.02),

    // Drivetrain Path-following Rotation PID
    val pathRotationGains: PIDFCoefficients = PIDFCoefficients(2.5, 0.0, 0.05),

    // Drive Feedback (Heading Lock PID)
    val headingGains: PIDFCoefficients = PIDFCoefficients(1.8, 0.0, 0.08),
    val headingDeadzoneDeg: Double = 2.5,

    // Drive Feedback (Position Hold PID)
    val positionHoldGains: PIDFCoefficients = PIDFCoefficients(1.5, 0.0, 0.1),
    val positionHoldDeadzoneMeters: Double = 0.02,

    val teleOpTurnScale: Double = 0.50,

    // Drivetrain Feedforward & Slew Acceleration Limits (GoBilda 312 RPM Mecanum Baseline)
    val driveFeedforward: SimpleFeedforwardCoeffs = SimpleFeedforwardCoeffs(kS = 0.05, kV = 0.638, kA = 0.02),
    val driveSlewRateLimit: Double? = null,

    // Motor Velocity Closed-Loop PIDF (Qualcomm DcMotorEx SDK level)
    val motorGains: PIDFCoefficients? = null,

    // Vision Filtering baseline standard deviations
    val visionStdDevsX: Double = 0.05,
    val visionStdDevsY: Double = 0.05,
    val visionStdDevsHeading: Double = 0.1,

    // Vision Outlier Rejection Thresholds
    val visionMaxDistanceMeters: Double = 6.0,
    val visionMaxAmbiguity: Double = 0.2,
    val visionMahalanobisThreshold: Double = 12.0,

    // Localization / EKF Noise Tuning
    val odomQx: Double = 0.01,
    val odomQy: Double = 0.01,
    val odomQtheta: Double = 0.01,

    // Pinpoint Odometry Tuning
    val pinpointXOffsetMm: Double = 0.0,
    val pinpointYOffsetMm: Double = 0.0,
    val pinpointEncoderResolution: Double = 20.44, // Typical Standard: 20.44 ticks/mm

    // Drivetrain Ticks per Meter
    val ticksPerMeter: Double = 2000.0,

    // Driver Profile Settings
    val driverDeadbandExponent: Double = 1.0,
    val driverSlewRateLimit: Double = 999.0,

    // Kidnapped / Stolen Robot Recovery
    val stolenRobotRejectionThreshold: Double = 10.0,
    val stolenRobotVelocityThreshold: Double = 0.05,

    // Pathfinding / Trajectory Limits
    val pathVelocityScale: Double = 0.85,
    val pathAccelerationLimit: Double = 3.0,

    // Vision-Based Closed-Loop Alignment
    val visionAlignTargetDistance: Double = 2.4384, // 8 feet
    val visionAlignMaxHeadingChangeRad: Double = 0.2618, // 15 degrees in rad
    val visionAlignAlphaTranslation: Double = 0.4,
    val visionAlignAlphaHeading: Double = 0.5,
    val visionAlignKpTranslation: Double = 1.0,
    val visionAlignKpRotation: Double = 1.1,
    val visionAlignKdRotation: Double = 0.35,
    val visionAlignKsRotational: Double = 0.06,
    val visionAlignTranslationDeadband: Double = 0.04,
    val visionAlignHeadingErrorDeadband: Double = 0.0175, // ~1 degree in rad
    val visionAlignClampTranslationX: Double = 0.5,
    val visionAlignClampTranslationY: Double = 0.3,
    val visionAlignClampRotation: Double = 0.65,
    val visionAlignSearchFirstSweepMs: Long = 1200,
    val visionAlignSearchSecondSweepMs: Long = 2400,
    val visionAlignSearchSpeed: Double = 0.85,

    // Telemetry & Watchdog / Bus Tuning
    val telemetryRateDivisor: Int = 3,
    val motorCurrentPollingIntervalMs: Long = 50,

    // Subsystem Presets
    val intakeNominalVoltage: Double = 12.0,
    val flywheelTargetRpmPreset: Double = 2000.0,
    val driverTriggerThreshold: Double = 0.5
)
