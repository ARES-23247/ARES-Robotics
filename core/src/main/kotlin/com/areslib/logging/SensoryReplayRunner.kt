package com.areslib.logging

import com.areslib.action.RobotAction
import com.areslib.Store
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Vector3
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * Class implementation for Replay Step Result.
 *
 * Real-time telemetry streaming, diagnostic logging, and NetworkTables 4 communication handler.
 */
data class ReplayStepResult(
    val timestampMs: Long,
    val realPose: Pose2d,
    val ghostPose: Pose2d,
    val cameraPoses: List<Pose3d> = emptyList()
)

/**
 * Class implementation for Replay Summary.
 *
 * Real-time telemetry streaming, diagnostic logging, and NetworkTables 4 communication handler.
 */
data class ReplaySummary(
    val steps: List<ReplayStepResult>,
    val finalRealPose: Pose2d,
    val finalGhostPose: Pose2d
)

/**
 * Offline sensory replay execution runner.
 * Satisfies REP-02 by feeding raw sensory telemetry frames through the root reducer,
 * generating twin traces (Real vs. Ghost) in parallel with parameterized overrides.
 */
object SensoryReplayRunner {
    private val gson = Gson()

    /**
     * Replays a raw sensory JSONL log file through two parallel state instances.
     * @param logFile The raw sensory input log file to process
     * @param ghostVisionStdDevs Custom standard deviation scaling (x, y, heading) to apply to the Ghost EKF estimator
     * @return ReplaySummary containing the complete step-by-step trace of both poses
     */
    fun replaySensoryLog(
        logFile: File,
        ghostVisionStdDevs: Vector3? = null
    ): ReplaySummary {
        if (!logFile.exists()) {
            return ReplaySummary(emptyList(), Pose2d(), Pose2d())
        }
        val lines = logFile.readLines()
        return replaySensoryLines(lines, ghostVisionStdDevs)
    }

    /**
     * Replays a list of raw sensory JSONL strings through two parallel state instances.
     * @param lines List of raw sensory input JSON strings to process
     * @param ghostVisionStdDevs Custom standard deviation scaling (x, y, heading) to apply to the Ghost EKF estimator
     * @return ReplaySummary containing the complete step-by-step trace of both poses
     */
    fun replaySensoryLines(
        lines: List<String>,
        ghostVisionStdDevs: Vector3? = null
    ): ReplaySummary {
        val steps = mutableListOf<ReplayStepResult>()
        
        val realStore = Store()
        val ghostStore = Store()

        var prevFrame: RobotInputsFrame? = null

        for (line in lines) {
            if (line.trim().isNotEmpty()) {
                try {
                    val frame = gson.fromJson(line, RobotInputsFrame::class.java)
                    
                    // Map raw sensors to DriveHardwareUpdate action
                    val prev = prevFrame
                    val deltaX = if (prev == null) 0.0 else frame.odometryInputs.posX - prev.odometryInputs.posX
                    val deltaY = if (prev == null) 0.0 else frame.odometryInputs.posY - prev.odometryInputs.posY
                    val deltaHeading = if (prev == null) 0.0 else frame.odometryInputs.heading - prev.odometryInputs.heading

                    val driveAction = RobotAction.DriveHardwareUpdate(
                        xVelocity = frame.odometryInputs.velX,
                        yVelocity = frame.odometryInputs.velY,
                        angularVelocity = frame.odometryInputs.headingVelocity,
                        deltaX = deltaX,
                        deltaY = deltaY,
                        deltaHeading = deltaHeading,
                        timestampMs = frame.timestampMs,
                        pitchDegrees = Math.toDegrees(frame.imuInputs.pitchRadians),
                        rollDegrees = Math.toDegrees(frame.imuInputs.rollRadians)
                    )

                    // 1. Dispatch Drive update to both states
                    realStore.dispatch(driveAction)
                    ghostStore.dispatch(driveAction)

                    // 2. Map and dispatch Vision measurements if present
                    if (frame.visionInputs.measurements.isNotEmpty()) {
                        val realVisionAction = RobotAction.VisionMeasurementsReceived(
                            measurements = frame.visionInputs.measurements,
                            timestampMs = frame.timestampMs,
                            customVisionStdDevs = null // Standard default trust
                        )

                        val ghostVisionAction = RobotAction.VisionMeasurementsReceived(
                            measurements = frame.visionInputs.measurements,
                            timestampMs = frame.timestampMs,
                            customVisionStdDevs = ghostVisionStdDevs // Customized 'what-if' trust
                        )

                        realStore.dispatch(realVisionAction)
                        ghostStore.dispatch(ghostVisionAction)
                    }

                    // Save execution step result
                    steps.add(
                        ReplayStepResult(
                            timestampMs = frame.timestampMs,
                            realPose = realStore.state.drive.poseEstimator.estimatedPose,
                            ghostPose = ghostStore.state.drive.poseEstimator.estimatedPose,
                            cameraPoses = frame.visionInputs.cameraPoses
                        )
                    )

                    prevFrame = frame
                } catch (e: Exception) {
                    System.err.println("SensoryReplayRunner: Error processing frame: ${e.message}")
                }
            }
        }

        return ReplaySummary(
            steps = steps,
            finalRealPose = realStore.state.drive.poseEstimator.estimatedPose,
            finalGhostPose = ghostStore.state.drive.poseEstimator.estimatedPose
        )
    }
}
