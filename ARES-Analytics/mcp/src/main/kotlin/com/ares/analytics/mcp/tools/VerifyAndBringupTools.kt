package com.ares.analytics.mcp.tools

import com.ares.analytics.mcp.bringup.HardwareBringupEngine
import com.ares.analytics.mcp.engine.HeadlessProjectEngine
import com.ares.analytics.mcp.engine.HeadlessProjectMutators
import com.ares.analytics.mcp.verify.ProjectGradleVerifier
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.putJsonObject

/**
 * Registers `ares_hardware_bringup` and `ares_project_verify` in both `FULL` and `COMPACT` modes.
 */
internal object VerifyAndBringupTools {
    fun register(
        server: Server,
        engine: HeadlessProjectEngine,
        mutators: HeadlessProjectMutators,
        verifier: ProjectGradleVerifier = ProjectGradleVerifier(engine),
    ) {
        val bringup = HardwareBringupEngine(engine, mutators.templateAndPatch)

        server.addTool(
            name = "ares_hardware_bringup",
            description = "Run hardware bring-up without acquiring physical leases or editing CTRE TunerConstants.java: audit inventory, list/install pinned 2026 FRC vendordeps, import TunerConstants into .aresdrivetrain/.arestuning, and generate/validate FTC Robot Controller Hub XML.",
            inputSchema = toolSchema(required = listOf("operation")) {
                putJsonObject("projectPath") { stringProp("Robot repository root (defaults to active Studio bridge project)") }
                putJsonObject("operation") {
                    enumProp(
                        "Bring-up operation to perform",
                        listOf(
                            "AUDIT_INVENTORY",
                            "LIST_VENDORDEPS",
                            "INSTALL_VENDORDEP",
                            "IMPORT_CTRE_TUNER_CONSTANTS",
                            "GENERATE_FTC_HUB_XML",
                            "VALIDATE_FTC_HUB_XML",
                        ),
                    )
                }
                putJsonObject("tunerConstantsPath") { stringProp("Optional repo-relative or absolute path to CTRE TunerConstants.java") }
                putJsonObject("vendordepPreset") {
                    enumProp(
                        "Pinned 2026 FRC vendordep preset for INSTALL_VENDORDEP",
                        listOf("PHOENIX6", "REVLIB", "PHOTONLIB", "WPILIB_NEW_COMMANDS"),
                    )
                }
                putJsonObject("vendordepJson") { stringProp("Custom vendordep JSON string when not using vendordepPreset") }
                putJsonObject("xmlOutputPath") { stringProp("Optional FTC XML output or validation path relative to project root") }
                putJsonObject("xmlContent") { stringProp("Inline FTC hardware configuration XML to validate") }
                putJsonObject("confirmationToken") { stringProp("Confirmation token required when overwriting an existing FTC XML file") }
            },
        ) { request ->
            guardedTool {
                val args = request.arguments
                val result = runBlocking {
                    val root = engine.resolveTargetRoot(args.optStr("projectPath"))
                    bringup.execute(
                        projectRoot = root,
                        operationRaw = args.reqStr("operation"),
                        tunerConstantsPath = args.optStr("tunerConstantsPath"),
                        vendordepPreset = args.optStr("vendordepPreset"),
                        vendordepJson = args.optStr("vendordepJson"),
                        xmlOutputPath = args.optStr("xmlOutputPath"),
                        xmlContent = args.optStr("xmlContent"),
                        confirmationToken = args.optStr("confirmationToken"),
                    )
                }
                okJsonResult(result)
            }
        }

        server.addTool(
            name = "ares_project_verify",
            description = "Run serialized headless verification across schema/compiler IR, all 12 Kotlin invariants, Gradle compile, and unit tests. PHYSICAL_VALIDATION always remains NOT_RUN.",
            inputSchema = toolSchema {
                putJsonObject("projectPath") { stringProp("Robot repository root (defaults to active Studio bridge project)") }
                putJsonObject("mode") {
                    enumProp(
                        "Verification depth (defaults to UNIT_TEST)",
                        listOf("COMPILE_ONLY", "UNIT_TEST", "FULL_VERIFICATION"),
                    )
                }
                putJsonObject("testFilter") { stringProp("Optional Gradle --tests filter pattern") }
                putJsonObject("timeoutSeconds") { numberProp("Subprocess timeout in seconds (15..600, default 180)") }
            },
        ) { request ->
            guardedTool {
                val args = request.arguments
                val result = runBlocking {
                    val root = engine.resolveTargetRoot(args.optStr("projectPath"))
                    verifier.verify(
                        projectRoot = root,
                        modeRaw = args.optStr("mode") ?: "UNIT_TEST",
                        testFilter = args.optStr("testFilter"),
                        timeoutSeconds = args.optInt("timeoutSeconds") ?: 180,
                    )
                }
                okJsonResult(result)
            }
        }
    }
}
