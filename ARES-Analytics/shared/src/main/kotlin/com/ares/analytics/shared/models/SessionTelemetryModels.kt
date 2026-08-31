package com.ares.analytics.shared.models

import kotlinx.serialization.Serializable

/** Latest supported instant (2100-01-01 UTC), also suitable for monotonic robot timelines. */
const val MAX_SUPPORTED_TIMESTAMP_MS: Long = 4_102_444_800_000L

fun timestampMillisToMicros(timestampMs: Long): Long {
    require(timestampMs in 0L..MAX_SUPPORTED_TIMESTAMP_MS) { "timestampMs is outside the supported domain" }
    return Math.multiplyExact(timestampMs, 1_000L)
}

/** Selects whether dashboard frames come from the robot, the rewind buffer, or persisted storage. */
@Serializable
enum class SessionMode {
    LIVE_STREAMING,
    LIVE_REWIND,
    HISTORICAL_REPLAY
}

/** Canonical tag applied to Studio-recorded simulator sessions. */
const val SIMULATION_SESSION_TAG: String = "simulation"

/**
 * External integrations are opt-in for simulation evidence. Simulation runs remain fully usable
 * for local analysis, replay, comparison, and reports, but must not automatically notify team or
 * publishing destinations.
 */
fun Iterable<String>.isSimulationSessionTags(): Boolean =
    any { it.equals(SIMULATION_SESSION_TAG, ignoreCase = true) }

/** Persisted recording identity. [createdAt] and [durationMs] are milliseconds. */
@Serializable
data class Session(
    val sessionId: String,
    val teamId: String,
    val seasonId: String,
    val robotId: String,
    val createdAt: Long,
    val durationMs: Long = 0L,
    val tags: List<String> = emptyList(),
    val matchNumber: Int? = null,
    val allianceColor: String? = null
)

fun Session.allowsAutomaticExternalUpdates(): Boolean = !tags.isSimulationSessionTags()

/**
 * Precomputed session metrics used by history and cloud indexes.
 * Voltage is volts, drift/cross-track error are meters, temperatures are Celsius,
 * current is amperes, and every `*Ms` field is milliseconds.
 */
@Serializable
data class SessionSummary(
    val sessionId: String,
    val teamId: String,
    val seasonId: String,
    val robotId: String,
    val createdAt: Long,
    val durationMs: Long = 0L,
    val minBatteryVoltage: Double = 0.0,
    val maxEkfDrift: Double = 0.0,
    val avgLoopTimeMs: Double = 0.0,
    val p95LoopTimeMs: Double = 0.0,
    val motorCurrentAverages: Map<String, Double> = emptyMap(),
    val visionAcceptanceRate: Double = 0.0,
    val avgCrossTrackError: Double = 0.0,
    val avgBatteryResistance: Double = 0.0,
    val maxMotorTemps: Map<String, Double> = emptyMap(),
    val avgVisionLatencyMs: Double = 0.0,
    val tags: List<String> = emptyList(),
    val matchNumber: Int? = null,
    val allianceColor: String? = null,
    val rawGcsPath: String? = null,
    val fileSizeBytes: Long = 0L,
    /** Immutable Google Drive object identity for cloud-session manifests. */
    val cloudFileId: String? = null,
    /** Exact canonical Drive filename; substring lookup is intentionally unsupported. */
    val cloudFileName: String? = null,
    /** Lowercase SHA-256 of the uploaded cloud object bytes. */
    val cloudSha256: String? = null,
    /** Version of the complete, immutable cloud session bundle. Zero means not uploaded yet. */
    val cloudBundleVersion: Int = 0,
    /** Stable league/team/season/robot boundary that owns the cloud object. */
    val cloudWorkspaceKey: String? = null,
)

fun SessionSummary.allowsAutomaticExternalUpdates(): Boolean = !tags.isSimulationSessionTags()

@Serializable
data class SessionAnnotation(
    val annotationId: String,
    val sessionId: String,
    val text: String,
    val createdAt: Long,
    val authorId: String? = null
)

/**
 * One topic update on a session timeline.
 *
 * [value] is always populated for columnar storage. For textual topics,
 * [stringValue] is authoritative and [value] is only a numeric placeholder.
 */
@Serializable
data class TelemetryFrame(
    val timestampMs: Long,
    val sessionId: String,
    val key: String,
    val value: Double,
    val stringValue: String? = null,
    /** Original source timestamp in microseconds. */
    val timestampUs: Long = timestampMillisToMicros(timestampMs),
    /** Stable order for samples that share a source timestamp and topic. */
    val sampleOrder: Long = 0L
) {
    init {
        require(timestampMs in 0L..MAX_SUPPORTED_TIMESTAMP_MS) {
            "timestampMs is outside the supported domain"
        }
        require(timestampUs >= 0L && timestampUs / 1_000L == timestampMs) {
            "timestampUs is inconsistent with timestampMs"
        }
        require(sampleOrder >= 0L) { "sampleOrder must be non-negative" }
    }
}

/** A replaceable analysis result derived from raw session telemetry, not a robot timeline sample. */
@Serializable
data class AnalysisDiagnostic(
    val sessionId: String,
    val key: String,
    val value: Double,
    val stringValue: String? = null,
) {
    init {
        require(sessionId.isNotBlank()) { "Analysis diagnostic sessionId must not be blank" }
        require(key.removePrefix("/").isNotBlank()) { "Analysis diagnostic key must not be blank" }
        require(value.isFinite()) { "Analysis diagnostic value must be finite" }
    }
}

@Serializable
data class RobotActionRecord(
    val timestampMs: Long,
    val sessionId: String,
    val runId: String,
    val robotId: String,
    val matchNumber: Int = 0,
    val alliance: String = "UNKNOWN",
    val actionType: String,
    val payloadJson: String
)

@Serializable
data class AlertRecord(
    val alertId: String,
    val sessionId: String,
    val ruleKey: String,
    val triggerTimestampMs: Long,
    val resolveTimestampMs: Long? = null,
    val durationMs: Long = 0L,
    val peakValue: Double = 0.0,
    val triaged: Boolean = false
)

@Serializable
data class ThresholdRule(
    val key: String,
    val displayName: String,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val audibleAlert: Boolean = true
)

@Serializable
data class ConsoleMessage(
    val timestampMs: Long,
    val text: String,
    val severity: String
)

@Serializable
data class ControllerBinding(
    val gamepadId: String,
    val button: String,
    val action: String,
    val sourceFile: String,
    val lineNumber: Int
)

/** Sampled field trajectory state: seconds, meters, CCW-positive radians, and meters/second. */
@Serializable
data class TrajectoryState(
    val timeSeconds: Double,
    val x: Double,
    val y: Double,
    val headingRad: Double,
    val velocity: Double
)

@Serializable
data class Trajectory(
    val durationSeconds: Double,
    val states: List<TrajectoryState>
)
