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
        val distance = RobotState().tuning.visionAlignTargetDistance
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
}
