package com.areslib.telemetry.schema

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HardwareTopologyBoundaryAuditTest {
    @Test fun `codec rejects ambiguous or blank identities on encode and decode`() {
        val node = TopologyNode("motor", TopologyNodeType.MOTOR, "Motor")
        val invalid = listOf(HardwareTopology("", listOf(node)), HardwareTopology("robot", listOf(node.copy(id = " "))),
            HardwareTopology("robot", listOf(node, node.copy(displayName = "Different motor"))))
        val payloads = listOf(
            """{"robotId":"","nodes":[{"id":"motor","type":"MOTOR","displayName":"Motor"}]}""",
            """{"robotId":"robot","nodes":[{"id":" ","type":"MOTOR","displayName":"Motor"}]}""",
            """{"robotId":"robot","nodes":[{"id":"motor","type":"MOTOR","displayName":"One"},{"id":"motor","type":"SERVO","displayName":"Two"}]}"""
        )
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { HardwareTopologyCodec.encode(it) } }
        payloads.forEach { assertThrows(IllegalArgumentException::class.java) { HardwareTopologyCodec.decode(it) } }
    }

    @Test fun `partial parent trees and every declared hardware category round trip`() {
        val nodes = TopologyNodeType.entries.mapIndexed { i, type ->
            TopologyNode("device/$i", type, "Device $i", parentId = "external hub", port = i,
                canId = i, canBus = "can", busPosition = i, connectionType = "wired",
                metadata = mapOf("escaped" to "value\nwith \"quotes\"", "units" to "meters"))
        }
        val topology = HardwareTopology("Robot", nodes)
        assertEquals(topology, HardwareTopologyCodec.decode(HardwareTopologyCodec.encode(topology)))
        assertEquals(HardwareTopology("empty"), HardwareTopologyCodec.decode("""{"robotId":"empty"}"""))
    }

    @Test fun `schema compatibility admits additive fields but rejects unknown versions and malformed types`() {
        val valid = """{"robotId":"robot","nodes":[{"id":"imu","type":"IMU","displayName":"IMU","future":true}],"future":42}"""
        assertEquals("imu", HardwareTopologyCodec.decode(valid).nodes.single().id)
        assertThrows(IllegalArgumentException::class.java) { HardwareTopologyCodec.encode(HardwareTopology("robot", schemaVersion = 2)) }
        for (bad in listOf("""{"robotId":"robot","schemaVersion":2}""",
            """{"robotId":"robot","nodes":[{"id":"imu","type":"UNKNOWN","displayName":"IMU"}]}""",
            """{"robotId":"robot","nodes":false}""", """{"nodes":[]}""")) {
            assertThrows(IllegalArgumentException::class.java) { HardwareTopologyCodec.decode(bad) }
        }
    }
}
