package com.areslib.control.drivetrain

import com.areslib.control.feedback.PIDController
import com.areslib.control.feedback.LinearADRC
import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure Mathematical Holonomic Trajectory Tracking Controller for Mecanum and Swerve Drivetrains.
 *
 * Combines spline path tangent velocity feedforward with PID or Linear ADRC feedback controllers for field-space
 * translation ($x, y$) and orientation ($\theta$). Enforces centripetal acceleration velocity limits along curved trajectories
 * and transforms field-relative control effort into robot-frame [ChassisSpeeds].
 *
 * ### Control Theory & Mathematics:
 * 1. **Centripetal Acceleration Velocity Throttling**:
 *    $$v_{limited} = \min\left(v_{target}, \sqrt{\frac{a_{max}}{|\kappa|}}\right)$$
 * 2. **Tangent Feedforward Vector**:
 *    $$v_{x,FF} = v_{limited} \cdot \cos(\gamma_{tangent}), \quad v_{y,FF} = v_{limited} \cdot \sin(\gamma_{tangent})$$
 * 3. **Field-Relative Control Effort**:
 *    $$v_{x,field} = v_{x,FF} + \text{Feedback}_X(x_{target}, x_{current}), \quad v_{y,field} = v_{y,FF} + \text{Feedback}_Y(y_{target}, y_{current})$$
 * 4. **Translation Vector Magnitude Clamping**:
 *    $$\text{If } \|\mathbf{v}_{field}\| > v_{max}, \quad \mathbf{v}_{field} \gets \mathbf{v}_{field} \cdot \frac{v_{max}}{\|\mathbf{v}_{field}\|}$$
 * 5. **Field-to-Robot Frame Coordinate Transformation**:
 *    $$\begin{bmatrix} v_{x,robot} \\ v_{y,robot} \end{bmatrix} = \begin{bmatrix} \cos\theta & \sin\theta \\ -\sin\theta & \cos\theta \end{bmatrix} \begin{bmatrix} v_{x,field} \\ v_{y,field} \end{bmatrix}$$
 *
 * ### Physical Units & Coordinate System:
 * - Field Position ($x, y$): Meters ($m$)
 * - Translational Velocity ($v_x, v_y$): Meters per second ($m/s$)
 * - Robot Heading ($\theta$): Radians ($rad$), counter-clockwise positive (0° = +X)
 * - Angular Velocity ($\omega$): Radians per second ($rad/s$)
 * - Path Curvature ($\kappa$): Inverse meters ($m^{-1}$)
 * - Centripetal Acceleration ($a_{max}$): Meters per second squared ($m/s^2$)
 * - Timestep ($\Delta t$): Seconds ($s$)
 *
 * @param xController PID controller for field X-axis translation ($m$).
 * @param yController PID controller for field Y-axis translation ($m$).
 * @param thetaController PID controller for field heading rotation ($rad$).
 * @param telemetry Platform telemetry backend for streaming tracking error metrics (optional).
 * @param maxOutputMps Maximum allowable translational speed limit in meters per second ($m/s$) (default: $4.0$ m/s).
 * @param xAdrc Linear ADRC controller for field X-axis (overrides [xController] if provided).
 * @param yAdrc Linear ADRC controller for field Y-axis (overrides [yController] if provided).
 * @param thetaAdrc Linear ADRC controller for heading (overrides [thetaController] if provided).
 *
 * @see PIDController
 * @see LinearADRC
 */
