package com.ares.analytics.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrecisionManufacturing
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SupervisorAccount
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector

enum class NavigationTarget(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Default.Speed),
    IMPORT_CENTER("Log Imports", Icons.Default.FolderOpen),
    CLOUD("Cloud Sync", Icons.Default.Cloud),
    PATH_PLANNER("Auto Builder", Icons.Default.Route),
    FIELD_EDITOR("Field Editor", Icons.Default.Layers),
    ACADEMY("Help & Learn", Icons.Default.School),
    KDOC_VIEWER("Developer Reference", Icons.Default.Book),
    PIT_DIAGNOSTICS("Pit Self-Test", Icons.Default.Build),
    MATCH_STRATEGY("Strategy Preview", Icons.Default.Analytics),
    GUIDED_RUN_ANALYSIS("Guided Run Review", Icons.Default.Analytics),
    RUN_HISTORY("Run History", Icons.Default.TableChart),
    DATABASE_VIEWER("Database", Icons.Default.Storage),
    CONTROLS("TeleOp Controls", Icons.Default.SportsEsports),
    TUNING("Tuning", Icons.Default.Tune),
    ROBOT_STUDIO("Robot Studio", Icons.Default.PrecisionManufacturing),
    HARDWARE_STUDIO("Hardware Studio", Icons.Default.Build),
    PROJECT_IDENTITY("Project Identity", Icons.Default.Person),
    HARDWARE_SETUP("Hardware Setup", Icons.Default.Build),
    DRIVEBASE_BUILDER("Drivebase Builder", Icons.Default.Settings),
    SUBSYSTEM_GEN("Subsystem Builder", Icons.Default.Construction),
    SUPERSTRUCTURE_STUDIO("Superstructure Studio", Icons.Default.Layers),
    PROJECT_BACKUP("Project History", Icons.Default.CloudUpload),
    PROFILE("Profile", Icons.Default.Person),
    ADMIN("Admin Panel", Icons.Default.SupervisorAccount)
}

enum class NavigationSection(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Default.Speed),
    ROBOT("Robot", Icons.Default.Build),
    AUTONOMOUS("Autonomous", Icons.Default.Route),
    ANALYSIS("Analysis", Icons.Default.Analytics),
    DATA("Data", Icons.Default.Cloud),
    SETTINGS("Settings", Icons.Default.Settings)
}

val primaryNavigationSections = NavigationSection.entries.toList()

val developerToolTargets = setOf(
    NavigationTarget.KDOC_VIEWER,
    NavigationTarget.DATABASE_VIEWER,
    NavigationTarget.MATCH_STRATEGY
)

fun NavigationTarget.section(): NavigationSection? = when (this) {
    NavigationTarget.DASHBOARD -> NavigationSection.DASHBOARD
    NavigationTarget.ROBOT_STUDIO, NavigationTarget.HARDWARE_STUDIO, NavigationTarget.PROJECT_IDENTITY, NavigationTarget.HARDWARE_SETUP, NavigationTarget.PIT_DIAGNOSTICS, NavigationTarget.CONTROLS,
    NavigationTarget.TUNING, NavigationTarget.DRIVEBASE_BUILDER, NavigationTarget.SUBSYSTEM_GEN, NavigationTarget.SUPERSTRUCTURE_STUDIO -> NavigationSection.ROBOT
    NavigationTarget.PATH_PLANNER, NavigationTarget.FIELD_EDITOR -> NavigationSection.AUTONOMOUS
    NavigationTarget.GUIDED_RUN_ANALYSIS, NavigationTarget.RUN_HISTORY -> NavigationSection.ANALYSIS
    NavigationTarget.IMPORT_CENTER, NavigationTarget.CLOUD -> NavigationSection.DATA
    NavigationTarget.PROJECT_BACKUP, NavigationTarget.PROFILE, NavigationTarget.ADMIN -> NavigationSection.SETTINGS
    NavigationTarget.ACADEMY, NavigationTarget.KDOC_VIEWER,
    NavigationTarget.DATABASE_VIEWER, NavigationTarget.MATCH_STRATEGY -> null
}

fun NavigationSection.defaultTarget(): NavigationTarget = when (this) {
    NavigationSection.DASHBOARD -> NavigationTarget.DASHBOARD
    NavigationSection.ROBOT -> NavigationTarget.ROBOT_STUDIO
    NavigationSection.AUTONOMOUS -> NavigationTarget.PATH_PLANNER
    NavigationSection.ANALYSIS -> NavigationTarget.GUIDED_RUN_ANALYSIS
    NavigationSection.DATA -> NavigationTarget.IMPORT_CENTER
    NavigationSection.SETTINGS -> NavigationTarget.PROFILE
}

