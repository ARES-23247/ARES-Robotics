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
            description = "Preview or write zero-GC USER-OWNED Kotlin skeletons, 4-file real vendor-SDK mechanism bundles (VENDOR_SUBSYSTEM_BUNDLE), or atomic HAND_AUTHORED hybrid subsystem registrations (REGISTER_EXISTING_HYBRID).",
            inputSchema = toolSchema(required = listOf("league", "kind", "name")) {
                putJsonObject("projectPath") { stringProp("Robot repository root (defaults to active Studio bridge project)") }
                putJsonObject("league") { enumProp("Target league", listOf("FTC", "FRC")) }
                putJsonObject("kind") {
                    enumProp(
                        "Kotlin scaffold kind",
                        listOf(
                            "HARDWARE_IO",
                            "SEASON_REDUX_SLICE",
                            "TELEOP_OPMODE",
                            "AUTO_OPMODE",
                            "TIMED_ROBOT_HOOK",
                            "UNIT_TEST",
                            "VENDOR_SUBSYSTEM_BUNDLE",
                            "REGISTER_EXISTING_HYBRID",
                        ),
                    )
                }
                putJsonObject("name") { stringProp("Primary Kotlin class/object/base name (e.g. 'Shooter'). For UNIT_TEST, use the hardware adapter name or its Test-suffixed name in the same package.") }
                putJsonObject("mode") { enumProp("PREVIEW (default) or APPLY", listOf("PREVIEW", "APPLY")) }
                putJsonObject("packageName") { stringProp("Optional Kotlin package override") }
                putJsonObject("relativePath") { stringProp("Optional repo-relative output path override (single-file kinds)") }
                putJsonObject("vendorProfile") {
                    enumProp(
                        "Vendor SDK profile for VENDOR_SUBSYSTEM_BUNDLE",
                        listOf(
                            "FRC_TALONFX_POSITION",
                            "FRC_TALONFX_VELOCITY",
                            "FRC_SPARKMAX_BRUSHLESS",
                            "FRC_SPARKFLEX_VORTEX",
                            "FRC_PHOTONVISION_APRILTAG",
                            "FTC_DCMOTOREX_PIDF",
                            "FTC_SERVO_ACTUATOR",
                            "FTC_LIMELIGHT3A_PINPOINT",
                        ),
                    )
                }
                putJsonObject("deviceNameOrCanId") { stringProp("Optional default CAN ID or hardwareMap device name for VENDOR_SUBSYSTEM_BUNDLE") }
                putJsonObject("registerHybrid") { boolProp("Automatically register HAND_AUTHORED hybrid metadata on VENDOR_SUBSYSTEM_BUNDLE (default true)") }
                putJsonObject("documentId") { stringProp("Optional .aressubsystem documentId slug (REGISTER_EXISTING_HYBRID)") }
                putJsonObject("sourceFiles") { stringArrayProp("Repo-relative USER-OWNED .kt paths (required for REGISTER_EXISTING_HYBRID)") }
                putJsonObject("subsystemClassName") { stringProp("Fully qualified Subsystem class name (REGISTER_EXISTING_HYBRID)") }
                putJsonObject("ioContractClassName") { stringProp("Fully qualified IO interface name (REGISTER_EXISTING_HYBRID)") }
                putJsonObject("hardwareAdapterClassName") { stringProp("Fully qualified hardware IO implementation class name (REGISTER_EXISTING_HYBRID)") }
                putJsonObject("mockAdapterClassName") { stringProp("Optional fully qualified mock/simulator IO adapter class name (REGISTER_EXISTING_HYBRID)") }
                putJsonObject("actionKeys") { stringArrayProp("Optional capability action keys to declare on subsystem and upsert in action-catalog.json") }
                putJsonObject("confirmationToken") { stringProp("Confirmation token from PREVIEW when overwriting an existing file") }
            },
        ) { request ->
            guardedTool {
                val args = request.arguments
                val kind = args.reqStr("kind").trim().uppercase()
                okJsonResult(
                    runBlocking {
                        val root = engine.resolveTargetRoot(args.optStr("projectPath"))
                        when (kind) {
                            "VENDOR_SUBSYSTEM_BUNDLE", "REGISTER_EXISTING_HYBRID" -> mutators.hybridRegistrar.scaffoldFromTool(
                                projectRoot = root,
                                leagueRaw = args.reqStr("league"),
                                kindRaw = kind,
                                name = args.reqStr("name"),
                                modeRaw = args.optStr("mode") ?: "PREVIEW",
                                packageName = args.optStr("packageName"),
                                vendorProfile = args.optStr("vendorProfile"),
                                deviceNameOrCanId = args.optStr("deviceNameOrCanId"),
                                registerHybrid = args.optBool("registerHybrid", true),
                                documentId = args.optStr("documentId"),
                                sourceFiles = args.optStrList("sourceFiles"),
                                subsystemClassName = args.optStr("subsystemClassName"),
                                ioContractClassName = args.optStr("ioContractClassName"),
                                hardwareAdapterClassName = args.optStr("hardwareAdapterClassName"),
                                mockAdapterClassName = args.optStr("mockAdapterClassName"),
                                actionKeys = args.optStrList("actionKeys"),
                                confirmationToken = args.optStr("confirmationToken"),
                            )
                            else -> mutators.scaffoldKotlin(
                                projectRoot = root,
                                leagueRaw = args.reqStr("league"),
                                kindRaw = kind,
                                name = args.reqStr("name"),
                                modeRaw = args.optStr("mode") ?: "PREVIEW",
                                packageName = args.optStr("packageName"),
                                relativePathOverride = args.optStr("relativePath"),
                                confirmationToken = args.optStr("confirmationToken"),
                            )
                        }
                    },
                )
            }
        }
    }
}
