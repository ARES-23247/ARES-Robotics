package com.areslib.ftc.drivetrain

import com.areslib.ftc.MockDcMotorEx
import com.areslib.hardware.HardwareRegistry
import com.qualcomm.robotcore.hardware.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class NativeMecanumTuningAuditTest {
    private class Motor(val base: MockDcMotorEx = MockDcMotorEx()) : DcMotorEx by base {
        var coefficients = PIDFCoefficients(1.0, 2.0, 3.0, 0.7)
        var writes = 0
        var reads = 0
        var rejectPid = false
        var rejectMode = false
        var rejectRead = false
        var beforeWrite: (() -> Unit)? = null
        override var mode: DcMotor.RunMode
            get() = base.mode
            set(value) { check(!rejectMode) { "mode rejected" }; base.mode = value }
        override fun getPIDFCoefficients(mode: DcMotor.RunMode): PIDFCoefficients {
            reads++
            check(!rejectRead) { "PIDF read rejected" }
            return coefficients
        }
        override fun setPIDFCoefficients(mode: DcMotor.RunMode, pidfCoefficients: PIDFCoefficients) {
            writes++
            beforeWrite?.invoke()
            check(!rejectPid) { "PIDF rejected" }
            assertEquals(DcMotor.RunMode.RUN_USING_ENCODER, mode)
            coefficients = PIDFCoefficients(pidfCoefficients.p, pidfCoefficients.i, pidfCoefficients.d, pidfCoefficients.f)
        }
    }
    private class Rig : AutoCloseable {
        val motors = Array(4) { Motor() }
        val registry = HardwareRegistry()
        var missing: String? = null
        val map = object : HardwareMap() {
            @Suppress("UNCHECKED_CAST")
            override fun <T> get(type: Class<out T>, name: String): T {
                check(name != missing) { "missing motor" }
                return motors[listOf("fl", "fr", "rl", "rr").indexOf(name)] as T
            }
            override fun <T> getAll(type: Class<out T>): List<T> = emptyList()
        }
        fun create(native: Boolean = true) = MecanumHardwareIO(map, registry, useClosedLoopVelocity = native)
        fun neutral() = motors.forEach { assertEquals(0.0, it.base.currentPower) }
        override fun close() {
            motors.forEach { it.rejectPid = false; it.rejectMode = false; it.base.power = 0.0 }
            registry.closeAll()
        }
    }

    @Test fun `live native gains reach every channel while preserving its F coefficient`() = Rig().use {
        it.motors.forEachIndexed { index, motor -> motor.coefficients.f = 0.5 + index }
        val io = it.create()
        val reads = it.motors.sumOf { m -> m.reads }
        io.updateMotorGains(4.0, 5.0, 6.0)
        it.motors.forEachIndexed { index, motor ->
            assertEquals(4.0, motor.coefficients.p)
            assertEquals(5.0, motor.coefficients.i)
            assertEquals(6.0, motor.coefficients.d)
            assertEquals(0.5 + index, motor.coefficients.f)
        }
        assertEquals(reads, it.motors.sumOf { m -> m.reads }, "Tuning must use the initialization snapshot")
    }

    @Test fun `unchanged successful gains do not repeat SDK writes`() = Rig().use {
        val io = it.create()
        io.updateMotorGains(4.0, 5.0, 6.0)
        assertEquals(4, it.motors.sumOf { m -> m.writes })
        io.updateMotorGains(4.0, 5.0, 6.0)
        assertEquals(4, it.motors.sumOf { m -> m.writes })
    }

    @Test fun `failed native update neutralizes and requires successful configuration before recovery`() = Rig().use {
        val io = it.create()
        io.setMotorPowers(0.5, 0.5, 0.5, 0.5)
        it.motors[1].rejectPid = true
        assertFailsWith<IllegalStateException> { io.updateMotorGains(4.0, 5.0, 6.0) }
        it.neutral()
        assertTrue(io.outputFaultLatched)
        assertFalse(io.recoverWithNeutral())
        it.motors[1].rejectPid = false
        io.updateMotorGains(4.0, 5.0, 6.0)
        assertTrue(io.outputFaultLatched)
        assertTrue(io.recoverWithNeutral())
        io.setMotorPowers(0.5, 0.5, 0.5, 0.5)
        assertTrue(it.motors.all { m -> m.base.currentPower == 0.5 })
    }

    @Test fun `invalid native gains stop without sending invalid values to hardware`() = Rig().use {
        val io = it.create()
        io.setMotorPowers(0.5, 0.5, 0.5, 0.5)
        assertFailsWith<IllegalArgumentException> { io.updateMotorGains(Double.NaN, 0.0, 0.0) }
        it.neutral()
        assertEquals(0, it.motors.sumOf { m -> m.writes })
        assertFalse(io.recoverWithNeutral())
    }

    @Test fun `failed mode setup neutralizes all motors without registering half initialized IO`() = Rig().use {
        it.motors.forEach { m -> m.base.power = 0.5 }
        it.motors[1].rejectMode = true
        assertFailsWith<IllegalStateException> { it.create() }
        it.neutral()
        assertTrue(it.registry.getRegisteredMotors().isEmpty())
    }

    @Test fun `missing hardware still neutralizes every resolved motor`() = Rig().use {
        it.motors.forEach { m -> m.base.power = 0.5 }
        it.missing = "fr"
        assertFailsWith<IllegalStateException> { it.create() }
        for (i in listOf(0, 2, 3)) assertEquals(0.0, it.motors[i].base.currentPower)
        assertTrue(it.registry.getRegisteredMotors().isEmpty())
    }

    @Test fun `closed cluster cannot accept new native configuration`() = Rig().use {
        val io = it.create()
        io.close()
        assertFailsWith<IllegalStateException> { io.updateMotorGains(4.0, 5.0, 6.0) }
        assertEquals(0, it.motors.sumOf { m -> m.writes })
        it.neutral()
    }
    @Test fun `explicit F reaches native motors and three argument updates preserve it`() = Rig().use {
        val io = it.create()
        io.updateMotorGains(4.0, 5.0, 6.0, 2.25)
        io.updateMotorGains(7.0, 8.0, 9.0)
        assertTrue(it.motors.all { motor -> motor.coefficients.f == 2.25 && motor.coefficients.p == 7.0 })
        assertEquals(4, it.motors.sumOf { motor -> motor.reads })
    }

    @Test fun `software mode never reads or writes hub gains`() = Rig().use {
        val io = it.create(native = false)
        io.updateMotorGains(4.0, 5.0, 6.0, 7.0)
        assertEquals(0, it.motors.sumOf { motor -> motor.reads + motor.writes })
    }

    @Test fun `coefficient writes happen only after every motor is neutral`() = Rig().use { rig ->
        val io = rig.create()
        io.setMotorPowers(0.5, 0.5, 0.5, 0.5)
        rig.motors.forEach { motor -> motor.beforeWrite = { rig.neutral() } }
        io.updateMotorGains(4.0, 5.0, 6.0)
        rig.neutral()
        assertFalse(io.outputFaultLatched)
    }

    @Test fun `neutral failure prevents PID writes and requires a repaired configuration`() = Rig().use {
        val io = it.create()
        io.setMotorPowers(0.5, 0.5, 0.5, 0.5)
        it.motors[1].base.rejectNextPowerWrite = true
        assertFailsWith<IllegalStateException> { io.updateMotorGains(4.0, 5.0, 6.0) }
        assertEquals(0, it.motors.sumOf { motor -> motor.writes })
        it.neutral()
        assertFalse(io.recoverWithNeutral())
        io.updateMotorGains(4.0, 5.0, 6.0)
        assertTrue(io.recoverWithNeutral())
    }

    @Test fun `partial F failure retains the previous snapshot for repair`() = Rig().use {
        val io = it.create()
        it.motors[2].rejectPid = true
        assertFailsWith<IllegalStateException> { io.updateMotorGains(4.0, 5.0, 6.0, 9.0) }
        it.motors[2].rejectPid = false
        io.updateMotorGains(4.0, 5.0, 6.0)
        assertTrue(it.motors.all { motor -> motor.coefficients.f == 0.7 })
        assertTrue(io.recoverWithNeutral())
    }

    @Test fun `failed coefficient read aborts initialization without registrations`() = Rig().use {
        it.motors.forEach { motor -> motor.base.power = 0.5 }
        it.motors[1].rejectRead = true
        assertFailsWith<IllegalStateException> { it.create() }
        it.neutral()
        assertTrue(it.registry.getRegisteredMotors().isEmpty())
    }

    @Test fun `nonfinite initial coefficients abort initialization`() = Rig().use {
        it.motors[3].coefficients.f = Double.POSITIVE_INFINITY
        assertFailsWith<IllegalArgumentException> { it.create() }
        it.neutral()
        assertTrue(it.registry.getRegisteredMotors().isEmpty())
    }

    @Test fun `invalid constructor gains never reach hardware`() = Rig().use {
        assertFailsWith<IllegalArgumentException> {
            MecanumHardwareIO(it.map, it.registry, useClosedLoopVelocity = true, motorKd = Double.NaN)
        }
        assertEquals(0, it.motors.sumOf { motor -> motor.writes })
        it.neutral()
        assertTrue(it.registry.getRegisteredMotors().isEmpty())
    }

    @Test fun `duplicate motor names abort initialization and stop all resolved channels`() = Rig().use {
        it.motors.forEach { motor -> motor.base.power = 0.5 }
        assertFailsWith<IllegalArgumentException> {
            MecanumHardwareIO(it.map, it.registry, frName = "fl")
        }
        for (i in listOf(0, 2, 3)) assertEquals(0.0, it.motors[i].base.currentPower)
        assertTrue(it.registry.getRegisteredMotors().isEmpty())
    }

    @Test fun `constructor supplied coefficients are retained by subsequent PID tuning`() = Rig().use {
        val io = MecanumHardwareIO(it.map, it.registry, useClosedLoopVelocity = true, motorKp = 2.0, motorKf = 1.5)
        assertTrue(it.motors.all { motor -> motor.coefficients.p == 2.0 && motor.coefficients.i == 0.0 })
        io.updateMotorGains(4.0, 5.0, 6.0)
        assertTrue(it.motors.all { motor -> motor.coefficients.f == 1.5 })
    }

    @Test fun `Redux tuning forwards F and rejected SDK updates leave controller state unchanged`() = Rig().use {
        val io = it.create()
        val store = com.areslib.Store()
        val drive = com.areslib.subsystem.DriveSubsystem(store)
        val facade = com.areslib.subsystem.MecanumDriveFacade(store)
        val controller = MecanumKinematicsController(io, drive, facade,
            com.areslib.ftc.calibration.FtcMecanumCalibrationController())
        val tuning = com.areslib.state.TuningState()
        val accepted = tuning.copy(drive = tuning.drive.copy(ftc = tuning.drive.ftc.copy(
            motorGains = com.areslib.control.tuning.PIDFCoefficients(4.0, 5.0, 6.0, 1.25))))
        controller.updateTuning(accepted)
        assertTrue(it.motors.all { motor -> motor.coefficients.f == 1.25 })
        val previous = controller.kinematics
        val priorKs = io.kS
        val rejected = accepted.copy(drive = accepted.drive.copy(trackWidthMeters = 0.9,
            driveFeedforward = accepted.drive.driveFeedforward.copy(kS = 0.25),
            ftc = accepted.drive.ftc.copy(motorGains = com.areslib.control.tuning.PIDFCoefficients(8.0, 5.0, 6.0, 1.5))))
        it.motors[2].rejectPid = true
        assertFailsWith<IllegalStateException> { controller.updateTuning(rejected) }
        assertSame(previous, controller.kinematics)
        assertEquals(priorKs, io.kS)
        it.neutral()
        it.motors[2].rejectPid = false
        controller.updateTuning(rejected)
        assertEquals(0.25, io.kS)
        assertTrue(it.motors.all { motor -> motor.coefficients.p == 8.0 && motor.coefficients.f == 1.5 })
        assertTrue(io.outputFaultLatched)
        assertTrue(io.recoverWithNeutral())
    }

}
