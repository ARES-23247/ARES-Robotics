package com.areslib.logging

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Vector3
import com.areslib.state.VisionMeasurement
import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileWriter

class SensoryReplayRunnerTest {

    private val gson = Gson()

    @Test
    fun `test parallel logged vs ghost offline replay`(@TempDir tempDir: File) {
        val logFile = File(tempDir, "sensory_log_test.jsonl")
        
        // 1. Generate dummy sensory log frames
        FileWriter(logFile).use { writer ->
            // Frame 1: Initial position at origin
            val f1 = RobotInputsFrame().apply {
                timestampMs = 1000L
                odometryInputs.apply {
                    posX = 0.0
                    posY = 0.0
                    heading = 0.0
                    velX = 0.0
                    velY = 0.0
                    headingVelocity = 0.0
                    timestampMs = 1000L
                }
                imuInputs.apply {
                    headingRadians = 0.0
                    timestampMs = 1000L
                }
            }
            writer.write(gson.toJson(f1) + "\n")

            // Frame 2: Robot drove forward by 1 meter in x
            val f2 = RobotInputsFrame().apply {
                timestampMs = 1020L
                odometryInputs.apply {
                    posX = 1.0
                    posY = 0.0
                    heading = 0.0
                    velX = 50.0 // 1m over 20ms
                    velY = 0.0
                    headingVelocity = 0.0
                    timestampMs = 1020L
                }
                imuInputs.apply {
                    headingRadians = 0.0
                    timestampMs = 1020L
                }
            }
            writer.write(gson.toJson(f2) + "\n")

            // Frame 3: Robot received a vision measurement saying it is at (1.1, 0.2)
            // This is slightly off from the 1.0 odometry reading, allowing EKF trust filters to trigger.
            val f3 = RobotInputsFrame().apply {
                timestampMs = 1040L
                odometryInputs.apply {
                    posX = 1.0
                    posY = 0.0
                    heading = 0.0
                    velX = 0.0
                    velY = 0.0
                    headingVelocity = 0.0
                    timestampMs = 1040L
                }
                imuInputs.apply {
                    headingRadians = 0.0
                    timestampMs = 1040L
                }
                visionInputs.apply {
                    isConnected = true
                    // Add vision measurement
                    measurements = listOf(
                        VisionMeasurement(
                            timestampMs = 1040L,
                            targetPose = com.areslib.math.geometry.Pose3d(
                                com.areslib.math.geometry.Translation3d(1.1, 0.2, 0.0),
                                com.areslib.math.geometry.Rotation3d()
                            ),
                            tagId = 2,
                            ambiguity = 0.05
                        )
                    )
                }
            }
            writer.write(gson.toJson(f3) + "\n")
        }

        // 2. Replay with default weights vs extremely aggressive Ghost vision weights (very small std dev = high trust)
        val defaultSummary = SensoryReplayRunner.replaySensoryLog(logFile)
        val customSummary = SensoryReplayRunner.replaySensoryLog(
            logFile = logFile,
            ghostVisionStdDevs = Vector3(0.001, 0.001, 0.001) // Snaps aggressively to vision
        )

        // Verify log parsing and execution
        assertEquals(3, defaultSummary.steps.size)
        assertEquals(3, customSummary.steps.size)

        // Frame 1: origin
        val step1Default = defaultSummary.steps[0]
        assertEquals(0.0, step1Default.realPose.x, 1e-6)
        assertEquals(0.0, step1Default.ghostPose.x, 1e-6)

        // Frame 2: drove to x = 1.0
        val step2Default = defaultSummary.steps[1]
        val step2Custom = customSummary.steps[1]
        assertEquals(1.0, step2Default.realPose.x, 0.1)
        assertEquals(1.0, step2Custom.ghostPose.x, 0.1)

        // Frame 3: vision update triggers!
        val step3Default = defaultSummary.steps[2]
        val step3Custom = customSummary.steps[2]

        // Under custom config, standard deviations are tiny, meaning EKF snaps aggressively to (1.1, 0.2).
        // Therefore, Ghost Pose should be closer to (1.1, 0.2) than Real Pose.
        val defaultXDist = Math.abs(step3Default.realPose.x - 1.1)
        val customXDist = Math.abs(step3Custom.ghostPose.x - 1.1)
        val defaultYDist = Math.abs(step3Default.realPose.y - 0.2)
        val customYDist = Math.abs(step3Custom.ghostPose.y - 0.2)

        assertTrue(customXDist < defaultXDist, "Ghost pose should be closer to vision measurement than Real pose on X")
        assertTrue(customYDist < defaultYDist, "Ghost pose should be closer to vision measurement than Real pose on Y")

        // Print final poses for verification
        println("Final Real Pose: ${defaultSummary.finalRealPose}")
        println("Final Ghost Pose: ${customSummary.finalGhostPose}")
    }

    @Test
    fun `test replay publisher streaming`() {
        val summary = ReplaySummary(
            steps = listOf(
                ReplayStepResult(1000L, Pose2d(0.0, 0.0, Rotation2d()), Pose2d(0.1, 0.1, Rotation2d())),
                ReplayStepResult(1010L, Pose2d(0.5, 0.0, Rotation2d()), Pose2d(0.6, 0.1, Rotation2d()))
            ),
            finalRealPose = Pose2d(0.5, 0.0, Rotation2d()),
            finalGhostPose = Pose2d(0.6, 0.1, Rotation2d())
        )

        val telemetry = com.areslib.telemetry.NT4Telemetry()
        val publisher = com.areslib.telemetry.ReplayPublisher(telemetry)
        
        // Stream at 100x speed multiplier to complete instantly during unit tests
        assertDoesNotThrow {
            publisher.publishReplay(summary, speedMultiplier = 100.0)
        }
    }
}
