package com.ares.analytics.shared.models

import com.areslib.telemetry.schema.HardwareTopology
import kotlinx.serialization.Serializable

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
