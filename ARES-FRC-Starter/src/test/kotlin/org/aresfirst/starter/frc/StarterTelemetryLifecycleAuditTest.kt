package org.aresfirst.starter.frc

import edu.wpi.first.networktables.NetworkTableInstance
import edu.wpi.first.networktables.GenericEntry
import java.lang.reflect.Proxy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StarterTelemetryLifecycleAuditTest {
    @Test
    fun `native round trips preserve types defaults normalized keys and array ownership`() {
        NetworkTableInstance.create().use { instance ->
            val telemetry = StarterFrcTelemetry(instance, startDataLog = false)
            try {
                assertEquals(7.0, telemetry.getNumber("missing", 7.0), 0.0)
                assertEquals("missing", telemetry.getString("missing", "missing"))
                assertTrue(telemetry.getBoolean("missing", true))
                telemetry.putNumber("///Drive/Vx", 2.0)
                telemetry.putBoolean("Robot/Enabled", true)
                telemetry.putString("Robot/Mode", "simulation")
                val values = doubleArrayOf(1.0, 2.0, 3.0)
                telemetry.putDoubleArray("Robot/Pose", values)
                values.fill(99.0)
                telemetry.update()
                assertEquals(2.0, telemetry.getNumber("Drive/Vx", -1.0), 0.0)
                assertTrue(telemetry.getBoolean("Robot/Enabled", false))
                assertEquals("simulation", telemetry.getString("Robot/Mode", "missing"))
                assertEquals(-1.0, telemetry.getNumber("Robot/Mode", -1.0), 0.0)
                instance.getDoubleArrayTopic("Robot/Pose").subscribe(doubleArrayOf()).use { subscriber ->
                    assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0), subscriber.get())
                }
            } finally { telemetry.close() }
        }
    }

    @Test
    fun `closing telemetry actually unpublishes owned entries and keeps the native instance usable`() {
        NetworkTableInstance.create().use { instance ->
            val telemetry = StarterFrcTelemetry(instance, startDataLog = false)
            try {
                telemetry.putNumber("owned", 1.0)
                assertTrue(instance.getTopic("owned").exists())
                telemetry.close()
                assertFalse(instance.getTopic("owned").exists(), "NetworkTableEntry.close is a compatibility no-op")
                instance.getDoubleTopic("other-owner").publish().use { other ->
                    other.set(2.0)
                    assertTrue(instance.getTopic("other-owner").exists())
                }
            } finally { telemetry.close() }
        }
    }

    @Test
    fun `separate adapters own separate native publishers even for the same canonical topic`() {
        NetworkTableInstance.create().use { instance ->
            val first = StarterFrcTelemetry(instance, startDataLog = false)
            val second = StarterFrcTelemetry(instance, startDataLog = false)
            try {
                first.putNumber("/shared", 1.0)
                second.putNumber("shared", 2.0)
                first.close()
                assertTrue(instance.getTopic("shared").exists())
                second.putNumber("shared", 3.0)
                assertEquals(3.0, second.getNumber("shared", -1.0), 0.0)
                second.close()
                assertFalse(instance.getTopic("shared").exists())
            } finally { first.close(); second.close() }
        }
    }

    @Test
    fun `closed telemetry cannot silently reacquire entries or flush its former backend`() {
        NetworkTableInstance.create().use { instance ->
            val telemetry = StarterFrcTelemetry(instance, startDataLog = false)
            telemetry.close()
            assertThrows(IllegalStateException::class.java) { telemetry.putNumber("late", 1.0) }
            assertThrows(IllegalStateException::class.java) { telemetry.getNumber("late", 0.0) }
            assertThrows(IllegalStateException::class.java) { telemetry.update() }
            assertFalse(instance.getTopic("late").exists())
        }
    }

    @Test
    fun `canonical spellings reuse one owned generic entry`() {
        NetworkTableInstance.create().use { instance ->
            var created = 0
            val telemetry = StarterFrcTelemetry(instance, startDataLog = false, createEntry = {
                created++
                instance.getTopic(it).getGenericEntry()
            })
            try {
                telemetry.putNumber("///same", 1.0)
                telemetry.putNumber("/same", 2.0)
                assertEquals(2.0, telemetry.getNumber("same", -1.0), 0.0)
                assertEquals(1, created)
            } finally { telemetry.close() }
        }
    }

    @Test
    fun `one entry cleanup failure cannot skip other entries or reopen the adapter`() {
        NetworkTableInstance.create().use { instance ->
            val failure = IllegalStateException("Repeated entry failure")
            var closes = 0
            val telemetry = StarterFrcTelemetry(instance, startDataLog = false, createEntry = {
                Proxy.newProxyInstance(GenericEntry::class.java.classLoader, arrayOf(GenericEntry::class.java)) { _, method, _ ->
                    when (method.name) {
                        "setDouble" -> true
                        "close" -> { closes++; throw failure }
                        else -> throw UnsupportedOperationException(method.name)
                    }
                } as GenericEntry
            })
            telemetry.putNumber("a", 1.0)
            telemetry.putNumber("b", 2.0)
            telemetry.putNumber("c", 3.0)
            assertSame(failure, assertThrows(IllegalStateException::class.java) { telemetry.close() })
            assertEquals(3, closes)
            assertEquals(0, failure.suppressed.size)
            assertDoesNotThrow { telemetry.close() }
            assertThrows(IllegalStateException::class.java) { telemetry.putNumber("d", 4.0) }
        }
    }
}
