package com.areslib.sim.xrp

import com.areslib.networktables.NT4Server
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class XrpSimulationBoundaryAuditTest {
    @Test fun `mutable raw odometry is not published as the Redux estimate`() {
        NT4Server.createInstance("127.0.0.1", 0)
        try {
            val engine = XrpSimulationEngine()
            engine.otosX = 2.0
            engine.publishTelemetry()
            val frame = NT4Server.getDoubleArray("ARES/SimulatorPoseFrame", doubleArrayOf())
            assertEquals(0.35, frame[0], 1e-12)
            assertEquals(0.35, frame[3], 1e-12)
        } finally {
            NT4Server.getInstance()?.stop()
            NT4Server.resetSharedState()
        }
    }

    @Test fun `invalid physics duration neutralizes before rejecting`() {
        val engine = XrpSimulationEngine()
        engine.leftPower = 0.5
        engine.rightPower = 0.5
        assertFailsWith<IllegalArgumentException> { engine.step(Double.NaN) }
        assertEquals(0.0, engine.leftPower)
        assertEquals(0.0, engine.rightPower)
    }

    @Test fun `invalid reset does not corrupt the physical pose`() {
        val engine = XrpSimulationEngine()
        assertFailsWith<IllegalArgumentException> { engine.resetPose(Double.NaN, 0.0, 0.0) }
        assertEquals(0.35, engine.physicsWorld.robotBody.transform.translationX, 1e-12)
    }

    @Test fun `invalid simulated speed and radius configuration rejects at construction`() {
        assertFailsWith<IllegalArgumentException> { XrpSimulationEngine(maxLinearSpeedMetersPerSecond = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { XrpSimulationEngine(maxAngularSpeedRadPerSec = -1.0) }
        assertFailsWith<IllegalArgumentException> { XrpSimulationEngine(wheelRadiusMeters = 0.0) }
    }
}
