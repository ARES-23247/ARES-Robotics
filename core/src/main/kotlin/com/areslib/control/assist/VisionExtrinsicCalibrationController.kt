package com.areslib.control.assist

import com.areslib.action.CalibrationFrameLogged
import com.areslib.action.StartCalibrationSweep
import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.state.VisionMeasurement
import com.areslib.Store
import com.areslib.telemetry.ARESNetworkStatePublisher
import com.areslib.control.drivetrain.HolonomicDriveController

/**
 * Controller for Vision Camera Extrinsic Calibration Sweeps.
 *
 * Executes a controlled $360^\circ$ ($2\pi$ rad) rotational sweep while pinning robot translation ($v_x = 0, v_y = 0$).
 * Captures AprilTag observations across multiple view angles to estimate precise camera-to-robot body frame transform matrices.
 *
 * ### Sweep Kinematics & Transformation:
 * Heading target trajectory over time:
 * $$\theta_{target}(t) = \theta_{start} + \omega_{sweep} \cdot t$$
 * Transform logging payload vector:
 * $$\mathbf{T}_{cam \to tag} = \begin{bmatrix} t_x & t_y & t_z & roll & pitch & yaw \end{bmatrix}^T$$
 *
 * ### Physical Units & Coordinates:
 * - Rotational Sweep Speed (`sweepSpeedRadPerSec`): Radians per second ($rad/s$), CCW positive
 * - Translation Errors ($x, y$): Locked to 0.0 meters ($m$)
 * - Gyro Heading: Radians ($rad$), CCW positive
 * - Camera Transform Translations ($t_x, t_y, t_z$): Meters ($m$) in camera frame
 * - Timestep ($\Delta t$): Seconds ($s$)
 *
 * @param store Global Redux store instance for dispatching calibration actions.
 * @param holonomicDriveController Drivetrain holonomic controller for closed-loop heading tracking.
 * @param publisher Network state publisher for live NT4 calibration telemetry streaming.
 * @param sweepSpeedRadPerSec Constant rotational velocity during calibration sweep in rad/s (default: $0.5$ rad/s).
 * @see HolonomicDriveController
 */
class VisionExtrinsicCalibrationController(
    private val store: Store,
    private val holonomicDriveController: HolonomicDriveController,
    private val publisher: ARESNetworkStatePublisher,
    private val sweepSpeedRadPerSec: Double = 0.5
) {
    /** `true` if a calibration sweep is currently in progress; `false` otherwise. */
    var isActive: Boolean = false
        private set

    /** Index of the camera currently under calibration. */
    var cameraIndex: Int = 0
        private set

    private var accumulatedRotation: Double = 0.0
    private var currentTargetHeading: Double = 0.0

    /**
     * Starts a new extrinsic calibration sweep sequence for the specified camera.
     *
     * @param cameraIndex Zero-indexed identifier of the target camera sensor.
     * @param currentHeading Initial robot heading orientation in radians ($rad$).
     */
    fun start(cameraIndex: Int, currentHeading: Double) {
        isActive = true
        this.cameraIndex = cameraIndex
        this.accumulatedRotation = 0.0
        this.currentTargetHeading = currentHeading
        store.dispatch(StartCalibrationSweep(currentHeading, cameraIndex))
        publishState(true, currentHeading, -1, cameraIndex, DoubleArray(6), unknownFieldPosition())
    }

    /**
     * Updates calibration sweep trajectory and processes detected vision target measurements.
     *
     * Commanded rotational velocity rotates the robot while translation is locked. Dispatches
     * calibration frames when AprilTag targets are observed. Automatically stops after completing a full $2\pi$ rotation.
     *
     * @param currentPose Current estimated robot pose on the field ($m, rad$).
     * @param measurements List of active vision measurements received in the current frame.
     * @param dtSeconds Elapsed cycle loop timestep in seconds ($s$).
     * @return Commanded robot-frame [ChassisSpeeds] (m/s, rad/s).
     */
    fun update(
        currentPose: Pose2d,
        measurements: List<VisionMeasurement>,
        dtSeconds: Double
    ): ChassisSpeeds {
        if (!isActive) {
            publishState(false, currentPose.heading.radians, -1, cameraIndex, DoubleArray(6), unknownFieldPosition())
            return ChassisSpeeds(0.0, 0.0, 0.0)
        }

        // Increment heading target
        val headingDelta = sweepSpeedRadPerSec * dtSeconds
        currentTargetHeading += headingDelta
        accumulatedRotation += kotlin.math.abs(headingDelta)

        if (accumulatedRotation >= 2.0 * Math.PI) {
            isActive = false
            publishState(false, currentPose.heading.radians, -1, cameraIndex, DoubleArray(6), unknownFieldPosition())
            return ChassisSpeeds(0.0, 0.0, 0.0)
        }

        // Call HolonomicDriveController with translation locked to currentPose (so translation error is 0)
        val speeds = holonomicDriveController.calculate(
            currentPose = currentPose,
            targetPose = currentPose,
            targetVelocityMps = 0.0,
            targetHeading = Rotation2d(currentTargetHeading),
            dtSeconds = dtSeconds
        )

        // Process any detected vision targets for calibration
        for (m in measurements) {
            if (m.tagId != -1) {
                val t = m.robotPoseTargetSpace.translation
                val rotation = m.robotPoseTargetSpace.rotation
                val transformArray = doubleArrayOf(t.x, t.y, t.z, rotation.x, rotation.y, rotation.z)
                val fieldTag = com.areslib.state.RobotFieldManager.activeConfig.apriltags.firstOrNull { it.id == m.tagId }
                val tagFieldPosition = if (fieldTag != null) {
                    doubleArrayOf(fieldTag.x, fieldTag.y, fieldTag.z)
                } else {
                    unknownFieldPosition()
                }

                // Dispatch calibration frame logged
                store.dispatch(
                    CalibrationFrameLogged(
                        gyroHeading = currentPose.heading.radians,
                        tagId = m.tagId,
                        cameraIndex = cameraIndex,
                        cameraToTagTransform = transformArray
                    )
                )

                // Publish to NT4 via our network state publisher
                publishState(
                    isActive = true,
                    gyroHeading = currentPose.heading.radians,
                    tagIndex = m.tagId,
                    cameraIndex = cameraIndex,
                    cameraToTag = transformArray,
                    tagFieldPosition = tagFieldPosition
                )
            }
        }

        return speeds
    }

    private fun publishState(
        isActive: Boolean,
        gyroHeading: Double,
        tagIndex: Int,
        cameraIndex: Int,
        cameraToTag: DoubleArray,
        tagFieldPosition: DoubleArray
    ) {
        publisher.publishCalibration(
            isActive = isActive,
            gyroHeading = gyroHeading,
            tagIndex = tagIndex,
            cameraIndex = cameraIndex,
            cameraToTag = cameraToTag,
            tagFieldPosition = tagFieldPosition
        )
    }

    private fun unknownFieldPosition() = doubleArrayOf(Double.NaN, Double.NaN, Double.NaN)
}
