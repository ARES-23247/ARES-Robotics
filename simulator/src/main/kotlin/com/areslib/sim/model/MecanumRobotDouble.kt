package com.areslib.sim.model

import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotorSimple
import com.qualcomm.robotcore.hardware.VoltageSensor
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver
import com.qualcomm.hardware.limelightvision.Limelight3A
import com.qualcomm.hardware.limelightvision.LLResult
import com.qualcomm.hardware.limelightvision.LLResultTypes
import org.firstinspires.ftc.robotcore.external.navigation.Pose3D
import org.firstinspires.ftc.robotcore.external.navigation.Position
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit
import com.qualcomm.robotcore.hardware.HardwareMap
import kotlin.math.abs

/**
 * Class implementation for Sim Dc Motor Ex.
 *
 * Robotics framework control component.
 */
class SimDcMotorEx : DcMotorEx {
    override var direction: DcMotorSimple.Direction = DcMotorSimple.Direction.FORWARD
    @Volatile override var mode: DcMotor.RunMode = DcMotor.RunMode.RUN_WITHOUT_ENCODER
    @Volatile private var _power: Double = 0.0

    override var power: Double
        get() = if (direction == DcMotorSimple.Direction.REVERSE) -_power else _power
        set(value) {
            _power = if (direction == DcMotorSimple.Direction.REVERSE) -value else value
        }
    
    @Volatile override var currentPosition: Int = 0
    @Volatile override var velocity: Double = 0.0

