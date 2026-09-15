package com.ares.analytics.domain.navigation

/** Stable screen identities shared by navigation and lesson documents. */
enum class NavigationTarget(val label: String) {
    DASHBOARD("Dashboard"),
    IMPORT_CENTER("Log Imports"),
    CLOUD("Cloud Sync"),
    PATH_PLANNER("Auto Builder"),
    FIELD_EDITOR("Field Editor"),
    ACADEMY("Help & Learn"),
    KDOC_VIEWER("Developer Reference"),
    PIT_DIAGNOSTICS("Pit Self-Test"),
    MATCH_STRATEGY("Strategy Preview"),
    GUIDED_RUN_ANALYSIS("Guided Run Review"),
    RUN_HISTORY("Run History"),
    DATABASE_VIEWER("Database"),
    CONTROLS("TeleOp Controls"),
    TUNING("Tuning"),
    ROBOT_STUDIO("Robot Studio"),
    HARDWARE_STUDIO("Hardware Studio"),
    PROJECT_IDENTITY("Project Identity"),
    HARDWARE_SETUP("Hardware Setup"),
    DRIVEBASE_BUILDER("Drivebase Builder"),
    SUBSYSTEM_GEN("Subsystem Builder"),
    SUPERSTRUCTURE_STUDIO("Superstructure Studio"),
    PROJECT_BACKUP("Project History"),
    INTEGRATIONS("Integrations"),
    PROFILE("Profile"),
    ADMIN("Admin Panel")
}
