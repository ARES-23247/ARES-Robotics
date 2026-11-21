package com.ares.analytics.mcp.tools

import com.ares.analytics.mcp.engine.HeadlessProjectEngine
import com.ares.analytics.mcp.engine.HeadlessProjectMutators
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.putJsonObject

/**
 * Registers `ares_project_config_mutate` and `ares_field_mutate` in FULL mode while keeping
 * [DescriptorMutationTools] compact and readable.
 */
internal object ConfigAndFieldTools {
    fun registerFull(server: Server, engine: HeadlessProjectEngine, mutators: HeadlessProjectMutators) {
        server.addTool(
            name = "ares_project_config_mutate",
            description = "Inspect or safely mutate .ares/project.json (GUI_OWNED/HYBRID/CODE_FIRST & runtime options), .ares/action-catalog.json, or .ares/autonomous-catalog.json (2-step confirmationToken on deletions).",
            inputSchema = toolSchema(required = listOf("configKind", "operation")) {
                putJsonObject("projectPath") { stringProp("Robot repository root (defaults to active Studio bridge project)") }
                putJsonObject("configKind") {
                    enumProp("Configuration artifact to inspect or mutate", listOf("PROJECT_METADATA", "ACTION_CATALOG", "AUTONOMOUS_CATALOG"))
                }
                putJsonObject("operation") {
                    enumProp(
                        "Operation (subset depends on configKind)",
                        listOf("INSPECT", "UPSERT", "PATCH", "UPSERT_ACTION", "DELETE_ACTION", "UPSERT_ENTRY", "DELETE_ENTRY"),
                    )
                }
                putJsonObject("documentJson") { stringProp("Full JSON document (UPSERT), ActionDescriptor (UPSERT_ACTION), or AutonomousCatalogEntry (UPSERT_ENTRY)") }
                putJsonObject("patchJson") { stringProp("RFC-7396 + keyed-array JSON merge patch for PATCH") }
                putJsonObject("targetKeyOrEntryId") { stringProp("Action key (DELETE_ACTION) or entryId (DELETE_ENTRY)") }
                putJsonObject("confirmationToken") { stringProp("Confirmation token required on 2nd call for DELETE_ACTION / DELETE_ENTRY") }
                putJsonObject("expectedRevision") { stringProp("Optional CAS project revision SHA-256") }
            },
        ) { request ->
            guardedTool {
                val args = request.arguments
                okJsonResult(
                    runBlocking {
                        val root = engine.resolveTargetRoot(args.optStr("projectPath"))
                        mutators.configAndField.mutateProjectConfig(
                            projectRoot = root,
                            configKindRaw = args.reqStr("configKind"),
                            operationRaw = args.reqStr("operation"),
                            documentJson = args.optStr("documentJson"),
                            patchJson = args.optStr("patchJson"),
                            targetKeyOrEntryId = args.optStr("targetKeyOrEntryId"),
                            confirmationToken = args.optStr("confirmationToken"),
                            expectedRevision = args.optStr("expectedRevision"),
                        )
                    },
                )
            }
        }

        server.addTool(
            name = "ares_field_mutate",
            description = "Inspect, install bundled field presets, import WPILib/Limelight AprilTags, or patch/upsert canonical league field.json with .ares/history/fields backup & JGit snapshot.",
            inputSchema = toolSchema(required = listOf("operation")) {
                putJsonObject("projectPath") { stringProp("Robot repository root (defaults to active Studio bridge project)") }
                putJsonObject("operation") {
                    enumProp(
                        "Field configuration operation",
                        listOf("INSPECT", "INSTALL_PRESET", "IMPORT_APRILTAGS", "UPSERT", "PATCH"),
                    )
                }
                putJsonObject("presetName") {
                    enumProp(
                        "Field preset for INSTALL_PRESET",
                        listOf("LEAGUE_DEFAULT", "VISION_CALIBRATION_TARGET_1M", "THREE_CONE_SLALOM_COURSE", "CLEAR_PRACTICE_FLOOR"),
                    )
                }
                putJsonObject("aprilTagSourceJson") { stringProp("Raw WPILib AprilTagFieldLayout or Limelight .fmap JSON for IMPORT_APRILTAGS") }
                putJsonObject("aprilTagSourcePath") { stringProp("Repo-relative path to WPILib/Limelight AprilTag file for IMPORT_APRILTAGS") }
                putJsonObject("defaultFamily") { stringProp("Default AprilTag family override (e.g. '36h11')") }
                putJsonObject("defaultTagSizeMeters") { numberProp("Default AprilTag size in meters (e.g. 0.1651 FRC / 0.127 FTC)") }
                putJsonObject("documentJson") { stringProp("Complete RobotFieldConfig JSON for UPSERT") }
                putJsonObject("patchJson") { stringProp("RFC-7396 + keyed-array JSON merge patch for PATCH") }
                putJsonObject("expectedRevision") { stringProp("Optional CAS project revision SHA-256") }
            },
        ) { request ->
            guardedTool {
                val args = request.arguments
                okJsonResult(
                    runBlocking {
                        val root = engine.resolveTargetRoot(args.optStr("projectPath"))
                        mutators.configAndField.mutateField(
                            projectRoot = root,
                            operationRaw = args.reqStr("operation"),
                            presetName = args.optStr("presetName"),
                            aprilTagSourceJson = args.optStr("aprilTagSourceJson"),
                            aprilTagSourcePath = args.optStr("aprilTagSourcePath"),
                            defaultFamily = args.optStr("defaultFamily"),
                            defaultTagSizeMeters = args.optDouble("defaultTagSizeMeters"),
                            documentJson = args.optStr("documentJson"),
                            patchJson = args.optStr("patchJson"),
                            expectedRevision = args.optStr("expectedRevision"),
                        )
                    },
                )
            }
        }
    }
}
