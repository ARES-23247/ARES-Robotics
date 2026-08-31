package com.areslib.simulation

/** Evidence levels intentionally stop short of claiming physical validation from simulation. */
public enum class VerificationEvidenceLevel {
    CONFIGURATION_REVIEWED,
    COMPILED_SUCCESSFULLY,
    SIMULATION_VERIFIED,
    READY_FOR_PHYSICAL_VALIDATION,
    PHYSICALLY_VALIDATED,
}

/** Immutable, run-scoped evidence emitted by simulator and verification products. */
public data class SimulationVerificationEvidence(
    val verificationRunId: String,
    val checkId: String,
    val level: VerificationEvidenceLevel,
    val passed: Boolean,
    val timestampNanos: Long,
    val message: String,
    val projectContentSha256: String,
) {
    init {
        require(verificationRunId.isNotBlank()) { "Verification run ID is required" }
        require(checkId.isNotBlank()) { "Verification check ID is required" }
        require(timestampNanos >= 0L) { "Verification timestamp must be non-negative" }
        require(message.isNotBlank()) { "Verification evidence needs a student-readable explanation" }
        require(projectContentSha256.matches(Regex("[a-f0-9]{64}"))) {
            "Verification evidence must be bound to a canonical project SHA-256"
        }
        require(level != VerificationEvidenceLevel.PHYSICALLY_VALIDATED) {
            "Simulation evidence cannot claim physical validation"
        }
    }
}
