package com.areslib.sim

import com.areslib.ftc.drivetrain.PinpointIO
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.sim.model.MecanumRobotDouble
import com.areslib.simulation.SimulationFaultCommand
import com.areslib.simulation.SimulationFaultKind
import com.areslib.simulation.SimulationFaultTimeline
import com.areslib.util.RobotClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FreshAuditSimRegressionTest {
    @After
    fun tearDown() {
        RobotClock.useSystemTime()
    }

    @Test
    fun `PinpointIO reports accurate field velocity at rotated headings after non-zero spawn`() {
        val fieldVx = 0.3
        val fieldVy = 0.8
        for (heading in listOf(0.0, Math.PI / 2.0, Math.PI, -Math.PI / 2.0)) {
            RobotClock.useMockTime(1_000L)
            val robotDouble = MecanumRobotDouble()
            robotDouble.updateSensors(0.02, 0.0, 0.0, 0.0, 0.0, 1.2, heading, isPinpointCcwPositive = true)

            val pinpointIO = PinpointIO(robotDouble.pinpoint, isHeadingCcwPositive = true)
            pinpointIO.initialize(Pose2d(0.0, 1.2, Rotation2d(heading)), resetHardware = true)

            RobotClock.useMockTime(1_020L)
            val cosH = cos(heading)
            val sinH = sin(heading)
            val robotVx = fieldVx * cosH + fieldVy * sinH
            val robotVy = -fieldVx * sinH + fieldVy * cosH
            robotDouble.updateSensors(
                dt = 0.02,
                actualVx = robotVx,
                actualVy = robotVy,
                actualOmega = 0.0,
                trueX = fieldVx * 0.02,
                trueY = 1.2 + fieldVy * 0.02,
                trueHeadingRad = heading,
                isPinpointCcwPositive = true,
            )

            val update = pinpointIO.getPoseUpdate()
            assertEquals("fieldVx mismatch at heading=$heading", fieldVx, update.xVelocityMetersPerSecond, 1e-6)
            assertEquals("fieldVy mismatch at heading=$heading", fieldVy, update.yVelocityMetersPerSecond, 1e-6)
        }
    }

    @Test
    fun `MecanumRobotDouble updateSensors survives every injected motor fault kind`() {
        RobotClock.useMockTime(500L)
        for (kind in SimulationFaultKind.entries) {
            val timeline = SimulationFaultTimeline(
                listOf(
                    SimulationFaultCommand(
                        commandId = "fault-${kind.name}",
                        targetId = "ftc.drive.fl",
                        kind = kind,
                        startsAtMillis = 0L,
                        endsAtMillis = 10_000L,
                    )
                )
            )
            val robotDouble = MecanumRobotDouble(timeline)
            robotDouble.updateSensors(0.02, 1.0, 0.2, 0.3, 0.1, 0.2, 0.4, isPinpointCcwPositive = true)
            assertTrue("updateSensors must not throw for fault kind=$kind", robotDouble.fl.rawPower.isFinite())
        }
    }
}
