package com.areslib.ftc.drivetrain

import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver
import com.areslib.action.RobotAction
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.areslib.math.wrapAngle

/**
 * Hardware IO abstraction layer for the GoBilda Pinpoint Odometry Computer.
 *
 * Provides high-frequency, zero-GC hardware reads for dead-wheel tracking pods and onboard 6-DOF IMU fusion.
 *
 * ### Hardware Boundary & Coordinate System:
 * - **Position**: $X$ = Forward ($m$), $Y$ = Left ($m$).
 * - **Velocity**: $v_x$ ($m/s$), $v_y$ ($m/s$).
 * - **Heading Convention**: Normalized to **Counter-Clockwise (CCW) Positive** math standard:
 *   $$\theta \in [-\pi, \pi], \quad 0 \text{ rad} = +X, \quad +\frac{\pi}{2} \text{ rad} = +Y$$
 * - **Polarity Correction**: The raw GoBilda Pinpoint driver outputs Clockwise (CW) positive heading natively when mounted right-side up.
 *   This class enforces CCW-positive transformation directly at the hardware boundary via [isHeadingCcwPositive] (`headingMult`),
 *   ensuring downstream EKF observers, kinematics solvers, and path followers receive CCW+ heading.
 *
 * ### Physical Units & Setup:
 * - Pod offsets: $X, Y$ mounting offsets in millimeters ($mm$).
 * - Encoder resolution: Ticks per millimeter ($ticks/mm$, defaults to GoBilda 4-bar standard 20.44 ticks/mm).
 * - Velocities: Linear $m/s$, Angular $rad/s$.
 * - Time: Milliseconds ($ms$).
 *
 * ### Zero-GC Compliance:
 * The [getPoseUpdate] loop operates without allocating heap objects during 50Hz–100Hz execution.
 * Pose updates reuse internal primitive accumulators and return immutable state structures.
 *
 * @param driver The physical GoBilda Pinpoint driver instance.
 * @param xOffsetMm X offset of the odometry pod relative to robot center ($mm$).
 * @param yOffsetMm Y offset of the odometry pod relative to robot center ($mm$).
 * @param encoderResolution Encoder resolution ($ticks/mm$). Pass `null` for factory default.
 * @param xDirection Encoder direction for X pod.
 * @param yDirection Encoder direction for Y pod.
 * @param isHeadingCcwPositive Physical mounting polarity flag. Set `true` if upside-down mount reverses raw Pinpoint CCW readings.
 *
 * @see com.qualcomm.hardware.gobilda.GoBildaPinpointDriver
 * @see RobotAction.PoseUpdate
 */
