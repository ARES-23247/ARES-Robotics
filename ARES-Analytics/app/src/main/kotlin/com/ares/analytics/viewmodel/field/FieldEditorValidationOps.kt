package com.ares.analytics.viewmodel.field

import com.ares.analytics.shared.models.League
import com.ares.analytics.viewmodel.FieldEditorState
import com.areslib.state.FieldType
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldValidator

internal object FieldEditorValidationOps {
    fun withValidation(state: FieldEditorState, activeLeague: League): FieldEditorState {
        val width = state.fieldImageConfig.widthMeters.takeIf { it > 0.0 } ?: when (activeLeague) {
            League.FTC -> 3.6576
            League.FRC -> 16.541
            League.XRP -> 2.54
        }
        val height = state.fieldImageConfig.heightMeters.takeIf { it > 0.0 } ?: when (activeLeague) {
            League.FTC -> 3.6576
            League.FRC -> 8.211
            League.XRP -> 1.4224
        }
        val editorIssues = FieldEditorValidator.validate(
            league = activeLeague,
            widthMeters = width,
            heightMeters = height,
            obstacles = state.obstacles,
            gamePieces = state.gamePieces,
            aprilTags = state.aprilTags,
            waypoints = state.fieldWaypoints,
        )
        val requiredFieldType = when (activeLeague) {
            League.FTC -> FieldType.FTC
            League.FRC -> FieldType.FRC
            League.XRP -> FieldType.XRP
        }
        val canonicalIssues = state.document.orEmptyValidation(requiredFieldType).map { issue ->
            FieldValidationIssue(
                severity = FieldValidationSeverity.ERROR,
                message = issue.message,
                elementIds = issue.elementIds,
            )
        }
        return state.copy(
            validationIssues = (editorIssues + canonicalIssues).distinctBy { issue ->
                issue.severity to (issue.message to issue.elementIds)
            }
        )
    }

    private fun RobotFieldConfig?.orEmptyValidation(requiredFieldType: FieldType) =
        this?.let { document ->
            RobotFieldValidator.validate(
                config = document,
                requiredFieldType = requiredFieldType,
                // The reviewed BIOBUZZ layout has no AprilTags. Keep normal FTC layout
                // requirements and validation of any authored tags; do not invent vision targets.
                requireAprilTags = requiredFieldType == FieldType.FTC && document.id != "ftc-2026-2027-biobuzz",
            )
        }.orEmpty()
}
