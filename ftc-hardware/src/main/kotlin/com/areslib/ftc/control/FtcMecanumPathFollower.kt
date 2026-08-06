package com.areslib.ftc.control

import com.areslib.ftc.FtcMecanumRobot
import com.areslib.control.drivetrain.HolonomicDriveController
import com.areslib.control.feedback.PIDController
import com.areslib.pathing.Path
import com.areslib.pathing.PathPoint

/**
 * Autonomous path follower executing holonomic feedback control for FTC Mecanum Robots.
 *
 * Encapsulates a [HolonomicDriveController] with independent $X, Y, \theta$ PID feedback loops to steer
 * the drivetrain along continuous trajectory splines derived from EKF odometry.
 *
 * ### Mathematical Formulations:
 * Given current pose $\mathbf{p} = [x, y, \theta]^T$ and target trajectory point $[x_r, y_r, \theta_r]^T$:
 * $$v_x = v_{r,x} + K_{p,x} (x_r - x) + K_{d,x} (\dot{x}_r - \dot{x})$$
 * $$v_y = v_{r,y} + K_{p,y} (y_r - y) + K_{d,y} (\dot{y}_r - \dot{y})$$
 * $$\omega = \omega_r + K_{p,\theta} \text{wrap}(\theta_r - \theta) + K_{d,\theta} (\omega_r - \omega)$$
 * where continuous input wrapping is enforced on heading error over $[-\pi, \pi]$ radians ($rad$).
 *
 * ### Physical Units & Coordinate Conventions:
 * - Position: Meters ($m$)
 * - Heading: Radians ($rad$), **CCW-positive** standard ($0 = +X$, $\pi/2 = +Y$)
 * - Velocities: Linear $m/s$, Angular $rad/s$
 * - Time: Seconds ($s$)
 *
 * ### Zero-GC Guarantee:
 * Pre-allocates internal controller structures and calculates normalized chassis speeds without heap allocations inside 50Hz update loops.
 *
 * @param robot Reference to active [FtcMecanumRobot] facade.
 * @param xController PID controller governing X-axis translational feedback ($m$).
 * @param yController PID controller governing Y-axis translational feedback ($m$).
 * @param thetaController PID controller governing rotational heading feedback ($rad$).
 *
 * @see HolonomicDriveController
 * @see FtcMecanumRobot
 */
class FtcMecanumPathFollower @kotlin.jvm.JvmOverloads constructor(
    val robot: FtcMecanumRobot,
    val xController: PIDController = PIDController(p = 2.0, i = 0.0, d = 0.02),
    val yController: PIDController = PIDController(p = 2.0, i = 0.0, d = 0.02),
    val thetaController: PIDController = PIDController(p = 2.5, i = 0.0, d = 0.05).apply {
        enableContinuousInput(-Math.PI, Math.PI)
    }
) {
    /** Underlying holonomic controller fusing feedback error and target path tangents. */
    val driveController = HolonomicDriveController(xController, yController, thetaController)

    /**
     * Updates drivetrain velocity commands to track a target trajectory waypoint sample.
     *
     * Computes field-relative chassis velocity commands, normalizes them against the robot's physical
     * maximum wheel speed, and dispatches drive intents into the robot facade.
     *
     * @param targetState Target position, heading, tangent angle, and linear velocity sample ([PathPoint]).
     * @param dtSeconds Loop time step interval in seconds ($s$).
     */
    fun update(targetState: PathPoint, dtSeconds: Double) {

        val currentPose = robot.drive.odometryPose
        
        // Calculate the required chassis speed vectors
        val chassisSpeeds = driveController.calculate(
            currentPose = currentPose,
            targetPose = targetState.pose,
            targetVelocityMps = targetState.velocityMps,
            targetHeading = targetState.pose.heading,
            dtSeconds = dtSeconds,
            pathTangentRadians = targetState.tangentRadians
        )

        // Normalize velocities against the robot's physical maximum speed capability
        val maxSpeed = robot.mecanumIO.maxWheelSpeedMetersPerSecond
        robot.drive.joystickDrive(
            x = chassisSpeeds.vxMetersPerSecond / maxSpeed,
            y = chassisSpeeds.vyMetersPerSecond / maxSpeed,
            rot = chassisSpeeds.omegaRadiansPerSecond / maxSpeed,
            isFieldCentric = false
        )
    }

    /**
     * Immediately halts all drivetrain motion by commanding zero velocity vectors.
     */
    fun stop() {
        robot.drive.joystickDrive(0.0, 0.0, 0.0, false)
    }
}

