package com.areslib.sim.xrp

import com.areslib.util.RobotClock
import com.areslib.action.RobotAction
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class XrpDriveLeaseAuditTest {
    @AfterTest fun restoreClock() { RobotClock.useSystemTime() }
    private fun frame(sequence: Int, vx: Double = 0.0, vy: Double = 0.0, omega: Double = 0.0, session: Int = 41) =
        doubleArrayOf(2.0, session.toDouble(), sequence.toDouble(), sequence * 20.0, vx, vy, omega, 8.0)
    private fun neutral(engine: XrpSimulationEngine) {
        assertEquals(0.0, engine.leftPower); assertEquals(0.0, engine.rightPower)
        assertEquals(0.0, engine.flPower); assertEquals(0.0, engine.frPower)
        assertEquals(0.0, engine.rlPower); assertEquals(0.0, engine.rrPower)
    }

    @Test fun `legacy unleased command cannot actuate the simulator`() {
        RobotClock.useMockTime(1000)
        val engine = XrpSimulationEngine()
        engine.processDriveFrame(doubleArrayOf(0.6, 0.0, 0.0))
        neutral(engine)
    }

    @Test fun `v2 metadata is not motion and a new session needs neutral first`() {
        RobotClock.useMockTime(1000)
        val engine = XrpSimulationEngine()
        engine.processDriveFrame(frame(0, vx = 0.6))
        neutral(engine)
        engine.processDriveFrame(frame(1))
        neutral(engine)
        engine.processDriveFrame(frame(2, vx = 0.425))
        assertEquals(0.5, engine.leftPower, 1e-14)
        assertEquals(0.5, engine.rightPower, 1e-14)
    }

    @Test fun `lease expiry neutralizes even without another network frame`() {
        RobotClock.useMockTime(1000)
        val engine = XrpSimulationEngine()
        engine.processDriveFrame(frame(0))
        engine.processDriveFrame(frame(1, vx = 0.425))
        RobotClock.useMockTime(1501)
        engine.step()
        neutral(engine)
    }

    @Test fun `repeated retained frame cannot refresh receiver time`() {
        RobotClock.useMockTime(1000)
        val engine = XrpSimulationEngine()
        engine.processDriveFrame(frame(0))
        val active = frame(1, vx = 0.425)
        engine.processDriveFrame(active)
        for (time in listOf(1100L, 1300L, 1501L)) {
            RobotClock.useMockTime(time)
            engine.processDriveFrame(active)
        }
        neutral(engine)
    }

    @Test fun `same sequence mutation and unhandshaken replacement session neutralize`() {
        RobotClock.useMockTime(1000)
        val engine = XrpSimulationEngine()
        engine.processDriveFrame(frame(0))
        engine.processDriveFrame(frame(1, vx = 0.425))
        engine.processDriveFrame(frame(1, vx = 0.5))
        neutral(engine)
        engine.processDriveFrame(frame(2))
        engine.processDriveFrame(frame(3, vx = 0.425))
        engine.processDriveFrame(frame(0, vx = 0.5, session = 42))
        neutral(engine)
    }

    @Test fun `mecanum network saturation preserves the coupled wheel ratio`() {
        RobotClock.useMockTime(1000)
        val engine = XrpSimulationEngine(drivetrainType = XrpDrivetrainType.MECANUM, maxLinearSpeedMetersPerSecond = 0.8)
        engine.processDriveFrame(frame(0))
        engine.processDriveFrame(frame(1, vx = 1.2, vy = 0.4))
        assertEquals(0.5, engine.flPower, 1e-14)
        assertEquals(1.0, engine.frPower, 1e-14)
        assertEquals(1.0, engine.rlPower, 1e-14)
        assertEquals(0.5, engine.rrPower, 1e-14)
    }

    @Test fun `receiver clock rewind invalidates previously active control`() {
        RobotClock.useMockTime(1000)
        val engine = XrpSimulationEngine()
        engine.processDriveFrame(frame(0))
        engine.processDriveFrame(frame(1, vx = 0.425))
        RobotClock.useMockTime(999)
        engine.step()
        neutral(engine)
    }

    @Test fun `field relative control uses Store heading and does not mirror alliance twice`() {
        RobotClock.useMockTime(1000)
        val engine = XrpSimulationEngine(drivetrainType = XrpDrivetrainType.MECANUM)
        engine.processDriveFrame(frame(0))
        engine.store.dispatch(RobotAction.PoseUpdate(0.35, 0.7112, Math.PI / 2.0, 1L,
            isReset = true, applyControlHubGyroCorrection = false, imuMeasurementsValid = false))
        assertEquals(0.0, engine.physicsWorld.robotBody.transform.rotationAngle, 1e-12)
        engine.processDriveFrame(frame(1, vx = 0.425).also { it[7] = 56.0 })
        assertEquals(0.5, engine.flPower, 1e-12)
        assertEquals(-0.5, engine.frPower, 1e-12)
        assertEquals(-0.5, engine.rlPower, 1e-12)
        assertEquals(0.5, engine.rrPower, 1e-12)
    }

    @Test fun `disabled leased control cannot be bypassed by another Store intent`() {
        RobotClock.useMockTime(1000)
        val engine = XrpSimulationEngine()
        engine.processDriveFrame(frame(0))
        engine.processDriveFrame(frame(1, vx = 0.425).also { it[7] = 0.0 })
        neutral(engine)
        engine.store.dispatch(RobotAction.JoystickDriveIntent(0.425, 0.0, 0.0, isFieldCentric = false))
        engine.step()
        neutral(engine)
    }

    @Test fun `stop invalidates authority and neutralizes despite a throwing subscriber`() {
        RobotClock.useMockTime(1000)
        val engine = XrpSimulationEngine()
        engine.processDriveFrame(frame(0))
        engine.processDriveFrame(frame(1, vx = 0.425))
        val failure = IllegalStateException("subscriber")
        val unsubscribe = engine.store.subscribe { throw failure }
        try { kotlin.test.assertSame(failure, kotlin.test.assertFailsWith<IllegalStateException> { engine.stop() }) }
        finally { unsubscribe() }
        neutral(engine)
        assertEquals(0.0, engine.physicsWorld.robotBody.linearVelocity.x)
        engine.processDriveFrame(frame(2, vx = 0.425))
        neutral(engine)
        engine.processDriveFrame(frame(3))
        engine.processDriveFrame(frame(4, vx = 0.425))
        assertEquals(0.5, engine.leftPower, 1e-12)
    }
}
