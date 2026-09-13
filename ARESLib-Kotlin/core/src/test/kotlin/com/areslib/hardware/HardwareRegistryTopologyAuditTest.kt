package com.areslib.hardware

import com.areslib.hardware.actuator.ServoIO
import com.areslib.telemetry.schema.HardwareTopologyCodec
import com.areslib.telemetry.schema.TopologyNode
import com.areslib.telemetry.schema.TopologyNodeType
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HardwareRegistryTopologyAuditTest {
    @Test
    fun bareReplacementDropsOnlyTheReplacedDevicesAddress() = withRegistry { registry ->
        val motor = HardwareRegistryTest.MockMotorIO()
        registry.registerMotor("arm", motor, canBus = "canivore", canId = 7)
        registry.registerMotor("alias", motor, parentHub = "hub", port = 2)
        registry.registerMotor("arm", motor)
        assertEquals(2, registry.buildTopology("robot").nodes.size, "Same-object registration preserves its address")
        registry.registerMotor("arm", HardwareRegistryTest.MockMotorIO())
        assertEquals(listOf("Motors/alias"), registry.buildTopology("robot").nodes.map { it.id })
        registry.registerTelemetryDevice("Motors/alias", object : LoggableDevice {})
        assertTrue(registry.buildTopology("robot").nodes.isEmpty())
    }

    @Test
    fun invalidIdentitiesCannotReplaceHardwareOrPoisonExport() = withRegistry { registry ->
        val original = HardwareRegistryTest.MockMotorIO()
        registry.registerMotor("arm", original, canBus = "rio", canId = 4)
        registry.registerDevice("external", object : LoggableDevice {}, node("external-id"))
        for (invalid in listOf("", " \t", "external-id")) {
            assertThrows(IllegalArgumentException::class.java) {
                registry.registerDevice("Motors/arm", HardwareRegistryTest.MockMotorIO(), node(invalid))
            }
            assertSame(original, registry.getRegisteredMotorsWithNames()["arm"])
            assertEquals(4, registry.buildTopology("robot").nodes.first { it.id == "Motors/arm" }.canId)
            assertEquals(2, HardwareTopologyCodec.decode(registry.getTopologyJson("robot")).nodes.size)
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.registerDevice(" ", original, node("valid-id"))
        }
    }

    @Test
    fun explicitIdsRemainIndependentOfLogicalNamesAndCanBeReusedAfterReplacement() = withRegistry { registry ->
        val first = object : LoggableDevice {}
        registry.registerDevice("logical", first, node("physical"))
        registry.registerDevice("logical", first, node("renamed"))
        registry.registerDevice("second", object : LoggableDevice {}, node("physical"))
        assertEquals(listOf("physical", "renamed"), registry.buildTopology("robot").nodes.map { it.id })
        registry.registerDevice("logical", object : LoggableDevice {})
        registry.registerDevice("third", object : LoggableDevice {}, node("renamed"))
        assertEquals(listOf("physical", "renamed"), HardwareTopologyCodec.decode(registry.getTopologyJson("robot")).nodes.map { it.id })
    }

    @Test
    fun metadataIsOwnedAtRegistrationAndExportCannotMutateTheRegistry() = withRegistry { registry ->
        val metadata = linkedMapOf("controller" to "original", "address" to "7")
        registry.registerDevice("one", object : LoggableDevice {}, node("one").copy(metadata = metadata))
        registry.registerDevice("two", object : LoggableDevice {}, node("two"))
        val snapshot = registry.buildTopology("robot")
        val before = HardwareTopologyCodec.encode(snapshot)
        metadata["controller"] = "mutated"
        metadata.clear()
        assertEquals(before, registry.getTopologyJson("robot"))
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.nodes.first().metadata as MutableMap<String, String>)["address"] = "99"
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.nodes as MutableList<TopologyNode>).clear()
        }
        assertEquals(before, HardwareTopologyCodec.encode(snapshot))
    }

    @Test
    fun retainedSnapshotsSurviveReplacementAndClose() = withRegistry { registry ->
        registry.registerDevice("one", object : LoggableDevice {}, node("one"))
        val first = registry.buildTopology("first-robot")
        registry.registerDevice("one", object : LoggableDevice {}, node("two"))
        val second = registry.buildTopology("second-robot")
        registry.clear()
        assertEquals(listOf("one"), first.nodes.map { it.id })
        assertEquals("first-robot", first.robotId)
        assertEquals(listOf("two"), second.nodes.map { it.id })
        assertEquals("second-robot", second.robotId)
        assertTrue(registry.buildTopology("third-robot").nodes.isEmpty())
    }

    @Test
    fun allOverloadsPreserveAddressesOrderingAndPartialParents() = withRegistry { registry ->
        registry.registerServo("claw", object : ServoIO { override var position = 0.0 }, "missing-hub", 3)
        registry.registerMotor("wheel", HardwareRegistryTest.MockMotorIO(), canBus = "canivore", canId = 8, busPosition = 2)
        registry.registerMotor("arm", HardwareRegistryTest.MockMotorIO(), parentHub = "missing-hub", port = 1)
        registry.registerDevice("Sensors/Gyro", object : LoggableDevice {}, "rio", 9, 4)
        val nodes = HardwareTopologyCodec.decode(registry.getTopologyJson("robot")).nodes
        assertEquals(listOf("Motors/arm", "Motors/wheel", "Sensors/Gyro", "Servos/claw"), nodes.map { it.id })
        assertEquals(TopologyNode("Motors/arm", TopologyNodeType.MOTOR, "arm", parentId = "missing-hub", port = 1), nodes[0])
        assertEquals(TopologyNode("Motors/wheel", TopologyNodeType.CAN_MOTOR_CONTROLLER, "wheel", canId = 8, canBus = "canivore", busPosition = 2), nodes[1])
        assertEquals(TopologyNode("Sensors/Gyro", TopologyNodeType.IMU, "Gyro", canId = 9, canBus = "rio", busPosition = 4), nodes[2])
        assertEquals(TopologyNode("Servos/claw", TopologyNodeType.SERVO, "claw", parentId = "missing-hub", port = 3), nodes[3])
    }

    @Test
    fun metadataCopyFailureLeavesPriorRegistrationIntact() = withRegistry { registry ->
        val original = HardwareRegistryTest.MockMotorIO()
        registry.registerMotor("arm", original, canBus = "rio", canId = 1)
        val before = registry.getTopologyJson("robot")
        val failure = IllegalStateException("metadata unavailable")
        val broken = object : AbstractMap<String, String>() {
            override val entries: Set<Map.Entry<String, String>> get() = throw failure
        }
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            registry.registerDevice("Motors/arm", HardwareRegistryTest.MockMotorIO(), node("arm").copy(metadata = broken))
        })
        assertSame(original, registry.getRegisteredMotorsWithNames()["arm"])
        assertEquals(before, registry.getTopologyJson("robot"))
    }

    @Test
    fun topologyReadersRetainCompletedSnapshotDuringMetadataAcquisition() = withRegistry { registry ->
        val original = HardwareRegistryTest.MockMotorIO()
        registry.registerMotor("arm", original, canBus = "rio", canId = 1)
        val before = registry.getTopologyJson("robot")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val replacement = HardwareRegistryTest.MockMotorIO()
        val metadata = object : AbstractMap<String, String>() {
            override val entries: Set<Map.Entry<String, String>> get() {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "metadata fixture timed out" }
                return mapOf("address" to "2").entries
            }
        }
        val worker = Thread {
            try {
                registry.registerDevice("Motors/arm", replacement, node("Motors/arm").copy(metadata = metadata))
            } catch (error: Throwable) { failure.set(error) }
        }
        worker.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS), "Registration must take ownership of caller metadata")
            assertEquals(before, registry.getTopologyJson("robot"))
            assertSame(original, registry.getRegisteredMotorsWithNames()["arm"])
        } finally {
            release.countDown()
            worker.join(6000)
            assertFalse(worker.isAlive)
        }
        failure.get()?.let { throw it }
        assertSame(replacement, registry.getRegisteredMotorsWithNames()["arm"])
        assertEquals(mapOf("address" to "2"), registry.buildTopology("robot").nodes.single().metadata)
    }

    @Test
    fun repeatedTopologyReadsAvoidResortingAndCopyingEveryNode() = withRegistry { registry ->
        repeat(128) { registry.registerDevice("device-$it", object : LoggableDevice {}, node("device-$it")) }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().id
        var count = 0L
        repeat(20_000) { observed = registry.buildTopology("robot"); count += observed!!.nodes.size }
        val before = bean.getThreadAllocatedBytes(threadId)
        repeat(10_000) { observed = registry.buildTopology("robot"); count += observed!!.nodes.size }
        val allocated = bean.getThreadAllocatedBytes(threadId) - before
        assertEquals(3_840_000L, count)
        println("Registry topology: $allocated bytes / 10,000 snapshots of 128 nodes (desktop JVM)")
        assertTrue(allocated <= 400_000L, "Repeated topology reads allocated $allocated bytes")
        observed = null
    }

    private fun node(id: String) = TopologyNode(id, TopologyNodeType.ANALOG_SENSOR, id)

    private inline fun withRegistry(block: (HardwareRegistry) -> Unit) {
        val registry = HardwareRegistry()
        try { block(registry) } finally { registry.closeAll(); observed = null }
    }

    private companion object {
        @Volatile var observed: com.areslib.telemetry.schema.HardwareTopology? = null
    }
}
