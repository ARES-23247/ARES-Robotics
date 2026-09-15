package org.aresfirst.starter.frc

import com.areslib.frc.runtime.FrcControllerPortSampler
import com.areslib.input.InputFrame
import com.areslib.state.FieldType
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldDocument
import com.areslib.state.RobotFieldManager
import com.google.gson.JsonParser
import edu.wpi.first.hal.HAL
import edu.wpi.first.networktables.NetworkTableInstance
import edu.wpi.first.networktables.PubSubOption
import edu.wpi.first.wpilibj.simulation.DriverStationSim
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StarterFieldApplyAuditTest {
    private fun payload(revision: Long = 1L, name: String = "Practice") = RobotFieldDocument.encode(
        RobotFieldConfig(id = "practice", name = name, revision = revision, fieldType = FieldType.FRC,
            widthMeters = 5.0, heightMeters = 5.0),
    )

    private inline fun withInstance(block: (NetworkTableInstance) -> Unit) {
        assertTrue(HAL.initialize(500, 0))
        DriverStationSim.resetData()
        val instance = NetworkTableInstance.create()
        try { block(instance) } finally {
            instance.close()
            DriverStationSim.resetData()
            DriverStationSim.notifyNewData()
        }
    }

    @Test
    fun `field application failure is reported without escaping the polling loop`() = withInstance { instance ->
        val bridge = FrcStudioSimulationBridge(instance = instance,
            onFieldApplied = { throw IllegalArgumentException("Field cannot fit chassis") })
        instance.getStringTopic(FrcStudioSimulationBridge.FIELD_CONFIG_TOPIC).publish().use { publisher ->
            try {
                publisher.set(payload())
                assertDoesNotThrow { bridge.updateFieldDocuments() }
                val error = instance.getEntry(FrcStudioSimulationBridge.FIELD_APPLY_ERROR_TOPIC).getString("")
                assertTrue(error.contains("Field cannot fit chassis"))
                assertEquals("", instance.getEntry(FrcStudioSimulationBridge.FIELD_APPLIED_RECEIPT_TOPIC).getString(""))
            } finally { bridge.close() }
        }
    }

    @Test
    fun `identical retry after failed application actually retries the callback before acknowledgement`() = withInstance { instance ->
        var calls = 0
        var installed: StarterFieldContract? = null
        val bridge = FrcStudioSimulationBridge(instance = instance, onFieldApplied = {
            calls++
            if (calls == 1) throw IllegalStateException("Transient apply failure")
            installed = it
        })
        instance.getStringTopic(FrcStudioSimulationBridge.FIELD_CONFIG_TOPIC).publish(PubSubOption.keepDuplicates(true)).use { publisher ->
            try {
                val document = payload()
                publisher.set(document)
                runCatching { bridge.updateFieldDocuments() }
                assertEquals("", instance.getEntry(FrcStudioSimulationBridge.FIELD_APPLIED_RECEIPT_TOPIC).getString(""))
                publisher.set(document)
                bridge.updateFieldDocuments()
                assertEquals(2, calls)
                assertNotNull(installed)
                val receipt = JsonParser.parseString(instance.getEntry(FrcStudioSimulationBridge.FIELD_APPLIED_RECEIPT_TOPIC).getString("")).asJsonObject
                assertEquals(1L, receipt["sequence"].asLong)
                assertEquals(1L, receipt["revision"].asLong)
                assertEquals("", instance.getEntry(FrcStudioSimulationBridge.FIELD_APPLY_ERROR_TOPIC).getString("missing"))
            } finally { bridge.close() }
        }
    }

    @Test
    fun `successfully applied exact retries avoid repeated decoding and resetting simulation`() = withInstance { instance ->
        var decodes = 0
        var applications = 0
        val gate = FrcStudioFieldGate(loader = { decodes++; loadStarterFieldContract(it) })
        val bridge = FrcStudioSimulationBridge(instance = instance, fieldGate = gate, onFieldApplied = { applications++ })
        instance.getStringTopic(FrcStudioSimulationBridge.FIELD_CONFIG_TOPIC).publish(PubSubOption.keepDuplicates(true)).use { publisher ->
            try {
                val document = payload()
                repeat(3) { publisher.set(document); bridge.updateFieldDocuments() }
                assertEquals(1, applications)
                assertEquals(1, decodes)
                val receipt = JsonParser.parseString(instance.getEntry(FrcStudioSimulationBridge.FIELD_APPLIED_RECEIPT_TOPIC).getString("")).asJsonObject
                assertEquals(3L, receipt["sequence"].asLong)
            } finally { bridge.close() }
        }
    }

    @Test
    fun `receipt escapes every JSON control character in field and session identities`() {
        val id = "practice\n\t\r\b\u000c\u0000\\\""
        val session = "session\n\t\u0001\\\""
        val config = RobotFieldConfig(id = id, fieldType = FieldType.FRC)
        val application = requireNotNull(FrcStudioFieldGate().accept(RobotFieldDocument.encode(config)))
        val receipt = encodeFrcStudioFieldReceipt(application, session, 1L)
        assertFalse(receipt.any { it.code < 0x20 }, "Control characters must be escaped inside JSON strings")
        val parsed = JsonParser.parseString(receipt).asJsonObject
        assertEquals(id, parsed["configId"].asString)
        assertEquals(session, parsed["session"].asString)
        assertEquals(application.sha256, parsed["sha256"].asString)
    }

    @Test
    fun `closed bridge disconnects controller sampling without invoking fallback hardware`() = withInstance { instance ->
        var samples = 0
        val fallback = object : FrcControllerPortSampler {
            override fun prepare(port: Int) = Unit
            override fun sampleInto(port: Int, frame: InputFrame, nowNanos: Long) {
                samples++
                frame.beginSample(connected = true, reportedAxisCount = 1, sampleTimeNanos = nowNanos)
                frame.setAxis(0, 1.0)
            }
        }
        val bridge = FrcStudioSimulationBridge(instance = instance, fallbackSampler = fallback)
        try {
            bridge.close()
            val frame = InputFrame()
            bridge.sampleInto(0, frame, 10L)
            assertFalse(frame.isConnected)
            assertEquals(0, samples)
            assertThrows(IllegalStateException::class.java) { bridge.updateFieldDocuments() }
            assertThrows(IllegalStateException::class.java) { bridge.prepare(0) }
            assertFalse(DriverStationSim.getEnabled())
            for (topic in listOf("ARES/Control/DriveInputAck", FrcStudioSimulationBridge.DRIVER_STATION_STATE_TOPIC,
                FrcStudioSimulationBridge.FIELD_APPLIED_RECEIPT_TOPIC, FrcStudioSimulationBridge.FIELD_APPLY_ERROR_TOPIC)) {
                assertFalse(instance.getTopic(topic).exists(), "Bridge still publishes $topic after close")
            }
        } finally { bridge.close() }
    }

    @Test
    fun `rejected geometry preserves simulator field publication and the last successful revision`() {
        val original = RobotFieldManager.activeConfig
        val simulation = StarterDriveSimulation()
        val gate = FrcStudioFieldGate()
        val apply: (StarterFieldContract) -> Unit = { applyStarterSimulationField(simulation, it) }
        try {
            val accepted = requireNotNull(gate.accept(payload(1L), apply))
            val tooSmall = RobotFieldDocument.encode(accepted.contract.config.copy(revision = 10L, widthMeters = 0.5))
            assertNull(gate.accept(tooSmall, apply))
            assertSame(accepted.contract.config, RobotFieldManager.activeConfig)
            assertEquals(1.0, simulation.xMeters, 0.0)
            assertFalse(requireNotNull(gate.accept(payload(1L), apply)).changed)
            // The failed high revision must not block a corrected update based on the active revision.
            val corrected = requireNotNull(gate.accept(payload(2L), apply))
            assertTrue(corrected.changed)
            assertEquals(2L, RobotFieldManager.activeConfig.revision)
            assertNull(gate.rejectionReason)
        } finally { RobotFieldManager.setActiveConfig(original) }
    }

    @Test
    fun `loader exceptions leave the gate reusable and application interruption remains observable`() {
        var failDecode = true
        val gate = FrcStudioFieldGate(loader = {
            if (failDecode) throw IllegalStateException("Decoder unavailable")
            loadStarterFieldContract(it)
        })
        assertNull(gate.accept(payload()))
        assertTrue(gate.rejectionReason!!.contains("Decoder unavailable"))
        failDecode = false
        val wasInterrupted = Thread.interrupted()
        try {
            assertNull(gate.accept(payload()) { throw InterruptedException("Apply interrupted") })
            assertTrue(Thread.currentThread().isInterrupted)
            Thread.interrupted()
            assertTrue(requireNotNull(gate.accept(payload())).changed)
            assertNull(gate.rejectionReason)
        } finally {
            Thread.interrupted()
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    @Test
    fun `a failed field update does not prevent an expired drive lease from disabling simulation`() = withInstance { instance ->
        val gate = FrcStudioDriveFrameGate()
        assertTrue(gate.accept(doubleArrayOf(2.0, 1.0, 1.0, 1000.0, 0.0, 0.0, 0.0, 24.0), 1000L))
        val bridge = FrcStudioSimulationBridge(instance = instance, gate = gate,
            onFieldApplied = { throw IllegalArgumentException("Rejected field") })
        instance.getStringTopic(FrcStudioSimulationBridge.DRIVER_STATION_COMMAND_TOPIC).publish().use { mode ->
            instance.getStringTopic(FrcStudioSimulationBridge.FIELD_CONFIG_TOPIC).publish().use { field ->
                try {
                    mode.set(FrcStudioSimulationBridge.DRIVER_STATION_ENABLE_TELEOP)
                    bridge.update(1001L)
                    assertTrue(DriverStationSim.getEnabled())
                    field.set(payload())
                    bridge.update(1600L)
                    assertFalse(DriverStationSim.getEnabled())
                    assertTrue(instance.getEntry(FrcStudioSimulationBridge.FIELD_APPLY_ERROR_TOPIC).getString("").contains("Rejected field"))
                } finally { bridge.close() }
            }
        }
    }
}
