package com.areslib.ftc

import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.DcMotor
import org.firstinspires.ftc.robotcore.external.Telemetry

/**
 * Builder class for configuring and instantiating an [FtcMecanumRobot] via a fluent Kotlin DSL.
 *
 * Configures 4-wheel drive motor hardware mapping names, default motor direction polarities,
 * odometry computer names (GoBilda Pinpoint), vision cameras (Limelight 3A), and telemetry channels.
 *
 * @param hardwareMap Qualcomm FTC SDK hardware map reference.
 *
 * @see FtcMecanumRobot
 * @see ftcMecanumRobot
 */
class FtcMecanumRobotBuilder(private val hardwareMap: HardwareMap) {
    /**
     * Hardware map name for the Front Left drive motor. Defaults to `"fl"`.
     */
    var frontLeftMotorName: String = "fl"
    
    /**
     * Hardware map name for the Front Right drive motor. Defaults to `"fr"`.
     */
    var frontRightMotorName: String = "fr"
    
    /**
     * Hardware map name for the Rear Left drive motor. Defaults to `"rl"`.
     */
    var rearLeftMotorName: String = "rl"
    
    /**
     * Hardware map name for the Rear Right drive motor. Defaults to `"rr"`.
     */
    var rearRightMotorName: String = "rr"
    
    /**
     * Hardware map name for the GoBilda Pinpoint odometry computer. Defaults to `"pinpoint"`. Pass `null` if unattached.
     */
    var pinpointName: String? = "pinpoint"
    
    /**
     * Hardware map name for the Limelight 3A vision camera. Defaults to `"limelight"`. Pass `null` if unattached.
     */
    var limelightName: String? = "limelight"
    
    /**
     * Optional local telemetry channel for FTC Driver Station or Dashboard telemetry output.
     */
    var telemetry: Telemetry? = null

    /** Maximum wheel surface speed used to normalize drivetrain commands, in meters per second. */
    var maxWheelSpeedMetersPerSecond: Double = 3.5

    /** Neutral behavior applied to all four drive motors during construction. */
    var driveZeroPowerBehavior: DcMotor.ZeroPowerBehavior = DcMotor.ZeroPowerBehavior.BRAKE


    /**
     * Motor direction polarity for the Front Left motor. Defaults to [com.qualcomm.robotcore.hardware.DcMotorSimple.Direction.FORWARD].
     */
    var frontLeftMotorDirection: com.qualcomm.robotcore.hardware.DcMotorSimple.Direction = com.qualcomm.robotcore.hardware.DcMotorSimple.Direction.FORWARD

    /**
     * Motor direction polarity for the Front Right motor. Defaults to [com.qualcomm.robotcore.hardware.DcMotorSimple.Direction.REVERSE].
     */
    var frontRightMotorDirection: com.qualcomm.robotcore.hardware.DcMotorSimple.Direction = com.qualcomm.robotcore.hardware.DcMotorSimple.Direction.REVERSE

    /**
     * Motor direction polarity for the Rear Left motor. Defaults to [com.qualcomm.robotcore.hardware.DcMotorSimple.Direction.FORWARD].
     */
    var rearLeftMotorDirection: com.qualcomm.robotcore.hardware.DcMotorSimple.Direction = com.qualcomm.robotcore.hardware.DcMotorSimple.Direction.FORWARD

    /**
     * Motor direction polarity for the Rear Right motor. Defaults to [com.qualcomm.robotcore.hardware.DcMotorSimple.Direction.REVERSE].
     */
    var rearRightMotorDirection: com.qualcomm.robotcore.hardware.DcMotorSimple.Direction = com.qualcomm.robotcore.hardware.DcMotorSimple.Direction.REVERSE

    /**
     * Constructs and returns the fully configured [FtcMecanumRobot] instance.
     *
     * @return Initialized [FtcMecanumRobot] instance.
     */
    fun build(): FtcMecanumRobot = FtcMecanumRobot(
        hardwareMap = hardwareMap,
        flName = frontLeftMotorName,
        frName = frontRightMotorName,
        rlName = rearLeftMotorName,
        rrName = rearRightMotorName,
        pinpointName = pinpointName,
        limelightName = limelightName,
        localTelemetry = telemetry,
        flDirection = frontLeftMotorDirection,
        frDirection = frontRightMotorDirection,
        rlDirection = rearLeftMotorDirection,
        rrDirection = rearRightMotorDirection,
        maxWheelSpeedMetersPerSecond = maxWheelSpeedMetersPerSecond,
        driveZeroPowerBehavior = driveZeroPowerBehavior,
    )
}

/**
 * Creates and configures an [FtcMecanumRobot] instance using a clean, fluent Kotlin DSL.
 *
 * ### Example Usage:
 * ```kotlin
 * val robot = ftcMecanumRobot(hardwareMap) {
 *     frontLeftMotorName = "fl"
 *     frontRightMotorName = "fr"
 *     rearLeftMotorName = "rl"
 *     rearRightMotorName = "rr"
 *     pinpointName = "pinpoint"
 *     limelightName = "limelight"
 * }
 * ```
 *
 * @param hardwareMap Qualcomm FTC SDK hardware map reference.
 * @param block Configuration builder lambda expression.
 * @return Fully initialized [FtcMecanumRobot] instance.
 */
fun ftcMecanumRobot(
    hardwareMap: HardwareMap,
    block: FtcMecanumRobotBuilder.() -> Unit
): FtcMecanumRobot = FtcMecanumRobotBuilder(hardwareMap).apply(block).build()

