package com.areslib.pathing

import com.areslib.control.drivetrain.HolonomicDriveController
import com.areslib.control.feedback.PIDController
import com.areslib.subsystem.DrivetrainSubsystem

/**
 * Closed-Loop Holonomic Path Following Trajectory Controller.
 *
 * Tracks parameterized trajectory paths ([Path]) by combining trajectory feedforward velocities $\mathbf{v}_{\text{ff}}$
 * with closed-loop PID error feedback corrections along orthogonal Cartesian axes $(e_x, e_y)$ and heading orientation $e_\theta$.
 *
 * ### Mathematical Formulation:
 * 1. **Field-Centric Feedback Velocity Correction**:
 *    $$\mathbf{v}_{\text{fb}} = \begin{bmatrix} K_{p,x} (x_{\text{target}} - x_{\text{est}}) + K_{d,x} (\dot{x}_{\text{target}} - \dot{x}_{\text{est}}) \\ K_{p,y} (y_{\text{target}} - y_{\text{est}}) + K_{d,y} (\dot{y}_{\text{target}} - \dot{y}_{\text{est}}) \end{bmatrix}$$
 * 2. **Heading Error Feedback Correction**:
 *    $$e_\theta = \text{wrapAngle}(\theta_{\text{target}} - \theta_{\text{est}})$$
 *    $$\omega_{\text{fb}} = K_{p,\theta} e_\theta + K_{d,\theta} \dot{e}_\theta$$
 * 3. **Combined Drivetrain Command Output**:
 *    $$\mathbf{v}_{\text{cmd}} = \mathbf{v}_{\text{ff}} + \mathbf{v}_{\text{fb}}, \quad \omega_{\text{cmd}} = \omega_{\text{ff}} + \omega_{\text{fb}}$$
 *
 * ### Physical Units & Coordinate Conventions:
 * - Trajectory & EKF Positions $(x, y)$: Meters ($m$)
 * - Trajectory & EKF Headings $(\theta)$: Radians ($rad$), **CCW-positive** ($0 = +X$, $\frac{\pi}{2} = +Y$)
 * - Linear Velocity Commands $(v_x, v_y)$: Meters per second ($m/s$)
 * - Angular Velocity Command ($\omega$): Radians per second ($rad/s$), CCW-positive
 * - Loop Period ($\Delta t$): Seconds ($s$)
 *
 * @param drivetrain Target holonomic drivetrain subsystem facade [DrivetrainSubsystem].
 * @param xController Translational PID controller along X-axis.
 * @param yController Translational PID controller along Y-axis.
 * @param thetaController Rotational PID controller with continuous $[-\pi, \pi)$ wrapping enabled.
 *
 * @see HolonomicDriveController
 * @see Path
 */
class HolonomicPathFollower @kotlin.jvm.JvmOverloads constructor(
    val drivetrain: DrivetrainSubsystem,
    val xController: PIDController = PIDController(p = 2.0, i = 0.0, d = 0.02),
    val yController: PIDController = PIDController(p = 2.0, i = 0.0, d = 0.02),
    val thetaController: PIDController = PIDController(p = 2.5, i = 0.0, d = 0.05).apply {
        enableContinuousInput(-Math.PI, Math.PI)
    }
) {

    /** Holonomic controller calculating corrective translational and angular velocities */
    val driveController = HolonomicDriveController(xController, yController, thetaController)

    private var currentPath: Path? = null
    private val triggeredEvents = mutableSetOf<String>()

    /** Callback invoked whenever a PathEvent is crossed */
    var onEventTriggered: ((String) -> Unit)? = null

    /**
     * Initializes tracking for a new path, resetting any previously triggered events.
     */
    fun startPath(path: Path) {
        currentPath = path
        triggeredEvents.clear()
    }

    /**
     * Updates the drivetrain commands to track the target state of a spline path.
     * Calculates the required field-relative steering and feeds it to the drivetrain subsystem.
     *
     * @param targetState The desired target position, heading, and velocity sample from the path.
     * @param dtSeconds Elapsed time since the last controller update in seconds.
     */
    fun update(targetState: PathPoint, dtSeconds: Double) {
        try {
            val currentPose = drivetrain.getEstimatedPose()
            
            val path = currentPath
            if (path != null) {
                val currentDist = targetState.distanceMeters
                val eventsSize = path.events.size
                for (i in 0 until eventsSize) {
                    val event = path.events[i]
                    if (currentDist >= event.triggerDistanceMeters && !triggeredEvents.contains(event.eventName)) {
                        triggeredEvents.add(event.eventName)
                        onEventTriggered?.invoke(event.eventName)
                    }
                }
            }
            
            val chassisSpeeds = driveController.calculate(
                currentPose = currentPose,
                targetPose = targetState.pose,
                targetVelocityMps = targetState.velocityMps,
                targetHeading = targetState.pose.heading,
                dtSeconds = dtSeconds,
                pathTangentRadians = targetState.tangentRadians,
                curvature = targetState.curvature
            )

            drivetrain.setChassisSpeeds(
                vx = chassisSpeeds.vxMetersPerSecond,
                vy = chassisSpeeds.vyMetersPerSecond,
                omega = chassisSpeeds.omegaRadiansPerSecond
            )
        } catch (e: Throwable) {
            System.err.println("HolonomicPathFollower FATAL ERROR: ${e.message}")
            stop()
        }
    }

    /**
     * Halts all chassis movement.
     */
    fun stop() {
        drivetrain.setChassisSpeeds(0.0, 0.0, 0.0)
    }
}
