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
    /** Minimal SDK metadata boundary; fixtures using encoder units must supply a motor type. */
    val motorType: com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType
        get() = throw UnsupportedOperationException("Motor type metadata is not configured in this fixture")

    enum class ZeroPowerBehavior {
        BRAKE,
        FLOAT
    }

    enum class RunMode {
        RUN_WITHOUT_ENCODER,
        RUN_USING_ENCODER,
        RUN_TO_POSITION,
        STOP_AND_RESET_ENCODER
    }
    var mode: RunMode
    var zeroPowerBehavior: ZeroPowerBehavior
}

/**
 * Mock representation of an FTC [DcMotorEx].
 */
interface DcMotorEx : DcMotor {
    val currentPosition: Int
    var velocity: Double
    fun getCurrent(unit: org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit): Double
    fun setPIDFCoefficients(mode: DcMotor.RunMode, pidfCoefficients: PIDFCoefficients) {}
    /** Default fixture coefficients; stateful motor doubles may override both PIDF methods. */
    fun getPIDFCoefficients(mode: DcMotor.RunMode): PIDFCoefficients = PIDFCoefficients()
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
