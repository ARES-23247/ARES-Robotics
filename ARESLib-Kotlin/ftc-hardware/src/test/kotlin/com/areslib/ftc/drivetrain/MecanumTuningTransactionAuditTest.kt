package com.areslib.ftc.drivetrain

import com.areslib.Store
import com.areslib.control.tuning.PIDFCoefficients as Gains
import com.areslib.ftc.MockDcMotorEx
import com.areslib.ftc.calibration.FtcMecanumCalibrationController
import com.areslib.hardware.HardwareRegistry
import com.areslib.state.DriveTuningState
import com.areslib.state.TuningState
import com.areslib.subsystem.DriveSubsystem
import com.areslib.subsystem.MecanumDriveFacade
import com.qualcomm.robotcore.hardware.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class MecanumTuningTransactionAuditTest {
    private class Motor : DcMotorEx by MockDcMotorEx() {
        var gains = PIDFCoefficients(1.0, 2.0, 3.0, 4.0)
        var writes = 0
        var reads = 0
        var reject = false
        override fun getPIDFCoefficients(mode: DcMotor.RunMode): PIDFCoefficients {
            reads++
            return gains
        }
        override fun setPIDFCoefficients(mode: DcMotor.RunMode, pidfCoefficients: PIDFCoefficients) {
            writes++
            check(!reject) { "SDK rejected coefficients" }
            gains = PIDFCoefficients(pidfCoefficients.p, pidfCoefficients.i, pidfCoefficients.d, pidfCoefficients.f)
        }
    }
    private class Rig(native: Boolean = true, initial: Double? = null) : AutoCloseable {
        val motors = Array(4) { index -> Motor().apply { gains.f = 4.0 + index } }
        val registry = HardwareRegistry()
        val io = MecanumHardwareIO(object : HardwareMap() {
            @Suppress("UNCHECKED_CAST")
            override fun <T> get(classOrType: Class<out T>, deviceName: String): T =
                motors[listOf("fl", "fr", "rl", "rr").indexOf(deviceName)] as T
            override fun <T> getAll(classOrType: Class<out T>): List<T> = emptyList()
        }, registry, maxWheelSpeedMetersPerSecond = 3.5, useClosedLoopVelocity = native,
            motorKp = initial, motorKf = initial)
        val store = Store()
        val drive = DriveSubsystem(store)
        val facade = MecanumDriveFacade(store)
        val controller = MecanumKinematicsController(io, drive, facade, FtcMecanumCalibrationController())
        fun tune(value: DriveTuningState) = controller.updateTuning(TuningState(drive = value))
        fun neutral() = motors.forEach { assertEquals(0.0, it.power) }
        override fun close() { motors.forEach { it.reject = false }; registry.closeAll() }
    }
    private val standard = DriveTuningState()
    private val withGains = standard.copy(ftc = standard.ftc.copy(motorGains = Gains(8.0, 9.0, 10.0, 11.0)))

    @Test fun `unchanged geometry reuses the solver across unrelated tuning updates`() = Rig().use {
        it.tune(standard)
        val solver = it.controller.kinematics
        it.tune(standard.copy(headingDeadzoneDeg = 3.0))
        assertSame(solver, it.controller.kinematics)
        it.tune(standard.copy(trackWidthMeters = 0.6))
        assertNotSame(solver, it.controller.kinematics)
        assertEquals(0.525, it.controller.kinematics.k, 1e-12)
    }

    @Test fun `invalid geometry stops output before changing any gains or settings`() = Rig().use {
        it.tune(standard)
        val solver = it.controller.kinematics
        val priorKs = it.io.kS
        it.io.setMotorPowers(0.5, 0.5, 0.5, 0.5)
        val bad = withGains.copy(trackWidthMeters = Double.NaN, driveFeedforward = standard.driveFeedforward.copy(kS = 0.9))
        assertFailsWith<IllegalArgumentException> { it.tune(bad) }
        assertEquals(0, it.motors.sumOf { m -> m.writes })
        assertSame(solver, it.controller.kinematics)
        assertEquals(priorKs, it.io.kS)
        it.neutral()
        assertTrue(it.io.outputFaultLatched)
        assertFalse(it.io.recoverWithNeutral())
        it.tune(standard)
        assertTrue(it.io.outputFaultLatched)
        assertTrue(it.io.recoverWithNeutral())
    }

    @Test fun `all consumed numeric settings validate before native writes`() {
        val invalid = listOf(
            withGains.copy(wheelBaseMeters = 0.0),
            withGains.copy(trackWidthMeters = -1.0),
            withGains.copy(driveFeedforward = standard.driveFeedforward.copy(kS = Double.NaN)),
            withGains.copy(driveFeedforward = standard.driveFeedforward.copy(kA = Double.POSITIVE_INFINITY)),
            withGains.copy(driveFeedforward = standard.driveFeedforward.copy(kV = -1.0)),
            withGains.copy(driveSlewRateLimit = 0.0),
            withGains.copy(driveSlewRateLimit = Double.NaN),
            withGains.copy(ftc = withGains.ftc.copy(ticksPerMeter = 0.0)),
            withGains.copy(ftc = withGains.ftc.copy(motorGains = Gains(1.0, Double.NaN, 0.0, 0.0))),
            withGains.copy(trackWidthMeters = Double.MIN_VALUE, wheelBaseMeters = Double.MIN_VALUE)
        )
        for (bad in invalid) Rig().use {
            assertFailsWith<IllegalArgumentException> { it.tune(bad) }
            assertEquals(0, it.motors.sumOf { m -> m.writes })
            assertFalse(it.io.recoverWithNeutral())
        }
    }

    @Test fun `disabling reciprocal kV speed tuning restores the constructor limit`() = Rig().use {
        it.tune(standard.copy(driveFeedforward = standard.driveFeedforward.copy(kV = 0.5)))
        assertEquals(2.0, it.io.maxWheelSpeedMetersPerSecond)
        it.tune(standard.copy(driveFeedforward = standard.driveFeedforward.copy(kV = 0.0)))
        assertEquals(3.5, it.io.maxWheelSpeedMetersPerSecond)
        assertEquals(3.5, it.drive.maxSpeedMps)
        assertEquals(3.5 / 0.45, it.facade.maxAngularSpeedRps, 1e-12)
    }

    @Test fun `both drive surfaces receive the derived angular limit`() = Rig().use {
        it.tune(standard.copy(trackWidthMeters = 0.5, wheelBaseMeters = 0.3,
            driveFeedforward = standard.driveFeedforward.copy(kV = 0.5)))
        assertEquals(5.0, it.drive.maxAngularSpeedRadiansPerSecond, 1e-12)
        assertEquals(5.0, it.facade.maxAngularSpeedRps, 1e-12)
    }

    @Test fun `removing native gains restores each channels initialization snapshot`() = Rig().use {
        it.tune(withGains)
        it.tune(standard)
        it.motors.forEachIndexed { index, m ->
            assertEquals(1.0, m.gains.p)
            assertEquals(2.0, m.gains.i)
            assertEquals(3.0, m.gains.d)
            assertEquals(4.0 + index, m.gains.f)
        }
        assertEquals(4, it.motors.sumOf { m -> m.reads })
        val writes = it.motors.sumOf { m -> m.writes }
        it.tune(standard)
        assertEquals(writes, it.motors.sumOf { m -> m.writes })
    }

    @Test fun `removing native gains restores explicit constructor configuration`() = Rig(initial = 2.5).use {
        it.tune(withGains)
        it.tune(standard)
        assertTrue(it.motors.all { m -> m.gains.p == 2.5 && m.gains.i == 0.0 && m.gains.d == 0.0 && m.gains.f == 2.5 })
    }

    @Test fun `failed default restoration can be repaired without losing the snapshot`() = Rig().use {
        it.tune(withGains)
        it.motors[2].reject = true
        assertFailsWith<IllegalStateException> { it.tune(standard) }
        assertFalse(it.io.recoverWithNeutral())
        it.neutral()
        it.motors[2].reject = false
        it.tune(standard)
        assertTrue(it.io.recoverWithNeutral())
        assertTrue(it.motors.all { m -> m.gains.p == 1.0 })
    }

    @Test fun `removing software gains restores feedforward only without requiring encoders`() = Rig(native = false).use {
        it.tune(withGains)
        it.tune(standard.copy(driveFeedforward = standard.driveFeedforward.copy(kS = 0.0, kV = 0.5, kA = 0.0)))
        it.io.apply(doubleArrayOf(1.0, 1.0, 1.0, 1.0))
        assertTrue(it.motors.all { m -> m.power == 0.5 })
        assertEquals(0, it.motors.sumOf { m -> m.writes + m.reads })
    }

    @Test fun `closed software IO rejects tuning without changing its settings`() = Rig(native = false).use {
        it.tune(standard)
        val ks = it.io.kS
        it.io.close()
        assertFailsWith<IllegalStateException> { it.tune(standard.copy(driveFeedforward = standard.driveFeedforward.copy(kS = 0.9))) }
        assertEquals(ks, it.io.kS)
        it.neutral()
    }

    @Test fun `invalid software construction defaults cannot become accepted tuning`() = Rig(native = false, initial = Double.NaN).use {
        assertFailsWith<IllegalArgumentException> { it.tune(standard) }
        assertFalse(it.io.recoverWithNeutral())
        it.neutral()
    }

    @Test fun `software gain removal restores a declared controller`() = Rig(native = false, initial = 0.1).use {
        try {
            com.areslib.util.RobotClock.useMockTime(1000L)
            it.io.updateInputs()
            com.areslib.util.RobotClock.useMockTime(1020L)
            it.io.updateInputs()
            it.tune(withGains)
            it.tune(standard.copy(driveFeedforward = standard.driveFeedforward.copy(kS = 0.0, kV = 0.5, kA = 0.0)))
            it.io.apply(doubleArrayOf(1.0, 1.0, 1.0, 1.0))
            assertTrue(it.motors.all { m -> kotlin.math.abs(m.power - 0.6) < 1e-12 })
        } finally { com.areslib.util.RobotClock.useSystemTime() }
    }

    @Test fun `large finite geometry uses an overflow safe moment arm`() = Rig().use {
        it.tune(standard.copy(trackWidthMeters = Double.MAX_VALUE, wheelBaseMeters = Double.MAX_VALUE))
        assertEquals(Double.MAX_VALUE, it.controller.kinematics.k)
        assertTrue(it.facade.maxAngularSpeedRps.isFinite() && it.facade.maxAngularSpeedRps > 0.0)
    }

    @Test fun `unchanged software tuning preserves its accumulated slew history`() = Rig(native = false).use {
        val tuning = standard.copy(driveSlewRateLimit = 1.0,
            driveFeedforward = standard.driveFeedforward.copy(kS = 0.0, kV = 0.5, kA = 0.0))
        it.tune(tuning)
        repeat(10) { _ -> it.io.apply(doubleArrayOf(1.0, 1.0, 1.0, 1.0)) }
        it.tune(tuning)
        it.io.apply(doubleArrayOf(1.0, 1.0, 1.0, 1.0))
        assertTrue(it.motors.all { m -> m.power >= 0.20 && m.power <= 0.22 })
    }

    @Test fun `robot reapplies a rolled back snapshot after a rejected tuning attempt`() {
        val motors = Array(4) { MockDcMotorEx() }
        val map = object : HardwareMap() {
            @Suppress("UNCHECKED_CAST")
            override fun <T> get(classOrType: Class<out T>, deviceName: String): T =
                motors[listOf("fl", "fr", "rl", "rr").indexOf(deviceName)] as T
            override fun <T> getAll(classOrType: Class<out T>): List<T> = emptyList()
        }
        val robot = object : com.areslib.ftc.FtcMecanumRobot(map, imuName = null) {
            fun step() = updateSubsystems(0.02, 12.0, 1.0)
        }
        try {
            val accepted = robot.store.state.tuning
            robot.step()
            robot.store.dispatch(com.areslib.action.RobotAction.UpdateTuningState(
                accepted.copy(drive = accepted.drive.copy(trackWidthMeters = Double.NaN))))
            assertFailsWith<IllegalArgumentException> { robot.step() }
            assertFalse(robot.recoverDriveOutputWithNeutral())
            robot.store.dispatch(com.areslib.action.RobotAction.UpdateTuningState(accepted))
            assertSame(accepted, robot.store.state.tuning)
            robot.step()
            assertTrue(robot.recoverDriveOutputWithNeutral())
        } finally { robot.close() }
    }
}
