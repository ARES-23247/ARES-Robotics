package com.ares.analytics.mcp.tools

import com.ares.analytics.mcp.engine.HeadlessProjectEngine
import com.ares.analytics.mcp.engine.HeadlessProjectMutators
import com.ares.analytics.mcp.engine.TuningAssignmentInput
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.putJsonObject

/**
 * Registers the 8 typed descriptor/config/field mutator tools in FULL mode OR the unified
 * discriminated `ares_descriptor_mutate` tool in COMPACT mode.
 */
internal object DescriptorMutationTools {
    fun registerFull(server: Server, engine: HeadlessProjectEngine, mutators: HeadlessProjectMutators) {
        server.addTool(
            name = "ares_subsystem_mutate",
            description = "Instantiate a template (all 31 SubsystemTemplates), PATCH preserving UIDs, UPSERT, or two-step-DELETE a .ares/subsystems/<id>.aressubsystem descriptor.",
            inputSchema = toolSchema(required = listOf("operation")) {
                putJsonObject("projectPath") { stringProp("Robot repository root (defaults to active Studio bridge project)") }
                putJsonObject("operation") { enumProp("Mutation operation", listOf("UPSERT", "TEMPLATE", "PATCH", "DELETE")) }
                putJsonObject("documentId") { stringProp("Subsystem documentId (required for PATCH and DELETE; optional slug hint for TEMPLATE)") }
                putJsonObject("documentJson") { stringProp("Canonical .aressubsystem JSON (required for UPSERT)") }
                putJsonObject("templateName") { stringProp("SubsystemTemplate enum name (required for TEMPLATE, e.g. 'FLYWHEEL_SHOOTER')") }
                putJsonObject("implementationKind") { enumProp("Ownership mode for TEMPLATE", listOf("DECLARATIVE_GENERATED", "GENERATED_STARTER")) }
                putJsonObject("kotlinTypeName") { stringProp("Optional PascalCase Kotlin type override for TEMPLATE") }
                putJsonObject("displayName") { stringProp("Optional display name override for TEMPLATE") }
                putJsonObject("patchJson") { stringProp("RFC-7396 + UID-preserving keyed-array merge patch JSON (required for PATCH)") }
                putJsonObject("expectedRevision") { stringProp("Optional CAS project revision SHA-256") }
                putJsonObject("confirmationToken") { stringProp("Confirmation token required to complete DELETE") }
            },
        ) { request ->
            guardedTool {
                val args = request.arguments
                okJsonResult(
                    runBlocking {
                        val root = engine.resolveTargetRoot(args.optStr("projectPath"))
                        mutators.mutateSubsystem(
                            projectRoot = root,
                            operationRaw = args.reqStr("operation"),
                            documentIdHint = args.optStr("documentId"),
                            documentJson = args.optStr("documentJson"),
                            expectedRevision = args.optStr("expectedRevision"),
                            confirmationToken = args.optStr("confirmationToken"),
                            templateName = args.optStr("templateName"),
                            kotlinTypeName = args.optStr("kotlinTypeName"),
                            displayName = args.optStr("displayName"),
                            implementationKind = args.optStr("implementationKind"),
                            patchJson = args.optStr("patchJson"),
                        )
                    },
                )
            }
        }

        server.addTool(
            name = "ares_drivetrain_mutate",
            description = "Update, PATCH, or instantiate a preset drivetrain template (FTC_MECANUM, FRC_CTRE_SWERVE, XRP_DIFFERENTIAL, DIFFERENTIAL) with synced canonical .arestuning.",
            inputSchema = toolSchema {
                putJsonObject("projectPath") { stringProp("Robot repository root (defaults to active Studio bridge project)") }
                putJsonObject("operation") { enumProp("Operation (default UPSERT)", listOf("UPSERT", "TEMPLATE", "PATCH")) }
                putJsonObject("presetName") { enumProp("Drivetrain preset for TEMPLATE", listOf("FTC_MECANUM", "FRC_CTRE_SWERVE", "XRP_DIFFERENTIAL", "DIFFERENTIAL")) }
                putJsonObject("drivebaseId") { stringProp("Optional drivebaseId slug for TEMPLATE (default 'main-drive')") }
                putJsonObject("documentJson") { stringProp("Optional complete .aresdrivetrain JSON replacement for UPSERT") }
                putJsonObject("patchJson") { stringProp("RFC-7396 + UID-preserving JSON merge patch (required for PATCH)") }
                putJsonObject("maxLinearSpeedMps") { numberProp("Optional geometry max linear speed (m/s) override for UPSERT") }
                putJsonObject("maxAngularSpeedRadPerSec") { numberProp("Optional geometry max angular speed (rad/s) override for UPSERT") }
                putJsonObject("expectedRevision") { stringProp("Optional CAS project revision SHA-256") }
            },
        ) { request ->
            guardedTool {
                val args = request.arguments
                okJsonResult(
                    runBlocking {
                        val root = engine.resolveTargetRoot(args.optStr("projectPath"))
                        mutators.mutateDrivetrain(
                            projectRoot = root,
                            documentJson = args.optStr("documentJson"),
                            maxLinearSpeedMps = args.optDouble("maxLinearSpeedMps"),
                            maxAngularSpeedRadPerSec = args.optDouble("maxAngularSpeedRadPerSec"),
                            expectedRevision = args.optStr("expectedRevision"),
                            operationRaw = args.optStr("operation") ?: "UPSERT",
                            presetName = args.optStr("presetName"),
                            drivebaseIdHint = args.optStr("drivebaseId"),
                            patchJson = args.optStr("patchJson"),
                        )
                    },
                )
            }
        }

        server.addTool(
            name = "ares_superstructure_mutate",
            description = "UPSERT, UID-preserving PATCH, or two-step-DELETE a .ares/superstructures/<id>.aressuperstructure state machine descriptor.",
            inputSchema = toolSchema(required = listOf("operation")) {
                putJsonObject("projectPath") { stringProp("Robot repository root (defaults to active Studio bridge project)") }
                putJsonObject("operation") { enumProp("Mutation operation", listOf("UPSERT", "PATCH", "DELETE")) }
                putJsonObject("superstructureId") { stringProp("Superstructure ID (required for PATCH and DELETE)") }
                putJsonObject("documentJson") { stringProp("Canonical .aressuperstructure JSON (required for UPSERT)") }
                putJsonObject("patchJson") { stringProp("RFC-7396 + UID-preserving JSON merge patch (required for PATCH)") }
                putJsonObject("expectedRevision") { stringProp("Optional CAS project revision SHA-256") }
                putJsonObject("confirmationToken") { stringProp("Confirmation token required to complete DELETE") }
            },
        ) { request ->
            guardedTool {
                val args = request.arguments
                okJsonResult(
                    runBlocking {
                        val root = engine.resolveTargetRoot(args.optStr("projectPath"))
                        mutators.mutateSuperstructure(
                            projectRoot = root,
                            operationRaw = args.reqStr("operation"),
                            superstructureIdHint = args.optStr("superstructureId") ?: args.optStr("documentId"),
                            documentJson = args.optStr("documentJson"),
                            expectedRevision = args.optStr("expectedRevision"),
                            confirmationToken = args.optStr("confirmationToken"),
                            patchJson = args.optStr("patchJson"),
                        )
                    },
                )
            }
        }

        server.addTool(
            name = "ares_routine_mutate",
            description = "UPSERT, PATCH (keyed by stepId with orphaned loop cleanup), or two-step-DELETE a .ares/routines/<id>.aresroutine autonomous routine.",
            inputSchema = toolSchema(required = listOf("operation")) {
                putJsonObject("projectPath") { stringProp("Robot repository root (defaults to active Studio bridge project)") }
                putJsonObject("operation") { enumProp("Mutation operation", listOf("UPSERT", "PATCH", "DELETE")) }
                putJsonObject("documentId") { stringProp("Routine documentId (required for PATCH and DELETE)") }
                putJsonObject("documentJson") { stringProp("Canonical .aresroutine JSON (required for UPSERT)") }
                putJsonObject("patchJson") { stringProp("RFC-7396 + stepId-keyed merge patch JSON (required for PATCH)") }
                putJsonObject("registerAutonomousDisplayName") { stringProp("Optional display name to add/update in .ares/autonomous-catalog.json") }
                putJsonObject("expectedRevision") { stringProp("Optional CAS project revision SHA-256") }
                putJsonObject("confirmationToken") { stringProp("Confirmation token required to complete DELETE") }
            },
        ) { request ->
            guardedTool {
                val args = request.arguments
                okJsonResult(
                    runBlocking {
                        val root = engine.resolveTargetRoot(args.optStr("projectPath"))
                        mutators.mutateRoutine(
                            projectRoot = root,
                            operationRaw = args.reqStr("operation"),
                            documentIdHint = args.optStr("documentId"),
                            documentJson = args.optStr("documentJson"),
                            registerAutonomousDisplayName = args.optStr("registerAutonomousDisplayName"),
                            expectedRevision = args.optStr("expectedRevision"),
                            confirmationToken = args.optStr("confirmationToken"),
                            patchJson = args.optStr("patchJson"),
                        )
                    },
                )
            }
        }

        server.addTool(
            name = "ares_controls_mutate",
            description = "UPSERT, PATCH, or two-step-DELETE a .ares/controls/*.arescontrols or .ares/controllers/*.arescontroller document.",
            inputSchema = toolSchema(required = listOf("operation")) {
                putJsonObject("projectPath") { stringProp("Robot repository root (defaults to active Studio bridge project)") }
                putJsonObject("operation") { enumProp("Mutation operation", listOf("UPSERT", "PATCH", "DELETE")) }
                putJsonObject("targetKind") { enumProp("Control artifact kind", listOf("CONTROL_SCHEME", "CONTROLLER_PROFILE")) }
                putJsonObject("documentId") { stringProp("Document/profile ID (required for PATCH and DELETE)") }
                putJsonObject("documentJson") { stringProp("Canonical .arescontrols or .arescontroller JSON (required for UPSERT)") }
                putJsonObject("patchJson") { stringProp("RFC-7396 + UID-preserving JSON merge patch (required for PATCH)") }
                putJsonObject("expectedRevision") { stringProp("Optional CAS project revision SHA-256") }
                putJsonObject("confirmationToken") { stringProp("Confirmation token required to complete DELETE") }
            },
        ) { request ->
            guardedTool {
                val args = request.arguments
                okJsonResult(
                    runBlocking {
                        val root = engine.resolveTargetRoot(args.optStr("projectPath"))
                        mutators.mutateControls(
                            projectRoot = root,
                            operationRaw = args.reqStr("operation"),
                            targetKindRaw = args.optStr("targetKind") ?: "CONTROL_SCHEME",
                            documentIdHint = args.optStr("documentId"),
                            documentJson = args.optStr("documentJson"),
                            expectedRevision = args.optStr("expectedRevision"),
                            confirmationToken = args.optStr("confirmationToken"),
                            patchJson = args.optStr("patchJson"),
                        )
                    },
                )
            }
        }

        server.addTool(
            name = "ares_tuning_mutate",
            description = "Apply bounded tuning assignments to .ares/local/tuning (1-step) or .ares/tuning canonical profiles (2-step confirmationToken).",
            inputSchema = toolSchema(required = listOf("target", "profileId", "assignments")) {
                putJsonObject("projectPath") { stringProp("Robot repository root (defaults to active Studio bridge project)") }
                putJsonObject("target") { enumProp("Tuning store target", listOf("LOCAL_OVERLAY", "CANONICAL")) }
                putJsonObject("profileId") { stringProp("Target profile ID (e.g. 'simulation' or 'local-sweep')") }
                putJsonObject("assignments") { anyArrayProp("Objects with keyOrUid plus doubleValue/intValue/booleanValue/textValue") }
                putJsonObject("confirmationToken") { stringProp("Required on 2nd call when target='CANONICAL'") }
                putJsonObject("expectedRevision") { stringProp("Optional CAS project revision SHA-256") }
            },
        ) { request ->
            guardedTool {
                val args = request.arguments
                val assignments = parseAssignments(args)
                okJsonResult(
                    runBlocking {
                        val root = engine.resolveTargetRoot(args.optStr("projectPath"))
                        mutators.mutateTuning(
                            projectRoot = root,
                            targetRaw = args.reqStr("target"),
                            profileId = args.reqStr("profileId"),
                            assignments = assignments,
                            confirmationToken = args.optStr("confirmationToken"),
                            expectedRevision = args.optStr("expectedRevision"),
                        )
                    },
                )
            }
        }

        ConfigAndFieldTools.registerFull(server, engine, mutators)
    }

