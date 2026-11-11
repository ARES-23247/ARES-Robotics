package com.ares.analytics.ui.components.robotstudio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PrecisionManufacturing
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.Icons
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.components.core.AresSpecRow
import com.ares.analytics.ui.components.core.AresSpecSection
import com.ares.analytics.ui.components.core.AresSpecSummaryModal
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresRed
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.viewmodel.project.ProjectIdentityEditorState
import com.ares.analytics.viewmodel.robotstudio.RobotStudioStage
import com.ares.analytics.viewmodel.robotstudio.RobotStudioStageId
import com.ares.analytics.viewmodel.robotstudio.RobotStudioStageStatus
import com.ares.analytics.viewmodel.robotstudio.RobotStudioState
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.toKotlinDsl
import com.areslib.drivetrain.DrivetrainDocument
import com.areslib.drivetrain.toKotlinDsl
import com.areslib.project.AresProjectAuthoringModel
import com.areslib.routine.RoutineDocument
import com.areslib.routine.toKotlinDsl
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.toKotlinDsl
import com.areslib.superstructure.SuperstructureDocument
import com.areslib.superstructure.toKotlinDsl
import java.util.Locale

data class RobotProjectSpecSnapshot(
    val projectId: String,
    val teamId: String,
    val seasonId: String,
    val robotId: String,
    val displayName: String,
    val league: League,
    val authoringModel: AresProjectAuthoringModel,
    val robotLengthMeters: Double,
    val robotWidthMeters: Double,
    val drivetrain: DrivetrainDocument? = null,
    val subsystems: List<SubsystemDocument> = emptyList(),
    val superstructures: List<SuperstructureDocument> = emptyList(),
    val controlSchemes: List<ControlSchemeDocument> = emptyList(),
    val routines: List<RoutineDocument> = emptyList(),
    val hardwareCount: Int = 0,
    val hardwareReviewStatus: String? = null,
)

fun buildRobotProjectSpecSnapshot(
    identityState: ProjectIdentityEditorState,
    drivetrain: DrivetrainDocument? = null,
    subsystems: List<SubsystemDocument> = emptyList(),
    superstructures: List<SuperstructureDocument> = emptyList(),
    controlSchemes: List<ControlSchemeDocument> = emptyList(),
    routines: List<RoutineDocument> = emptyList(),
    hardwareCount: Int = 0,
    hardwareReviewStatus: String? = null,
): RobotProjectSpecSnapshot {
    val draft = identityState.draft
    val doc = identityState.currentDocument
    val defaultLength = when (identityState.workspaceLeague) {
        League.FTC -> 0.4572
        League.FRC -> 0.85
        League.XRP -> 0.19
    }
    return RobotProjectSpecSnapshot(
        projectId = draft.projectId.ifBlank { doc?.projectId ?: "robot-project" },
        teamId = draft.teamId.ifBlank { doc?.identity?.teamId ?: "0000" },
        seasonId = draft.seasonId.ifBlank { doc?.identity?.seasonId ?: "2026" },
        robotId = draft.robotId.ifBlank { doc?.identity?.robotId ?: "robot" },
        displayName = draft.displayName.ifBlank { doc?.identity?.displayName ?: "ARES Robot" },
        league = identityState.workspaceLeague,
        authoringModel = draft.authoringModel,
        robotLengthMeters = draft.robotLengthMeters.toDoubleOrNull() ?: doc?.robotLengthMeters ?: defaultLength,
        robotWidthMeters = draft.robotWidthMeters.toDoubleOrNull() ?: doc?.robotWidthMeters ?: defaultLength,
        drivetrain = drivetrain,
        subsystems = subsystems,
        superstructures = superstructures,
        controlSchemes = controlSchemes,
        routines = routines,
        hardwareCount = hardwareCount,
        hardwareReviewStatus = hardwareReviewStatus,
    )
}

