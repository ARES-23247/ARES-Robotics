package com.ares.analytics.ui.help

/**
 * In-app glossary backed by `docs/learn/GLOSSARY.md`.
 *
 * Definitions are ported verbatim from that document, which stays authoritative:
 * `GlossaryCatalogTest` fails when the term sets drift apart. Cross-links point at
 * LearningCatalog lesson ids and DeveloperReferenceCatalog entry ids, and the test
 * enforces that every linked id resolves.
 */
data class GlossaryTerm(
    val term: String,
    val definition: String,
    val mentorNote: String? = null,
    val relatedLessonIds: Set<String> = emptySet(),
    val relatedDeveloperReferenceIds: Set<String> = emptySet(),
)

object GlossaryCatalog {
    /**
     * Terms with no owning in-app concept yet. Each stays unlinked until its owning
     * lesson or reference entry exists (ADB describes an external Android tool with no
     * current lesson or Developer Reference entry).
     */
    val unlinkableTerms: Set<String> = setOf(
        "ADB (Android Debug Bridge)",
    )

    val terms: List<GlossaryTerm> = listOf(
        GlossaryTerm(
            term = "ADB (Android Debug Bridge)",
            definition = "A tool the laptop uses to communicate with an FTC Control Hub, including pulling log files and deploying an app. ADB connection is separate from the NT4 telemetry connection.",
        ),
        GlossaryTerm(
            term = "Alliance",
            definition = "The red or blue side assigned to the robot. Alliance can affect starting pose and field-centric controls. Set it before INIT when using the simulator.",
            relatedLessonIds = setOf("first-routine"),
        ),
        GlossaryTerm(
            term = "CCW-positive",
            definition = "Angles increase counter-clockwise. ARES uses radians internally: 0 points along +X and π/2 points along +Y.",
            relatedLessonIds = setOf("drive-kinematics-lab"),
            relatedDeveloperReferenceIds = setOf("pose-estimator"),
        ),
        GlossaryTerm(
            term = "Cloud Sync",
            definition = "An optional desktop action that copies local artifacts to Google Drive or another cloud service. It is not the live robot connection, and a cloud failure does not erase local data.",
            relatedLessonIds = setOf("compare-run-evidence"),
            relatedDeveloperReferenceIds = setOf("log-server"),
        ),
        GlossaryTerm(
            term = "Control Hub",
            definition = "The Android computer on an FTC robot. It runs the FTC app and may provide NT4 telemetry and logs.",
            relatedLessonIds = setOf("start-simulator"),
        ),
        GlossaryTerm(
            term = "Dashboard",
            definition = "The main configurable screen showing live or replayed telemetry widgets. Always check its mode/target before interpreting a value.",
            relatedLessonIds = setOf("start-simulator"),
        ),
        GlossaryTerm(
            term = "Drivetrain",
            definition = "The motors, wheels/modules, sensors, and control logic that move the robot.",
            relatedLessonIds = setOf("drivebase-blueprint"),
            relatedDeveloperReferenceIds = setOf("drive-facades"),
        ),
        GlossaryTerm(
            term = "DuckDB",
            definition = "The embedded database on the laptop where Analytics stores imported sessions and derived results. It does not run on the robot.",
            relatedLessonIds = setOf("compare-run-evidence", "query-stored-telemetry"),
            relatedDeveloperReferenceIds = setOf("log-server"),
        ),
        GlossaryTerm(
            term = "EKF (Extended Kalman Filter)",
            definition = "The estimator that combines motion and sensor measurements into an estimated robot pose. Raw odometry and EKF pose can differ because the estimator also models uncertainty and may use vision.",
            relatedLessonIds = setOf("sensor-fusion-lab"),
            relatedDeveloperReferenceIds = setOf("pose-estimator"),
        ),
        GlossaryTerm(
            term = "Field-centric drive",
            definition = "Driver commands are interpreted relative to the field instead of the robot's current facing direction.",
            relatedLessonIds = setOf("drivebase-blueprint"),
            relatedDeveloperReferenceIds = setOf("drive-facades"),
        ),
        GlossaryTerm(
            term = "Gateway",
            definition = "The small authenticated cloud service used for limited remote/AI operations. It is not in the robot-to-dashboard telemetry path.",
            relatedLessonIds = setOf("compare-run-evidence"),
        ),
        GlossaryTerm(
            term = "Imported run",
            definition = "A completed log converted into a persistent local session. It can be replayed after the robot or simulator has stopped.",
            relatedLessonIds = setOf("compare-run-evidence"),
            relatedDeveloperReferenceIds = setOf("log-server"),
        ),
        GlossaryTerm(
            term = "INIT",
            definition = "The preparation phase before an FTC OpMode starts. Some state, including simulator starting pose/alliance behavior, is established at INIT.",
            relatedLessonIds = setOf("start-simulator"),
        ),
        GlossaryTerm(
            term = "JSONL",
            definition = "A log format with one JSON object per line. ARES robot/simulator logs may use it.",
            relatedDeveloperReferenceIds = setOf("log-server"),
        ),
        GlossaryTerm(
            term = "Live robot",
            definition = "The physical FTC or FRC robot currently publishing NT4 data. Some explicit dashboard tools can send commands or tuning values, so use the team's hardware safety process.",
            relatedLessonIds = setOf("start-simulator"),
        ),
        GlossaryTerm(
            term = "Local Sim / simulator",
            definition = "A robot program and physics model running on the laptop. Its values are live, but they do not come from physical hardware.",
            relatedLessonIds = setOf("start-simulator"),
        ),
        GlossaryTerm(
            term = "Log",
            definition = "A file of time-stamped robot or simulator evidence. A logger creates it; Analytics imports the completed file.",
            relatedLessonIds = setOf("compare-run-evidence"),
            relatedDeveloperReferenceIds = setOf("log-server"),
        ),
        GlossaryTerm(
            term = "NT4 (NetworkTables 4)",
            definition = "The live topic protocol between the robot/simulator and Analytics. Default ARES traffic uses port 5810.",
            relatedLessonIds = setOf("start-simulator"),
            relatedDeveloperReferenceIds = setOf("nt4-contract"),
        ),
        GlossaryTerm(
            term = "Offline-first",
            definition = "The robot works and records without cloud access. The laptop pulls or receives data locally, then may synchronize later.",
            relatedDeveloperReferenceIds = setOf("log-server"),
        ),
        GlossaryTerm(
            term = "OpMode",
            definition = "An FTC program mode, such as TeleOp or Autonomous, with INIT, start, and stop lifecycle phases.",
            relatedLessonIds = setOf("start-simulator"),
        ),
        GlossaryTerm(
            term = "Pose",
            definition = "The robot's field position and heading: X, Y, and rotation.",
            relatedLessonIds = setOf("autonomous-safety-lab"),
            relatedDeveloperReferenceIds = setOf("pose-estimator"),
        ),
        GlossaryTerm(
            term = "Quarantine",
            definition = "The safe holding area for a log that automatic import could not decode. The evidence and error report are preserved for repair/retry.",
            relatedLessonIds = setOf("compare-run-evidence"),
        ),
        GlossaryTerm(
            term = "Recording",
            definition = "A saved run of timestamped telemetry. In Local Sim, use Record run and Stop & save on the Dashboard; physical robot logs are still imported from the robot afterward.",
            relatedLessonIds = setOf("compare-run-evidence"),
        ),
        GlossaryTerm(
            term = "Replay",
            definition = "Historical playback of an imported session. Replay can drive Dashboard visualizations but cannot move robot hardware.",
            relatedLessonIds = setOf("start-simulator", "compare-run-evidence"),
        ),
        GlossaryTerm(
            term = "RoboRIO",
            definition = "The real-time controller on an FRC robot. It can publish NT4 data and store logs that Analytics pulls over SSH/SCP.",
            relatedLessonIds = setOf("robot-studio-tour"),
            relatedDeveloperReferenceIds = setOf("routine-documents"),
        ),
        GlossaryTerm(
            term = "RobotClock",
            definition = "The shared deterministic time source used in ARES library code so simulation and replay follow the same timing model.",
            relatedLessonIds = setOf("state-flow-lab"),
            relatedDeveloperReferenceIds = setOf("robot-clock"),
        ),
        GlossaryTerm(
            term = "Session",
            definition = "A group of telemetry and metadata treated as one run. live-telemetry is the reserved live buffer; imported runs receive persistent session IDs.",
            relatedLessonIds = setOf("compare-run-evidence"),
            relatedDeveloperReferenceIds = setOf("log-server"),
        ),
        GlossaryTerm(
            term = "Simulator command",
            definition = "The command Analytics launches in the selected robot project. It is optional because FTC and FRC have default commands.",
            relatedLessonIds = setOf("start-simulator"),
        ),
        GlossaryTerm(
            term = "Telemetry",
            definition = "Measurements and state sent for observation: pose, battery, motor current, mechanism state, alerts, and more.",
            relatedLessonIds = setOf("state-flow-lab"),
            relatedDeveloperReferenceIds = setOf("nt4-contract"),
        ),
        GlossaryTerm(
            term = "Timestamp",
            definition = "The time attached to a telemetry value. Replay order and alignment depend on timestamps increasing correctly.",
            relatedLessonIds = setOf("state-flow-lab"),
            relatedDeveloperReferenceIds = setOf("robot-clock"),
        ),
        GlossaryTerm(
            term = "Topic",
            definition = "A named NT4 data channel, such as Drive/Pose_X. The name, type, units, and producer together form the telemetry contract.",
            relatedDeveloperReferenceIds = setOf("nt4-contract"),
        ),
        GlossaryTerm(
            term = "Workspace / robot profile",
            definition = "Analytics' saved selection for one robot project: project folder, team, season, robot ID, league, target host, and optional simulator command. It is not the same as a cloud account.",
            relatedLessonIds = setOf("robot-studio-tour"),
        ),
    )

    fun term(name: String): GlossaryTerm? =
        terms.firstOrNull { it.term.equals(name.trim(), ignoreCase = true) }

    fun search(query: String): List<GlossaryTerm> {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) return terms
        return terms.filter { term ->
            normalized in term.term.lowercase() || normalized in term.definition.lowercase()
        }
    }
}
