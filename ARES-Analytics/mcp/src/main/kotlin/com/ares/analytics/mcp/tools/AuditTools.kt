package com.ares.analytics.mcp.tools

import com.ares.analytics.mcp.audit.KotlinInvariantAuditor
import com.ares.analytics.mcp.engine.HeadlessProjectEngine
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.putJsonObject

/** Registers `ares_code_audit` running rules `ARES-GC-001` through `ARES-SIM-008`. */
internal object AuditTools {
    fun register(server: Server, engine: HeadlessProjectEngine) {
        server.addTool(
            name = "ares_code_audit",
            description = "Run deterministic ARES static invariant audit (ARES-GC-001 through ARES-SIM-008: zero-GC loops, RobotClock, Redux purity, disable/fault neutralization, cached reads, CCW coordinates, offline-first, truth isolation).",
            inputSchema = toolSchema {
                putJsonObject("projectPath") { stringProp("Robot repository root (defaults to active Studio bridge project)") }
                putJsonObject("paths") { stringArrayProp("Optional repo-relative Kotlin/Java paths (defaults to all auditable non-generated sources)") }
            },
        ) { request ->
            guardedTool {
                val args = request.arguments
                val report = runBlocking {
                    val root = engine.resolveTargetRoot(args.optStr("projectPath"))
                    KotlinInvariantAuditor.auditProject(
                        projectRoot = root,
                        explicitRelativePaths = args.optStrList("paths"),
                    )
                }
                okJsonResult(report)
            }
        }
    }
}
