package com.ares.analytics.mcp.resources

import com.ares.analytics.mcp.engine.HeadlessProjectEngine
import com.ares.analytics.mcp.verify.ProjectGradleVerifier
import com.ares.analytics.shared.AppJson
import com.ares.analytics.shared.AppJsonPretty
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/** Registers static `ares://` resources and URI templates for lazy context loading. */
internal object AresMcpResources {
    fun register(server: Server, engine: HeadlessProjectEngine) {
        server.addResource(
            uri = "ares://conventions/coordinates-and-safety",
            name = "ARES Coordinates and Safety Conventions",
            description = "CCW-positive radians, Limelight/Pinpoint signs, RobotClock discipline, offline-first limits, and fail-closed hardware leases.",
            mimeType = "text/markdown",
        ) { request ->
            textResource(request.uri, "text/markdown", coordinatesAndSafetyText())
        }

        server.addResource(
            uri = "ares://conventions/overview",
            name = "ARES Project MCP Overview",
            description = "Overview of ares-project-mcp dual-mode bridge, ownership rules, and tool workflow.",
            mimeType = "text/markdown",
        ) { request ->
            textResource(request.uri, "text/markdown", overviewText())
        }

        server.addResource(
            uri = "ares://project/summary",
            name = "Active ARES Project Summary",
            description = "Read-only JSON summary of the currently attached Studio project or most recently inspected project.",
            mimeType = "application/json",
        ) { request ->
            val json = runBlocking {
                val root = resolveActiveOrLatestRoot(engine)
                if (root == null) {
                    """{"attached":false,"message":"No active Studio bridge project or previously inspected project yet."}"""
                } else {
                    AppJsonPretty.encodeToString(engine.inspectProject(root, includeFiles = true, includeCatalog = false))
                }
            }
            textResource(request.uri, "application/json", json)
        }

        server.addResource(
            uri = "ares://project/catalog",
            name = "Active ARES Action & Tuning Catalog",
            description = "Effective derived action keys and tuning parameter keys for the attached project.",
            mimeType = "application/json",
        ) { request ->
            val json = runBlocking {
                val root = resolveActiveOrLatestRoot(engine)
                if (root == null) {
                    """{"attached":false,"actions":[],"tuningKeys":[]}"""
                } else {
                    val inspection = engine.inspectProject(root, includeFiles = false, includeCatalog = true)
                    AppJsonPretty.encodeToString(
                        buildJsonObject {
                            put("projectRoot", inspection.projectRoot)
                            put("projectId", inspection.projectId ?: "")
                            putJsonArray("actions") { inspection.actionKeys.forEach { add(JsonPrimitive(it)) } }
                            putJsonArray("tuningKeys") { inspection.tuningKeys.forEach { add(JsonPrimitive(it)) } }
                        },
                    )
                }
            }
            textResource(request.uri, "application/json", json)
        }

        server.addResource(
            uri = "ares://verification/latest",
            name = "Latest Project Validation Summary",
            description = "Latest RobotProjectAssembler, compiler IR, and ares_project_verify Gradle verification summary.",
            mimeType = "application/json",
        ) { request ->
            val json = runBlocking {
                val root = resolveActiveOrLatestRoot(engine)
                if (root == null) {
                    """{"attached":false,"message":"Run ares_project_inspect or ares_project_validate first."}"""
                } else {
                    val baseObj = AppJson.parseToJsonElement(AppJsonPretty.encodeToString(engine.validateProject(root))).jsonObject
                    val latestVerifyFile = File(root, ProjectGradleVerifier.LATEST_VERIFY_RELATIVE_PATH)
                    if (!latestVerifyFile.isFile) {
                        AppJsonPretty.encodeToString(JsonObject.serializer(), baseObj)
                    } else {
                        val latestVerify = runCatching { AppJson.parseToJsonElement(latestVerifyFile.readText()) }.getOrNull()
                        val merged = buildJsonObject {
                            baseObj.forEach { (k, v) -> put(k, v) }
                            if (latestVerify != null) put("latestProjectVerify", latestVerify)
                        }
                        AppJsonPretty.encodeToString(JsonObject.serializer(), merged)
                    }
                }
            }
            textResource(request.uri, "application/json", json)
        }

        server.addResource(
            uri = "ares://runs",
            name = "Recorded runs",
            description = "Latest 100 completed recordings in the active Studio workspace, newest first.",
            mimeType = "application/json",
        ) { request -> textResource(request.uri, "application/json", engine.bridgeClient.recordedRuns()) }

        server.addResource(
            uri = "ares://templates/catalog",
            name = "ARES Built-In Templates, Presets & Vendor Profiles Catalog",
            description = "JSON inventory of all 31 subsystem templates, 4 drivetrain presets, 4 field presets, and 8 vendor SDK scaffold profiles.",
            mimeType = "application/json",
        ) { request ->
            textResource(request.uri, "application/json", SchemaExemplarCatalog.renderTemplatesCatalogJson())
        }

        server.addResourceTemplate(
            uriTemplate = "ares://runs/{sessionId}",
            name = "Recorded run evidence",
            description = "Run-wide measured summaries with units, sample times, source digests and topic inventory. Null metrics are unavailable.",
            mimeType = "application/json",
        ) { request, _ ->
            val encodedId = request.uri.removePrefix("ares://runs/")
            val id = try {
                java.net.URLDecoder.decode(encodedId.replace("+", "%2B"), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid percent-encoding in sessionId: '$encodedId'")
            }
            textResource(request.uri, "application/json", engine.bridgeClient.recordedRuns(id))
        }

        server.addResourceTemplate(
            uriTemplate = "ares://schemas/{documentKind}",
            name = "ARES Canonical Document Schema Reference",
            description = "Schema fields, contracts, and canonical codec-validated JSON exemplar for subsystem, drivetrain, superstructure, routine, controls, controller, tuning, project, action-catalog, autonomous-catalog, or field.",
            mimeType = "text/markdown",
        ) { request, _ ->
            val docKind = request.uri.substringAfter("ares://schemas/").trim().lowercase()
            textResource(request.uri, "text/markdown", schemaReference(docKind))
        }

        server.addResourceTemplate(
            uriTemplate = "ares://cookbook/{league}/{pattern}",
            name = "ARES Code Cookbook",
            description = "Working zero-GC Kotlin patterns: ftc/hardware-io, ftc/teleop-context, ftc/limelight-pinpoint, frc/swerve-season-reducer, frc/phoenix-talonfx-io, frc/photon-vision, shared/redux-slice, shared/mock-io-unit-test, shared/mock-io-test.",
            mimeType = "text/markdown",
        ) { request, _ ->
            val suffix = request.uri.substringAfter("ares://cookbook/").trim().lowercase()
            textResource(request.uri, "text/markdown", cookbookSnippet(suffix))
        }
    }

    private suspend fun resolveActiveOrLatestRoot(engine: HeadlessProjectEngine): File? {
        val bridge = engine.bridgeClient.health()
        if (bridge.connected) {
            bridge.activeProjectRoot?.takeIf { it.isNotBlank() }?.let { path ->
                val f = File(path)
                if (f.isDirectory) return f
            }
        }
        return engine.latestProjectRoot()?.let(::File)?.takeIf { it.isDirectory }
    }

    private fun textResource(uri: String, mimeType: String, content: String): ReadResourceResult =
        ReadResourceResult(
            contents = listOf(TextResourceContents(text = content, uri = uri, mimeType = mimeType)),
        )

    private fun overviewText(): String = """
        |# ARES Project MCP (`ares-project-mcp`)
        |
        |- **Dual-mode execution**: Runs headless against `.ares/*` files when Studio is closed; automatically upgrades to Studio's authenticated `127.0.0.1` loopback bridge (`mcp-bridge.json`) to refresh open sessions when Studio is running.
        |- **Clock & safety**: Always inject `com.areslib.util.RobotClock` and neutralize actuators on `disable()` / `stop()` / `close()` / stale feedback.
        |- **Ownership**: Batch codegen never modifies `// ARES OWNERSHIP: USER-OWNED` files. `GENERATED STARTER` updates require a `PREVIEW` SHA-256 `confirmationToken` before `APPLY`.
    """.trimMargin()

    private fun coordinatesAndSafetyText(): String = """
        |# ARES Coordinates, Timing, and Hardware Safety Rules
        |
        |1. **Heading & Coordinates**: Internally all angles and angular velocities are **CCW-positive in radians**.
        |   - FTC: `CENTER_ORIGIN_CCW` (+X downrange/right depending on field frame, CCW+ heading).
        |   - FRC: `BLUE_CORNER_ORIGIN_CCW`.
        |   - **Limelight**: Use `-robotPoseTargetSpace.rotation.y` (never `robotPoseTargetSpace.rotation.z`).
        |   - **Pinpoint**: Negate once at the IO boundary only; never stack negative signs downstream.
        |2. **Deterministic Clock (`RobotClock`)**: Use `com.areslib.util.RobotClock.currentTimeMillis()` / `RobotClock.nanoTime()` everywhere in robot code. Forbid `System.currentTimeMillis()`, `System.nanoTime()`, `Thread.sleep()`, and `ElapsedTime()`.
        |3. **Fail-Closed Actuators**: Every actuator output requires `configured && enabled && !faulted && freshFeedback`, with explicit neutral `0.0` / `NeutralOut()` / `stopMotor()` on `disable()`, `stop()`, and `close()`.
        |4. **Zero-GC Control Loops & Redux Purity**: No heap allocations inside `periodic()`, `reduce()`, `readInputs()`, or `writeOutputs()`. Keep `*State` properties immutable (`val`).
        |5. **Offline-First Robot Code**: No cloud/HTTP client calls in `TeamCode/` or robot `src/main/kotlin`. NanoHTTPD telemetry runs on port `5002`; NT4 telemetry runs offline.
        |6. **Truth Separation**: Never consume `ARES/TruePose` or `simTruth` inside estimators or controllers.
    """.trimMargin()

    private fun schemaReference(kind: String): String = SchemaExemplarCatalog.renderSchemaReference(kind)

    private fun cookbookSnippet(path: String): String = SchemaExemplarCatalog.cookbookSnippet(path) ?: when (path) {
        "ftc/hardware-io" -> "### FTC Hardware IO Cookbook\nCache encoder/limit reads once inside `readInputs()`, stamp `lastFeedbackMillis = com.areslib.util.RobotClock.currentTimeMillis()`, enforce freshness & finite bounds inside `writeOutputs()`, and zero all motors in `disable()`, `stop()`, and `close()`."
        "ftc/teleop-context" -> "### FTC TeleOp Context Cookbook\nAvoid allocations inside `runOpMode()` loops; delegate velocity commands in CCW-positive radians/sec and coerce finite stick axes."
        "frc/swerve-season-reducer" -> "### FRC Swerve Season Reducer Cookbook\nKeep `*State` data classes immutable (`val` properties only), return `state.copy(...)`, and never import `com.ctre.*` or `com.revrobotics.*` into reducer files."
        "frc/phoenix-talonfx-io" -> "### FRC Phoenix TalonFX IO Cookbook\nApply `NeutralOut()` in `disable()`/`stop()`/`close()`, clamp requested voltage within `[-12.0, 12.0]`, and stamp cached sensor signals with `RobotClock.currentTimeMillis()`."
        "shared/mock-io-unit-test" -> "### Shared Mock-IO Unit Test Cookbook\nCall `RobotClock.useMockTime(1_000L)` inside `try { ... } finally { RobotClock.useSystemTime() }`, verifying actuation, stale-feedback zeroing, and disable neutralization."
        else -> "### Cookbook index\nAvailable patterns: `ftc/hardware-io`, `ftc/teleop-context`, `ftc/limelight-pinpoint`, `frc/swerve-season-reducer`, `frc/phoenix-talonfx-io`, `frc/photon-vision`, `shared/redux-slice`, `shared/mock-io-unit-test`, `shared/mock-io-test`."
    }
}
