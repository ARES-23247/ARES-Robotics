package com.areslib.sim.xrp

import com.areslib.networktables.NT4Instance
import com.areslib.networktables.NT4Server
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XrpSimulationEngineTest {

    companion object {
        @org.junit.BeforeClass
        @JvmStatic
        fun initServer() {
            if (NT4Instance.defaultInstance.defaultServer == null) {
                try {
                    NT4Instance.defaultInstance.startServer("127.0.0.1", 5812)
                } catch (_: Exception) {}
            }
        }
    }

    @BeforeTest
    fun setup() {
        initServer()
        NT4Server.resetSharedState()
    }

    @Test
    fun testDifferentialForwardMotionAndOtos() {
        val engine = XrpSimulationEngine(
            drivetrainType = XrpDrivetrainType.DIFFERENTIAL,
            maxLinearSpeedMetersPerSecond = 1.0
        )
        engine.resetPose(0.35, 0.7112, 0.0)

        engine.leftPower = 1.0
        engine.rightPower = 1.0

        for (i in 0 until 10) {
            engine.step(0.02)
        }

        assertTrue(engine.otosX > 0.35, "Robot should translate forward along X (was ${engine.otosX})")
        assertEquals(0.7112, engine.otosY, 0.02, "Robot should not drift in Y during straight motion")
        assertEquals(0.0, engine.otosHeading, 0.05, "Robot heading should remain straight")

        engine.publishTelemetry()
        assertEquals(engine.otosX, NT4Server.getDouble("Drive/Pose_X", 0.0), 1e-4)
        assertEquals(engine.otosY, NT4Server.getDouble("Drive/Pose_Y", 0.0), 1e-4)
        val frame = NT4Server.getDoubleArray("ARES/SimulatorPoseFrame", DoubleArray(0))
        assertEquals(10, frame.size)
        assertEquals(engine.otosX, frame[0], 1e-4) // trueX
        assertEquals(engine.otosX, frame[3], 1e-4) // estX
    }

    @Test
    fun testDifferentialRotation() {
        val engine = XrpSimulationEngine(
            drivetrainType = XrpDrivetrainType.DIFFERENTIAL,
            trackWidthMeters = 0.155,
            maxLinearSpeedMetersPerSecond = 1.0
        )
        engine.resetPose(0.35, 0.7112, 0.0)

        // Left reverse, right forward => CCW positive rotation
        engine.leftPower = -1.0
        engine.rightPower = 1.0

        for (i in 0 until 5) {
            engine.step(0.02)
        }

        assertTrue(engine.otosHeading > 0.0, "Heading should rotate CCW positive (was ${engine.otosHeading})")
        assertTrue(engine.otosOmega > 0.0, "Angular velocity should be positive CCW")
    }

    @Test
    fun testMecanumStrafe() {
        val engine = XrpSimulationEngine(
            drivetrainType = XrpDrivetrainType.MECANUM,
            maxLinearSpeedMetersPerSecond = 1.0
        )
        engine.resetPose(0.35, 0.7112, 0.0)

        // Command pure lateral strafe: vx=0, vy=0.5, omega=0
        val driveFrame = doubleArrayOf(0.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        engine.processDriveFrame(driveFrame)

        for (i in 0 until 10) {
            engine.step(0.02)
        }

        assertTrue(engine.otosY > 0.7112, "Mecanum should translate along Y (was ${engine.otosY})")
    }
}
