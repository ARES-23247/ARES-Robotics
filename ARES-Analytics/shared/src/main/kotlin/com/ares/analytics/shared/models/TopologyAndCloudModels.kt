package com.ares.analytics.shared.models

import kotlinx.serialization.Serializable

/** Hardware categories exchanged through the serialized `Topology/HardwareMap` NT4 topic. */
@Serializable
enum class TopologyNodeType {
    CONTROL_HUB, EXPANSION_HUB, SRS_HUB,
    ROBORIO, CANIVORE,
    MOTOR, CAN_MOTOR_CONTROLLER, SERVO,
    CAMERA, ODOMETRY_COMPUTER, IMU,
    COLOR_SENSOR, DISTANCE_SENSOR, BEAM_BREAK, ANALOG_SENSOR,
    CAN_CODER, PIGEON_IMU, POWER_DISTRIBUTION
}

/** A node in the robot hardware tree; absent port/CAN fields are not applicable to that node. */
@Serializable
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

/** Complete hardware tree for one robot. Parent links refer to [TopologyNode.id]. */
@Serializable
data class HardwareTopology(
    val robotId: String,
    val nodes: List<TopologyNode> = emptyList()
)

/** Gateway request for correlating alerts, summary metrics, and the physical hardware tree. */
@Serializable
data class ForensicsRequest(
    val teamId: String,
    val sessionId: String,
    val alerts: List<AlertRecord>,
    val summary: SessionSummary,
    val topology: HardwareTopology? = null,
    val sysIdDrift: Map<String, Double> = emptyMap()
)

@Serializable
data class HardwareFaultLocus(
    val failedNodeId: String,
    val interruptedLinkId: String? = null
)

/** Gateway diagnosis. [confidenceScore] is normalized to the range 0.0 through 1.0. */
@Serializable
data class ForensicsResponse(
    val probableRootCause: String,
    val confidenceScore: Double,
    val cascadingNodesAffected: List<String> = emptyList(),
    val hardwareFaultLocus: HardwareFaultLocus? = null,
    val recommendedActions: List<String> = emptyList()
)

/** Identified feedforward constants and goodness-of-fit for one SysId analysis. */
@Serializable
data class CalculatedSummary(
    val kS: Double = 0.0,
    val kV: Double = 0.0,
    val kA: Double = 0.0,
    val rSquared: Double = 0.0,
    val transientClassification: TransientClassification = TransientClassification.UNKNOWN
)

@Serializable
enum class TransientClassification {
    UNDERDAMPED, OVERDAMPED, CRITICALLY_DAMPED, UNKNOWN
}

/** Driver-input shaping and measured jitter characteristics; frequency is hertz. */
@Serializable
data class DriverProfile(
    val name: String,
    val deadbandExponent: Double = 1.0,
    val slewRateLimit: Double = Double.MAX_VALUE,
    val jitterPeakFrequencyHz: Double = 0.0,
    val jitterAmplitude: Double = 0.0
)
