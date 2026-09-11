package com.areslib.sim.model

import org.junit.Test
import org.junit.Assert.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp

class MechanismIntegrationAuditTest {
    @Test fun `flywheel matches the analytic damped motor response across a long step`() {
        val sim = FlywheelSim()
        val damping = sim.kt * sim.ke / sim.resistance + sim.frictionCoeff
        val equilibrium = (sim.kt * 6.0 / sim.resistance) / damping
        val expectedRpm = equilibrium * (1.0 - exp(-damping / sim.momentOfInertia)) * 60.0 / (2.0 * PI)
        sim.update(6.0, 1.0)
        assertEquals(expectedRpm, sim.velocityRpm, 1e-9)
    }

    @Test fun `default pivot does not numerically reverse under constant positive voltage at 20ms`() {
        val sim = IntakePivotSim()
        var previous = 0.0
        repeat(20) {
            sim.update(6.0, 0.02)
            assertTrue("Unexpected reversal from $previous to ${sim.angleDegrees}", sim.angleDegrees >= previous)
            previous = sim.angleDegrees
        }
        assertTrue(sim.angleDegrees > 70.0)
    }

    @Test fun `pivot agrees with an independent fine RK4 torque integration`() {
        // Independently expanded default motor/gravity equation, before reaching the upper stop.
        fun acceleration(angle: Double, velocity: Double) =
            (6.0 * 0.02 * 80.0 / 0.06 - 3.5 * 9.80665 * 0.35 * cos(angle) -
                (0.02 * 0.018 * 80.0 * 80.0 / 0.06 + 0.05) * velocity) / 0.15
        var angle = 0.0
        var velocity = 0.0
        val h = 0.00001
        repeat(40_000) {
            val a1 = acceleration(angle, velocity)
            val v2 = velocity + h * a1 / 2
            val a2 = acceleration(angle + h * velocity / 2, v2)
            val v3 = velocity + h * a2 / 2
            val a3 = acceleration(angle + h * v2 / 2, v3)
            val v4 = velocity + h * a3
            val a4 = acceleration(angle + h * v3, v4)
            angle += h * (velocity + 2 * v2 + 2 * v3 + v4) / 6
            velocity += h * (a1 + 2 * a2 + 2 * a3 + a4) / 6
        }
        val sim = IntakePivotSim()
        repeat(20) { sim.update(6.0, 0.02) }
        assertEquals(angle * 180.0 / PI, sim.angleDegrees, 0.05)
        assertEquals(velocity * 180.0 / PI, sim.velocityDegreesPerSec, 0.1)
    }

    @Test fun `invalid time and voltage reject without corrupting model state`() {
        val flywheel = FlywheelSim()
        flywheel.update(6.0, 0.02)
        val rpm = flywheel.velocityRpm
        assertThrows(IllegalArgumentException::class.java) { flywheel.update(Double.NaN, 0.02) }
        assertEquals(rpm, flywheel.velocityRpm, 0.0)
        val pivot = IntakePivotSim()
        assertThrows(IllegalArgumentException::class.java) { pivot.update(6.0, -0.02) }
        assertEquals(0.0, pivot.angleDegrees, 0.0)
    }

