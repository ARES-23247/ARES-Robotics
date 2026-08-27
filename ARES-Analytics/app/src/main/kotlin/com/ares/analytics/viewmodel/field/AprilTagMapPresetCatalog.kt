package com.ares.analytics.viewmodel.field

import com.ares.analytics.shared.League

/**
 * Reviewed AprilTag-map resources shipped with Robotics Studio.
 *
 * Presets intentionally enter the same preview-and-confirm path as an imported WPILib or
 * Limelight file. Selecting a season never silently replaces a student's canonical field, and
 * the resulting tags are stored in `field.json`, where the simulator and generated robot vision
 * adapters consume the same coordinates.
 */
internal data class AprilTagMapPreset(
    val id: String,
    val league: League,
    val displayName: String,
    val sourceLabel: String,
    val resourcePath: String,
) {
    fun readContent(): String {
        val stream = requireNotNull(AprilTagMapPreset::class.java.classLoader.getResourceAsStream(resourcePath)) {
            "Bundled AprilTag map '$resourcePath' is missing"
        }
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}

internal object AprilTagMapPresetCatalog {
    private val presets = listOf(
        AprilTagMapPreset(
            id = "ftc-2025-2026-decode-team23247",
            league = League.FTC,
            displayName = "2025–26 DECODE · Team 23247",
            sourceLabel = "Reviewed ARES season targets (IDs 20 and 24)",
            resourcePath = "field-presets/ftc/2025-2026-decode-team23247.json",
        ),
        AprilTagMapPreset(
            id = "frc-2024-crescendo",
            league = League.FRC,
            displayName = "2024 CRESCENDO",
            sourceLabel = "Reviewed WPILib field layout (16 tags)",
            resourcePath = "field-presets/frc/2024-crescendo.json",
        ),
    )

    fun forLeague(league: League): List<AprilTagMapPreset> = presets.filter { it.league == league }
}
