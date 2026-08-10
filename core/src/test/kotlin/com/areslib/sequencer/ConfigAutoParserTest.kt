package com.areslib.sequencer

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.pathing.HolonomicPathFollower
import com.areslib.state.RobotState
import com.areslib.subsystem.DrivetrainSubsystem
import com.areslib.Store
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConfigAutoParserTest {

    class MockDrivetrain : DrivetrainSubsystem {
        override fun setChassisSpeeds(vx: Double, vy: Double, omega: Double) {}
        override fun getEstimatedPose(): Pose2d = Pose2d(0.0, 0.0, Rotation2d())
        override fun readSensors(store: Store, timestampMs: Long) {}
        override fun writeOutputs(state: RobotState, scale: Double) {}
        override fun close() {}
    }

    @Test
    fun testParseJsonScript() {
        val drivetrain = MockDrivetrain()
        val follower = HolonomicPathFollower(drivetrain)

        val json = """
            {
                "name": "TestAutoScript",
                "steps": [
                    { "type": "waittime", "durationMs": 1000 },
                    { "type": "dispatchpathevent", "eventName": "AlignDone" },
                    { "type": "waitdistance", "meters": 1.5 }
                ]
            }
        """.trimIndent()

        val parsedTask = ConfigAutoParser.parse(json, follower, 12345L)
        assertNotNull(parsedTask)
        assertTrue(parsedTask is SequentialTaskGroup)
        assertEquals(
            "Sequential(TimeWait(1000 ms), ActionDispatch(PathEventTriggered), PathProgressWait(1.5 m))",
            parsedTask.name
        )
    }

    @Test
    fun testParseUnknownType() {
        val drivetrain = MockDrivetrain()
        val follower = HolonomicPathFollower(drivetrain)

        val json = """
            {
                "name": "BadScript",
                "steps": [
                    { "type": "flytoroyalty" }
                ]
            }
        """.trimIndent()

        assertThrows(IllegalStateException::class.java) {
            ConfigAutoParser.parse(json, follower, 12345L)
        }
    }
}
