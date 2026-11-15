package com.ares.analytics.mcp.tools

import com.ares.analytics.mcp.engine.HeadlessProjectEngine
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.putJsonObject
import java.io.File

/** Registers `ares_project_init`, `ares_project_inspect`, and `ares_project_validate` (present in both FULL and COMPACT). */
internal object ProjectLifecycleTools {
    fun register(server: Server, engine: HeadlessProjectEngine) {
        server.addTool(
            name = "ares_project_init",
            description = "Initialize a new standalone ARES robot repository (FTC, FRC, or XRP) from verified starters into an empty/non-.ares directory.",
            inputSchema = toolSchema(required = listOf("targetDir", "league", "displayName")) {
                putJsonObject("targetDir") { stringProp("Destination project directory (must not already contain .ares)") }
                putJsonObject("league") { enumProp("Robot league", listOf("FTC", "FRC", "XRP")) }
                putJsonObject("displayName") { stringProp("Human-readable robot display name (1..80 chars)") }
                putJsonObject("projectId") { stringProp("Optional stable project identifier override") }
                putJsonObject("teamId") { stringProp("Team number or key (default '00000')") }
                putJsonObject("seasonId") { stringProp("Season identifier (default '2026')") }
                putJsonObject("robotId") { stringProp("Robot class/key identifier starting with a letter (default 'AresRobot')") }
                putJsonObject("initGit") { boolProp("Initialize local Git repository and baseline snapshot (default true)") }
            },
        ) { request ->
            guardedTool {
                val args = request.arguments
                val result = kotlinx.coroutines.runBlocking {
                    engine.initProject(
                        targetDir = File(args.reqStr("targetDir")),
                        leagueRaw = args.reqStr("league"),
                        projectIdHint = args.optStr("projectId"),
                        displayName = args.reqStr("displayName"),
                        teamId = args.optStr("teamId") ?: "00000",
                        seasonId = args.optStr("seasonId") ?: "2026",
                        robotId = args.optStr("robotId") ?: "AresRobot",
                        initGit = args.optBool("initGit", default = true),
                    )
                }
                okJsonResult(result)
            }
        }

        server.addTool(
            name = "ares_project_inspect",
            description = "Inspect an ARES robot project: metadata, league, coordinate convention, effective action/tuning keys, file ownership classes, and Studio bridge status.",
            inputSchema = toolSchema {
                putJsonObject("projectPath") { stringProp("Path to robot repo root (defaults to active Studio bridge project)") }
                putJsonObject("includeFiles") { boolProp("Include source file ownership classifications (default true)") }
                putJsonObject("includeCatalog") { boolProp("Include derived action & tuning keys (default true)") }
            },
        ) { request ->
            guardedTool {
                val args = request.arguments
                val result = kotlinx.coroutines.runBlocking {
                    val root = engine.resolveTargetRoot(args.optStr("projectPath"))
                    engine.inspectProject(
                        projectRoot = root,
                        includeFiles = args.optBool("includeFiles", default = true),
                        includeCatalog = args.optBool("includeCatalog", default = true),
                    )
                }
                okJsonResult(result)
            }
        }

        server.addTool(
            name = "ares_project_validate",
            description = "Run RobotProjectAssembler cross-document validation and RobotProjectCompiler IR lowering on an ARES project.",
            inputSchema = toolSchema {
                putJsonObject("projectPath") { stringProp("Path to robot repo root (defaults to active Studio bridge project)") }
            },
        ) { request ->
            guardedTool {
                val args = request.arguments
                val result = kotlinx.coroutines.runBlocking {
                    val root = engine.resolveTargetRoot(args.optStr("projectPath"))
                    engine.validateProject(root)
                }
                okJsonResult(result)
            }
        }
    }
}