class HolonomicDriveController(
    private val xController: PIDController,
    private val yController: PIDController,
    private val thetaController: PIDController,
    private val telemetry: com.areslib.telemetry.ITelemetry? = null,
    private val maxOutputMps: Double = 4.0,
    private val xAdrc: LinearADRC? = null,
    private val yAdrc: LinearADRC? = null,
    private val thetaAdrc: LinearADRC? = null
) {
    private val maxOutputMpsSq: Double = maxOutputMps * maxOutputMps

    init {
        thetaController.enableContinuousInput(-Math.PI, Math.PI)
        thetaAdrc?.enableContinuousInput(-Math.PI, Math.PI)
    }

    /**
     * Calculates commanded robot-frame chassis velocities given current estimated robot pose and trajectory target pose.
     *
     * @param currentPose Current estimated robot pose on the field ($m, rad$).
     * @param targetPose Target robot pose on the field ($m, rad$).
     * @param targetVelocityMps Target feedforward velocity magnitude along path tangent ($m/s$).
     * @param targetHeading Target field-relative heading orientation ($rad$, CCW positive).
     * @param dtSeconds Timestep duration since last loop iteration in seconds ($s$).
     * @param pathTangentRadians Spline path derivative tangent angle ($rad$). Defaults to NaN (falls back to error vector angle).
     * @param curvature Trajectory path curvature $\kappa = 1/R$ ($m^{-1}$).
     * @param maxCentripetalAccel Maximum allowable centripetal acceleration before speed throttling ($m/s^2$).
     * @param progressPercentage Trajectory progress completed percentage ($0.0 \dots 100.0$).
     * @return Commanded robot-centric [ChassisSpeeds] ready for kinematics inverse calculation ($m/s, rad/s$).
     */
    fun calculate(
        currentPose: Pose2d,
        targetPose: Pose2d,
        targetVelocityMps: Double,
        targetHeading: Rotation2d,
        dtSeconds: Double,
        pathTangentRadians: Double = Double.NaN,
        curvature: Double = 0.0,
        maxCentripetalAccel: Double = 2.5,
        progressPercentage: Double = 0.0
    ): ChassisSpeeds {
        return calculateDirect(
            currentPose.x, currentPose.y, currentPose.heading.radians,
            targetPose.x, targetPose.y, targetHeading.radians,
            targetVelocityMps, dtSeconds, pathTangentRadians, curvature, maxCentripetalAccel, progressPercentage
        )
    }

    /**
     * Zero-GC direct primitive calculation overload for holonomic trajectory tracking control.
     *
     * @param currentX Current robot X position in meters ($m$).
     * @param currentY Current robot Y position in meters ($m$).
     * @param currentHeadingRad Current robot heading in radians ($rad$, CCW positive).
     * @param targetX Target robot X position in meters ($m$).
     * @param targetY Target robot Y position in meters ($m$).
     * @param targetHeadingRad Target robot heading in radians ($rad$, CCW positive).
     * @param targetVelocityMps Target feedforward velocity magnitude along path tangent ($m/s$).
     * @param dtSeconds Timestep duration in seconds ($s$).
     * @param pathTangentRadians Spline path derivative tangent angle ($rad$).
     * @param curvature Trajectory path curvature $\kappa$ ($m^{-1}$).
     * @param maxCentripetalAccel Maximum centripetal acceleration limit ($m/s^2$).
     * @param progressPercentage Trajectory progress completed percentage ($0.0 \dots 100.0$).
     * @return Commanded robot-centric [ChassisSpeeds] ($m/s, rad/s$).
     */
    fun calculateDirect(
        currentX: Double,
        currentY: Double,
        currentHeadingRad: Double,
        targetX: Double,
        targetY: Double,
        targetHeadingRad: Double,
        targetVelocityMps: Double,
        dtSeconds: Double,
        pathTangentRadians: Double = Double.NaN,
        curvature: Double = 0.0,
        maxCentripetalAccel: Double = 2.5,
        progressPercentage: Double = 0.0
    ): ChassisSpeeds {
        if (!currentX.isFinite() || !currentY.isFinite() || !currentHeadingRad.isFinite() ||
            !targetX.isFinite() || !targetY.isFinite() || !targetHeadingRad.isFinite() ||
            !targetVelocityMps.isFinite() || !dtSeconds.isFinite() || dtSeconds <= 0.0 ||
            pathTangentRadians.isInfinite() || !curvature.isFinite() ||
            !maxCentripetalAccel.isFinite() || maxCentripetalAccel <= 0.0 ||
            !maxOutputMps.isFinite() || maxOutputMps <= 0.0 || !progressPercentage.isFinite()
        ) {
            return ChassisSpeeds()
        }

        val xError = targetX - currentX
        val yError = targetY - currentY

        val pathTangent = if (!pathTangentRadians.isNaN()) {
            pathTangentRadians
        } else {
            val distanceToTarget = kotlin.math.hypot(xError, yError)
            if (distanceToTarget > 0.01) {
                kotlin.math.atan2(yError, xError)
            } else {
                targetHeadingRad
            }
        }

        val cosTangent = cos(pathTangent)
        val sinTangent = sin(pathTangent)

        val lateralError = xError * sinTangent - yError * cosTangent

        var angularError = targetHeadingRad - currentHeadingRad
        angularError = com.areslib.math.wrapAngle(angularError)

        telemetry?.let { tel ->
            tel.putNumber("PathError/LateralMeters", lateralError)
            tel.putNumber("PathError/AngularDegrees", Math.toDegrees(angularError))
            tel.putNumber("PathError/XErrorMeters", xError)
            tel.putNumber("PathError/YErrorMeters", yError)
            tel.putNumber("PathError/ProgressPercentage", progressPercentage)
        }

        val xFeedback = xAdrc?.calculate(targetX, currentX, dtSeconds)
            ?: xController.calculate(currentX, targetX, dtSeconds)

        val yFeedback = yAdrc?.calculate(targetY, currentY, dtSeconds)
            ?: yController.calculate(currentY, targetY, dtSeconds)

        val thetaFeedback = thetaAdrc?.calculate(targetHeadingRad, currentHeadingRad, dtSeconds)
            ?: thetaController.calculate(currentHeadingRad, targetHeadingRad, dtSeconds)

        if (!xFeedback.isFinite() || !yFeedback.isFinite() || !thetaFeedback.isFinite()) {
            return ChassisSpeeds()
        }

        val limitedVelocity = if (kotlin.math.abs(curvature) > 1e-4) {
            val maxVel = kotlin.math.sqrt(maxCentripetalAccel / kotlin.math.abs(curvature))
            kotlin.math.min(targetVelocityMps, maxVel)
        } else {
            targetVelocityMps
        }

        val xFF = limitedVelocity * cosTangent
        val yFF = limitedVelocity * sinTangent

        val fieldRelativeX = xFF + xFeedback
        val fieldRelativeY = yFF + yFeedback

        val cosHeading = cos(currentHeadingRad)
        val sinHeading = sin(currentHeadingRad)

        var vxRobot = fieldRelativeX * cosHeading + fieldRelativeY * sinHeading
        var vyRobot = -fieldRelativeX * sinHeading + fieldRelativeY * cosHeading

        val magSq = vxRobot * vxRobot + vyRobot * vyRobot
        if (magSq > maxOutputMpsSq) {
            val scale = maxOutputMps / kotlin.math.sqrt(magSq)
            vxRobot *= scale
            vyRobot *= scale
        }

        return ChassisSpeeds.discretize(vxRobot, vyRobot, thetaFeedback, dtSeconds)
    }
}
