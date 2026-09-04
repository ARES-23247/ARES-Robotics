package com.areslib.sim.model

import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotorSimple
import com.qualcomm.robotcore.hardware.VoltageSensor
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver
import com.qualcomm.hardware.sparkfun.SparkFunOTOS
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
import com.areslib.state.RobotFieldAprilTag
import com.areslib.state.RobotFieldConfig
import com.areslib.simulation.SimulationFaultKind
import com.areslib.simulation.SimulationFaultTimeline
import kotlin.math.abs

/**
 * Class implementation for Sim Dc Motor Ex.
 *
 * Robotics framework control component.
 */
class SimDcMotorEx(
    private val faultTimeline: SimulationFaultTimeline? = null,
    private val faultTargetId: String = "ftc.motor",
    private val busTargetId: String = "ftc.control-hub",
    private val powerTargetId: String = "ftc.power",
) : DcMotorEx {
    override var direction: DcMotorSimple.Direction = DcMotorSimple.Direction.FORWARD
    @Volatile override var mode: DcMotor.RunMode = DcMotor.RunMode.RUN_WITHOUT_ENCODER
    @Volatile override var zeroPowerBehavior: DcMotor.ZeroPowerBehavior = DcMotor.ZeroPowerBehavior.FLOAT
    @Volatile private var _power: Double = 0.0

    override var power: Double
        get() = if (direction == DcMotorSimple.Direction.REVERSE) -_power else _power
        set(value) {
            check(!writeUnavailable()) { "Simulated FTC motor write rejected for '$faultTargetId'" }
            val safeValue = value.takeIf(Double::isFinite)?.coerceIn(-1.0, 1.0) ?: 0.0
            val availableValue = if (isActive(powerTargetId, SimulationFaultKind.BROWNOUT)) {
                safeValue * 0.35
            } else {
                safeValue
            }
            _power = if (direction == DcMotorSimple.Direction.REVERSE) -availableValue else availableValue
        }

    @Volatile private var rawCurrentPosition: Int = 0
    @Volatile private var lastHealthyPosition: Int = 0
    override var currentPosition: Int
        get() {
            check(!inputDisconnected()) { "Simulated FTC motor input disconnected for '$faultTargetId'" }
            check(!isActive(faultTargetId, SimulationFaultKind.INVALID_INPUT)) {
                "Simulated FTC motor input invalid for '$faultTargetId'"
            }
            if (inputFrozen()) return lastHealthyPosition
            lastHealthyPosition = rawCurrentPosition
            return rawCurrentPosition
        }
        set(value) {
            rawCurrentPosition = value
            if (!inputFrozen() && !inputDisconnected()) lastHealthyPosition = value
        }

    @Volatile private var rawVelocity: Double = 0.0
    @Volatile private var lastHealthyVelocity: Double = 0.0
    override var velocity: Double
        get() {
            check(!inputDisconnected()) { "Simulated FTC motor input disconnected for '$faultTargetId'" }
            if (isActive(faultTargetId, SimulationFaultKind.INVALID_INPUT)) return Double.NaN
            if (inputFrozen()) return lastHealthyVelocity
            lastHealthyVelocity = rawVelocity
            return rawVelocity
        }
        set(value) {
            rawVelocity = value
            if (!inputFrozen() && !inputDisconnected() && value.isFinite()) lastHealthyVelocity = value
        }

    val inputValid: Boolean
        get() = !inputDisconnected() && !isActive(faultTargetId, SimulationFaultKind.INVALID_INPUT)

    val inputFresh: Boolean
        get() = inputValid && !isActive(faultTargetId, SimulationFaultKind.STALE_INPUT)

    override fun getCurrent(unit: CurrentUnit): Double {
        if (!inputValid) return Double.NaN
        // Return simulated current draw: 0.15A idle, scaling up to 4.2A under load
        return abs(power) * 4.05 + 0.15
    }

    private fun inputFrozen(): Boolean =
        isActive(faultTargetId, SimulationFaultKind.FROZEN_INPUT) ||
            isActive(faultTargetId, SimulationFaultKind.STALE_INPUT)

    private fun inputDisconnected(): Boolean =
        isActive(faultTargetId, SimulationFaultKind.DEVICE_DISCONNECTED) ||
            isActive(busTargetId, SimulationFaultKind.BUS_DISCONNECTED)

    private fun writeUnavailable(): Boolean = inputDisconnected() ||
        isActive(faultTargetId, SimulationFaultKind.WRITE_REJECTED)

    private fun isActive(targetId: String, kind: SimulationFaultKind): Boolean =
        faultTimeline?.isActive(targetId, kind) == true

}

/**
 * Class implementation for Sim Servo.
 *
 * Robotics framework control component.
 */
class SimServo : com.qualcomm.robotcore.hardware.Servo {
    private var commandedPosition = 0.0
    override var position: Double
        get() = commandedPosition
        set(value) {
            commandedPosition = value.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        }
}

/**
 * Class implementation for Sim Limelight3 A.
 *
 * Robotics framework control component.
 */
