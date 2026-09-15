package org.aresfirst.marvin.marvin

import com.areslib.Store
import com.areslib.state.RobotState
import com.areslib.state.SuperstructureState
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MarvinFacadeAuditTest {
    @BeforeEach fun mockClock() = RobotClock.useMockTime(1000L)
    @AfterEach fun restoreClock() = RobotClock.useSystemTime()
    private fun store() = Store(RobotState(superstructure = SuperstructureState(custom = MarvinState()))) {
        state, action -> MarvinReducer.reduce(state, action)
    }

    @Test fun `cowl rejects nonfinite commands before publishing a target`() {
        val store = store()
        val cowl = MarvinCowlController(store)
        cowl.setCowlAngleRotations(0.5)
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { cowl.setCowlAngleRotations(value) }
            assertEquals(0.5, store.state.superstructure.marvin.cowl.targetAngleRotations, 0.0)
        }
    }

    @Test fun `flywheel rejects invalid RPM before arming or changing target`() {
        val store = store()
        val flywheel = MarvinFlywheelController(store)
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
            assertThrows(IllegalArgumentException::class.java) { flywheel.spinUp(value) }
            assertEquals(0.0, store.state.superstructure.marvin.flywheel.targetVelocityRpm, 0.0)
            assertFalse(store.state.superstructure.marvin.flywheelActive)
        }
    }

    @Test fun `transfer closes on rollback even when elapsed subtraction wraps positive`() {
        val store = store()
        val feeder = MarvinFeederController(store)
        RobotClock.useMockTime(Long.MAX_VALUE)
        feeder.updateFeeders(true, true, true, true)
        assertTrue(feeder.transferActive)
        RobotClock.useMockTime(Long.MIN_VALUE)
        feeder.updateFeeders(true, true, true, true)
        assertFalse(feeder.transferActive)
        assertEquals(0.0, store.state.superstructure.marvin.feeder.targetVelocityRps, 0.0)
        assertEquals(0.0, store.state.superstructure.marvin.floor.targetVelocityRps, 0.0)
    }

    @Test fun `unchanged facade calls avoid Redux dispatch and steady allocation`() {
        val store = store()
        val cowl = MarvinCowlController(store)
        val flywheel = MarvinFlywheelController(store)
        val feeder = MarvinFeederController(store)
        cowl.setCowlAngleRotations(0.5)
        flywheel.spinUp(3500.0)
        var changes = 0
        val unsubscribe = store.subscribe { changes++ }
        try {
            repeat(30_000) { cowl.setCowlAngleRotations(0.5); flywheel.spinUp(3500.0); feeder.cancelTransfer() }
            val bean = java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
            org.junit.jupiter.api.Assumptions.assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
            bean!!.isThreadAllocatedMemoryEnabled = true
            val id = Thread.currentThread().id
            val before = bean.getThreadAllocatedBytes(id)
            repeat(100_000) { cowl.setCowlAngleRotations(0.5); flywheel.spinUp(3500.0); feeder.cancelTransfer() }
            val bytes = bean.getThreadAllocatedBytes(id) - before
            println("Unchanged cowl/flywheel/feeder facades: $bytes bytes / 100000 ticks")
            assertEquals(0, changes)
            assertTrue(bytes <= 4096L, "Steady facade allocation: $bytes bytes")
        } finally { unsubscribe() }
    }

    @Test fun `readiness gates enforce validity and tolerance boundaries`() {
        val store = store()
        val cowl = MarvinCowlController(store)
        val flywheel = MarvinFlywheelController(store)
        store.dispatch(SuperstructureSensorUpdate(flywheelRpm = 3500.0, cowlAngleRotations = 0.5,
            intakeAngle = 0.0, pieceDetected = false, flywheelVelocityValid = true,
            flywheelAllMotorsAtTarget = true, cowlAngleValid = true))
        assertTrue(cowl.isAngleAligned(0.5))
        assertFalse(cowl.isAngleAligned(0.56))
        assertFalse(cowl.isAngleAligned(Double.NaN))
        assertTrue(flywheel.isRpmAligned(3500.0))
        assertFalse(flywheel.isRpmAligned(3650.0))
        assertFalse(flywheel.isRpmAligned(100.0))
        assertFalse(flywheel.isRpmAligned(Double.POSITIVE_INFINITY))
        store.dispatch(SuperstructureSensorUpdate(flywheelRpm = 3500.0, cowlAngleRotations = 0.5,
            intakeAngle = 0.0, pieceDetected = false, flywheelVelocityValid = false, cowlAngleValid = false))
        assertFalse(cowl.isAngleAligned(0.5))
        assertFalse(flywheel.isRpmAligned(3500.0))
    }

    @Test fun `all starting interlocks and optional floor assist are respected`() {
        for (mask in 0..7) {
            val store = store()
            val feeder = MarvinFeederController(store)
            feeder.updateFeeders(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0, true)
            assertEquals(mask == 7, feeder.transferActive)
            if (mask == 7) {
                feeder.updateFeeders(false, false, false, false)
                assertEquals(MarvinConfig.FEEDER_SHOOT_SPEED_RPS, store.state.superstructure.marvin.feeder.targetVelocityRps, 0.0)
                assertEquals(0.0, store.state.superstructure.marvin.floor.targetVelocityRps, 0.0)
                RobotClock.useMockTime(1449L)
                feeder.updateFeeders(false, false, false, true)
                assertTrue(feeder.transferActive)
                RobotClock.useMockTime(1450L)
                feeder.updateFeeders(true, true, true, true)
                assertFalse(feeder.transferActive)
            }
        }
    }

    @Test fun `finite clamp stop and inhibit preserve command semantics`() {
        val store = store()
        val cowl = MarvinCowlController(store)
        val flywheel = MarvinFlywheelController(store)
        cowl.setCowlAngleRotations(99.0)
        assertEquals(MarvinConfig.cowlMaxRotations, store.state.superstructure.marvin.cowl.targetAngleRotations, 0.0)
        cowl.setCowlAngleRotations(-1.0)
        assertEquals(0.0, store.state.superstructure.marvin.cowl.targetAngleRotations, 0.0)
        flywheel.spinUp(3500.0)
        assertTrue(store.state.superstructure.marvin.flywheelActive)
        flywheel.stop()
        assertFalse(store.state.superstructure.marvin.flywheelActive)
        assertEquals(0.0, store.state.superstructure.marvin.flywheel.targetVelocityRpm, 0.0)
        store.dispatch(SetMechanismSafetyInhibit(true))
        flywheel.spinUp(3500.0)
        cowl.setCowlAngleRotations(0.5)
        assertFalse(store.state.superstructure.marvin.flywheelActive)
        assertEquals(0.0, store.state.superstructure.marvin.cowl.targetAngleRotations, 0.0)
    }

    @Test fun `canonical runtime mapping preserves immutable state and canonical geometry`() {
        val adapter = org.aresfirst.marvin.config.CanonicalDrivebaseConfig
        val initial = adapter.initialTuningState()
        val runtime = org.aresfirst.marvin.generated.drivebase.GeneratedAresTuningConfig.createRuntime()
        val context = com.areslib.tuning.TuningApplyContext(sessionArmed = true, robotDisabled = true)
        assertEquals(com.areslib.tuning.TuningUpdateResult.APPLIED,
            runtime.apply("frc.ares.path.velocity-scale", com.areslib.tuning.TuningValue(doubleValue = 0.5), context))
        assertEquals(com.areslib.tuning.TuningUpdateResult.APPLIED,
            runtime.apply("frc.ares.path.acceleration-limit", com.areslib.tuning.TuningValue(doubleValue = 2.0), context))
        val changed = adapter.withRuntimeValues(initial, runtime)
        assertEquals(0.5, changed.drive.pathVelocityScale, 0.0)
        assertEquals(2.0, changed.drive.pathAccelerationLimit, 0.0)
        assertEquals(0.85, initial.drive.pathVelocityScale, 0.0)
        assertEquals(initial.drive.trackWidthMeters, changed.drive.trackWidthMeters, 0.0)
        assertEquals(initial.drive.wheelBaseMeters, changed.drive.wheelBaseMeters, 0.0)
        assertEquals(initial.copy(drive = changed.drive), changed)
        assertTrue(adapter.supportsRuntimeParameter("frc.ares.path.acceleration-limit"))
        assertFalse(adapter.supportsRuntimeParameter("frc.module.fl.offset"))
    }

    @Test fun `shot configuration yields finite monotone outputs and safe cowl targets`() {
        val config = MarvinConfig.SHOT_CONFIG
        val shot = com.areslib.control.assist.ShotSetup(config)
        var previousRpm = 0.0
        var previousTof = 0.0
        var previousCowl = 0.0
        for (i in 0..100) {
            val distance = i / 10.0
            val rpm = shot.interpolateRpm(distance)
            val tof = shot.interpolateTof(distance)
            val cowl = shot.interpolateCowlRotations(distance)
            assertTrue(rpm.isFinite() && rpm >= previousRpm && rpm > 100.0)
            assertTrue(tof.isFinite() && tof >= previousTof && tof > 0.0)
            assertTrue(cowl.isFinite() && cowl >= previousCowl && cowl in 0.0..MarvinConfig.cowlMaxRotations)
            previousRpm = rpm; previousTof = tof; previousCowl = cowl
        }
        assertEquals(3350.0, shot.interpolateRpm(1.24), 0.0)
        assertEquals(4200.0, shot.interpolateRpm(5.6), 0.0)
        assertEquals(MarvinConfig.MechanismGeometry.SHOOTER_EXIT_X_METERS, config.shooterOffsetX, 0.0)
        assertEquals(MarvinConfig.MechanismGeometry.SHOOTER_EXIT_Y_METERS, config.shooterOffsetY, 0.0)
        assertTrue(config.shooterFacesRearward)
    }
}
