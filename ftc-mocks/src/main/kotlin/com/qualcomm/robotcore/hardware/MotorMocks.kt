package com.qualcomm.robotcore.hardware

/**
 * Mock representation of an FTC [DcMotorSimple].
 */
interface DcMotorSimple : HardwareDevice {
    enum class Direction { FORWARD, REVERSE }
    var direction: Direction
    var power: Double
}

/**
 * Mock representation of an FTC [DcMotor].
 */
interface DcMotor : DcMotorSimple {
    enum class RunMode {
        RUN_WITHOUT_ENCODER,
        RUN_USING_ENCODER,
        RUN_TO_POSITION,
        STOP_AND_RESET_ENCODER
    }
    var mode: RunMode
}

/**
 * Mock representation of an FTC [DcMotorEx].
 */
interface DcMotorEx : DcMotor {
    val currentPosition: Int
    var velocity: Double
    fun getCurrent(unit: org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit): Double
    fun setPIDFCoefficients(mode: DcMotor.RunMode, pidfCoefficients: PIDFCoefficients) {}
}

/**
 * Mock representation of an FTC [CRServo].
 */
interface CRServo : DcMotorSimple

/**
 * Mock representation of an FTC [Servo].
 */
interface Servo : HardwareDevice {
    var position: Double
}

/**
 * Mock representation of an FTC [PIDFCoefficients].
 */
open class PIDFCoefficients(
    @JvmField var p: Double = 0.0,
    @JvmField var i: Double = 0.0,
    @JvmField var d: Double = 0.0,
    @JvmField var f: Double = 0.0
)