fun generateRobotProjectSpecSections(
    snapshot: RobotProjectSpecSnapshot,
): List<AresSpecSection> {
    val footprint = computeRobotFootprintSummary(
        league = snapshot.league,
        outerLengthMeters = snapshot.robotLengthMeters,
        outerWidthMeters = snapshot.robotWidthMeters,
    )

    val identitySection = AresSpecSection(
        title = "Identity & Chassis Footprint",
        rows = listOf(
            AresSpecRow(
                id = "identity-project",
                primaryLabel = snapshot.displayName,
                secondaryLabel = "projectId: ${snapshot.projectId} · robotId: ${snapshot.robotId}",
                badge = snapshot.league.name,
                columns = listOf(
                    "Team / Season" to "${snapshot.teamId} · ${snapshot.seasonId}",
                    "Authoring Model" to snapshot.authoringModel.name,
                ),
            ),
            AresSpecRow(
                id = "identity-footprint",
                primaryLabel = "Outer Bumper Footprint",
                secondaryLabel = footprint.ruleSummary,
                badge = if (footprint.fitsLeagueSizingRule) "COMPLIANT" else "CHECK SIZING",
                columns = listOf(
                    "Length (+X)" to String.format(Locale.US, "%.3f m (%.1f\")", footprint.outerLengthMeters, footprint.outerLengthInches),
                    "Width (+Y)" to String.format(Locale.US, "%.3f m (%.1f\")", footprint.outerWidthMeters, footprint.outerWidthInches),
                    "Perimeter" to String.format(Locale.US, "%.1f\"", footprint.outerPerimeterInches),
                ),
            ),
        ),
    )

    val driveRows = if (snapshot.drivetrain != null) {
        val dt = snapshot.drivetrain
        listOf(
            AresSpecRow(
                id = "drivetrain-${dt.drivebaseId}",
                primaryLabel = dt.displayName.ifBlank { dt.drivebaseId },
                secondaryLabel = "id: ${dt.drivebaseId}",
                badge = dt.kind.name,
                columns = listOf(
                    "Platform" to dt.platform.name,
                    "Wheelbase × Track" to String.format(
                        Locale.US,
                        "%.3f m × %.3f m",
                        dt.geometry.wheelBaseMeters,
                        dt.geometry.trackWidthMeters,
                    ),
                ),
            )
        )
    } else {
        emptyList()
    }

    val subsystemRows = snapshot.subsystems.map { sub ->
        AresSpecRow(
            id = "sub-${sub.documentId}",
            primaryLabel = sub.displayName.ifBlank { sub.documentId },
            secondaryLabel = "id: ${sub.documentId}",
            badge = sub.template.name,
            columns = listOf(
                "Hardware" to "${sub.hardware.size} device(s)",
                "State Fields" to "${sub.stateFields.size} field(s)",
                "Control Loops" to "${sub.controlLoops.size} loop(s)",
            ),
        )
    }

    val superstructureRows = snapshot.superstructures.map { sup ->
        AresSpecRow(
            id = "sup-${sup.superstructureId}",
            primaryLabel = sup.displayName.ifBlank { sup.superstructureId },
            secondaryLabel = "id: ${sup.superstructureId}",
            badge = "COORDINATOR",
            columns = listOf(
                "Postures" to "${sup.states.size} state(s)",
                "Transitions" to "${sup.transitions.size} route(s)",
                "Interlocks" to "${sup.interlocks.size} guard(s)",
            ),
        )
    }

    val controlsAndRoutinesRows = buildList<AresSpecRow> {
        snapshot.controlSchemes.forEach { scheme ->
            add(
                AresSpecRow(
                    id = "scheme-${scheme.documentId}",
                    primaryLabel = scheme.name.ifBlank { scheme.documentId },
                    secondaryLabel = "controlScheme: ${scheme.documentId}",
                    badge = "CONTROLS",
                    columns = listOf(
                        "Controllers" to "${scheme.controllers.size} slot(s)",
                        "Bindings" to "${scheme.bindings.size} binding(s)",
                    ),
                )
            )
        }
        snapshot.routines.forEach { routine ->
            add(
                AresSpecRow(
                    id = "routine-${routine.documentId}",
                    primaryLabel = routine.name.ifBlank { routine.documentId },
                    secondaryLabel = "routine: ${routine.documentId}",
                    badge = "ROUTINE",
                    columns = listOf(
                        "Steps" to "${routine.steps.size} top-level step(s)",
                        "Revision" to "v${routine.revision}",
                    ),
                )
            )
        }
        if (snapshot.hardwareCount > 0 || snapshot.hardwareReviewStatus != null) {
            add(
                AresSpecRow(
                    id = "hardware-summary",
                    primaryLabel = "Port Map & Physical Review",
                    secondaryLabel = "Canonical hardware inventory",
                    badge = snapshot.hardwareReviewStatus ?: "UNREVIEWED",
                    columns = listOf(
                        "Mapped Devices" to "${snapshot.hardwareCount} device(s)",
                    ),
                )
            )
        }
    }

    return listOf(
        identitySection,
        AresSpecSection(title = "Drivetrain (${driveRows.size})", rows = driveRows),
        AresSpecSection(title = "Subsystems (${subsystemRows.size})", rows = subsystemRows),
        AresSpecSection(title = "Superstructures (${superstructureRows.size})", rows = superstructureRows),
        AresSpecSection(title = "Controls, Autonomous & Hardware (${controlsAndRoutinesRows.size})", rows = controlsAndRoutinesRows),
    )
}

