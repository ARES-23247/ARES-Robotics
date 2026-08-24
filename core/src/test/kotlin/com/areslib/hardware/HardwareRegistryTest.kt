package com.areslib.hardware

import com.areslib.telemetry.ITelemetry
import com.areslib.hardware.actuator.MotorIO
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class HardwareRegistryTest {

    @BeforeEach
    fun setUp() {
        HardwareRegistry.clear()
    }

    class MockLoggableDevice : LoggableDevice {
        var logTelemetryCalled = false
        var telemetryPrefix: String? = null
        override fun logTelemetry(telemetry: ITelemetry, prefix: String) {
            logTelemetryCalled = true
            telemetryPrefix = prefix
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
        var closeCount = 0
        override fun close() {
            closeCount++
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
        assertEquals("Hardware/test_device", device.telemetryPrefix)

        HardwareRegistry.clear()
    }

    @Test
    fun `domain telemetry registration preserves its canonical prefix`() {
        val device = MockLoggableDevice()
        HardwareRegistry.registerTelemetryDevice("Subsystems/elevator", device)
        val publishedNumbers = mutableListOf<Pair<String, Double>>()
        val telemetry = object : ITelemetry {
            override fun putNumber(key: String, value: Double) { publishedNumbers += key to value }
            override fun putBoolean(key: String, value: Boolean) {}
            override fun putString(key: String, value: String) {}
            override fun putDoubleArray(key: String, value: DoubleArray) {}
            override fun getNumber(key: String, defaultValue: Double): Double = defaultValue
            override fun getBoolean(key: String, defaultValue: Boolean): Boolean = defaultValue
            override fun getString(key: String, defaultValue: String): String = defaultValue
        }

        HardwareRegistry.publishAll(telemetry)
        HardwareRegistry.publishAll(telemetry)

        assertEquals("Subsystems/elevator", device.telemetryPrefix)
        assertEquals(
            listOf(
                "Subsystems/elevator/TelemetryHeartbeat" to 1.0,
                "Subsystems/elevator/TelemetryHeartbeat" to 2.0,
            ),
            publishedNumbers,
        )
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
        assertEquals(1, closeable.closeCount)
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

    @Test
    fun `throwing polled device does not stop healthy devices`() {
        val throwing = object : SyncPolledDevice {
            override fun pollSync() = error("sensor unavailable")
        }
        val healthy = MockSyncPolledDevice()
        HardwareRegistry.setPollingIntervalMs(10L)

        HardwareRegistry.registerRoundRobinDevice(throwing)
        HardwareRegistry.registerRoundRobinDevice(healthy)
        Thread.sleep(100L)
        HardwareRegistry.closeAll()

        assertTrue(healthy.pollSyncCount > 0)
    }

    @Test
    fun `blocked old polling generation cannot resurrect after close and re-register`() {
        val enteredOldPoll = CountDownLatch(1)
        val releaseOldPoll = CountDownLatch(1)
        val oldWorker = AtomicReference<Thread>()
        val blocking = object : SyncPolledDevice {
            override fun pollSync() {
                oldWorker.set(Thread.currentThread())
                enteredOldPoll.countDown()
                while (releaseOldPoll.count > 0L) {
                    try {
                        releaseOldPoll.await()
                    } catch (_: InterruptedException) {
                        // Deliberately model a vendor call that ignores interruption.
                    }
                }
            }
        }
        HardwareRegistry.setPollingIntervalMs(10L)
        HardwareRegistry.registerRoundRobinDevice(blocking)
        assertTrue(enteredOldPoll.await(1, TimeUnit.SECONDS))

        // closeAll times out waiting for the deliberately blocked worker, then clears the registry.
        HardwareRegistry.closeAll()

        val newWorkerPolls = AtomicInteger()
        val resurrectedOldWorkerPolls = AtomicInteger()
        val newWorkerObserved = CountDownLatch(1)
        val replacement = object : SyncPolledDevice {
            override fun pollSync() {
                if (Thread.currentThread() === oldWorker.get()) {
                    resurrectedOldWorkerPolls.incrementAndGet()
                } else {
                    newWorkerPolls.incrementAndGet()
                    newWorkerObserved.countDown()
                }
            }
        }
        HardwareRegistry.registerRoundRobinDevice(replacement)
        assertTrue(newWorkerObserved.await(1, TimeUnit.SECONDS))
        releaseOldPoll.countDown()
        Thread.sleep(100L)
        HardwareRegistry.closeAll()

        assertTrue(newWorkerPolls.get() > 0)
        assertEquals(0, resurrectedOldWorkerPolls.get())
    }

    @Test
    fun `same logical name replaces lifecycle entry instead of duplicating it`() {
        val first = MockSubsystemIO()
        val replacement = MockSubsystemIO()
        HardwareRegistry.registerDevice("arm", first)
        HardwareRegistry.registerDevice("arm", replacement)

        HardwareRegistry.refreshAll()

        assertFalse(first.refreshCalled)
        assertTrue(replacement.refreshCalled)
    }

    @Test
    fun `resource registered through both ownership paths closes once`() {
        class CloseableDevice : LoggableDevice, AutoCloseable {
            var closeCount = 0
            override fun logTelemetry(telemetry: ITelemetry, prefix: String) = Unit
            override fun close() { closeCount++ }
        }
        val device = CloseableDevice()
        HardwareRegistry.registerDevice("owned", device)
        HardwareRegistry.registerCloseable(device)

        HardwareRegistry.closeAll()

        assertEquals(1, device.closeCount)
    }

    @Test
    fun `current source registration replaces by name and clear removes cached views`() {
        val first = object : SubsystemIO, CurrentSourceIO { override val currentAmps = 1.0 }
        val replacement = object : SubsystemIO, CurrentSourceIO { override val currentAmps = 2.0 }
        HardwareRegistry.registerDevice("current", first)
        HardwareRegistry.registerDevice("current", replacement)

        assertEquals(1, HardwareRegistry.getRegisteredCurrentSources().size)
        assertSame(replacement, HardwareRegistry.getRegisteredCurrentSources().single())

        HardwareRegistry.clear()
        assertTrue(HardwareRegistry.getRegisteredCurrentSources().isEmpty())
    }

    @Test
    fun `current sampler reads once isolates failures and suppresses covered constituents`() {
        class Source(private val amps: Double) : SubsystemIO, CurrentSourceIO {
            var reads = 0
            override val currentAmps: Double get() { reads++; return amps }
        }
        val constituent = Source(5.0)
        val aggregate = object : SubsystemIO, CurrentSourceIO {
            var reads = 0
            override val currentAmps: Double get() { reads++; return 7.0 }
            override fun includesCurrentFrom(other: CurrentSourceIO): Boolean =
                other === this || other === constituent
        }
        val independent = Source(4.0)
        val throwing = object : SubsystemIO, CurrentSourceIO {
            var reads = 0
            override val currentAmps: Double get() { reads++; error("offline") }
        }

        val sampler = CurrentSourceSampler()
        val total = sampler.sample(listOf(constituent, aggregate, independent, throwing))

        assertEquals(11.0, total, 1e-9)
        assertEquals(1, constituent.reads)
        assertEquals(1, aggregate.reads)
        assertEquals(1, independent.reads)
        assertEquals(1, throwing.reads)
        assertFalse(sampler.hasCompleteCoverage)

        val completeTotal = sampler.sample(listOf(constituent, aggregate, independent))
        assertEquals(11.0, completeTotal, 1e-9)
        assertTrue(sampler.hasCompleteCoverage)
    }
}
