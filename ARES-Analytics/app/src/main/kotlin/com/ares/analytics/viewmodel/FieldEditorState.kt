package com.ares.analytics.viewmodel

import androidx.compose.ui.graphics.ImageBitmap
import com.ares.analytics.service.project.ProjectSessionRevision
import com.ares.analytics.shared.AprilTagPlacement
import com.ares.analytics.shared.FieldImageConfig
import com.ares.analytics.shared.FieldWaypoint
import com.ares.analytics.shared.GamePiece
import com.ares.analytics.shared.GamePieceType
import com.ares.analytics.shared.Obstacle
import com.ares.analytics.shared.models.League
import com.ares.analytics.viewmodel.field.FieldEditorLayout
import com.ares.analytics.viewmodel.field.FieldMeasurementUnit
import com.ares.analytics.viewmodel.field.FieldValidationIssue
import com.areslib.state.AprilTagMapFormat
import com.areslib.state.RobotFieldConfig
import java.io.File

/** Immutable editor state backed by one canonical, revisioned field document. */
data class FieldEditorState(
    val document: RobotFieldConfig? = null,
    val projectRevision: ProjectSessionRevision? = null,
    val fieldImage: ImageBitmap? = null,
    val fieldImageConfig: FieldImageConfig = FieldImageConfig(),
    val obstacles: List<Obstacle> = emptyList(),
    val gamePieces: List<GamePiece> = emptyList(),
    val gamePieceTypes: List<GamePieceType> = emptyList(),
    val aprilTags: List<AprilTagPlacement> = emptyList(),
    val aprilTagImportPreview: AprilTagImportPreview? = null,
    val fieldWaypoints: List<FieldWaypoint> = emptyList(),
    val saveStatus: String = "",
    val selectedElementIds: Set<String> = emptySet(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val clipboardCount: Int = 0,
    val snapEnabled: Boolean = true,
    val gridSpacingMeters: Double = 0.1,
    val measurementUnit: FieldMeasurementUnit = FieldMeasurementUnit.METERS,
    val validationIssues: List<FieldValidationIssue> = emptyList(),
    val simulatorStatus: String = "",
    val isLoading: Boolean = false,
    val isDirty: Boolean = false,
    val errorMessage: String? = null,
) {
    val selectedElement: String? get() = selectedElementIds.singleOrNull()
}

sealed class FieldEditorIntent {
    data object LoadBiobuzzPreset : FieldEditorIntent()
    data class LoadConfig(val projectPath: String?, val league: League) : FieldEditorIntent()
    object SaveDocument : FieldEditorIntent()
    data class ImportFieldImage(val imageFile: File, val projectPath: String?, val league: League) : FieldEditorIntent()
    data object ClearFieldImage : FieldEditorIntent()
    data class UpdateFieldImageConfig(val config: FieldImageConfig, val projectPath: String?, val league: League) : FieldEditorIntent()
    data class AddObstacle(val obstacle: Obstacle) : FieldEditorIntent()
    data class UpdateObstacle(val index: Int, val obstacle: Obstacle) : FieldEditorIntent()
    data class DeleteObstacle(val index: Int) : FieldEditorIntent()
    data class AddGamePiece(val piece: GamePiece) : FieldEditorIntent()
    data class UpdateGamePiece(val index: Int, val piece: GamePiece) : FieldEditorIntent()
    data class DeleteGamePiece(val index: Int) : FieldEditorIntent()
    data class AddAprilTag(val tag: AprilTagPlacement) : FieldEditorIntent()
    data class UpdateAprilTag(val index: Int, val tag: AprilTagPlacement) : FieldEditorIntent()
    data class DeleteAprilTag(val index: Int) : FieldEditorIntent()
    data class AddFieldWaypoint(val waypoint: FieldWaypoint) : FieldEditorIntent()
    data class UpdateFieldWaypoint(val index: Int, val waypoint: FieldWaypoint) : FieldEditorIntent()
    data class DeleteFieldWaypoint(val index: Int) : FieldEditorIntent()
    data class SelectElement(val elementId: String?, val additive: Boolean = false) : FieldEditorIntent()
    data class SelectElements(val elementIds: Set<String>, val additive: Boolean = false) : FieldEditorIntent()
    object SelectAll : FieldEditorIntent()
    object Undo : FieldEditorIntent()
    object Redo : FieldEditorIntent()
    object CopySelection : FieldEditorIntent()
    object PasteSelection : FieldEditorIntent()
    object DuplicateSelection : FieldEditorIntent()
    object DeleteSelection : FieldEditorIntent()
    data class NudgeSelection(val deltaX: Double, val deltaY: Double) : FieldEditorIntent()
    data class SetSnapEnabled(val enabled: Boolean) : FieldEditorIntent()
    data class SetGridSpacing(val meters: Double) : FieldEditorIntent()
    data class SetMeasurementUnit(val unit: FieldMeasurementUnit) : FieldEditorIntent()
    data class AddPrefab(val prefabId: String) : FieldEditorIntent()
    object PushToSimulator : FieldEditorIntent()
    object ClearSaveStatus : FieldEditorIntent()
    data class SetObstacles(val obstacles: List<Obstacle>) : FieldEditorIntent()
    data class SetGamePieces(val gamePieces: List<GamePiece>) : FieldEditorIntent()
    data class SetGamePieceTypes(val gamePieceTypes: List<GamePieceType>) : FieldEditorIntent()
    data class SetAprilTags(val tags: List<AprilTagPlacement>) : FieldEditorIntent()
    data class SetFieldWaypoints(val waypoints: List<FieldWaypoint>) : FieldEditorIntent()
    data class SetLayout(val layout: FieldEditorLayout) : FieldEditorIntent()
    data class PreviewAprilTagMap(
        val content: String,
        val fileName: String,
        val projectPath: String?,
        val league: League,
    ) : FieldEditorIntent()
    data class ApplyAprilTagImport(val replaceExisting: Boolean) : FieldEditorIntent()
    data object DismissAprilTagImport : FieldEditorIntent()
    data class ExportAprilTagMap(val format: AprilTagExportFormat, val destination: File) : FieldEditorIntent()
}

enum class AprilTagExportFormat { LIMELIGHT_FMAP, WPILIB_JSON }

data class AprilTagImportPreview(
    val format: AprilTagMapFormat,
    val tags: List<AprilTagPlacement>,
    val fieldLengthMeters: Double?,
    val fieldWidthMeters: Double?,
    val warnings: List<String>,
    val sourceName: String,
)

internal fun allElementIds(state: FieldEditorState): Set<String> = buildSet {
    state.obstacles.forEach { add(it.id) }
    state.gamePieces.forEach { add(it.id) }
    state.aprilTags.forEach { add(it.id) }
    state.fieldWaypoints.forEach { add(it.id) }
}

internal fun League.targetPlatform(): com.areslib.controls.ControllerInputPlatform = when (this) {
    League.FTC -> com.areslib.controls.ControllerInputPlatform.FTC
    League.FRC -> com.areslib.controls.ControllerInputPlatform.FRC
    League.XRP -> com.areslib.controls.ControllerInputPlatform.XRP
}
