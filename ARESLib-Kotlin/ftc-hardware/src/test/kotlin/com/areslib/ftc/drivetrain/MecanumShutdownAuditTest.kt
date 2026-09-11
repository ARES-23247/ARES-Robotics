package com.areslib.ftc.drivetrain

import com.areslib.ftc.MockDcMotorEx
import com.areslib.hardware.HardwareRegistry
import com.qualcomm.robotcore.hardware.HardwareMap
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MecanumShutdownAuditTest {
    private class Rig : AutoCloseable {
        val motors = Array(4) { MockDcMotorEx() }
        val registry = HardwareRegistry()
        val map = object : HardwareMap() {
            @Suppress("UNCHECKED_CAST")
            override fun <T> get(type: Class<out T>, name: String): T =
                motors[listOf("fl", "fr", "rl", "rr").indexOf(name)] as T
            override fun <T> getAll(type: Class<out T>): List<T> = emptyList()
        }
        val io = MecanumHardwareIO(map, registry).apply { kV = 0.2 }
        fun energize() = io.setMotorPowers(0.5, 0.5, 0.5, 0.5)
        fun assertNeutral() = motors.forEach { assertEquals(0.0, it.currentPower) }
        override fun close() {
            motors.forEach { it.rejectPowerWrites = false }
            io.safe()
            registry.closeAll()
        }
    }

    @Test fun `direct facade close neutralizes every energized motor`() = Rig().use {
        it.energize()
        it.io.close()
        it.assertNeutral()
        assertTrue(it.io.outputFaultLatched)
    }

    @Test fun `registry shutdown also neutralizes the drive`() = Rig().use {
        it.energize()
        it.registry.closeAll()
        it.assertNeutral()
    }

    @Test fun `confirmed shutdown is idempotent`() = Rig().use {
        it.energize()
        it.io.close()
        it.motors.forEach { motor -> motor.rejectPowerWrites = true }
        it.io.close()
        it.assertNeutral()
    }

    @Test fun `closed drive cannot be revived by recovery or raw commands`() = Rig().use {
        it.io.close()
        assertFalse(it.io.recoverWithNeutral())
        it.energize()
        it.assertNeutral()
        assertTrue(it.io.outputFaultLatched)
    }

    @Test fun `closed drive cannot be revived by feedforward output`() = Rig().use {
        it.io.close()
        it.io.applyPowerScale(1.0)
        it.io.apply(doubleArrayOf(1.0, 1.0, 1.0, 1.0))
        it.assertNeutral()
        assertEquals(0.0, it.io.flIO.power)
    }

    @Test fun `failed close stops other motors reports failure and remains terminal`() = Rig().use {
        it.energize()
        it.motors[1].rejectPowerWrites = true
        assertFailsWith<IllegalStateException> { it.io.close() }
        for (i in listOf(0, 2, 3)) assertEquals(0.0, it.motors[i].currentPower)
        assertTrue(it.io.outputFaultLatched)
        assertTrue(it.io.flIO.position.isNaN())
        it.motors[1].rejectPowerWrites = false
        it.io.close()
        it.assertNeutral()
        assertFalse(it.io.recoverWithNeutral())
        it.energize()
        it.assertNeutral()
    }
}
