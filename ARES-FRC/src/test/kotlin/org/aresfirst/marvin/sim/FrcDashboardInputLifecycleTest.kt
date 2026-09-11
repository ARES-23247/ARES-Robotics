package org.aresfirst.marvin.sim

import com.areslib.telemetry.schema.DesktopDriveFrameGate
import edu.wpi.first.hal.HAL
import edu.wpi.first.networktables.NetworkTableInstance
import edu.wpi.first.networktables.DoubleArraySubscriber
import edu.wpi.first.wpilibj.simulation.DriverStationSim
import java.lang.reflect.Proxy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FrcDashboardInputLifecycleTest {
    @Test fun `simulated enable requires a fresh frame and close revokes it`() {
        assertTrue(HAL.initialize(500, 0))
        val instance = NetworkTableInstance.create()
        val publisher = instance.getStringTopic("ARES/Simulation/FrcDriverStationCommand").publish()
        val gate = DesktopDriveFrameGate(timeoutMs = 500L)
        val input = FrcDashboardDriveInput(instance = instance, gate = gate)
        try {
            publisher.set(" enable_teleop ")
            assertNull(input.poll(1_000L))
            assertFalse(DriverStationSim.getEnabled())
            assertTrue(gate.observe(doubleArrayOf(2.0, 1.0, 1.0, 1.0, 0.0, 0.0, 0.0, 24.0), 1_001L))
            assertNotNull(input.poll(1_001L))
            assertTrue(DriverStationSim.getEnabled())
            assertNull(input.poll(1_501L))
            assertFalse(DriverStationSim.getEnabled())
            assertTrue(gate.observe(doubleArrayOf(2.0, 1.0, 2.0, 2.0, 0.0, 0.0, 0.0, 24.0), 1_502L))
            input.poll(1_502L)
            assertTrue(DriverStationSim.getEnabled())
            publisher.set("DISABLE")
            input.poll(1_503L)
            assertFalse(DriverStationSim.getEnabled())
            publisher.set("ENABLE_TELEOP")
            input.poll(1_504L)
            assertTrue(DriverStationSim.getEnabled())
            input.close()
            assertFalse(DriverStationSim.getEnabled())
        } finally {
            try { input.close() } finally {
                publisher.close()
                instance.close()
                DriverStationSim.setEnabled(false)
                DriverStationSim.setDsAttached(false)
                DriverStationSim.notifyNewData()
            }
        }
    }

    @Test fun `local NT frames pass through handshake mode filtering and lease expiry`() {
        assertTrue(HAL.initialize(500, 0))
        val instance = NetworkTableInstance.create()
        val publisher = instance.getDoubleArrayTopic("ARES/Input/driveFrame").publish()
        val input = FrcDashboardDriveInput(instance = instance)
        fun frame(sequence: Double, vx: Double = 0.0, flags: Double = 24.0) =
            doubleArrayOf(2.0, 1.0, sequence, sequence, vx, 0.0, 0.0, flags)
        try {
            publisher.set(frame(1.0))
            assertNotNull(input.poll(1_000L))
            publisher.set(frame(2.0, 1.0))
            assertEquals(1.0, input.poll(1_001L)!!.vxMetersPerSecond)
            assertNull(input.poll(1_501L))
            publisher.set(frame(3.0, 1.0))
            assertNull(input.poll(1_502L))
            publisher.set(frame(4.0))
            assertNotNull(input.poll(1_503L))
            publisher.set(frame(5.0, flags = 16.0))
            assertNull(input.poll(1_504L))
        } finally {
            try { input.close() } finally { publisher.close(); instance.close() }
        }
    }

    @Test fun `closed dashboard input cannot return a still leased command`() {
        assertTrue(HAL.initialize(500, 0))
        val instance = NetworkTableInstance.create()
        try {
            val gate = DesktopDriveFrameGate(timeoutMs = 500L)
            assertTrue(gate.observe(doubleArrayOf(2.0, 1.0, 1.0, 1.0, 0.0, 0.0, 0.0, 24.0), 1_000L))
            val input = FrcDashboardDriveInput(instance = instance, gate = gate)
            try {
                assertNotNull(input.poll(1_000L))
                input.close()
                assertNull(input.poll(1_001L))
            } finally { input.close() }
        } finally { instance.close() }
    }

    @Test fun `failed subscriber close still releases both owned publishers`() {
        assertTrue(HAL.initialize(500, 0))
        val instance = NetworkTableInstance.create()
        val failure = IllegalStateException("subscriber close failed")
        var closes = 0
        val subscriber = Proxy.newProxyInstance(
            DoubleArraySubscriber::class.java.classLoader,
            arrayOf(DoubleArraySubscriber::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "close" -> { closes++; throw failure }
                else -> throw UnsupportedOperationException(method.name)
            }
        } as DoubleArraySubscriber
        try {
            val input = FrcDashboardDriveInput(instance = instance, subscriber = subscriber)
            assertTrue(instance.getTopic("ARES/Control/DriveInputAck").exists())
            assertSame(failure, assertThrows(IllegalStateException::class.java) { input.close() })
            assertFalse(instance.getTopic("ARES/Control/DriveInputAck").exists())
            assertFalse(instance.getTopic("ARES/Simulation/FrcDriverStationState").exists())
            assertDoesNotThrow { input.close() }
            assertEquals(1, closes)
        } finally { instance.close() }
    }
}
