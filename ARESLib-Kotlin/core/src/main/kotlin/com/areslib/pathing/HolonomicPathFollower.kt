package com.areslib.pathing

import com.areslib.control.drivetrain.HolonomicDriveController
import com.areslib.control.feedback.PIDController
import com.areslib.subsystem.DrivetrainSubsystem

/**
 * Single-loop holonomic follower combining tangent velocity feedforward with X/Y/heading PID.
 * Field poses use meters and CCW-positive radians; output is robot-relative m/s and rad/s.
 * PID derivative acts on measurement, not setpoint error. Heading control is feedback only:
 * [PathPoint] does not provide angular feedforward. Translation is limited by the drive controller.
 *
 * Calls, mutable inputs, and controllers belong to one robot loop; this class is not thread-safe.
 * Direct sample following does not require [startPath]. That optional method owns callback markers;
 * [com.areslib.sequencer.FollowPathTask] separately owns its named-command tasks and progress.
 * Do not register both owners for the same physical side effects. Callbacks must be bounded and
 * nonblocking. An update costs O(1 + crossed markers), excluding controller/IO/callback work.
 * Marker setup allocates; update reuses speed storage. The drivetrain's pose getter and output
 * implementation can still allocate, and this facade supplies no feedback freshness or enable latch.
 * Those remain responsibilities of the robot's Redux/hardware pipeline.
 *
 * @param drivetrain Drivetrain facade accepting robot-relative chassis velocities.
 * @param xController PID feedback along field X, reset when starting a path or stopping.
 * @param yController PID feedback along field Y, reset when starting a path or stopping.
 * @param thetaController Heading PID; continuous input is enabled over [-pi, pi).
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

    private var events = emptyArray<PathEvent>()
    private var nextEvent = 0
    private var revision = 0L
    private var updating = false
    private val chassisSpeeds = com.areslib.math.geometry.ChassisSpeeds()

    /** Callback invoked whenever a PathEvent is crossed */
    var onEventTriggered: ((String) -> Unit)? = null

    /**
     * Snapshots markers in ascending distance order (stable for ties), and resets PID history.
     * Each marker occurrence may fire once, including repeated command names. Setup may allocate;
     * subsequent changes to the caller's event list have no effect. Distances must be finite and
     * nonnegative. Rejected setup attempts neutral output and propagates the original failure.
     * This configures markers only; callers continue to supply trajectory samples to [update].
     */
    fun startPath(path: Path) {
        try {
            val snapshot = path.events.toTypedArray()
            for (event in snapshot) {
                require(event.triggerDistanceMeters.isFinite() && event.triggerDistanceMeters >= 0.0) {
                    "Path event distance must be finite and nonnegative"
                }
            }
            snapshot.sortBy { it.triggerDistanceMeters }
            events = snapshot
            nextEvent = 0
            revision++
            resetControllers()
        } catch (failure: Throwable) {
            stopAndRethrow(failure)
        }
    }

    /**
     * Updates the drivetrain commands to track the target state of a spline path.
     * Calculates robot-relative chassis velocities from field-relative poses. Invalid pose, target,
     * distance or time inputs stop/reset without consuming markers; NaN tangent retains the
     * controller's automatic tangent fallback. A callback sees a precomputed command: target
     * mutation takes effect on the next update. Calling [stop] or [startPath] from a callback
     * cancels the remaining callbacks and command of this update. Recursive updates are rejected.
     * Callback/output failures attempt neutral output and propagate, without replaying the marker.
     *
     * @param targetState The desired target position, heading, and velocity sample from the path.
     * @param dtSeconds Elapsed time since the last controller update in seconds.
     */
    fun update(targetState: PathPoint, dtSeconds: Double) {
        check(!updating) { "HolonomicPathFollower.update must not be called recursively" }
        updating = true
        var neutralAttempted = false
        try {
            val updateRevision = revision
            val currentPose = drivetrain.getEstimatedPose()
            val targetPose = targetState.pose
            val currentDist = targetState.distanceMeters
            if (!currentPose.x.isFinite() || !currentPose.y.isFinite() ||
                !currentPose.heading.rawRadians.isFinite() ||
                !targetPose.x.isFinite() || !targetPose.y.isFinite() ||
                !targetPose.heading.rawRadians.isFinite() ||
                !(targetPose.x - currentPose.x).isFinite() || !(targetPose.y - currentPose.y).isFinite() ||
                !targetState.velocityMps.isFinite() || !targetState.curvature.isFinite() ||
                targetState.tangentRadians.isInfinite() || !currentDist.isFinite() || currentDist < 0.0 ||
                !dtSeconds.isFinite() || dtSeconds <= 0.0
            ) {
                neutralAttempted = true
                stop()
                return
            }

            driveController.calculateInto(
                out = chassisSpeeds,
                currentX = currentPose.x,
                currentY = currentPose.y,
                currentHeadingRad = currentPose.heading.radians,
                targetX = targetPose.x,
                targetY = targetPose.y,
                targetVelocityMps = targetState.velocityMps,
                targetHeadingRad = targetPose.heading.radians,
                dtSeconds = dtSeconds,
                pathTangentRadians = targetState.tangentRadians,
                curvature = targetState.curvature
            )

            // No list scans or per-marker bookkeeping allocations in the loop. Consume before
            // invoking external code, and abandon this update if its owner changes the lifecycle.
            while (nextEvent < events.size && currentDist >= events[nextEvent].triggerDistanceMeters) {
                val event = events[nextEvent++]
                onEventTriggered?.invoke(event.eventName)
                if (revision != updateRevision) return
            }

            drivetrain.setChassisSpeeds(
                vx = chassisSpeeds.vxMetersPerSecond,
                vy = chassisSpeeds.vyMetersPerSecond,
                omega = chassisSpeeds.omegaRadiansPerSecond
            )
        } catch (failure: Throwable) {
            if (neutralAttempted) throw failure
            stopAndRethrow(failure)
        } finally {
            updating = false
        }
    }

    /**
     * Requests zero chassis velocity, clears PID history, and cancels an in-flight update.
     * Marker progress is retained for a temporary pause; a later explicit [update] may resume.
     * This is not an enable/fault latch or a guarantee that physical hardware has stopped.
     */
    fun stop() {
        revision++
        resetControllers()
        drivetrain.setChassisSpeeds(0.0, 0.0, 0.0)
    }

    private fun resetControllers() {
        xController.reset()
        yController.reset()
        thetaController.reset()
    }

    private fun stopAndRethrow(failure: Throwable): Nothing {
        try {
            stop()
        } catch (stopFailure: Throwable) {
            if (stopFailure !== failure) failure.addSuppressed(stopFailure)
        }
        throw failure
    }
}
