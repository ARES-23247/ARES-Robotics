package com.areslib.subsystem

/** Builder-owned behavior groups shown in the novice Verification report. */
enum class SubsystemVerificationCategory {
    STATE_AND_CONTROLS,
    SAFE_STARTUP_AND_STOP,
    HARDWARE_SIMULATION_PARITY,
    INVALID_FEEDBACK_AND_WRITES,
    LIMITS_AND_HOMING,
    FAULT_RECOVERY,
    GENERATED_ACTIONS,
    ALLOCATION,
}

/** Evidence a generated subsystem check can honestly provide without physical hardware. */
enum class SubsystemVerificationEvidence {
    CONFIGURATION,
    COMPILED_GENERATED_CODE,
    GENERATED_BEHAVIOR_TEST,
    PLATFORM_INTEGRATION_TEST,
}

/**
 * Stable verification identity derived from a canonical `.aressubsystem` document.
 *
 * [testMethodName] is null when the declaration is enforced by generator/platform verification
 * rather than one generated JUnit method. It is intentionally stable so desktop reporting can
 * join the canonical contract to Gradle XML without exposing generated test files to students.
 */
data class SubsystemVerificationCheck(
    val id: String,
    val category: SubsystemVerificationCategory,
    val title: String,
    val explanation: String,
    val evidence: SubsystemVerificationEvidence,
    val testMethodName: String? = null,
)

object SubsystemGeneratedTestNames {
    const val SAFE_STARTUP = "generated state and mock IO start safely"
    const val OUTPUT_FAULT_POLICY = "failed writes neutralize and follow the declared fault policy"
    const val HOMING_AND_CURRENT = "homing and current validity are independent safety permits"
    const val CONTROL_LIMITS = "every controller remains inside its declared output limits"
    const val HOMING_DWELL = "homing evidence must dwell before home is established"
    const val NEUTRAL_RECOVERY = "neutral recovery requests are consumed once and failed neutral stays latched"
    const val CALIBRATION = "calibration confirmation requires fresh healthy state and successful neutral"
    const val GENERATED_ACTIONS = "every generated target and safety action updates immutable state"
    const val STALE_FEEDBACK = "stale feedback is rejected by the immutable state contract"
    const val DISABLED_STOP = "zero scale models disabled and commands neutral"
    const val INVALID_AND_CLEANUP = "invalid feedback and cleanup fail closed"
}

/**
 * Builds the verification contract implied by the subsystem descriptor.
 *
 * Safety settings are not duplicated as optional test toggles: selecting homing, fault latching,
 * calibration, feedback freshness, or generated actions automatically requires the corresponding
 * checks. This prevents a student from accidentally disabling the test that enforces a selected
 * safety feature.
 */