class SimLimelight3A : Limelight3A() {
    override fun setLatestResult(result: LLResult?) {
        simulatedResult = result
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
    override fun isValid(): Boolean = valid
    override fun getFiducialResults(): List<LLResultTypes.FiducialResult> = fiducials
    override fun getBotpose(): Pose3D? = botpose
}

/**
 * Class implementation for Mecanum Robot Double.
 *
 * Robotics framework control component.
 */
class MecanumRobotDouble(
    private val faultTimeline: SimulationFaultTimeline = SimulationFaultTimeline(emptyList()),
) {
    val fl = SimDcMotorEx(faultTimeline, "ftc.drive.fl")
    val fr = SimDcMotorEx(faultTimeline, "ftc.drive.fr")
    val rl = SimDcMotorEx(faultTimeline, "ftc.drive.rl")
    val rr = SimDcMotorEx(faultTimeline, "ftc.drive.rr")
    
    val pinpoint = GoBildaPinpointDriver()
    val otos = SparkFunOTOS()
    val limelight = SimLimelight3A()
    private var simulatedAprilTags: List<RobotFieldAprilTag> = DEFAULT_SIM_TAGS
    @Volatile private var simulatedImuHeadingRadians = 0.0
    @Volatile private var simulatedImuAngularVelocityRadiansPerSecond = 0.0
    
    val voltageSensor = object : VoltageSensor {
        override val voltage: Double
            get() = if (faultTimeline.isActive("ftc.power", SimulationFaultKind.BROWNOUT)) 7.0 else 12.8
    }

    val mockImu = object : com.qualcomm.robotcore.hardware.IMU {
        override fun initialize(parameters: com.qualcomm.robotcore.hardware.IMU.Parameters): Boolean = true
        override fun resetYaw() {}
        override fun getRobotYawPitchRollAngles(): org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles {
            return org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles(
                org.firstinspires.ftc.robotcore.external.navigation.AngleUnit.RADIANS,
                simulatedImuHeadingRadians, 0.0, 0.0, com.areslib.util.RobotClock.currentTimeMillis()
            )
        }
        override fun getRobotAngularVelocity(unit: org.firstinspires.ftc.robotcore.external.navigation.AngleUnit): org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity {
            val yawRate = if (unit == org.firstinspires.ftc.robotcore.external.navigation.AngleUnit.RADIANS) {
                simulatedImuAngularVelocityRadiansPerSecond
            } else {
                Math.toDegrees(simulatedImuAngularVelocityRadiansPerSecond)
            }
            return org.firstinspires.ftc.robotcore.external.navigation.AngularVelocity(
                unit, 0.0f, 0.0f, yawRate.toFloat(), com.areslib.util.RobotClock.currentTimeMillis()
            )
        }
        override fun close() {}
    }

    val hardwareMap = object : HardwareMap() {
        private val fallbackDevices = java.util.concurrent.ConcurrentHashMap<String, Any>()

        @Suppress("UNCHECKED_CAST")
        private fun <T> cachedFallback(
            deviceName: String,
            requestedType: Class<out T>,
            factory: () -> Any
        ): T {
            val device = fallbackDevices.computeIfAbsent(deviceName) { factory() }
            require(requestedType.isInstance(device)) {
                "Simulated device '$deviceName' was first requested as ${device.javaClass.simpleName}, " +
                    "not ${requestedType.simpleName}"
            }
            return device as T
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T> get(classOrType: Class<out T>, deviceName: String): T {
            return when (deviceName) {
                "fl", "front_left", "leftFront", "frontLeft" -> fl as T
                "fr", "front_right", "rightFront", "frontRight" -> fr as T
                "rl", "bl", "rear_left", "back_left", "leftRear", "leftBack", "rearLeft", "backLeft" -> rl as T
                "rr", "br", "rear_right", "back_right", "rightRear", "rightBack", "rearRight", "backRight" -> rr as T
                "pinpoint" -> pinpoint as T
                "otos", "sensor_otos" -> otos as T
                "limelight" -> limelight as T
                "imu" -> mockImu as T
                else -> {
                    when {
                        SparkFunOTOS::class.java.isAssignableFrom(classOrType) -> {
                            otos as T
                        }
                        com.qualcomm.robotcore.hardware.IMU::class.java.isAssignableFrom(classOrType) -> {
                            println("[SimHardwareMap] Device '$deviceName' requested as IMU. Returning default mock IMU.")
                            mockImu as T
                        }
                        com.qualcomm.robotcore.hardware.Servo::class.java.isAssignableFrom(classOrType) -> {
                            println("[SimHardwareMap] Device '$deviceName' requested as Servo. Returning default SimServo.")
                            cachedFallback(deviceName, classOrType, ::SimServo)
                        }
                        com.qualcomm.robotcore.hardware.DcMotor::class.java.isAssignableFrom(classOrType) -> {
                            println("[SimHardwareMap] Device '$deviceName' requested as DcMotor. Returning default SimDcMotorEx.")
                            cachedFallback(deviceName, classOrType) {
                                SimDcMotorEx(faultTimeline, "ftc.device.$deviceName")
                            }
                        }
                        VoltageSensor::class.java.isAssignableFrom(classOrType) -> {
                            this@MecanumRobotDouble.voltageSensor as T
                        }
                        else -> {
                            if (classOrType.isInterface) {
                                println("[SimHardwareMap] Unknown device '$deviceName' (${classOrType.simpleName}) requested. Returning dynamic proxy.")
                                cachedFallback(deviceName, classOrType) {
                                    java.lang.reflect.Proxy.newProxyInstance(
                                        classOrType.classLoader,
                                        arrayOf(classOrType)
                                    ) { _, method, _ ->
                                        when (method.returnType) {
                                            Boolean::class.javaPrimitiveType -> false
                                            Double::class.javaPrimitiveType -> 0.0
                                            Float::class.javaPrimitiveType -> 0.0f
                                            Int::class.javaPrimitiveType -> 0
                                            Long::class.javaPrimitiveType -> 0L
                                            String::class.java -> ""
                                            Void.TYPE -> null
                                            else -> throw UnsupportedOperationException(
                                                "No simulated return value for ${classOrType.simpleName}.${method.name}()"
                                            )
                                        }
                                    }
                                }
                            } else {
                                throw IllegalArgumentException(
                                    "No simulated hardware implementation for '$deviceName' (${classOrType.name})"
                                )
                            }
                        }
                    }
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T> getAll(classOrType: Class<out T>): List<T> {
            if (classOrType == VoltageSensor::class.java) {
                return listOf(this@MecanumRobotDouble.voltageSensor as T)
            }
            return emptyList()
        }
    }

    // Encoder properties
    private val encoderTicksPerMeter = 2000.0 // Ticks per meter of wheel travel

    /** Replaces the immutable AprilTag snapshot used by the 50 Hz camera simulation. */
    fun configureField(config: RobotFieldConfig) {
        simulatedAprilTags = config.apriltags
    }

    fun updateSensors(dt: Double, actualVx: Double, actualVy: Double, actualOmega: Double, trueX: Double, trueY: Double, trueHeadingRad: Double, isPinpointCcwPositive: Boolean = false) {
        simulatedImuHeadingRadians = trueHeadingRad
        simulatedImuAngularVelocityRadiansPerSecond = actualOmega

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

        otos.setPosition(SparkFunOTOS.Pose2D(trueX, trueY, trueHeadingRad))
        otos.setVelocity(SparkFunOTOS.Pose2D(actualVx, actualVy, actualOmega))

        var visibleTagId: Int? = null
        var tagIndex = 0
        while (tagIndex < simulatedAprilTags.size) {
            val tag = simulatedAprilTags[tagIndex]
            val dx = tag.x - trueX
            val dy = tag.y - trueY
            val distanceSquared = dx * dx + dy * dy

            if (distanceSquared in MIN_VISION_RANGE_SQUARED..MAX_VISION_RANGE_SQUARED) {
                val angleToTag = kotlin.math.atan2(dy, dx)
                val relAngle = com.areslib.math.wrapAngle(angleToTag - trueHeadingRad)

                if (kotlin.math.abs(relAngle) <= HORIZONTAL_HALF_FOV_RADIANS) {
                    visibleTagId = tag.id
                    break
                }
            }
            tagIndex++
        }

        if (visibleTagId != null) {
            this.limelight.setSimulatedPose(trueX, trueY, Math.toDegrees(trueHeadingRad), visibleTagId)
            com.areslib.networktables.NT4Server.publishTopic("Vision/HasTarget", true)
            com.areslib.networktables.NT4Server.publishTopic("Vision/Pose_X", trueX)
            com.areslib.networktables.NT4Server.publishTopic("Vision/Pose_Y", trueY)
            com.areslib.networktables.NT4Server.publishTopic("Vision/Pose_Heading", trueHeadingRad)
        } else {
            this.limelight.setLatestResult(null)
            com.areslib.networktables.NT4Server.publishTopic("Vision/HasTarget", false)
            com.areslib.networktables.NT4Server.publishTopic("Vision/Pose_X", 0.0)
            com.areslib.networktables.NT4Server.publishTopic("Vision/Pose_Y", 0.0)
            com.areslib.networktables.NT4Server.publishTopic("Vision/Pose_Heading", 0.0)
        }
    }
}

private const val HORIZONTAL_HALF_FOV_RADIANS = 0.6108652381980153 // 35 degrees
private const val MIN_VISION_RANGE_SQUARED = 0.04 // 0.2 m
private const val MAX_VISION_RANGE_SQUARED = 12.25 // 3.5 m

private val DEFAULT_SIM_TAGS = listOf(
    RobotFieldAprilTag(id = 1, x = 1.8, y = 1.8),
    RobotFieldAprilTag(id = 2, x = -1.8, y = 1.8),
    RobotFieldAprilTag(id = 3, x = 1.8, y = -1.8),
    RobotFieldAprilTag(id = 4, x = -1.8, y = -1.8),
    RobotFieldAprilTag(id = 11, x = 0.0, y = 1.8),
)
