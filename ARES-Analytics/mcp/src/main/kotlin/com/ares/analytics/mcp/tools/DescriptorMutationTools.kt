package com.ares.analytics.mcp.tools

import com.ares.analytics.mcp.engine.HeadlessProjectEngine
import com.ares.analytics.mcp.engine.HeadlessProjectMutators
import com.ares.analytics.mcp.engine.TuningAssignmentInput
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.putJsonObject

/**
 * Registers the 6 typed `.ares/` descriptor mutator tools in FULL mode OR the unified
 * discriminated `ares_descriptor_mutate` tool in COMPACT mode.
 */
internal object DescriptorMutationTools {
    fun registerFull(server: Server, engine: HeadlessProjectEngine, mutators: HeadlessProjectMutators) {
        server.addTool(
            name = "ares_subsystem_mutate",
            description = "Create, update, or two-step-delete a .ares/subsystems/<id>.aressubsystem descriptor with schema validation and JGit snapshot.",
            inputSchema = toolSchema(required = listOf("operation")) {
                putJsonObject("projectPath") { stringProp("Robot repository root (defaults to active Studio bridge project)") }
                putJsonObject("operation") { enumProp("Mutation operation", listOf("UPSERT", "DELETE")) }
                putJsonObject("documentId") { stringProp("Subsystem documentId (required for DELETE)") }
                putJsonObject("documentJson") { stringProp("Canonical .aressubsystem JSON (required for UPSERT)") }
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
                        )
                    },
                )
            }
        }

        server.addTool(
            name = "ares_drivetrain_mutate",
            description = "Update or replace the project's .ares/drivetrains/*.aresdrivetrain contract with schema validation and JGit snapshot.",
            inputSchema = toolSchema {
                putJsonObject("projectPath") { stringProp("Robot repository root (defaults to active Studio bridge project)") }
                putJsonObject("documentJson") { stringProp("Optional complete .aresdrivetrain JSON replacement") }
                putJsonObject("maxLinearSpeedMps") { numberProp("Optional geometry max linear speed (m/s) override") }
                putJsonObject("maxAngularSpeedRadPerSec") { numberProp("Optional geometry max angular speed (rad/s) override") }
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
                        )
                    },
                )
            }
        }

        server.addTool(
            name = "ares_superstructure_mutate",
            description = "Create, update, or two-step-delete a .ares/superstructures/<id>.aressuperstructure state machine descriptor.",
            inputSchema = toolSchema(required = listOf("operation")) {
                putJsonObject("projectPath") { stringProp("Robot repository root (defaults to active Studio bridge project)") }
                putJsonObject("operation") { enumProp("Mutation operation", listOf("UPSERT", "DELETE")) }
                putJsonObject("superstructureId") { stringProp("Superstructure ID (required for DELETE)") }
                putJsonObject("documentJson") { stringProp("Canonical .aressuperstructure JSON (required for UPSERT)") }
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
                        )
                    },
                )
            }
        }

        server.addTool(
            name = "ares_routine_mutate",
            description = "Create, update, or two-step-delete a .ares/routines/<id>.aresroutine autonomous routine and optionally register it in autonomous-catalog.json.",
            inputSchema = toolSchema(required = listOf("operation")) {
                putJsonObject("projectPath") { stringProp("Robot repository root (defaults to active Studio bridge project)") }
                putJsonObject("operation") { enumProp("Mutation operation", listOf("UPSERT", "DELETE")) }
                putJsonObject("documentId") { stringProp("Routine documentId (required for DELETE)") }
                putJsonObject("documentJson") { stringProp("Canonical .aresroutine JSON (required for UPSERT)") }
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
                        )
                    },
                )
            }
        }

        server.addTool(
            name = "ares_controls_mutate",
            description = "Create, update, or two-step-delete a .ares/controls/*.arescontrols or .ares/controllers/*.arescontroller document.",
            inputSchema = toolSchema(required = listOf("operation")) {
                putJsonObject("projectPath") { stringProp("Robot repository root (defaults to active Studio bridge project)") }
                putJsonObject("operation") { enumProp("Mutation operation", listOf("UPSERT", "DELETE")) }
                putJsonObject("targetKind") { enumProp("Control artifact kind", listOf("CONTROL_SCHEME", "CONTROLLER_PROFILE")) }
                putJsonObject("documentId") { stringProp("Document/profile ID (required for DELETE)") }
                putJsonObject("documentJson") { stringProp("Canonical .arescontrols or .arescontroller JSON (required for UPSERT)") }
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
    }

    fun registerCompact(server: Server, engine: HeadlessProjectEngine, mutators: HeadlessProjectMutators) {
        server.addTool(
            name = "ares_descriptor_mutate",
            description = "Consolidated mutator for SUBSYSTEM, DRIVETRAIN, SUPERSTRUCTURE, ROUTINE, CONTROLS, or TUNING descriptors.",
            inputSchema = toolSchema(required = listOf("descriptorKind")) {
                putJsonObject("projectPath") { stringProp("Robot repository root (defaults to active Studio bridge project)") }
                putJsonObject("descriptorKind") {
                    enumProp(
                        "Descriptor kind to mutate",
                        listOf("SUBSYSTEM", "DRIVETRAIN", "SUPERSTRUCTURE", "ROUTINE", "CONTROLS", "TUNING"),
                    )
                }
                putJsonObject("operation") { enumProp("UPSERT or DELETE (default UPSERT)", listOf("UPSERT", "DELETE")) }
                putJsonObject("documentId") { stringProp("Target documentId / profileId / superstructureId") }
                putJsonObject("documentJson") { stringProp("Canonical JSON payload for descriptor upsert") }
                putJsonObject("target") { enumProp("Tuning target (LOCAL_OVERLAY or CANONICAL)", listOf("LOCAL_OVERLAY", "CANONICAL")) }
                putJsonObject("assignments") { anyArrayProp("Tuning assignments for descriptorKind='TUNING'") }
                putJsonObject("expectedRevision") { stringProp("Optional CAS project revision SHA-256") }
                putJsonObject("confirmationToken") { stringProp("Two-step confirmation token when deleting or modifying CANONICAL tuning") }
            },
        ) { request ->
            guardedTool {
                val args = request.arguments
                val kind = args.reqStr("descriptorKind").trim().uppercase()
                val op = args.optStr("operation") ?: "UPSERT"
                okJsonResult(
                    runBlocking {
                        val root = engine.resolveTargetRoot(args.optStr("projectPath"))
                        when (kind) {
                            "SUBSYSTEM" -> mutators.mutateSubsystem(
                                root, op, args.optStr("documentId"), args.optStr("documentJson"),
                                args.optStr("expectedRevision"), args.optStr("confirmationToken"),
                            )
                            "DRIVETRAIN" -> mutators.mutateDrivetrain(
                                root, args.optStr("documentJson"), args.optDouble("maxLinearSpeedMps"),
                                args.optDouble("maxAngularSpeedRadPerSec"), args.optStr("expectedRevision"),
                            )
                            "SUPERSTRUCTURE" -> mutators.mutateSuperstructure(
                                root, op, args.optStr("documentId"), args.optStr("documentJson"),
                                args.optStr("expectedRevision"), args.optStr("confirmationToken"),
                            )
                            "ROUTINE" -> mutators.mutateRoutine(
                                root, op, args.optStr("documentId"), args.optStr("documentJson"),
                                args.optStr("registerAutonomousDisplayName"),
                                args.optStr("expectedRevision"), args.optStr("confirmationToken"),
                            )
                            "CONTROLS" -> mutators.mutateControls(
                                root, op, args.optStr("targetKind") ?: "CONTROL_SCHEME",
                                args.optStr("documentId"), args.optStr("documentJson"),
                                args.optStr("expectedRevision"), args.optStr("confirmationToken"),
                            )
                            "TUNING" -> mutators.mutateTuning(
                                root, args.optStr("target") ?: "LOCAL_OVERLAY",
                                args.reqStr("documentId"), parseAssignments(args),
                                args.optStr("confirmationToken"), args.optStr("expectedRevision"),
                            )
                            else -> throw IllegalArgumentException("Unsupported descriptorKind '$kind'")
                        }
                    },
                )
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