fun NavigationSection.targets(): List<NavigationTarget> = when (this) {
    NavigationSection.DASHBOARD -> listOf(NavigationTarget.DASHBOARD)
    NavigationSection.ROBOT -> listOf(
        NavigationTarget.ROBOT_STUDIO,
        NavigationTarget.HARDWARE_STUDIO,
        NavigationTarget.SUPERSTRUCTURE_STUDIO,
        NavigationTarget.CONTROLS,
        NavigationTarget.TUNING,
        NavigationTarget.PIT_DIAGNOSTICS,
    )
    NavigationSection.AUTONOMOUS -> listOf(NavigationTarget.PATH_PLANNER, NavigationTarget.FIELD_EDITOR)
    NavigationSection.ANALYSIS -> listOf(NavigationTarget.GUIDED_RUN_ANALYSIS, NavigationTarget.RUN_HISTORY)
    NavigationSection.DATA -> listOf(NavigationTarget.IMPORT_CENTER, NavigationTarget.CLOUD)
    NavigationSection.SETTINGS -> listOf(NavigationTarget.PROFILE, NavigationTarget.PROJECT_BACKUP, NavigationTarget.ADMIN)
}

fun availablePaletteTargets(developerMode: Boolean): List<NavigationTarget> = NavigationTarget.entries.filter {
    developerMode || it !in developerToolTargets
}

fun NavigationTarget.groupLabel(): String = section()?.label ?: when (this) {
    NavigationTarget.ACADEMY -> "Help"
    in developerToolTargets -> "Developer tools"
    else -> "Tools"
}

/** Plain-language tasks and symptoms recognized by global navigation search. */
fun NavigationTarget.searchTerms(): Set<String> = when (this) {
    NavigationTarget.DASHBOARD -> setOf("home", "live data", "connected", "disconnected", "telemetry")
    NavigationTarget.IMPORT_CENTER -> setOf("logs", "bring in a run", "quarantine", "file", "robot storage")
    NavigationTarget.CLOUD -> setOf("sync", "google drive", "upload", "download", "share")
    NavigationTarget.PATH_PLANNER -> setOf("autonomous", "routine", "path", "drive to")
    NavigationTarget.FIELD_EDITOR -> setOf("field", "obstacle", "april tag", "game piece")
    NavigationTarget.ACADEMY -> setOf(
        "help", "learn", "tutorial", "start here", "student", "novice", "glossary",
        "academy", "robot academy", "training", "classroom", "mentor", "lesson",
    )
    NavigationTarget.KDOC_VIEWER -> setOf("api", "code reference", "kdoc", "source", "architecture", "units")
    NavigationTarget.PIT_DIAGNOSTICS -> setOf("hardware", "readiness", "pit", "self test", "freshness")
    NavigationTarget.MATCH_STRATEGY -> setOf("match", "strategy", "preview")
    NavigationTarget.GUIDED_RUN_ANALYSIS -> setOf("analyze", "evidence", "possible cause", "safe next step", "guided review", "compare runs")
    NavigationTarget.RUN_HISTORY -> setOf("replay", "review", "compare", "past run", "session")
    NavigationTarget.DATABASE_VIEWER -> setOf("sql", "duckdb", "table")
    NavigationTarget.CONTROLS -> setOf("gamepad", "button", "driver", "operator", "binding")
    NavigationTarget.TUNING -> setOf("pid", "sysid", "feedforward", "calibration")
    NavigationTarget.ROBOT_STUDIO -> setOf("create robot", "build robot", "robot workflow", "project readiness", "start robot")
    NavigationTarget.HARDWARE_STUDIO -> setOf("hardware", "drivetrain", "subsystems", "mechanisms", "port map", "wiring", "localization", "kinematics")
    NavigationTarget.PROJECT_IDENTITY -> setOf("project metadata", "project id", "robot dimensions", "field dimensions", "coordinate convention")
    NavigationTarget.HARDWARE_SETUP -> setOf("hardware map", "can id", "wiring", "addresses", "direction", "physical robot", "deployment review")
    NavigationTarget.DRIVEBASE_BUILDER -> setOf("drivetrain", "mecanum", "swerve", "differential", "ctre", "tuner constants", "wheelbase", "track width", "localization")
    NavigationTarget.SUBSYSTEM_GEN -> setOf("mechanism", "motor", "sensor", "redux", "io", "generator")
    NavigationTarget.SUPERSTRUCTURE_STUDIO -> setOf("coordinator", "superstructure", "state machine", "posture", "interlock", "lookup table", "lut", "multiple mechanisms")
    NavigationTarget.PROJECT_BACKUP -> setOf("backup", "git", "github", "history", "version", "restore", "save project")
    NavigationTarget.PROFILE -> setOf("workspace", "robot", "team", "accessibility", "project folder")
    NavigationTarget.ADMIN -> setOf("roster", "shared robots", "administrator")
}
