package com.areslib.control.drivetrain

import com.areslib.control.feedback.PIDController
import com.areslib.hardware.drive.OdometryIO
import com.areslib.hardware.drive.OdometryInputs
import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.math.geometry.Pose2d

/**
 * Closed-Loop Active Anti-Drift Controller for High-Frequency Odometry Calibration.
 *
 * During active odometry calibration spins (e.g. GoBilda Pinpoint / IMU offset calibration), the robot center-of-rotation
 * linear velocity should theoretically equal zero ($v_x = 0, v_y = 0$). Any non-zero measured linear velocity represents
 * mechanical scrub, wheel slippage, or off-center odometry sensor mounting.
 *
 * This controller feeds linear velocity errors into high-frequency PID loops to compute active counter-strafe velocity offsets.
 *
 * ### Control Mathematics:
 * $$v_{x,corr} = \text{PID}_x(v_{x,measured}, 0, \Delta t)$$
 * $$v_{y,corr} = \text{PID}_y(v_{y,measured}, 0, \Delta t)$$
 * $$\mathbf{v}_{corrected} = \begin{bmatrix} v_{x,cmd} + v_{x,corr} \\ v_{y,cmd} + v_{y,corr} \\ \omega_{cmd} \end{bmatrix}$$
 *
 * ### Physical Units & Coordinates:
 * - Linear Velocity ($v_x, v_y$): Meters per second ($m/s$), +X forward, +Y left
 * - Angular Velocity ($\omega$): Radians per second ($rad/s$), CCW positive
 * - Calibration Loop Timestep: $\Delta t \approx 0.00067$ seconds (~1500Hz odometry loop)
 *
 * @param baseOdometry Underlying [OdometryIO] hardware interface instance.
 * @param xPid PID controller for X-axis (forward/backward) velocity drift correction.
 * @param yPid PID controller for Y-axis (strafe) velocity drift correction.
 * @see OdometryIO
 * @see PIDController
 */
class AntiDriftController(
    private val baseOdometry: OdometryIO,
    val xPid: PIDController = PIDController(1.5, 0.0, 0.1),
    val yPid: PIDController = PIDController(1.5, 0.0, 0.1)
) : OdometryIO {

    /**
     * Enables active anti-drift correction when set to `true`. Resets PID loop accumulators upon activation.
     */
    var isCalibrationActive: Boolean = false
        set(value) {
            field = value
            if (value) {
                xPid.reset()
                yPid.reset()
            }
        }

    /** Active counter-strafe correction velocity along X axis in meters per second ($m/s$). */
    var correctionVx: Double = 0.0
        private set

    /** Active counter-strafe correction velocity along Y axis in meters per second ($m/s$). */
    var correctionVy: Double = 0.0
        private set

    /**
     * Initializes the underlying odometry sensor hardware and clears active PID accumulators.
     *
     * @param startPose Initial starting pose configuration on the field ($m, rad$).
     */
    override fun initialize(startPose: Pose2d) {
        baseOdometry.initialize(startPose)
        xPid.reset()
        yPid.reset()
        correctionVx = 0.0
        correctionVy = 0.0
    }

    /**
     * Updates odometry inputs and calculates closed-loop counter-strafe corrections if calibration is active.
     *
     * @param inputs [OdometryInputs] container populated with latest raw odometry measurements.
     */
    override fun updateInputs(inputs: OdometryInputs) {
        baseOdometry.updateInputs(inputs)

        if (isCalibrationActive) {
            // Target linear velocity is 0.0 (pin the center of rotation)
            // Feed the negative of the velocity deviation into the PID loops to calculate counter-strafe
            correctionVx = xPid.calculate(inputs.velX, 0.0, 0.00067)
            correctionVy = yPid.calculate(inputs.velY, 0.0, 0.00067)
        } else {
            correctionVx = 0.0
            correctionVy = 0.0
        }
    }

    /**
     * Applies the calculated anti-drift counter-strafe velocity offsets to commanded chassis speeds.
     *
     * @param original Uncorrected robot-centric [ChassisSpeeds] ($m/s, rad/s$).
     * @return Corrected [ChassisSpeeds] with counter-strafe velocity offsets applied.
     */
    fun applyCorrection(original: ChassisSpeeds): ChassisSpeeds {
        if (!isCalibrationActive) return original
        return ChassisSpeeds(
            vxMetersPerSecond = original.vxMetersPerSecond + correctionVx,
            vyMetersPerSecond = original.vyMetersPerSecond + correctionVy,
            omegaRadiansPerSecond = original.omegaRadiansPerSecond
        )
    }
}
