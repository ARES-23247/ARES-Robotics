package com.ares.analytics.mcp.resources

import com.ares.analytics.mcp.engine.HeadlessProjectEngine
import com.ares.analytics.shared.AppJsonPretty
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive
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
            description = "Latest RobotProjectAssembler and compiler IR validation state for the attached project.",
            mimeType = "application/json",
        ) { request ->
            val json = runBlocking {
                val root = resolveActiveOrLatestRoot(engine)
                if (root == null) {
                    """{"attached":false,"message":"Run ares_project_inspect or ares_project_validate first."}"""
                } else {
                    AppJsonPretty.encodeToString(engine.validateProject(root))
                }
            }
            textResource(request.uri, "application/json", json)
        }

        server.addResourceTemplate(
            uriTemplate = "ares://schemas/{documentKind}",
            name = "ARES Canonical Document Schema Reference",
            description = "Schema fields and validation rules for subsystem, drivetrain, superstructure, routine, controls, or tuning.",
            mimeType = "text/markdown",
        ) { request, _ ->
            val docKind = request.uri.substringAfter("ares://schemas/").trim().lowercase()
            textResource(request.uri, "text/markdown", schemaReference(docKind))
        }

        server.addResourceTemplate(
            uriTemplate = "ares://cookbook/{league}/{pattern}",
            name = "ARES Code Cookbook",
            description = "Working zero-GC Kotlin patterns: ftc/hardware-io, ftc/teleop-context, frc/swerve-season-reducer, frc/phoenix-talonfx-io, shared/mock-io-unit-test.",
            mimeType = "text/markdown",
        ) { request, _ ->
            val suffix = request.uri.substringAfter("ares://cookbook/").trim().lowercase()
            textResource(request.uri, "text/markdown", cookbookSnippet(suffix))
        }
    }

    private suspend fun resolveActiveOrLatestRoot(engine: HeadlessProjectEngine): File? {
        val bridge = engine.bridgeClient.health()
        bridge.activeProjectRoot?.takeIf { it.isNotBlank() }?.let { path ->
            val f = File(path)
            if (f.isDirectory) return f
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

    private fun schemaReference(kind: String): String = when (kind) {
        "subsystem" -> "## `.ares/subsystems/<id>.aressubsystem`\nCanonical fields: `schemaVersion=11`, `documentId` (lowercase slug), `uid`, `displayName`, `kotlinTypeName` (PascalCase), `platform` (`FTC|FRC|XRP`), `hardware[]`, `stateFields[]`, `controlLoops[]`, `tuningParameters[]`, `template`, `implementation` (`kind`: `DECLARATIVE_GENERATED|GENERATED_STARTER|HAND_AUTHORED`), `safety`."
        "drivetrain" -> "## `.ares/drivetrains/<id>.aresdrivetrain`\nCanonical fields: `schemaVersion=1`, `uid`, `drivebaseId`, `kind` (`FTC_MECANUM|FRC_SWERVE|XRP_DIFFERENTIAL|...`), `platform`, `components[]`, `geometry` (`wheelDiameterMeters`, `trackWidthMeters`, `wheelBaseMeters`, `driveGearRatio`, `maxLinearSpeedMetersPerSecond`, `maxAngularSpeedRadiansPerSecond`), `localization`."
        "superstructure" -> "## `.ares/superstructures/<id>.aressuperstructure`\nCanonical fields: `superstructureId`, `initialStateId`, `faultStateId`, `disabledStateId`, `disabledPolicy=FORCE_SAFE_AND_REJECT_REQUESTS`, `states[]` (every state commands the exact same target field set), `transitions[]`, `interlocks[]`, `healthFallbacks[]`."
        "routine" -> "## `.ares/routines/<id>.aresroutine`\nCanonical fields: `schemaVersion=2`, `documentId`, `revision`, `name`, `description`, `steps[]` (`ACTION`, `DRIVE_TO`, `WAIT`, `WAIT_UNTIL`, `TOGETHER`, `FIRST_TO_FINISH`, `DEADLINE`, `CALL`, `REPEAT`). Register in `.ares/autonomous-catalog.json` to expose on INIT selector."
        "controls" -> "## `.ares/controls/<id>.arescontrols`\nBinds `CONTROLLER_PROFILE` slots (`ftc-driver`, `frc-driver`) to catalog action keys or drivetrain axes (`vx`, `vy`, `omega`) with deadbands, exponents, and chord windows."
        "tuning" -> "## `.ares/tuning/*.arestuning` & `.ares/local/tuning/*.arestuning`\nCanonical profiles (`CANONICAL_CHECKED_IN`) live in `.ares/tuning/`; local experiments (`LOCAL_EXPERIMENTAL`, no promotion block) live in `.ares/local/tuning/`. Parameter values must satisfy declared `[minimum, maximum]` bounds."
        else -> "## Unknown schema '$kind'\nSupported kinds: `subsystem`, `drivetrain`, `superstructure`, `routine`, `controls`, `tuning`."
    }

    private fun cookbookSnippet(path: String): String = when (path) {
        "ftc/hardware-io" -> "### FTC Hardware IO Cookbook\nCache encoder/limit reads once inside `readInputs()`, stamp `lastFeedbackMillis = com.areslib.util.RobotClock.currentTimeMillis()`, enforce freshness & finite bounds inside `writeOutputs()`, and zero all motors in `disable()`, `stop()`, and `close()`."
        "ftc/teleop-context" -> "### FTC TeleOp Context Cookbook\nAvoid allocations inside `runOpMode()` loops; delegate velocity commands in CCW-positive radians/sec and coerce finite stick axes."
        "frc/swerve-season-reducer" -> "### FRC Swerve Season Reducer Cookbook\nKeep `*State` data classes immutable (`val` properties only), return `state.copy(...)`, and never import `com.ctre.*` or `com.revrobotics.*` into reducer files."
        "frc/phoenix-talonfx-io" -> "### FRC Phoenix TalonFX IO Cookbook\nApply `NeutralOut()` in `disable()`/`stop()`/`close()`, clamp requested voltage within `[-12.0, 12.0]`, and stamp cached sensor signals with `RobotClock.currentTimeMillis()`."
        "shared/mock-io-unit-test" -> "### Shared Mock-IO Unit Test Cookbook\nCall `RobotClock.useMockTime(1_000L)` inside `try { ... } finally { RobotClock.useSystemTime() }`, verifying actuation, stale-feedback zeroing, and disable neutralization."
        else -> "### Cookbook index\nAvailable patterns: `ftc/hardware-io`, `ftc/teleop-context`, `frc/swerve-season-reducer`, `frc/phoenix-talonfx-io`, `shared/mock-io-unit-test`."
    }
}