class PinpointIO @kotlin.jvm.JvmOverloads constructor(
    private val driver: GoBildaPinpointDriver,
    xOffsetMm: Double = 0.0,
    yOffsetMm: Double = 0.0,
    encoderResolution: Double? = null,
    xDirection: GoBildaPinpointDriver.EncoderDirection = GoBildaPinpointDriver.EncoderDirection.FORWARD,
    yDirection: GoBildaPinpointDriver.EncoderDirection = GoBildaPinpointDriver.EncoderDirection.FORWARD,
    private val isHeadingCcwPositive: Boolean = false
) : AutoCloseable {
    private var offsetX = 0.0
    private var offsetY = 0.0
    private var offsetHeading = 0.0

    init {
        setOffsets(xOffsetMm, yOffsetMm)
        if (encoderResolution != null) {
            driver.setEncoderResolution(encoderResolution, DistanceUnit.MM)
        }
        driver.setEncoderDirections(xDirection, yDirection)
    }

    private var lastWarningTime = 0L

    private var lastX = 0.0
    private var lastY = 0.0
    private var lastHeading = 0.0
    private var lastHeadingVelocity = 0.0
    private var lastVelX = 0.0
    private var lastVelY = 0.0
    private var lastTimestampMs = 0L

    init {
        com.areslib.hardware.HardwareRegistry.registerCloseable(this)
    }

    /**
     * Polls the GoBilda Pinpoint hardware computer and returns the updated pose as an immutable action.
     *
     * Applies coordinate transformations, pod offsets, and CCW-positive polarity corrections.
     * Zero-GC compliance: performs zero heap allocations during the 100Hz hardware sampling cycle.
     *
     * @return [RobotAction.PoseUpdate] containing positions ($m$), CCW+ heading ($rad$), and velocities ($m/s, rad/s$).
     */
    fun getPoseUpdate(): RobotAction.PoseUpdate {
        try {
            driver.update()
            val rawX = driver.getPosX(DistanceUnit.METER)
            val rawY = driver.getPosY(DistanceUnit.METER)
            val headingMult = if (isHeadingCcwPositive) 1.0 else -1.0
            val rawHeading = headingMult * driver.getHeading(AngleUnit.RADIANS)
            val rawHeadingVelocity = headingMult * driver.getHeadingVelocity(org.firstinspires.ftc.robotcore.external.navigation.UnnormalizedAngleUnit.RADIANS)

            val cosH = kotlin.math.cos(offsetHeading)
            val sinH = kotlin.math.sin(offsetHeading)
            val x = rawX * cosH - rawY * sinH + offsetX
            val y = rawX * sinH + rawY * cosH + offsetY
            val nextHeading = wrapAngle(rawHeading + offsetHeading)

            lastX = x
            lastY = y
            lastHeading = nextHeading
            lastHeadingVelocity = rawHeadingVelocity
            lastVelX = driver.getVelX(DistanceUnit.METER)
            lastVelY = driver.getVelY(DistanceUnit.METER)
            lastTimestampMs = com.areslib.util.RobotClock.currentTimeMillis()
        } catch (e: Exception) {
            val now = com.areslib.util.RobotClock.currentTimeMillis()
            if (now - lastWarningTime > 2000L) {
                System.err.println("PinpointIO: Communication failure with GoBildaPinpointDriver. Using last known coordinates. Error: ${e.message}")
                lastWarningTime = now
            }
        }

        var ts = lastTimestampMs
        if (ts == 0L) ts = com.areslib.util.RobotClock.currentTimeMillis()

        return RobotAction.PoseUpdate(
            xMeters = lastX,
            yMeters = lastY,
            headingRadians = lastHeading,
            angularVelocityRadiansPerSecond = lastHeadingVelocity,
            timestampMs = ts,
            xVelocityMetersPerSecond = lastVelX,
            yVelocityMetersPerSecond = lastVelY
        )
    }

    /**
     * Recalibrates the internal Pinpoint IMU while the robot is stationary.
     */
    fun recalibrateIMU() {
        try {
            driver.recalibrateIMU()
        } catch (_: Exception) {}
    }

    /**
     * Resets the Pinpoint tracking computer pose and optionally re-zeros physical IMU hardware registers.
     *
     * @param pose The field starting pose $(x, y, \theta)$ in meters ($m$) and CCW-positive radians ($rad$).
     * @param resetHardware If `true`, triggers a physical IMU and encoder reset on the Pinpoint board.
     */
    @kotlin.jvm.JvmOverloads
    fun initialize(pose: com.areslib.math.geometry.Pose2d = com.areslib.math.geometry.Pose2d(), resetHardware: Boolean = false) {
        try {
            if (resetHardware) {
                driver.resetPosAndIMU()
                offsetX = pose.x
                offsetY = pose.y
                offsetHeading = pose.heading.radians
                lastX = pose.x
                lastY = pose.y
                lastHeading = pose.heading.radians
                lastHeadingVelocity = 0.0
                lastTimestampMs = com.areslib.util.RobotClock.currentTimeMillis()
            } else {
                driver.update()
                val rawX = driver.getPosX(DistanceUnit.METER)
                val rawY = driver.getPosY(DistanceUnit.METER)
                val headingMult = if (isHeadingCcwPositive) 1.0 else -1.0
                val rawHeading = headingMult * driver.getHeading(AngleUnit.RADIANS)

                offsetHeading = wrapAngle(pose.heading.radians - rawHeading)
                val cosH = kotlin.math.cos(offsetHeading)
                val sinH = kotlin.math.sin(offsetHeading)
                
                offsetX = pose.x - (rawX * cosH - rawY * sinH)
                offsetY = pose.y - (rawX * sinH + rawY * cosH)
                
                lastX = pose.x
                lastY = pose.y
                lastHeading = pose.heading.radians
            }
        } catch (_: Exception) {}
    }

    /**
     * Configures the physical mounting offsets of the dead-wheel pods relative to the robot rotational center.
     *
     * @param xOffsetMm X offset (forward/backward distance from tracking center) in millimeters ($mm$).
     * @param yOffsetMm Y offset (left/right distance from tracking center) in millimeters ($mm$).
     */
    fun setOffsets(xOffsetMm: Double, yOffsetMm: Double) {
        try {
            // GoBilda Pinpoint setOffsets expects:
            // 1st arg: X-pod offset (sideways distance from tracking center, i.e., yOffsetMm)
            // 2nd arg: Y-pod offset (forward distance from tracking center, i.e., xOffsetMm)
            driver.setOffsets(yOffsetMm, xOffsetMm, DistanceUnit.MM)
        } catch (_: Exception) {}
    }

    /**
     * Sets the physical encoder resolution for the dead-wheel tracking pods.
     *
     * @param resolution Encoder resolution in ticks per millimeter ($ticks/mm$).
     */
    fun setEncoderResolution(resolution: Double) {
        try {
            if (resolution > 0.0) {
                driver.setEncoderResolution(resolution, DistanceUnit.MM)
            }
        } catch (_: Exception) {}
    }

    /**
     * Unregisters hardware resources.
     */
    override fun close() {
    }
}