    fun registerCompact(server: Server, engine: HeadlessProjectEngine, mutators: HeadlessProjectMutators) {
        server.addTool(
            name = "ares_descriptor_mutate",
            description = "Consolidated mutator for SUBSYSTEM, DRIVETRAIN, SUPERSTRUCTURE, ROUTINE, CONTROLS, TUNING, PROJECT_CONFIG, ACTION_CATALOG, AUTONOMOUS_CATALOG, and FIELD.",
            inputSchema = toolSchema(required = listOf("descriptorKind")) {
                putJsonObject("projectPath") { stringProp("Robot repository root (defaults to active Studio bridge project)") }
                putJsonObject("descriptorKind") {
                    enumProp(
                        "Artifact kind to inspect or mutate",
                        listOf(
                            "SUBSYSTEM",
                            "DRIVETRAIN",
                            "SUPERSTRUCTURE",
                            "ROUTINE",
                            "CONTROLS",
                            "TUNING",
                            "PROJECT_CONFIG",
                            "ACTION_CATALOG",
                            "AUTONOMOUS_CATALOG",
                            "FIELD",
                        ),
                    )
                }
                putJsonObject("operation") { stringProp("Operation (UPSERT, TEMPLATE, PATCH, DELETE, INSPECT, INSTALL_PRESET, IMPORT_APRILTAGS, UPSERT_ACTION, DELETE_ACTION, UPSERT_ENTRY, DELETE_ENTRY)") }
                putJsonObject("documentId") { stringProp("Target documentId / profileId / superstructureId / drivebaseId") }
                putJsonObject("documentJson") { stringProp("Canonical JSON payload for upsert / single-entry operations") }
                putJsonObject("patchJson") { stringProp("RFC-7396 + UID-preserving merge patch JSON") }
                putJsonObject("templateName") { stringProp("SubsystemTemplate enum name when SUBSYSTEM TEMPLATE") }
                putJsonObject("implementationKind") { enumProp("Ownership mode when SUBSYSTEM TEMPLATE", listOf("DECLARATIVE_GENERATED", "GENERATED_STARTER")) }
                putJsonObject("kotlinTypeName") { stringProp("Optional PascalCase Kotlin type override") }
                putJsonObject("displayName") { stringProp("Optional display name override") }
                putJsonObject("presetName") { stringProp("Preset name for DRIVETRAIN TEMPLATE or FIELD INSTALL_PRESET") }
                putJsonObject("maxLinearSpeedMps") { numberProp("Optional geometry max linear speed (m/s) override for DRIVETRAIN UPSERT") }
                putJsonObject("maxAngularSpeedRadPerSec") { numberProp("Optional geometry max angular speed (rad/s) override for DRIVETRAIN UPSERT") }
                putJsonObject("targetKind") { enumProp("Control target when CONTROLS", listOf("CONTROL_SCHEME", "CONTROLLER_PROFILE")) }
                putJsonObject("registerAutonomousDisplayName") { stringProp("Optional display name for autonomous catalog registration on ROUTINE") }
                putJsonObject("target") { enumProp("Tuning target (LOCAL_OVERLAY or CANONICAL)", listOf("LOCAL_OVERLAY", "CANONICAL")) }
                putJsonObject("assignments") { anyArrayProp("Tuning assignments for descriptorKind='TUNING'") }
                putJsonObject("targetKeyOrEntryId") { stringProp("Action key (DELETE_ACTION) or entryId (DELETE_ENTRY)") }
                putJsonObject("aprilTagSourceJson") { stringProp("WPILib/Limelight AprilTag JSON for FIELD IMPORT_APRILTAGS") }
                putJsonObject("aprilTagSourcePath") { stringProp("Repo-relative AprilTag JSON path for FIELD IMPORT_APRILTAGS") }
                putJsonObject("defaultFamily") { stringProp("Default AprilTag family override") }
                putJsonObject("defaultTagSizeMeters") { numberProp("Default AprilTag size in meters") }
                putJsonObject("expectedRevision") { stringProp("Optional CAS project revision SHA-256") }
                putJsonObject("confirmationToken") { stringProp("Two-step confirmation token when deleting or modifying CANONICAL tuning") }
            },
        ) { request ->
            guardedTool {
                val args = request.arguments
                val kind = args.reqStr("descriptorKind").trim().uppercase()
                val op = args.optStr("operation") ?: "UPSERT"
                runBlocking {
                    val root = engine.resolveTargetRoot(args.optStr("projectPath"))
                    when (kind) {
                        "SUBSYSTEM" -> okJsonResult(
                            mutators.mutateSubsystem(
                                root, op, args.optStr("documentId"), args.optStr("documentJson"),
                                args.optStr("expectedRevision"), args.optStr("confirmationToken"),
                                args.optStr("templateName"), args.optStr("kotlinTypeName"),
                                args.optStr("displayName"), args.optStr("implementationKind"), args.optStr("patchJson"),
                            ),
                        )
                        "DRIVETRAIN" -> okJsonResult(
                            mutators.mutateDrivetrain(
                                root, args.optStr("documentJson"), args.optDouble("maxLinearSpeedMps"),
                                args.optDouble("maxAngularSpeedRadPerSec"), args.optStr("expectedRevision"),
                                op, args.optStr("presetName"), args.optStr("documentId"), args.optStr("patchJson"),
                            ),
                        )
                        "SUPERSTRUCTURE" -> okJsonResult(
                            mutators.mutateSuperstructure(
                                root, op, args.optStr("documentId"), args.optStr("documentJson"),
                                args.optStr("expectedRevision"), args.optStr("confirmationToken"), args.optStr("patchJson"),
                            ),
                        )
                        "ROUTINE" -> okJsonResult(
                            mutators.mutateRoutine(
                                root, op, args.optStr("documentId"), args.optStr("documentJson"),
                                args.optStr("registerAutonomousDisplayName"),
                                args.optStr("expectedRevision"), args.optStr("confirmationToken"), args.optStr("patchJson"),
                            ),
                        )
                        "CONTROLS" -> okJsonResult(
                            mutators.mutateControls(
                                root, op, args.optStr("targetKind") ?: "CONTROL_SCHEME",
                                args.optStr("documentId"), args.optStr("documentJson"),
                                args.optStr("expectedRevision"), args.optStr("confirmationToken"), args.optStr("patchJson"),
                            ),
                        )
                        "TUNING" -> okJsonResult(
                            mutators.mutateTuning(
                                root, args.optStr("target") ?: "LOCAL_OVERLAY",
                                args.reqStr("documentId"), parseAssignments(args),
                                args.optStr("confirmationToken"), args.optStr("expectedRevision"),
                            ),
                        )
                        "PROJECT_CONFIG" -> okJsonResult(
                            mutators.configAndField.mutateProjectConfig(
                                root, "PROJECT_METADATA", op, args.optStr("documentJson"), args.optStr("patchJson"),
                                args.optStr("targetKeyOrEntryId"), args.optStr("confirmationToken"), args.optStr("expectedRevision"),
                            ),
                        )
                        "ACTION_CATALOG", "AUTONOMOUS_CATALOG" -> okJsonResult(
                            mutators.configAndField.mutateProjectConfig(
                                root, kind, op, args.optStr("documentJson"), args.optStr("patchJson"),
                                args.optStr("targetKeyOrEntryId"), args.optStr("confirmationToken"), args.optStr("expectedRevision"),
                            ),
                        )
                        "FIELD" -> okJsonResult(
                            mutators.configAndField.mutateField(
                                root, op, args.optStr("presetName"), args.optStr("aprilTagSourceJson"),
                                args.optStr("aprilTagSourcePath"), args.optStr("defaultFamily"),
                                args.optDouble("defaultTagSizeMeters"), args.optStr("documentJson"),
                                args.optStr("patchJson"), args.optStr("expectedRevision"),
                            ),
                        )
                        else -> throw IllegalArgumentException("Unsupported descriptorKind '$kind'")
                    }
                }
            }
        }
    }

    private fun parseAssignments(args: JsonObject?): List<TuningAssignmentInput> =
        args.optObjList("assignments").map { item ->
            TuningAssignmentInput(
                keyOrUid = item.reqStr("keyOrUid"),
                doubleValue = item.optDouble("doubleValue"),
                intValue = item.optInt("intValue"),
                booleanValue = if (item.containsKey("booleanValue")) item.optBool("booleanValue", false) else null,
                textValue = item.optStr("textValue"),
            )
        }
}