fun subsystemVerificationContract(document: SubsystemDocument): List<SubsystemVerificationCheck> {
    if (!document.generateTest) return emptyList()

    val checks = mutableListOf(
        SubsystemVerificationCheck(
            id = "${document.documentId}.state.safe-startup",
            category = SubsystemVerificationCategory.STATE_AND_CONTROLS,
            title = "State and mock IO start safely",
            explanation = "The immutable state and simulated adapter begin at declared neutral outputs without a fault.",
            evidence = SubsystemVerificationEvidence.GENERATED_BEHAVIOR_TEST,
            testMethodName = SubsystemGeneratedTestNames.SAFE_STARTUP,
        ),
        SubsystemVerificationCheck(
            id = "${document.documentId}.outputs.fault-policy",
            category = SubsystemVerificationCategory.INVALID_FEEDBACK_AND_WRITES,
            title = "Failed output writes fail closed",
            explanation = "A rejected actuator write applies every declared safe output and follows the selected fault-latching policy.",
            evidence = SubsystemVerificationEvidence.GENERATED_BEHAVIOR_TEST,
            testMethodName = SubsystemGeneratedTestNames.OUTPUT_FAULT_POLICY,
        ),
        SubsystemVerificationCheck(
            id = "${document.documentId}.safety.homing-current",
            category = SubsystemVerificationCategory.LIMITS_AND_HOMING,
            title = "Homing and current permits remain independent",
            explanation = "Homing state and current validity cannot silently substitute for one another when motion is permitted.",
            evidence = SubsystemVerificationEvidence.GENERATED_BEHAVIOR_TEST,
            testMethodName = SubsystemGeneratedTestNames.HOMING_AND_CURRENT,
        ),
        SubsystemVerificationCheck(
            id = "${document.documentId}.controls.output-limits",
            category = SubsystemVerificationCategory.LIMITS_AND_HOMING,
            title = "Controller outputs stay inside declared limits",
            explanation = "Extreme target requests cannot drive any generated actuator beyond its configured output envelope.",
            evidence = SubsystemVerificationEvidence.GENERATED_BEHAVIOR_TEST,
            testMethodName = SubsystemGeneratedTestNames.CONTROL_LIMITS,
        ),
        SubsystemVerificationCheck(
            id = "${document.documentId}.lifecycle.disabled-stop",
            category = SubsystemVerificationCategory.SAFE_STARTUP_AND_STOP,
            title = "Disabled and stop commands are neutral",
            explanation = "A zero output scale models disabled/stop behavior and commands the declared safe neutral.",
            evidence = SubsystemVerificationEvidence.GENERATED_BEHAVIOR_TEST,
            testMethodName = SubsystemGeneratedTestNames.DISABLED_STOP,
        ),
        SubsystemVerificationCheck(
            id = "${document.documentId}.feedback.invalid-cleanup",
            category = SubsystemVerificationCategory.INVALID_FEEDBACK_AND_WRITES,
            title = "Invalid feedback and cleanup fail closed",
            explanation = "Invalid cached inputs are reported and resource cleanup remains safe and idempotent.",
            evidence = SubsystemVerificationEvidence.GENERATED_BEHAVIOR_TEST,
            testMethodName = SubsystemGeneratedTestNames.INVALID_AND_CLEANUP,
        ),
        SubsystemVerificationCheck(
            id = "${document.documentId}.parity.generated-adapters",
            category = SubsystemVerificationCategory.HARDWARE_SIMULATION_PARITY,
            title = "Hardware and simulation share one generated contract",
            explanation = "The physical and mock adapters compile against the same IO, controller, limits, inversion, follower, and safe-output contract.",
            evidence = SubsystemVerificationEvidence.COMPILED_GENERATED_CODE,
        ),
    )

    if (document.safety.feedbackTimeoutMs != null) {
        checks += SubsystemVerificationCheck(
            id = "${document.documentId}.feedback.stale",
            category = SubsystemVerificationCategory.INVALID_FEEDBACK_AND_WRITES,
            title = "Stale feedback is rejected",
            explanation = "Cached feedback older than the configured timeout cannot permit a non-neutral command.",
            evidence = SubsystemVerificationEvidence.GENERATED_BEHAVIOR_TEST,
            testMethodName = SubsystemGeneratedTestNames.STALE_FEEDBACK,
        )
    }
    if (document.safety.homing.method != SubsystemHomingMethod.NONE) {
        checks += SubsystemVerificationCheck(
            id = "${document.documentId}.homing.dwell",
            category = SubsystemVerificationCategory.LIMITS_AND_HOMING,
            title = "Homing evidence must remain valid",
            explanation = "The configured evidence must remain healthy for its dwell interval before the mechanism becomes homed.",
            evidence = SubsystemVerificationEvidence.GENERATED_BEHAVIOR_TEST,
            testMethodName = SubsystemGeneratedTestNames.HOMING_DWELL,
        )
    }
    if (document.safety.requiresExplicitNeutralRecovery) {
        checks += SubsystemVerificationCheck(
            id = "${document.documentId}.fault.neutral-recovery",
            category = SubsystemVerificationCategory.FAULT_RECOVERY,
            title = "Fault recovery requires a successful neutral",
            explanation = "A recovery request is consumed once and motion stays latched when the neutral write fails.",
            evidence = SubsystemVerificationEvidence.GENERATED_BEHAVIOR_TEST,
            testMethodName = SubsystemGeneratedTestNames.NEUTRAL_RECOVERY,
        )
    }
    if (document.safety.requiresCalibration) {
        checks += SubsystemVerificationCheck(
            id = "${document.documentId}.calibration.confirmation",
            category = SubsystemVerificationCategory.LIMITS_AND_HOMING,
            title = "Calibration needs fresh healthy evidence",
            explanation = "Calibration is established only after valid feedback, healthy configuration, and a successful neutral write.",
            evidence = SubsystemVerificationEvidence.GENERATED_BEHAVIOR_TEST,
            testMethodName = SubsystemGeneratedTestNames.CALIBRATION,
        )
    }
    if (subsystemTargetCapabilities(listOf(document)).isNotEmpty()) {
        checks += SubsystemVerificationCheck(
            id = "${document.documentId}.actions.generated",
            category = SubsystemVerificationCategory.GENERATED_ACTIONS,
            title = "Generated actions enter the Redux command flow",
            explanation = "Every generated target and safety action resolves and updates immutable robot state through Redux.",
            evidence = SubsystemVerificationEvidence.GENERATED_BEHAVIOR_TEST,
            testMethodName = SubsystemGeneratedTestNames.GENERATED_ACTIONS,
        )
    }
    if (document.safety.zeroAllocationPeriodic) {
        checks += SubsystemVerificationCheck(
            id = "${document.documentId}.allocation.steady-state",
            category = SubsystemVerificationCategory.ALLOCATION,
            title = "Steady-state allocation policy is selected",
            explanation = "Generated periodic code follows the zero-allocation contract; byte-allocation regression remains an independent ARES platform test because Android project tests do not expose JVM allocation counters.",
            evidence = SubsystemVerificationEvidence.COMPILED_GENERATED_CODE,
        )
    }
    return checks.sortedBy { it.id }
}
