package com.areslib.control.drivetrain

import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation3d
import com.areslib.state.RobotState
import com.areslib.state.VisionMeasurement
import com.areslib.state.VisionState
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.math.tan
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisionAlignControllerTest {
    @AfterEach
    fun restoreClock() {
        RobotClock.useSystemTime()
    }

    @Test
    fun `first vision sample does not apply derivative kick from zero`() {
        RobotClock.useMockTime(1_000L)
        val controller = VisionAlignController()
        val distance = RobotState().tuning.visionAlign.targetDistanceMeters
        val measurement = VisionMeasurement(
            timestampMs = 1_000L,
            tagId = 7,
            robotPoseTargetSpace = Pose3d(
                translation = Translation3d(tan(0.5) * distance, 0.0, distance),
                rotation = Rotation3d()
            )
        )
        val state = RobotState(vision = VisionState(measurements = listOf(measurement)))

        val command = assertNotNull(controller.calculate(state, targetTagId = 7, isAlignmentRequested = true))

        assertTrue(command.targetAngularVelocity in 0.58..0.62,
            "First sample should contain P/I/kS only, got ${command.targetAngularVelocity}")
    }

    @Test
    fun `future dated target space sample is not used for closed loop translation`() {
        RobotClock.useMockTime(1_000L)
        val measurement = VisionMeasurement(
            timestampMs = 1_001L,
            tagId = 7,
            robotPoseTargetSpace = Pose3d(Translation3d(1.0, 0.0, 1.0), Rotation3d())
        )
        val command = assertNotNull(
            VisionAlignController().calculate(
                RobotState(vision = VisionState(measurements = listOf(measurement))),
                targetTagId = 7,
                isAlignmentRequested = true
            )
        )

        assertEquals(0.0, command.targetXVelocity, 1e-9)
        assertEquals(0.0, command.targetYVelocity, 1e-9)
        assertTrue(command.targetAngularVelocity.isFinite())
    }

    @Test
    fun `nonfinite target space sample cannot emit nonfinite commands`() {
        RobotClock.useMockTime(1_000L)
        val measurement = VisionMeasurement(
            timestampMs = 1_000L,
            tagId = 7,
            robotPoseTargetSpace = Pose3d(
                Translation3d(Double.NaN, 0.0, 1.0),
                Rotation3d()
            )
        )
        val command = assertNotNull(
            VisionAlignController().calculate(
                RobotState(vision = VisionState(measurements = listOf(measurement))),
                targetTagId = 7,
                isAlignmentRequested = true
            )
        )

        assertTrue(command.targetXVelocity.isFinite())
        assertTrue(command.targetYVelocity.isFinite())
        assertTrue(command.targetAngularVelocity.isFinite())
    }

    @Test
    fun `target-space depth is not rotated again by robot pitch`() {
        RobotClock.useMockTime(1_000L)
        val measurement = VisionMeasurement(
            timestampMs = 1_000L,
            tagId = 7,
            // Limelight target-space Y is positive downward. Z is already the solved
            // tag-normal separation and must remain independent of IMU pitch.
            robotPoseTargetSpace = Pose3d(Translation3d(0.0, 1.0, 1.5), Rotation3d())
        )
        val state = RobotState(vision = VisionState(measurements = listOf(measurement)))

        val level = assertNotNull(
            VisionAlignController().calculate(state, 7, true, imuPitch = 0.0)
        )
        val pitched = assertNotNull(
            VisionAlignController().calculate(state, 7, true, imuPitch = 0.4)
        )

        assertEquals(level.targetXVelocity, pitched.targetXVelocity, 1e-9)
        assertEquals(level.targetYVelocity, pitched.targetYVelocity, 1e-9)
    }
}
