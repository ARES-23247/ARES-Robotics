package com.ares.analytics.service

import com.areslib.telemetry.schema.HARDWARE_TOPOLOGY_SCHEMA_VERSION
import com.areslib.telemetry.schema.HardwareTopologyCodec
import com.areslib.telemetry.schema.TopologyNodeType
import kotlin.test.Test
import kotlin.test.assertEquals

class HardwareTopologyWireCompatibilityTest {
    @Test
    fun `Studio decodes the released robot topology golden payload`() {
        val topology = HardwareTopologyCodec.decode(GOLDEN_ROBOT_PAYLOAD)

        assertEquals("Lightbot", topology.robotId)
        assertEquals(HARDWARE_TOPOLOGY_SCHEMA_VERSION, topology.schemaVersion)
        assertEquals(TopologyNodeType.CONTROL_HUB, topology.nodes[0].type)
        assertEquals(TopologyNodeType.MOTOR, topology.nodes[1].type)
        assertEquals("forward", topology.nodes[1].metadata["direction"])
    }

    private companion object {
        const val GOLDEN_ROBOT_PAYLOAD =
            "{\"robotId\":\"Lightbot\",\"nodes\":[" +
                "{\"id\":\"hub\",\"type\":\"CONTROL_HUB\",\"displayName\":\"Control Hub\",\"metadata\":{}}," +
                "{\"id\":\"Motors/fl\",\"type\":\"MOTOR\",\"displayName\":\"Front left\"," +
                "\"parentId\":\"hub\",\"port\":0,\"connectionType\":\"REV\"," +
                "\"metadata\":{\"direction\":\"forward\"}}],\"schemaVersion\":1}"
    }
}
