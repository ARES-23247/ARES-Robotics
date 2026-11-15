package com.ares.analytics.mcp.tools

import com.ares.analytics.mcp.engine.HeadlessProjectEngine
import com.ares.analytics.mcp.engine.HeadlessProjectMutators
import com.ares.analytics.mcp.engine.JGitSnapshotSafety
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.putJsonObject

/** Registers `ares_codegen_reconcile` and `ares_kotlin_scaffold` (both FULL and COMPACT modes). */
internal object CodegenAndScaffoldTools {
    fun register(server: Server, engine: HeadlessProjectEngine, mutators: HeadlessProjectMutators) {
        server.addTool(
            name = "ares_codegen_reconcile",
            description = "Run ARESLib project codegen and SubsystemStarterReconciler in PREVIEW or APPLY mode (protects USER-OWNED files; requires preview confirmationToken for GENERATED STARTER replacements), or list/restore JGit safety snapshots.",
            inputSchema = toolSchema(required = listOf("mode")) {
                putJsonObject("projectPath") { stringProp("Robot repository root (defaults to active Studio bridge project)") }
                putJsonObject("mode") {
                    enumProp(
                        "Execution mode",
                        listOf("PREVIEW", "APPLY", "SNAPSHOT_LIST", "SNAPSHOT_RESTORE"),
                    )
                }
                putJsonObject("confirmationToken") { stringProp("SHA-256 confirmation token returned by PREVIEW when starter files would be replaced") }
                putJsonObject("snapshotRef") { stringProp("Snapshot ref under refs/ares-mcp/snapshots/ when mode='SNAPSHOT_RESTORE'") }
            },
        ) { request ->
            guardedTool {
                val args = request.arguments
                val mode = args.reqStr("mode").trim().uppercase()
                runBlocking {
                    val root = engine.resolveTargetRoot(args.optStr("projectPath"))
                    when (mode) {
                        "SNAPSHOT_LIST" -> okJsonResult(JGitSnapshotSafety.listSnapshots(root))
                        "SNAPSHOT_RESTORE" -> okJsonResult(
                            JGitSnapshotSafety.restoreSnapshot(root, args.reqStr("snapshotRef")),
                        )
                        else -> okJsonResult(
                            engine.reconcileCodegen(
                                projectRoot = root,
                                modeRaw = mode,
                                confirmationToken = args.optStr("confirmationToken"),
                            ),
                        )
                    }
                }
            }
        }

        server.addTool(
            name = "ares_kotlin_scaffold",
            description = "Preview or write zero-GC USER-OWNED Kotlin skeletons (HARDWARE_IO, SEASON_REDUX_SLICE, TELEOP_OPMODE, AUTO_OPMODE, TIMED_ROBOT_HOOK, UNIT_TEST) with two-step preview confirmation and JGit snapshots.",
            inputSchema = toolSchema(required = listOf("league", "kind", "name")) {
                putJsonObject("projectPath") { stringProp("Robot repository root (defaults to active Studio bridge project)") }
                putJsonObject("league") { enumProp("Target league", listOf("FTC", "FRC")) }
                putJsonObject("kind") {
                    enumProp(
                        "Kotlin scaffold kind",
                        listOf("HARDWARE_IO", "SEASON_REDUX_SLICE", "TELEOP_OPMODE", "AUTO_OPMODE", "TIMED_ROBOT_HOOK", "UNIT_TEST"),
                    )
                }
                putJsonObject("name") { stringProp("Primary Kotlin class/object name") }
                putJsonObject("mode") { enumProp("PREVIEW (default) or APPLY", listOf("PREVIEW", "APPLY")) }
                putJsonObject("packageName") { stringProp("Optional Kotlin package override") }
                putJsonObject("relativePath") { stringProp("Optional repo-relative output path override") }
                putJsonObject("confirmationToken") { stringProp("Required confirmationToken from PREVIEW when overwriting an existing file") }
            },
        ) { request ->
            guardedTool {
                val args = request.arguments
                okJsonResult(
                    runBlocking {
                        val root = engine.resolveTargetRoot(args.optStr("projectPath"))
                        mutators.scaffoldKotlin(
                            projectRoot = root,
                            leagueRaw = args.reqStr("league"),
                            kindRaw = args.reqStr("kind"),
                            name = args.reqStr("name"),
                            modeRaw = args.optStr("mode") ?: "PREVIEW",
                            packageName = args.optStr("packageName"),
                            relativePathOverride = args.optStr("relativePath"),
                            confirmationToken = args.optStr("confirmationToken"),
                        )
                    },
                )
            }
        }
    }
}
