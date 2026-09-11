package com.areslib.ftc.drivetrain

import com.areslib.ftc.MockDcMotorEx
import com.areslib.hardware.HardwareRegistry
import com.qualcomm.robotcore.hardware.HardwareMap
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterEach
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DriveConfigurationAuditTest {
    @AfterEach fun restoreClock() = com.areslib.util.RobotClock.useSystemTime()
    private fun calculate(c: MecanumDriveFeedforward, target: Double = 1.0, dt: Double = 0.02,
                          volts: Double = 12.0, max: Double = 3.5,
                          speeds: DoubleArray = DoubleArray(4) { target },
                          output: DoubleArray = DoubleArray(4)): DoubleArray {
        c.calculateMotorPowers(speeds, max, volts, dt, false, 2000.0, 0.0, 0.0, 0.0, 0.0, output)
        return output
    }
    private fun neutral(values: DoubleArray) = assertTrue(values.all { it == 0.0 }, values.contentToString())

    @Test fun `invalid feedforward coefficients reject the entire drive request`() {
        for (coefficient in 0..2) {
            val c = MecanumDriveFeedforward().apply { kV = 0.2; kS = 0.1; kA = 0.001 }
            when (coefficient) { 0 -> c.kV = Double.NaN; 1 -> c.kS = Double.NaN; 2 -> c.kA = Double.NaN }
            neutral(calculate(c))
        }
    }

    @Test fun `invalid PID gains cannot leave feedforward energized`() {
        val c = MecanumDriveFeedforward(motorKp = 0.1).apply { kV = 0.2 }
        c.updateMotorGains(Double.NaN, 0.0, 0.0)
        neutral(calculate(c))
    }

    @Test fun `rejected finite PID arithmetic cannot leave feedforward energized`() {
        val c = MecanumDriveFeedforward(motorKp = Double.MAX_VALUE).apply { kV = 0.2 }
        neutral(calculate(c, target = 2.0))
    }

    @Test fun `invalid slew configuration cannot silently remove the limiter`() {
        val c = MecanumDriveFeedforward(initialSlewRateLimit = 1.0).apply { kV = 0.2 }
        for (bad in listOf(Double.NaN, 0.0, -1.0, Double.POSITIVE_INFINITY)) {
            c.slewRateLimit = bad
            neutral(calculate(c))
        }
    }

    @Test fun `invalid dt cannot become a fictitious twenty millisecond update`() {
        val c = MecanumDriveFeedforward().apply { kV = 0.2 }
        for (bad in listOf(0.0, -0.01, Double.NaN, Double.POSITIVE_INFINITY)) neutral(calculate(c, dt = bad))
    }

    @Test fun `one invalid wheel request neutralizes the coupled vector`() {
        val c = MecanumDriveFeedforward().apply { kV = 0.2 }
        neutral(calculate(c, speeds = doubleArrayOf(1.0, Double.NaN, 1.0, 1.0)))
    }

    @Test fun `short output buffers are cleared instead of retaining old effort`() {
        val c = MecanumDriveFeedforward().apply { kV = 0.2 }
        neutral(calculate(c, output = doubleArrayOf(0.5, 0.5)))
    }

    @Test fun `input and output arrays can alias without destroying target speeds`() {
        val c = MecanumDriveFeedforward().apply { kV = 0.2 }
        val shared = doubleArrayOf(1.0, -1.0, 0.5, -0.5)
        val result = calculate(c, speeds = shared, output = shared)
        assertEquals(0.2, result[0], 1e-12)
        assertEquals(-0.2, result[1], 1e-12)
        assertEquals(0.1, result[2], 1e-12)
    }

    @Test fun `rejected supply resets slew history before recovery`() {
        val c = MecanumDriveFeedforward(initialSlewRateLimit = 1.0).apply { kV = 0.8 }
        repeat(10) { calculate(c) }
        neutral(calculate(c, volts = Double.NaN))
        assertEquals(0.02, calculate(c)[0], 1e-12)
    }

    @Test fun `reapplying unchanged slew configuration preserves its accumulated ramp`() {
        val c = MecanumDriveFeedforward(initialSlewRateLimit = 1.0).apply { kV = 0.8 }
        repeat(5) { calculate(c) }
        c.slewRateLimit = 1.0
        assertEquals(0.12, calculate(c)[0], 1e-12)
    }

    @Test fun `public PID property updates change the controller actually used`() {
        val c = MecanumDriveFeedforward(motorKp = 0.1)
        assertEquals(0.1, calculate(c)[0], 1e-12)
        c.motorKp = 0.4
        assertEquals(0.4, calculate(c)[0], 1e-12)
    }

    @Test fun `gain update keeps public configuration synchronized`() {
        val c = MecanumDriveFeedforward(motorKp = 0.1)
        c.updateMotorGains(0.4, 0.2, 0.01)
        assertEquals(0.4, c.motorKp)
        assertEquals(0.2, c.motorKi)
        assertEquals(0.01, c.motorKd)
    }

    @Test fun `integral only feedback is not silently omitted`() {
        val c = MecanumDriveFeedforward(motorKi = 0.5)
        assertTrue(calculate(c)[0] > 0.0)
    }

    @Test fun `finite saturated effort does not overflow voltage compensation into zero`() {
        val c = MecanumDriveFeedforward().apply { kV = Double.MAX_VALUE }
        assertEquals(1.0, calculate(c, volts = 6.0)[0])
    }

    @Test fun `constructor properties forward changes to active configuration`() {
        val c = MecanumDriveFeedforward()
        c.initialKs = 0.2
        assertEquals(0.2, calculate(c)[0], 1e-12)
        c.initialSlewRateLimit = 1.0
        assertEquals(0.02, calculate(c)[0], 1e-12)
    }

    @Test fun `zero acceleration gain needs no unstable derivative for a tiny positive dt`() {
        val c = MecanumDriveFeedforward().apply { kV = 0.2 }
        assertEquals(0.2, calculate(c, dt = Double.MIN_VALUE)[0], 1e-12)
    }

    @Test fun `one overflowing feedforward component neutralizes the whole vector`() {
        val c = MecanumDriveFeedforward().apply { kV = Double.MAX_VALUE }
        neutral(calculate(c, speeds = doubleArrayOf(2.0, 1.0, 1.0, 1.0)))
    }

    @Test fun `valid rate changes retain continuity and extra output slots remain untouched`() {
        val c = MecanumDriveFeedforward(initialSlewRateLimit = 1.0).apply { kV = 0.8 }
        repeat(5) { calculate(c) }
        c.slewRateLimit = 2.0
        val out = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 99.0)
        assertEquals(0.14, calculate(c, output = out)[0], 1e-12)
        assertEquals(99.0, out[4])
    }

    @Test fun `feedback and slew calculation reuses hot loop storage`() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        org.junit.jupiter.api.Assumptions.assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        val allocation = requireNotNull(bean)
        allocation.isThreadAllocatedMemoryEnabled = true
        val c = MecanumDriveFeedforward(motorKp = 0.1, initialSlewRateLimit = 1.0).apply { kV = 0.2 }
        val speeds = doubleArrayOf(1.0, -1.0, 0.5, -0.5)
        val output = DoubleArray(4)
        repeat(30000) { c.calculateMotorPowers(speeds, 3.5, 12.0, 0.02, false, 2000.0, 1.0, -1.0, 1.0, -1.0, output) }
        val thread = Thread.currentThread().id
        val before = allocation.getThreadAllocatedBytes(thread)
        repeat(10000) { c.calculateMotorPowers(speeds, 3.5, 12.0, 0.02, false, 2000.0, 1.0, -1.0, 1.0, -1.0, output) }
        val bytes = allocation.getThreadAllocatedBytes(thread) - before
        assertTrue(bytes <= 4096L, "Allocated $bytes bytes over 10000 samples")
        println("Mecanum feedforward allocation: $bytes bytes over 10000 samples")
    }

    private class Rig(kp: Double? = null) : AutoCloseable {
        val motors = Array(4) { MockDcMotorEx() }
        val registry = HardwareRegistry()
        val io = MecanumHardwareIO(object : HardwareMap() {
            @Suppress("UNCHECKED_CAST")
            override fun <T> get(type: Class<out T>, name: String): T =
                motors[listOf("fl", "fr", "rl", "rr").indexOf(name)] as T
            override fun <T> getAll(type: Class<out T>): List<T> = emptyList()
        }, registry, motorKp = kp).apply { kV = 0.8 }
        fun apply(scale: Double = 1.0) = io.apply(doubleArrayOf(1.0, 1.0, 1.0, 1.0), powerScale = scale)
        fun neutral() = motors.forEach { assertEquals(0.0, it.currentPower) }
        override fun close() = registry.closeAll()
    }

    @Test fun `one invalid motor scale neutralizes and latches the whole cluster`() {
        for (bad in listOf(Double.NaN, -0.5, 1.5, Double.POSITIVE_INFINITY)) Rig().use {
            it.io.frIO.powerScale = bad
            it.io.setMotorPowers(0.5, 0.5, 0.5, 0.5)
            it.neutral()
            assertTrue(it.io.outputFaultLatched)
        }
    }

    @Test fun `out of range master scale is rejected instead of clamped`() = Rig().use {
        it.apply(1.5)
        it.neutral()
        assertTrue(it.io.outputFaultLatched)
    }

    @Test fun `safe resets feedforward ramp before the next command`() = Rig().use {
        it.io.slewRateLimit = 1.0
        repeat(10) { _ -> it.apply() }
        it.io.safe()
        it.neutral()
        it.apply()
        assertEquals(0.02, it.motors[0].currentPower, 1e-12)
    }

    @Test fun `zero power scale cannot wind up feedforward ramp`() = Rig().use {
        it.io.slewRateLimit = 1.0
        repeat(10) { _ -> it.apply(0.0) }
        it.apply(1.0)
        assertEquals(0.02, it.motors[0].currentPower, 1e-12)
    }

    @Test fun `invalid chassis intent cannot be hidden by kinematic normalization`() {
        for (state in listOf(
            com.areslib.state.DriveState(xVelocityMetersPerSecond = Double.NaN),
            com.areslib.state.DriveState(xVelocityMetersPerSecond = Double.MAX_VALUE,
                yVelocityMetersPerSecond = Double.MAX_VALUE))) Rig().use {
            it.io.drive(state, com.areslib.kinematics.MecanumKinematics(0.4, 0.4), 12.0, 0.02)
            it.neutral()
            assertTrue(it.io.outputFaultLatched)
        }
    }

    @Test fun `startup feedback can become ready without an invented permanent fault`() = Rig(0.1).use {
        com.areslib.util.RobotClock.useMockTime(1000L)
        it.io.updateInputs()
        it.apply()
        it.neutral()
        assertTrue(!it.io.outputFaultLatched)
        com.areslib.util.RobotClock.useMockTime(1020L)
        it.io.updateInputs()
        it.apply()
        assertTrue(it.motors.all { motor -> motor.currentPower > 0.0 })
    }

    @Test fun `valid individual derating applies exactly once and invalid global scale stops immediately`() = Rig().use {
        it.io.flIO.powerScale = 0.5
        it.io.frIO.powerScale = 0.25
        it.io.setMotorPowers(0.4, 0.4, 0.4, 0.4)
        assertEquals(0.2, it.motors[0].currentPower, 1e-12)
        assertEquals(0.1, it.motors[1].currentPower, 1e-12)
        assertEquals(0.4, it.io.flIO.power, 1e-12)
        it.io.applyPowerScale(Double.NaN)
        it.neutral()
        assertTrue(it.io.outputFaultLatched)
    }
}
