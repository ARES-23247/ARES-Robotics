package org.aresfirst.marvin

import com.areslib.hardware.HardwareRegistry
import com.areslib.telemetry.ITelemetry
import com.areslib.hardware.LoggableDevice
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import edu.wpi.first.hal.HAL

class FrcHardwareHandoffTest {
    private class Device : LoggableDevice, AutoCloseable {
        var closes = 0
        override fun logTelemetry(telemetry: ITelemetry, prefix: String) = Unit
        override fun close() { closes++ }
    }

    @Test fun `failed composition closes retained IO even before normal device registration`() {
        val registry = HardwareRegistry()
        val mechanism = Device()
        val drivetrain = Device()
        val vision = Device()
        registry.retainFrcHardware(mechanism, drivetrain, vision)
        try {
            registry.registerDevice("mechanism", mechanism)
            assertThrows(IllegalArgumentException::class.java) { registry.registerDevice("", drivetrain) }
        } finally { registry.closeAll() }
        assertEquals(1, mechanism.closes)
        assertEquals(1, drivetrain.closes)
        assertEquals(1, vision.closes)
    }

    @Test fun `successful registration and repeated teardown never double close shared IO`() {
        val registry = HardwareRegistry()
        val first = Device()
        val second = Device()
        registry.retainFrcHardware(first, second, first, null, Any())
        registry.registerDevice("first", first)
        registry.registerDevice("second", second)
        registry.closeAll()
        registry.closeAll()
        assertEquals(1, first.closes)
        assertEquals(1, second.closes)
    }

    @Test fun `initialization failure tears down retained owners and preserves both failures`() {
        val registry = HardwareRegistry()
        val device = Device()
        val original = IllegalStateException("initialization")
        val cleanupFailure = AssertionError("cleanup")
        val actual = assertThrows(IllegalStateException::class.java) {
            initializeFrcRobot(
                initialize = { registry.retainFrcHardware(device); throw original },
                cleanup = { registry.closeAll(); throw cleanupFailure },
            )
        }
        assertSame(original, actual)
        assertEquals(listOf(cleanupFailure), original.suppressed.toList())
        assertEquals(1, device.closes)
    }

    @Test fun `successful initialization leaves cleanup to normal lifecycle`() {
        var initialized = false
        var closed = false
        initializeFrcRobot(initialize = { initialized = true }, cleanup = { closed = true })
        assertTrue(initialized)
        assertFalse(closed)
    }

    @Test fun `partial season robot teardown tolerates subsequent framework cleanup`() {
        assertTrue(HAL.initialize(500, 0))
        val robot = ARESRobot()
        robot.close()
        assertDoesNotThrow { robot.close() }
    }
}