fun renderRobotProjectKotlinDsl(snapshot: RobotProjectSpecSnapshot): String = buildString {
    appendLine("aresProject(${snapshot.projectId.quoteDsl()}, AresLeague.${snapshot.league.name}) {")
    appendLine(
        "    identity(teamId = ${snapshot.teamId.quoteDsl()}, seasonId = ${snapshot.seasonId.quoteDsl()}, " +
            "robotId = ${snapshot.robotId.quoteDsl()}, displayName = ${snapshot.displayName.quoteDsl()})"
    )
    appendLine("    authoringModel = com.areslib.project.AresProjectAuthoringModel.${snapshot.authoringModel.name}")
    appendLine("    geometry(robotLengthMeters = ${snapshot.robotLengthMeters}, robotWidthMeters = ${snapshot.robotWidthMeters})")
    snapshot.drivetrain?.let { doc ->
        appendLine()
        val rendered = doc.toKotlinDsl()
        val expression = if (rendered.startsWith("com.")) rendered else "com.areslib.drivetrain.$rendered"
        appendLine("    drivetrain(\n${expression.prependIndent("        ")}\n    )")
    }
    snapshot.subsystems.forEach { doc ->
        appendLine()
        appendLine("    subsystem(\n${("com.areslib.subsystem." + doc.toKotlinDsl()).prependIndent("        ")}\n    )")
    }
    snapshot.superstructures.forEach { doc ->
        appendLine()
        appendLine("    superstructure(\n${("com.areslib.superstructure." + doc.toKotlinDsl()).prependIndent("        ")}\n    )")
    }
    snapshot.routines.forEach { doc ->
        appendLine()
        appendLine("    routine(\n${("com.areslib.routine." + doc.toKotlinDsl()).prependIndent("        ")}\n    )")
    }
    snapshot.controlSchemes.forEach { doc ->
        appendLine()
        appendLine("    controlScheme(\n${("com.areslib.controls." + doc.toKotlinDsl()).prependIndent("        ")}\n    )")
    }
    append("}")
}

@Composable
fun RobotProjectSpecModal(
    isOpen: Boolean,
    snapshot: RobotProjectSpecSnapshot,
    onDismiss: () -> Unit,
) {
    AresSpecSummaryModal(
        isOpen = isOpen,
        title = "${snapshot.displayName} — Whole-Robot Specification",
        subtitle = "Project ${snapshot.projectId} · ${snapshot.league.name} · .ares/project.json",
        sections = generateRobotProjectSpecSections(snapshot),
        onDismiss = onDismiss,
        kotlinDslGenerator = { renderRobotProjectKotlinDsl(snapshot) },
    )
}

private fun String.quoteDsl(): String = buildString {
    append('"')
    for (ch in this@quoteDsl) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '$' -> append("\\$")
            else -> append(ch)
        }
    }
    append('"')
}
