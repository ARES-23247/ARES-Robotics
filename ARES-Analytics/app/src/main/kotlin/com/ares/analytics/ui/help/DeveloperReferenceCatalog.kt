package com.ares.analytics.ui.help

data class DeveloperReference(
    val id: String,
    val title: String,
    val category: String,
    val responsibility: String,
    val sourcePath: String,
    val units: String,
    val invariants: List<String>,
    val relatedTests: String,
    val keywords: Set<String> = emptySet(),
)

/**
 * Small, source-backed map of concepts team members commonly need to locate.
 *
 * This is deliberately not presented as complete generated API documentation. The linked source
 * and its tests remain authoritative, which prevents curated examples from masquerading as a
 * current compile-checked API surface.
 */
object DeveloperReferenceCatalog {
    val entries = listOf(
        DeveloperReference(
            id = "redux",
            title = "Redux state flow",
            category = "State & control",
            responsibility = "Purely reduce RobotAction values into immutable RobotState snapshots.",
            sourcePath = "ARESLib-Kotlin/core/src/main/kotlin/com/areslib/reducer/RootReducer.kt",
            units = "The action and state fields declare their own physical units.",
            invariants = listOf(
                "Input and controllers dispatch actions; they do not mutate state directly.",
                "Reducers are pure and season reducers compose over rootReducer.",
                "Controllers read one immutable state snapshot before writing outputs.",
            ),
            relatedTests = "core/src/test/kotlin/com/areslib/reducer/",
            keywords = setOf("action", "reducer", "state", "store", "immutable"),
        ),
        DeveloperReference(
            id = "pose-estimator",
            title = "Pose estimation",
            category = "Localization",
            responsibility = "Fuse odometry and accepted vision measurements while retaining bounded pose history.",
            sourcePath = "ARESLib-Kotlin/core/src/main/kotlin/com/areslib/math/estimation/PoseEstimator.kt",
            units = "Position: meters; heading: radians, CCW-positive; timestamps: milliseconds.",
            invariants = listOf(
                "Heading uses 0 = +X and π/2 = +Y.",
                "Vision quality and rejection are handled before correction is trusted.",
                "Hot rewind/fusion paths use preallocated storage.",
            ),
            relatedTests = "core/src/test/kotlin/com/areslib/math/estimation/",
            keywords = setOf("ekf", "vision", "odometry", "pose", "kalman"),
        ),
        DeveloperReference(
            id = "drive-facades",
            title = "Team-member drive facades",
            category = "Drivetrain",
            responsibility = "Expose Mecanum and Swerve drive intent through the shared Redux drivetrain boundary.",
            sourcePath = "ARESLib-Kotlin/core/src/main/kotlin/com/areslib/subsystem/{MecanumDriveFacade,SwerveDriveFacade}.kt",
            units = "Translation: meters/second; rotation: radians/second; heading: radians, CCW-positive.",
            invariants = listOf(
                "Alliance mirroring belongs at the season input boundary, not inside the facade.",
                "FTC and FRC may use different driver-origin conventions.",
                "Swerve X-brake dispatches an explicit X_BRAKE drive mode.",
            ),
            relatedTests = "core/src/test/kotlin/com/areslib/subsystem/",
            keywords = setOf("mecanum", "swerve", "field centric", "brake", "drive"),
        ),
        DeveloperReference(
            id = "hardware-registry",
            title = "Hardware lifecycle registry",
            category = "Hardware & IO",
            responsibility = "Own registered refresh, topology, safety, telemetry, polling, and close lifecycles.",
            sourcePath = "ARESLib-Kotlin/core/src/main/kotlin/com/areslib/hardware/HardwareRegistry.kt",
            units = "Device-specific; cached readings must retain declared units and validity.",
            invariants = listOf(
                "Hardware reads happen once and are cached for the loop.",
                "Getters and writeOutputs do not perform direct hardware reads.",
                "Safety and close passes isolate device exceptions best-effort.",
            ),
            relatedTests = "core/src/test/kotlin/com/areslib/hardware/HardwareRegistryTest.kt",
            keywords = setOf("cache", "poll", "topology", "safe", "close", "device"),
        ),
        DeveloperReference(
            id = "robot-clock",
            title = "Deterministic robot time",
            category = "Runtime",
            responsibility = "Provide one clock boundary for live code, tests, replay, and simulation.",
            sourcePath = "ARESLib-Kotlin/core/src/main/kotlin/com/areslib/util/RobotClock.kt",
            units = "Milliseconds for currentTimeMillis; nanoseconds for nanoTime.",
            invariants = listOf(
                "Library code does not call system clocks directly.",
                "Mock time advances only when its lifecycle owner changes it.",
                "Tests and replay restore system time after use.",
            ),
            relatedTests = "Search ARESLib tests for RobotClock.useMockTime.",
            keywords = setOf("time", "simulation", "replay", "deterministic", "mock"),
        ),
        DeveloperReference(
            id = "theta-star",
            title = "Theta* path planning",
            category = "Autonomous",
            responsibility = "Plan an any-angle route around costmap obstacles using line-of-sight shortcuts.",
            sourcePath = "ARESLib-Kotlin/core/src/main/kotlin/com/areslib/pathing/ThetaStarPlanner.kt",
            units = "Field coordinates: meters; costmap resolution: meters/cell.",
            invariants = listOf(
                "Invalid or out-of-bounds inputs return no route.",
                "A route preview is not proof of physical robot clearance.",
                "Path expansion uses pooled planner state.",
            ),
            relatedTests = "core/src/test/kotlin/com/areslib/pathing/ThetaStarPlannerTest.kt",
            keywords = setOf("path", "costmap", "obstacle", "route", "theta star"),
        ),
        DeveloperReference(
            id = "subsystem-controller",
            title = "Subsystem controller boundary",
            category = "State & control",
            responsibility = "Translate immutable desired state into safe IO commands for one subsystem.",
            sourcePath = "ARESLib-Kotlin/core/src/main/kotlin/com/areslib/subsystem/SubsystemControllerBase.kt",
            units = "Declared by each state field, sensor snapshot, and output command.",
            invariants = listOf(
                "State, controller, IO contract, hardware adapter, and simulator adapter stay separate.",
                "Invalid or stale feedback fails toward the declared neutral output.",
                "Fault latches require explicit successful neutral recovery where applicable.",
            ),
            relatedTests = "Generated subsystem contract tests plus season subsystem safety tests.",
            keywords = setOf("subsystem", "controller", "io", "neutral", "fault", "redux"),
        ),
        DeveloperReference(
            id = "task-sequencer",
            title = "Task sequencer",
            category = "State & control",
            responsibility = "Compose routine steps into task trees that emit actions for the store to dispatch.",
            sourcePath = "ARESLib-Kotlin/core/src/main/kotlin/com/areslib/sequencer/TaskExecutor.kt",
            units = "Durations are seconds in documents and nanoseconds in generated code; path progress is 0..1.",
            invariants = listOf(
                "Tasks never touch hardware or state directly; they return actions for dispatch.",
                "Sequential, parallel, race, and deadline groups mirror the routine step kinds.",
                "Preemption is nested LIFO and transitions per update are bounded.",
            ),
            relatedTests = "core/src/test/kotlin/com/areslib/sequencer/",
            keywords = setOf("task", "sequence", "parallel", "race", "deadline", "preemption"),
        ),
        DeveloperReference(
            id = "routine-documents",
            title = "Routine documents & compiler",
            category = "Autonomous",
            responsibility = "Define validated routine steps as schema-versioned documents and compile them into task trees.",
            sourcePath = "ARESLib-Kotlin/core/src/main/kotlin/com/areslib/routine/RoutineDocument.kt",
            units = "Poses in meters and CCW-positive radians; durations in seconds.",
            invariants = listOf(
                "Ten step kinds; each kind validates exactly its own fields.",
                "Routines are trigger-neutral; catalogs supply start pose, alliance, and mirroring.",
                "Robots execute generated artifacts, never loose routine JSON.",
            ),
            relatedTests = "core/src/test/kotlin/com/areslib/routine/",
            keywords = setOf("routine", "aresroutine", "steps", "catalog", "alliance", "mirror"),
        ),
        DeveloperReference(
            id = "control-schemes",
            title = "Teleop control schemes",
            category = "Teleop",
            responsibility = "Declare controller assignments and input bindings as documents compiled into controller runtimes.",
            sourcePath = "ARESLib-Kotlin/core/src/main/kotlin/com/areslib/controls/ControlSchemeDocument.kt",
            units = "Axis values normalized to -1..1; durations in seconds.",
            invariants = listOf(
                "Bindings may only target catalog actions, routine starts, or routine cancels.",
                "Controller assignments pin Driver Station device ports explicitly.",
                "Hysteresis, debounce, and suppression are declared, not improvised at runtime.",
            ),
            relatedTests = "core/src/test/kotlin/com/areslib/controls/",
            keywords = setOf("controls", "binding", "gamepad", "chord", "axis", "arescontrols"),
        ),
        DeveloperReference(
            id = "codegen-ownership",
            title = "Generated code ownership",
            category = "Code generation",
            responsibility = "Turn project documents into reviewed Kotlin with explicit ownership headers and staleness gates.",
            sourcePath = "ARESLib-Kotlin/codegen/src/main/kotlin/com/areslib/codegen/SubsystemKotlinGenerator.kt",
            units = "Content hashes tie each generated artifact to its source document.",
            invariants = listOf(
                "GENERATED - DO NOT EDIT files change only through regeneration.",
                "Starters are user-reviewable; regeneration replaces them only after a previewed diff and replacement token.",
                "Consumer builds fail when generated output is stale relative to its documents.",
            ),
            relatedTests = "core/src/test/kotlin/com/areslib/codegen/",
            keywords = setOf("generator", "ownership", "starter", "token", "stale", "hash"),
        ),
        DeveloperReference(
            id = "power-safety",
            title = "Power & current safety",
            category = "Hardware & IO",
            responsibility = "Scale mechanism output as battery voltage sags and estimated current approaches the budget.",
            sourcePath = "ARESLib-Kotlin/core/src/main/kotlin/com/areslib/control/safety/CurrentBudgetManager.kt",
            units = "Current in amps (defaults warn 15 A, critical 18 A); voltage in volts (warn 10.0, critical 7.5).",
            invariants = listOf(
                "Graduated power scaling, not an abrupt disable.",
                "Brownout decisions use hysteresis to avoid oscillation.",
                "Controllers receive one scale factor alongside each state snapshot.",
            ),
            relatedTests = "core/src/test/kotlin/com/areslib/control/safety/",
            keywords = setOf("brownout", "current", "budget", "voltage", "power"),
        ),
        DeveloperReference(
            id = "nt4-contract",
            title = "NT4 telemetry contract",
            category = "Runtime",
            responsibility = "Publish robot state over NetworkTables and define the one guarded input topic the dashboard may write.",
            sourcePath = "ARESLib-Kotlin/core/src/main/kotlin/com/areslib/telemetry/ARESNetworkStatePublisher.kt",
            units = "Topic values carry their own units; timestamps are milliseconds.",
            invariants = listOf(
                "The robot is the server on port 5810; the dashboard subscribes.",
                "Topic names are stripped of leading slashes at publish and subscribe.",
                "driveFrame is the only dashboard input, leased and disarmed to neutral on any invalid frame.",
            ),
            relatedTests = "Wire-contract verification in verify-autos (runVerification).",
            keywords = setOf("nt4", "networktables", "telemetry", "topics", "driveframe"),
        ),
        DeveloperReference(
            id = "log-server",
            title = "Offline-first log pipeline",
            category = "Logging",
            responsibility = "Serve robot logs over local Wi-Fi so the laptop pulls, parses, and owns any cloud sync.",
            sourcePath = "ARESLib-Kotlin/core/src/main/kotlin/com/areslib/logging/LogManagerServer.kt",
            units = "Logs are JSONL; file sizes reported in bytes.",
            invariants = listOf(
                "The robot never initiates cloud traffic.",
                "Downloads are confined to canonical paths; deletes are token-gated.",
                "Analysis happens on the laptop after the pull, never by pushing from the robot.",
            ),
            relatedTests = "core/src/test/kotlin/com/areslib/logging/",
            keywords = setOf("logs", "jsonl", "download", "offline", "pull"),
        ),
    )

    val categories: List<String> = listOf("All") + entries.map(DeveloperReference::category).distinct().sorted()

    fun search(query: String, category: String = "All"): List<DeveloperReference> {
        val normalized = query.trim().lowercase()
        return entries.filter { entry ->
            (category == "All" || entry.category == category) &&
                (normalized.isBlank() || listOf(
                    entry.title,
                    entry.responsibility,
                    entry.sourcePath,
                    entry.units,
                    entry.invariants.joinToString(" "),
                    entry.keywords.joinToString(" "),
                ).any { normalized in it.lowercase() })
        }
    }
}