    /**
     * getCurrent declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun getCurrent(unit: CurrentUnit): Double {
        // Return simulated current draw: 0.15A idle, scaling up to 4.2A under load
        return abs(power) * 4.05 + 0.15
    }
}

/**
 * Class implementation for Sim Servo.
 *
 * Robotics framework control component.
 */
class SimServo : com.qualcomm.robotcore.hardware.Servo {
    override var position: Double = 0.0
}

/**
 * Class implementation for Sim Limelight3 A.
 *
 * Robotics framework control component.
 */
class SimLimelight3A : Limelight3A() {
    override fun setLatestResult(res: LLResult?) {
        simulatedResult = res
    }
}

/**
 * Class implementation for Sim L L Result.
 *
 * Robotics framework control component.
 */
class SimLLResult(
    private val valid: Boolean,
    private val fiducials: List<LLResultTypes.FiducialResult>,
    private val botpose: Pose3D? = null
) : LLResult() {
    /**
     * isValid declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun isValid(): Boolean = valid
    /**
     * getFiducialResults declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun getFiducialResults(): List<LLResultTypes.FiducialResult> = fiducials
    /**
     * getBotpose declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun getBotpose(): Pose3D? = botpose
}

/**
 * Class implementation for Mecanum Robot Double.
 *
 * Robotics framework control component.
 */
class MecanumRobotDouble {
    val fl = SimDcMotorEx()
    val fr = SimDcMotorEx()
    val rl = SimDcMotorEx()
    val rr = SimDcMotorEx()
    
    val pinpoint = GoBildaPinpointDriver()
    val limelight = SimLimelight3A()
    
    val voltageSensor = object : VoltageSensor {
        override val voltage: Double = 12.8
    }

    val mockImu = object : com.qualcomm.robotcore.hardware.IMU {
        override fun initialize(parameters: com.qualcomm.robotcore.hardware.IMU.Parameters): Boolean = true
        override fun resetYaw() {}
        override fun getRobotYawPitchRollAngles(): org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles {
            return org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles(
                org.firstinspires.ftc.robotcore.external.navigation.AngleUnit.RADIANS,
                0.0, 0.0, 0.0, 0L
            )
        }
        override fun getRobotAngularVelocity(unit: org.firstinspires.ftc.robotcore.external.navigation.AngleUnit): org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity {
            return org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity(
                unit, 0.0f, 0.0f, 0.0f, 0L
            )
        }
        override fun close() {}
    }

    val hardwareMap = object : HardwareMap() {
        @Suppress("UNCHECKED_CAST")
        override fun <T> get(classOrType: Class<out T>, deviceName: String): T {
            return when (deviceName) {
                "fl", "front_left", "leftFront", "frontLeft" -> fl as T
                "fr", "front_right", "rightFront", "frontRight" -> fr as T
                "rl", "bl", "rear_left", "back_left", "leftRear", "leftBack", "rearLeft", "backLeft" -> rl as T
                "rr", "br", "rear_right", "back_right", "rightRear", "rightBack", "rearRight", "backRight" -> rr as T
                "pinpoint" -> pinpoint as T
                "limelight" -> limelight as T
                "imu" -> mockImu as T
                else -> {
                    when {
                        com.qualcomm.robotcore.hardware.IMU::class.java.isAssignableFrom(classOrType) -> {
                            println("[SimHardwareMap] Device '$deviceName' requested as IMU. Returning default mock IMU.")
                            mockImu as T
                        }
                        com.qualcomm.robotcore.hardware.Servo::class.java.isAssignableFrom(classOrType) -> {
                            println("[SimHardwareMap] Device '$deviceName' requested as Servo. Returning default SimServo.")
                            SimServo() as T
                        }
                        com.qualcomm.robotcore.hardware.DcMotor::class.java.isAssignableFrom(classOrType) -> {
                            println("[SimHardwareMap] Device '$deviceName' requested as DcMotor. Returning default SimDcMotorEx.")
                            SimDcMotorEx() as T
                        }
                        VoltageSensor::class.java.isAssignableFrom(classOrType) -> {
                            voltageSensor as T
                        }
                        else -> {
                            if (classOrType.isInterface) {
                                println("[SimHardwareMap] Unknown device '$deviceName' (${classOrType.simpleName}) requested. Returning dynamic proxy.")
                                java.lang.reflect.Proxy.newProxyInstance(
                                    classOrType.classLoader,
                                    arrayOf(classOrType),
                                    { _, method, _ ->
                                        when (method.returnType) {
                                            Boolean::class.javaPrimitiveType -> false
                                            Double::class.javaPrimitiveType -> 0.0
                                            Float::class.javaPrimitiveType -> 0.0f
                                            Int::class.javaPrimitiveType -> 0
                                            Long::class.javaPrimitiveType -> 0L
                                            String::class.java -> ""
                                            else -> throw UnsupportedOperationException("Method ${method.name} not implemented in dynamic mock for ${classOrType.simpleName}")
                                        }
                                    }
                                ) as T
                            } else {
                                println("[SimHardwareMap] Unknown device '$deviceName' (${classOrType.simpleName}) requested. Returning default SimDcMotorEx.")
                                SimDcMotorEx() as T
                            }
                        }
                    }
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T> getAll(classOrType: Class<out T>): List<T> {
            if (classOrType == VoltageSensor::class.java) {
                return listOf(voltageSensor as T)
            }
            return emptyList()
        }
    }

    // Encoder properties
    private val encoderTicksPerMeter = 2000.0 // Ticks per meter of wheel travel

    /**
     * updateSensors declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun updateSensors(dt: Double, actualVx: Double, actualVy: Double, actualOmega: Double, trueX: Double, trueY: Double, trueHeadingRad: Double, isPinpointCcwPositive: Boolean = false) {
        // FL = vx - vy - omega * (trackWidth + wheelBase)/2
        // FR = vx + vy + omega * (trackWidth + wheelBase)/2
        // RL = vx + vy - omega * (trackWidth + wheelBase)/2
        // RR = vx - vy + omega * (trackWidth + wheelBase)/2
        
        val flV = actualVx - actualVy - actualOmega * 0.45
        val frV = actualVx + actualVy + actualOmega * 0.45
        val rlV = actualVx + actualVy - actualOmega * 0.45
        val rrV = actualVx - actualVy + actualOmega * 0.45

        fl.velocity = flV * encoderTicksPerMeter
        fr.velocity = frV * encoderTicksPerMeter
        rl.velocity = rlV * encoderTicksPerMeter
        rr.velocity = rrV * encoderTicksPerMeter

        fl.currentPosition += (fl.velocity * dt).toInt()
        fr.currentPosition += (fr.velocity * dt).toInt()
        rl.currentPosition += (rl.velocity * dt).toInt()
        rr.currentPosition += (rr.velocity * dt).toInt()

        // Feed simulated EKF/Pinpoint sensor coordinates
        val xOff = pinpoint.xOffsetMeters
        val yOff = pinpoint.yOffsetMeters
        val cosH = kotlin.math.cos(trueHeadingRad)
        val sinH = kotlin.math.sin(trueHeadingRad)
        pinpoint.posX = trueX + (xOff * cosH - yOff * sinH)
        pinpoint.posY = trueY + (xOff * sinH + yOff * cosH)
        pinpoint.trueHeading = trueHeadingRad
        pinpoint.heading = if (isPinpointCcwPositive) trueHeadingRad else -trueHeadingRad
        pinpoint.headingVelocity = if (isPinpointCcwPositive) actualOmega else -actualOmega
        pinpoint.velX = actualVx
        pinpoint.velY = actualVy

        var visibleTagId: Int? = null
        val hFovRad = Math.toRadians(35.0)
        val maxRangeMeters = 3.5

        for ((tagId, pos) in SIM_TAGS) {
            val dx = pos.first - trueX
            val dy = pos.second - trueY
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)

            if (dist in 0.2..maxRangeMeters) {
                val angleToTag = kotlin.math.atan2(dy, dx)
                val relAngle = com.areslib.math.wrapAngle(angleToTag - trueHeadingRad)

                if (kotlin.math.abs(relAngle) <= hFovRad) {
                    visibleTagId = tagId
                    break
                }
            }
        }

        if (visibleTagId != null) {
            this.limelight.setSimulatedPose(trueX, trueY, Math.toDegrees(trueHeadingRad), visibleTagId)
            com.areslib.networktables.NT4Server.publishTopic("Vision/HasTarget", 1.0)
            com.areslib.networktables.NT4Server.publishTopic("Vision/Pose_X", trueX)
            com.areslib.networktables.NT4Server.publishTopic("Vision/Pose_Y", trueY)
            com.areslib.networktables.NT4Server.publishTopic("Vision/Pose_Heading", trueHeadingRad)
        } else {
            this.limelight.setLatestResult(null)
            com.areslib.networktables.NT4Server.publishTopic("Vision/HasTarget", 0.0)
            com.areslib.networktables.NT4Server.publishTopic("Vision/Pose_X", 0.0)
            com.areslib.networktables.NT4Server.publishTopic("Vision/Pose_Y", 0.0)
            com.areslib.networktables.NT4Server.publishTopic("Vision/Pose_Heading", 0.0)
        }
    }
}

private val SIM_TAGS = mapOf(
    1 to Pair(1.8, 1.8),
    2 to Pair(-1.8, 1.8),
    3 to Pair(1.8, -1.8),
    4 to Pair(-1.8, -1.8),
    11 to Pair(0.0, 1.8)
)
