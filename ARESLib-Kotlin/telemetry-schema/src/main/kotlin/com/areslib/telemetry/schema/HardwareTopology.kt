package com.areslib.telemetry.schema

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

public const val HARDWARE_TOPOLOGY_TOPIC: String = "Topology/HardwareMap"
public const val HARDWARE_TOPOLOGY_SCHEMA_VERSION: Int = 1

/** Hardware categories exchanged through [HARDWARE_TOPOLOGY_TOPIC]. */
@Serializable
public enum class TopologyNodeType {
    CONTROL_HUB,
    EXPANSION_HUB,
    SRS_HUB,
    ROBORIO,
    CANIVORE,
    MOTOR,
    CAN_MOTOR_CONTROLLER,
    SERVO,
    CAMERA,
    ODOMETRY_COMPUTER,
    IMU,
    COLOR_SENSOR,
    DISTANCE_SENSOR,
    BEAM_BREAK,
    ANALOG_SENSOR,
    CAN_CODER,
    PIGEON_IMU,
    POWER_DISTRIBUTION,
}

/** A node in the physical hardware tree; absent port and CAN fields are not applicable. */
@Serializable
public data class TopologyNode(
    val id: String,
    val type: TopologyNodeType,
    val displayName: String,
    val parentId: String? = null,
    val port: Int? = null,
    val canId: Int? = null,
    val canBus: String? = null,
    val busPosition: Int? = null,
    val connectionType: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

/** Complete versioned hardware tree for one robot. Parent links refer to [TopologyNode.id]. */
@Serializable
public data class HardwareTopology(
    val robotId: String,
    val nodes: List<TopologyNode> = emptyList(),
    val schemaVersion: Int = HARDWARE_TOPOLOGY_SCHEMA_VERSION,
)

/** The canonical JSON boundary shared by robot publishers and desktop consumers. */
public object HardwareTopologyCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    public fun encode(topology: HardwareTopology): String {
        requireSupported(topology)
        return json.encodeToString(topology)
    }

    public fun decode(payload: String): HardwareTopology {
        return json.decodeFromString<HardwareTopology>(payload).also(::requireSupported)
    }

    private fun requireSupported(topology: HardwareTopology) {
        require(topology.schemaVersion == HARDWARE_TOPOLOGY_SCHEMA_VERSION) {
            "Unsupported hardware topology schema ${topology.schemaVersion}; " +
                "expected $HARDWARE_TOPOLOGY_SCHEMA_VERSION"
        }
    }
}
