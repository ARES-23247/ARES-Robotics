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
    fun setMotorType(value: com.qualcomm.robotcore.hardware.configuration.typecontainers.MotorConfigurationType) { error("Motor type is not configured") }
    val controller: DcMotorController get() = error("Motor controller is not configured")
    val portNumber: Int get() = error("Motor port is not configured")
    fun setPowerFloat() { error("Float mode is not configured") }
    val powerFloat: Boolean get() = error("Float mode is not configured")
    fun setTargetPosition(value: Int) { error("Target position is not configured") }
    fun getTargetPosition(): Int = error("Target position is not configured")
    val isBusy: Boolean get() = error("Target position feedback is not configured")
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
    fun setMotorEnable() { error("Motor enable is not configured") }
    fun setMotorDisable() { error("Motor disable is not configured") }
    val isMotorEnabled: Boolean get() = false
    fun getVelocity(unit: org.firstinspires.ftc.robotcore.external.navigation.AngleUnit): Double = error("Angular velocity is not configured")
    fun setVelocity(value: Double, unit: org.firstinspires.ftc.robotcore.external.navigation.AngleUnit) { error("Angular velocity is not configured") }
    fun getPIDCoefficients(mode: DcMotor.RunMode): PIDCoefficients = error("PID is not configured")
    fun setPIDCoefficients(mode: DcMotor.RunMode, coefficients: PIDCoefficients) { error("PID is not configured") }
    fun setVelocityPIDFCoefficients(p: Double, i: Double, d: Double, f: Double) { error("Velocity PIDF is not configured") }
    fun setPositionPIDFCoefficients(p: Double) { error("Position PIDF is not configured") }
    fun getTargetPositionTolerance(): Int = error("Target tolerance is not configured")
    fun setTargetPositionTolerance(value: Int) { error("Target tolerance is not configured") }
    fun getCurrentAlert(unit: org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit): Double = error("Current alert is not configured")
    fun setCurrentAlert(value: Double, unit: org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit) { error("Current alert is not configured") }
    val isOverCurrent: Boolean get() = error("Current alert feedback is not configured")
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
    enum class Direction { FORWARD, REVERSE }
    fun getDirection(): Direction = Direction.FORWARD
    fun setDirection(value: Direction) { error("Servo direction is not configured") }
    val controller: ServoController get() = error("Servo controller is not configured")
    val portNumber: Int get() = error("Servo port is not configured")
    fun scaleRange(min: Double, max: Double) { error("Servo range is not configured") }
    var position: Double
}

interface DcMotorController : HardwareDevice
interface ServoController : HardwareDevice
class PIDCoefficients(@JvmField var p: Double = 0.0, @JvmField var i: Double = 0.0, @JvmField var d: Double = 0.0)

/**
 * Mock representation of an FTC [PIDFCoefficients].
 */
open class PIDFCoefficients(
    @JvmField var p: Double = 0.0,
    @JvmField var i: Double = 0.0,
    @JvmField var d: Double = 0.0,
    @JvmField var f: Double = 0.0
)