    @Test fun `invalid physical parameters reject at construction`() {
        assertThrows(IllegalArgumentException::class.java) { FlywheelSim(momentOfInertia = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { IntakePivotSim(resistance = -1.0) }
    }

    @Test fun `flywheel partitions time exactly and brakes at zero`() {
        val single = FlywheelSim()
        val split = FlywheelSim()
        single.update(9.0, 2.0)
        repeat(100) { split.update(9.0, 0.02) }
        assertEquals(single.velocityRpm, split.velocityRpm, 1e-9)
        split.update(-12.0, 2.0)
        assertEquals(0.0, split.velocityRpm, 0.0)
        assertEquals(240.0, split.getCurrentAmps(-12.0), 1e-10)
        single.reset()
        assertEquals(0.0, single.velocityRpm, 0.0)
    }

    @Test fun `zero damping flywheel has constant acceleration`() {
        val sim = FlywheelSim(ke = 0.0, frictionCoeff = 0.0)
        sim.update(6.0, 0.4)
        assertEquals((6.0 * sim.kt / sim.resistance / sim.momentOfInertia) * 0.4 * 60.0 / (2 * PI),
            sim.velocityRpm, 1e-9)
    }

    @Test fun `pivot handles a hitch and both physical stops`() {
        val single = IntakePivotSim()
        val split = IntakePivotSim()
        single.update(6.0, 0.4)
        repeat(20) { split.update(6.0, 0.02) }
        assertEquals(single.angleDegrees, split.angleDegrees, 1e-10)
        assertEquals(single.velocityDegreesPerSec, split.velocityDegreesPerSec, 1e-10)
        single.update(12.0, 1.0)
        assertEquals(120.0, single.angleDegrees, 1e-12)
        assertEquals(0.0, single.velocityDegreesPerSec, 0.0)
        single.update(-12.0, 1.0)
        assertEquals(0.0, single.angleDegrees, 0.0)
        assertEquals(0.0, single.velocityDegreesPerSec, 0.0)
        single.reset(60.0)
        single.update(0.0, 0.02)
        assertTrue(single.angleDegrees < 60.0)
        assertTrue(single.velocityDegreesPerSec < 0.0)
    }

    @Test fun `gravity free pivot matches exact damped position and small damping limit`() {
        val sim = IntakePivotSim(armMassKg = 0.0)
        val damping = (sim.kt * 0.018 * sim.gearRatio * sim.gearRatio / sim.resistance + sim.frictionCoeff) /
            sim.momentOfInertia
        val equilibrium = (6.0 * sim.kt * sim.gearRatio / sim.resistance / sim.momentOfInertia) / damping
        sim.update(6.0, 0.1)
        assertEquals(equilibrium * (0.1 - (1.0 - exp(-damping * 0.1)) / damping) * 180.0 / PI,
            sim.angleDegrees, 1e-10)
        assertEquals(equilibrium * (1.0 - exp(-damping * 0.1)) * 180.0 / PI,
            sim.velocityDegreesPerSec, 1e-10)
        val tiny = IntakePivotSim(armMassKg = 0.0, momentOfInertia = 1.0, kt = 1.0,
            resistance = 1.0, gearRatio = 1e-8, frictionCoeff = 0.0)
        tiny.update(6.0, 0.02)
        assertEquals(0.5 * 6e-8 * 0.02 * 0.02 * 180.0 / PI, tiny.angleDegrees, 1e-20)
        assertEquals(6e-8 * 0.02 * 180.0 / PI, tiny.velocityDegreesPerSec, 1e-20)
    }

    @Test fun `invalid updates and reset are atomic including work budget and overflow`() {
        val flywheel = FlywheelSim()
        val pivot = IntakePivotSim()
        flywheel.update(6.0, 0.02)
        pivot.update(6.0, 0.02)
        val rpm = flywheel.velocityRpm
        val angle = pivot.angleDegrees
        val velocity = pivot.velocityDegreesPerSec
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { flywheel.update(invalid, 0.02) }
            assertThrows(IllegalArgumentException::class.java) { pivot.update(invalid, 0.02) }
            assertThrows(IllegalArgumentException::class.java) { flywheel.getCurrentAmps(invalid) }
        }
        for (invalid in listOf(-0.01, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { flywheel.update(6.0, invalid) }
            assertThrows(IllegalArgumentException::class.java) { pivot.update(6.0, invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) { pivot.update(6.0, 50.00001) }
        assertThrows(IllegalArgumentException::class.java) { pivot.update(Double.MAX_VALUE, 0.02) }
        assertThrows(IllegalArgumentException::class.java) { flywheel.update(Double.MAX_VALUE, 0.02) }
        for (invalid in listOf(-1.0, 121.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { pivot.reset(invalid) }
        }
        flywheel.update(6.0, 0.0)
        pivot.update(6.0, 0.0)
        assertEquals(rpm, flywheel.velocityRpm, 0.0)
        assertEquals(angle, pivot.angleDegrees, 0.0)
        assertEquals(velocity, pivot.velocityDegreesPerSec, 0.0)
    }

    @Test fun `all model parameters reject nonphysical or unrepresentable values`() {
        val factories = listOf<() -> Any>(
            { FlywheelSim(kt = -1.0) }, { FlywheelSim(ke = -1.0) },
            { FlywheelSim(resistance = Double.NaN) }, { FlywheelSim(frictionCoeff = -1.0) },
            { FlywheelSim(momentOfInertia = Double.MIN_VALUE) },
            { IntakePivotSim(armMassKg = -1.0) }, { IntakePivotSim(lengthToComMeters = Double.NaN) },
            { IntakePivotSim(momentOfInertia = 0.0) }, { IntakePivotSim(kt = 0.0) },
            { IntakePivotSim(gearRatio = -1.0) }, { IntakePivotSim(frictionCoeff = Double.POSITIVE_INFINITY) },
            { IntakePivotSim(gearRatio = Double.MAX_VALUE) },
        )
        factories.forEach { create -> assertThrows(IllegalArgumentException::class.java) { create() } }
    }

    @Test fun `steady model loops avoid per update allocation`() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        org.junit.Assume.assumeTrue(bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!.isThreadAllocatedMemoryEnabled = true
        val flywheel = FlywheelSim()
        val pivot = IntakePivotSim()
        repeat(20_000) { flywheel.update(6.0, 0.02); pivot.update(if (it % 200 < 100) 6.0 else -6.0, 0.02) }
        val id = Thread.currentThread().id
        val startBytes = bean.getThreadAllocatedBytes(id)
        val startNanos = System.nanoTime()
        repeat(100_000) { flywheel.update(6.0, 0.02); pivot.update(if (it % 200 < 100) 6.0 else -6.0, 0.02) }
        val elapsed = System.nanoTime() - startNanos
        val bytes = bean.getThreadAllocatedBytes(id) - startBytes
        println("Mechanism pair: $bytes bytes / 100000 updates, ${elapsed / 100000.0} ns/update on this host")
        assertTrue("Unexpected steady-state allocation: $bytes bytes", bytes <= 4096L)
    }
}
