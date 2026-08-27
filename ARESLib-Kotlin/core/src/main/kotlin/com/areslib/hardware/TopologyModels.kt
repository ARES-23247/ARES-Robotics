package com.areslib.hardware

enum class TopologyNodeType {
    CONTROL_HUB, EXPANSION_HUB, SRS_HUB,
    ROBORIO, CANIVORE,
    MOTOR, CAN_MOTOR_CONTROLLER, SERVO,
    CAMERA, ODOMETRY_COMPUTER, IMU,
    COLOR_SENSOR, DISTANCE_SENSOR, BEAM_BREAK, ANALOG_SENSOR,
    CAN_CODER, PIGEON_IMU, POWER_DISTRIBUTION
}

/**
 * Class implementation for Topology Node.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
data class TopologyNode(
    val id: String,
    val type: TopologyNodeType,
    val displayName: String,
    val parentId: String? = null,
    val port: Int? = null,
    val canId: Int? = null,
    val canBus: String? = null,
    val busPosition: Int? = null,
    val connectionType: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Class implementation for Hardware Topology.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
data class HardwareTopology(
    val robotId: String,
    val nodes: List<TopologyNode> = emptyList()
)
