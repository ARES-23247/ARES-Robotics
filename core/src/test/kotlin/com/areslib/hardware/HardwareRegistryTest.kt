package com.areslib.hardware

import com.areslib.telemetry.ITelemetry
import com.areslib.hardware.actuator.MotorIO
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HardwareRegistryTest {

    @BeforeEach
    fun setUp() {
        HardwareRegistry.clear()
    }

    class MockLoggableDevice : LoggableDevice {
        var logTelemetryCalled = false
        override fun logTelemetry(telemetry: ITelemetry, prefix: String) {
            logTelemetryCalled = true
        }
    }

    class MockMotorIO : MotorIO {
        override val position: Double = 0.0
        override val velocity: Double = 0.0
        override val currentAmps: Double = 0.0
        override var power: Double = 0.0
        override fun resetEncoder() {}
        override fun refresh() {}
        override fun logTelemetry(telemetry: ITelemetry, prefix: String) {}
    }

    class MockSubsystemIO : SubsystemIO {
        var refreshCalled = false
        var safeCalled = false
        override fun refresh() {
            refreshCalled = true
        }
        override fun safe() {
            safeCalled = true
        }
        override fun logTelemetry(telemetry: ITelemetry, prefix: String) {}
    }

    class MockCloseable : AutoCloseable {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    @Test
    fun testRegisterDeviceAndClose() {
        val device = MockLoggableDevice()
        HardwareRegistry.registerDevice("test_device", device)

        val telemetry = object : ITelemetry {
            override fun putNumber(key: String, value: Double) {}
            override fun putBoolean(key: String, value: Boolean) {}
            override fun putString(key: String, value: String) {}
            override fun putDoubleArray(key: String, value: DoubleArray) {}
            override fun getNumber(key: String, defaultValue: Double): Double = defaultValue
            override fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue
            override fun getString(key: String, defaultValue: String): String = defaultValue
        }

        HardwareRegistry.publishAll(telemetry)
        assertTrue(device.logTelemetryCalled)

        HardwareRegistry.clear()
    }

    @Test
    fun testRegisterMotorAndLifeCycle() {
        val motor = MockMotorIO()
        HardwareRegistry.registerMotor("drive_fl", motor)

        val motors = HardwareRegistry.getRegisteredMotors()
        assertEquals(1, motors.size)
        assertSame(motor, motors[0])

        val motorsMap = HardwareRegistry.getRegisteredMotorsWithNames()
        assertEquals(1, motorsMap.size)
        assertSame(motor, motorsMap["drive_fl"])
    }

    @Test
    fun testRegisterCloseable() {
        val closeable = MockCloseable()
        HardwareRegistry.registerCloseable(closeable)

        HardwareRegistry.closeAll()
        assertTrue(closeable.closed)
    }

    @Test
    fun testRefreshAndSafeAll() {
        val subsystem = MockSubsystemIO()
        HardwareRegistry.registerDevice("test_subsystem", subsystem)

        HardwareRegistry.refreshAll()
        assertTrue(subsystem.refreshCalled)

        HardwareRegistry.safeAll()
        assertTrue(subsystem.safeCalled)
    }

    @Test
    fun testBuildTopologyAndJson() {
        val motor = MockMotorIO()
        // Register with FRC CAN topology
        HardwareRegistry.registerMotor("swerve_fl", motor, "rio", 10, 1)

        val topology = HardwareRegistry.buildTopology("ares_frc_robot")
        assertEquals("ares_frc_robot", topology.robotId)
        assertEquals(1, topology.nodes.size)

        val node = topology.nodes[0]
        assertEquals("Motors/swerve_fl", node.id)
        assertEquals("swerve_fl", node.displayName)
        assertEquals(TopologyNodeType.CAN_MOTOR_CONTROLLER, node.type)
        assertEquals(10, node.canId)
        assertEquals("rio", node.canBus)
        assertEquals(1, node.busPosition)

        val json = HardwareRegistry.getTopologyJson("ares_frc_robot")
        assertTrue(json.contains("ares_frc_robot"))
        assertTrue(json.contains("swerve_fl"))
        assertTrue(json.contains("CAN_MOTOR_CONTROLLER"))
    }

    class MockSyncPolledDevice : SyncPolledDevice {
        var pollSyncCount = 0
        override fun pollSync() {
            pollSyncCount++
        }
    }

    @Test
    fun testRoundRobinDevicePolling() {
        val d1 = MockSyncPolledDevice()
        val d2 = MockSyncPolledDevice()
        
        HardwareRegistry.registerRoundRobinDevice(d1)
        HardwareRegistry.registerRoundRobinDevice(d2)

        // Give the background polling thread some time to spin up and poll
        Thread.sleep(150)
        
        HardwareRegistry.closeAll()

        // It should have polled each one at least once depending on timing
        assertTrue(d1.pollSyncCount > 0)
        assertTrue(d2.pollSyncCount > 0)
        // Usually should be roughly equal (differ by at most 1)
        assertTrue(kotlin.math.abs(d1.pollSyncCount - d2.pollSyncCount) <= 1)
    }
}
