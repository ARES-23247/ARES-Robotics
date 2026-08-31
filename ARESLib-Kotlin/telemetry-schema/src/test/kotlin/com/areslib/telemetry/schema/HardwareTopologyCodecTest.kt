package com.areslib.telemetry.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HardwareTopologyCodecTest {
    private val expected = HardwareTopology(
        robotId = "Lightbot",
        nodes = listOf(
            TopologyNode(
                id = "hub",
                type = TopologyNodeType.CONTROL_HUB,
                displayName = "Control Hub",
            ),
            TopologyNode(
                id = "Motors/fl",
                type = TopologyNodeType.MOTOR,
                displayName = "Front left",
                parentId = "hub",
                port = 0,
                connectionType = "REV",
                metadata = mapOf("direction" to "forward"),
            ),
        ),
    )

    @Test
    fun `canonical producer payload matches the version one golden document`() {
        val payload = HardwareTopologyCodec.encode(expected)

        assertEquals(GOLDEN_PAYLOAD, payload)
        assertEquals(expected, HardwareTopologyCodec.decode(payload))
    }

    @Test
    fun `consumer accepts unknown additive fields`() {
        val payload = GOLDEN_PAYLOAD.dropLast(1) + ",\"futureField\":true}"

        assertEquals(expected, HardwareTopologyCodec.decode(payload))
    }

    @Test
    fun `consumer rejects an unsupported schema version`() {
        val payload = GOLDEN_PAYLOAD.replace("\"schemaVersion\":1", "\"schemaVersion\":2")

        assertFailsWith<IllegalArgumentException> { HardwareTopologyCodec.decode(payload) }
    }

    private companion object {
        const val GOLDEN_PAYLOAD =
            "{\"robotId\":\"Lightbot\",\"nodes\":[" +
                "{\"id\":\"hub\",\"type\":\"CONTROL_HUB\",\"displayName\":\"Control Hub\",\"metadata\":{}}," +
                "{\"id\":\"Motors/fl\",\"type\":\"MOTOR\",\"displayName\":\"Front left\"," +
                "\"parentId\":\"hub\",\"port\":0,\"connectionType\":\"REV\"," +
                "\"metadata\":{\"direction\":\"forward\"}}],\"schemaVersion\":1}"
    }
}
