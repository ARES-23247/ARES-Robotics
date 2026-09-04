package com.ares.analytics.service.verification

import com.areslib.codegen.ProjectGeneratedTestNames
import com.areslib.subsystem.SubsystemGeneratedTestNames

internal data class ProjectGeneratedCheck(
    val id: String,
    val methodName: String,
    val title: String,
    val explanation: String,
)

internal fun xrpSubsystemTestName(methodName: String): String? = when (methodName) {
    SubsystemGeneratedTestNames.SAFE_STARTUP,
    SubsystemGeneratedTestNames.DISABLED_STOP -> "test_generated_subsystems_start_and_stop_neutral"
    SubsystemGeneratedTestNames.OUTPUT_FAULT_POLICY -> "test_generated_subsystems_latch_failed_writes"
    SubsystemGeneratedTestNames.CONTROL_LIMITS -> "test_declared_target_limits_reject_out_of_range_values"
    SubsystemGeneratedTestNames.INVALID_AND_CLEANUP -> "test_generated_subsystems_fail_closed_on_invalid_feedback"
    SubsystemGeneratedTestNames.STALE_FEEDBACK -> "test_generated_subsystems_reject_failed_feedback_reads"
    SubsystemGeneratedTestNames.NEUTRAL_RECOVERY -> "test_generated_subsystems_recover_only_after_successful_neutral"
    SubsystemGeneratedTestNames.GENERATED_ACTIONS -> "test_generated_subsystem_actions_update_state"
    else -> null
}

internal fun xrpProjectTestName(methodName: String): String = when (methodName) {
    ProjectGeneratedTestNames.PROJECT_IDENTITY -> "test_generated_project_identity_and_footprint_are_valid"
    ProjectGeneratedTestNames.DRIVETRAIN_SAFETY -> "test_generated_drivetrain_safety_contract_is_valid"
    ProjectGeneratedTestNames.CONTROLS -> "test_generated_controls_resolve_typed_project_targets"
    ProjectGeneratedTestNames.AUTONOMOUS -> "test_generated_autonomous_graph_is_closed"
    ProjectGeneratedTestNames.SUPERSTRUCTURE -> "test_generated_superstructure_references_and_interlocks_are_valid"
    else -> methodName
}

internal val PROJECT_GENERATED_CHECKS = listOf(
    ProjectGeneratedCheck(
        id = "project.identity.generated-contract",
        methodName = ProjectGeneratedTestNames.PROJECT_IDENTITY,
        title = "Generated project identity contract",
        explanation = "The generated suite decoded the canonical project identity and verified its robot and field dimensions.",
    ),
    ProjectGeneratedCheck(
        id = "project.drivetrain.generated-contract",
        methodName = ProjectGeneratedTestNames.DRIVETRAIN_SAFETY,
        title = "Generated drivetrain safety contract",
        explanation = "The generated suite verified every GUI-authored drivetrain document and its fail-closed safety rules.",
    ),
    ProjectGeneratedCheck(
        id = "project.controls.generated-contract",
        methodName = ProjectGeneratedTestNames.CONTROLS,
        title = "Generated control bindings contract",
        explanation = "The generated suite resolved controller inputs against typed drivetrain, subsystem, routine, and action targets.",
    ),
    ProjectGeneratedCheck(
        id = "project.autonomous.generated-contract",
        methodName = ProjectGeneratedTestNames.AUTONOMOUS,
        title = "Generated autonomous graph contract",
        explanation = "The generated suite verified routine references and autonomous choices without treating compilation as simulated execution.",
    ),
    ProjectGeneratedCheck(
        id = "project.superstructure.generated-contract",
        methodName = ProjectGeneratedTestNames.SUPERSTRUCTURE,
        title = "Generated superstructure contract",
        explanation = "The generated suite verified presets, interlocks, subsystem fields, and named actions referenced by GUI-authored superstructures.",
    ),
)
