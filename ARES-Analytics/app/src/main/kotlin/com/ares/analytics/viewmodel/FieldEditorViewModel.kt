package com.ares.analytics.viewmodel

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.ares.analytics.shared.*
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.writeFileAtomically
import com.ares.analytics.util.ProjectLayout
import com.ares.analytics.viewmodel.field.FieldDocumentMapper
import com.ares.analytics.service.project.persistence.FieldDocumentStore
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.project.ProjectSessionMutationResult
import com.ares.analytics.service.project.ProjectSessionRevision
import com.ares.analytics.viewmodel.field.FieldImageLoader
import com.ares.analytics.viewmodel.field.FieldEditorValidator
import com.ares.analytics.viewmodel.field.FieldEditorLayout
import com.ares.analytics.viewmodel.field.FieldMeasurementUnit
import com.ares.analytics.viewmodel.field.FieldPrefabCatalog
import com.ares.analytics.viewmodel.field.FieldPrefabKind
import com.ares.analytics.viewmodel.field.FieldValidationIssue
import com.ares.analytics.viewmodel.field.FieldValidationSeverity
import com.ares.analytics.viewmodel.field.ExpectedSimulatorField
import com.ares.analytics.viewmodel.field.SIMULATOR_FIELD_APPLIED_RECEIPT_TOPIC
import com.ares.analytics.viewmodel.field.SIMULATOR_FIELD_APPLY_ERROR_TOPIC
import com.ares.analytics.viewmodel.field.SimulatorFieldApplyReceipt
import com.ares.analytics.viewmodel.field.SimulatorFieldApplyFailure
import com.ares.analytics.viewmodel.field.parseSimulatorFieldApplyReceipt
import com.ares.analytics.viewmodel.field.sha256Hex
import com.areslib.state.FieldType
import com.areslib.state.AprilTagMapCodec
import com.areslib.state.AprilTagMapFormat
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldDocument
import com.areslib.state.RobotFieldValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

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
    val errorMessage: String? = null
) {
    val selectedElement: String? get() = selectedElementIds.singleOrNull()
}

sealed class FieldEditorIntent {
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
    /** Existing intent alias; imports now require review before changing the canonical field. */
    data class ImportFmap(val fmapContent: String, val projectPath: String?, val league: League) : FieldEditorIntent()
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

/**
 * Single owner for field editor state and persistence.
 *
 * Edits are applied synchronously, folded into a new canonical revision, and atomically persisted
 * after a short debounce. The canonical field document is the only persistence format.
 */
class FieldEditorViewModel(
    private val scope: CoroutineScope,
    private val nt4ClientService: Nt4ClientService? = null,
    private val fieldConfigPublisher: (suspend (String) -> Boolean)? = null,
    private val fieldApplyReceiptProvider: (() -> SimulatorFieldApplyReceipt?)? = null,
    private val fieldApplyFailureProvider: (() -> SimulatorFieldApplyFailure?)? = null,
    private val fieldApplyConfirmer: (suspend (
        expected: ExpectedSimulatorField,
        previousReceipt: SimulatorFieldApplyReceipt?,
    ) -> SimulatorFieldApplyReceipt?)? = null,
    private val projectSession: ProjectSession? = null,
) {
    private val _state = MutableStateFlow(FieldEditorState())
    val state: StateFlow<FieldEditorState> = _state.asStateFlow()

    private val saveMutex = Mutex()
    private var saveJob: Job? = null
    private var loadGeneration = 0L
    private var activeProjectPath: String? = null
    private var activeLeague: League = League.FTC
    private val undoStack = ArrayDeque<FieldEditorSnapshot>()
    private val redoStack = ArrayDeque<FieldEditorSnapshot>()
    private var clipboard = FieldEditorClipboard()
    private var lastHistoryGroup: String? = null

    fun onIntent(intent: FieldEditorIntent) {
        when (intent) {
            is FieldEditorIntent.LoadConfig -> load(intent.projectPath, intent.league)
            is FieldEditorIntent.ImportFieldImage -> importFieldImage(intent)
            FieldEditorIntent.ClearFieldImage -> applyEdit {
                it.copy(
                    fieldImage = null,
                    fieldImageConfig = it.fieldImageConfig.copy(imagePath = ""),
                )
            }
            is FieldEditorIntent.PreviewAprilTagMap -> previewAprilTagMap(intent)
            is FieldEditorIntent.ImportFmap -> previewAprilTagMap(
                FieldEditorIntent.PreviewAprilTagMap(
                    content = intent.fmapContent,
                    fileName = "import.fmap",
                    projectPath = intent.projectPath,
                    league = intent.league,
                )
            )
            is FieldEditorIntent.ApplyAprilTagImport -> applyAprilTagImport(intent.replaceExisting)
            FieldEditorIntent.DismissAprilTagImport -> _state.update { it.copy(aprilTagImportPreview = null) }
            is FieldEditorIntent.ExportAprilTagMap -> exportAprilTagMap(intent)
            is FieldEditorIntent.UpdateFieldImageConfig -> {
                activeProjectPath = intent.projectPath ?: activeProjectPath
                activeLeague = intent.league
                applyEdit("field-image") { it.copy(fieldImageConfig = intent.config) }
            }
            is FieldEditorIntent.AddObstacle -> applyEdit { it.copy(obstacles = it.obstacles + intent.obstacle) }
            is FieldEditorIntent.UpdateObstacle -> applyEdit("obstacle-${intent.obstacle.id}") { state ->
                state.copy(obstacles = state.obstacles.mapIndexed { index, value -> if (index == intent.index) intent.obstacle else value })
            }
            is FieldEditorIntent.DeleteObstacle -> applyEdit { state ->
                state.copy(obstacles = state.obstacles.filterIndexed { index, _ -> index != intent.index })
            }
            is FieldEditorIntent.AddGamePiece -> applyEdit { it.copy(gamePieces = it.gamePieces + intent.piece) }
            is FieldEditorIntent.UpdateGamePiece -> applyEdit("game-piece-${intent.piece.id}") { state ->
                state.copy(gamePieces = state.gamePieces.mapIndexed { index, value -> if (index == intent.index) intent.piece else value })
            }
            is FieldEditorIntent.DeleteGamePiece -> applyEdit { state ->
                state.copy(gamePieces = state.gamePieces.filterIndexed { index, _ -> index != intent.index })
            }
            is FieldEditorIntent.AddAprilTag -> applyEdit { it.copy(aprilTags = it.aprilTags + intent.tag) }
            is FieldEditorIntent.UpdateAprilTag -> applyEdit("apriltag-${intent.tag.id}") { state ->
                state.copy(aprilTags = state.aprilTags.mapIndexed { index, value -> if (index == intent.index) intent.tag else value })
            }
            is FieldEditorIntent.DeleteAprilTag -> applyEdit { state ->
                state.copy(aprilTags = state.aprilTags.filterIndexed { index, _ -> index != intent.index })
            }
            is FieldEditorIntent.AddFieldWaypoint -> applyEdit { it.copy(fieldWaypoints = it.fieldWaypoints + intent.waypoint) }
            is FieldEditorIntent.UpdateFieldWaypoint -> applyEdit("field-waypoint-${intent.waypoint.id}") { state ->
                state.copy(fieldWaypoints = state.fieldWaypoints.mapIndexed { index, value -> if (index == intent.index) intent.waypoint else value })
            }
            is FieldEditorIntent.DeleteFieldWaypoint -> applyEdit { state ->
                state.copy(fieldWaypoints = state.fieldWaypoints.filterIndexed { index, _ -> index != intent.index })
            }
            is FieldEditorIntent.SetObstacles -> applyEdit("canvas-obstacles") { it.copy(obstacles = intent.obstacles) }
            is FieldEditorIntent.SetGamePieces -> applyEdit("canvas-game-pieces") { it.copy(gamePieces = intent.gamePieces) }
            is FieldEditorIntent.SetGamePieceTypes -> updateGamePieceTypes(intent.gamePieceTypes)
            is FieldEditorIntent.SetAprilTags -> applyEdit("canvas-apriltags") { it.copy(aprilTags = intent.tags) }
            is FieldEditorIntent.SetFieldWaypoints -> applyEdit("canvas-field-waypoints") { it.copy(fieldWaypoints = intent.waypoints) }
            is FieldEditorIntent.SetLayout -> applyEdit("canvas-layout") {
                it.copy(
                    obstacles = intent.layout.obstacles,
                    gamePieces = intent.layout.gamePieces,
                    aprilTags = intent.layout.aprilTags,
                    fieldWaypoints = intent.layout.fieldWaypoints
                )
            }
            is FieldEditorIntent.SelectElement -> selectElements(intent.elementId?.let(::setOf).orEmpty(), intent.additive)
            is FieldEditorIntent.SelectElements -> selectElements(intent.elementIds, intent.additive)
            FieldEditorIntent.SelectAll -> selectAll()
            FieldEditorIntent.Undo -> undo()
            FieldEditorIntent.Redo -> redo()
            FieldEditorIntent.CopySelection -> copySelection()
            FieldEditorIntent.PasteSelection -> pasteSelection()
            FieldEditorIntent.DuplicateSelection -> duplicateSelection()
            FieldEditorIntent.DeleteSelection -> deleteSelection()
            is FieldEditorIntent.NudgeSelection -> nudgeSelection(intent.deltaX, intent.deltaY)
            is FieldEditorIntent.SetSnapEnabled -> _state.update { it.copy(snapEnabled = intent.enabled) }
            is FieldEditorIntent.SetGridSpacing -> _state.update { it.copy(gridSpacingMeters = intent.meters.coerceIn(0.001, 1.0)) }
            is FieldEditorIntent.SetMeasurementUnit -> _state.update { it.copy(measurementUnit = intent.unit) }
            is FieldEditorIntent.AddPrefab -> addPrefab(intent.prefabId)
            FieldEditorIntent.PushToSimulator -> pushToSimulator()
            FieldEditorIntent.ClearSaveStatus -> _state.update { it.copy(saveStatus = "") }
            FieldEditorIntent.SaveDocument -> saveImmediately()
        }
    }

    private fun load(projectPath: String?, league: League) {
        activeProjectPath = projectPath
        activeLeague = league
        saveJob?.cancel()
        val generation = ++loadGeneration
        _state.update { it.copy(isLoading = true, errorMessage = null, saveStatus = "") }
        scope.launch {
            try {
                if (projectPath.isNullOrBlank()) {
                    val imageConfig = FieldDocumentMapper.defaultImageConfig(league)
                    val document = FieldDocumentMapper.newDocument(league, imageConfig)
                    if (generation == loadGeneration) {
                        installLoadedState(
                            FieldEditorState(
                                document = document,
                                fieldImageConfig = imageConfig,
                                gamePieceTypes = FieldDocumentMapper.defaultGamePieceTypes(league),
                            ),
                        )
                    }
                    return@launch
                }

                val (loaded, projectRevision) = withContext(Dispatchers.IO) {
                    val snapshot = projectSession?.snapshot(projectPath, league.targetPlatform(), forceReload = true)
                    val field = snapshot?.documents?.effectiveProject?.raw?.field
                    (field?.let(FieldDocumentStore::fromDocument) ?: FieldDocumentStore.load(projectPath, league)) to
                        snapshot?.revision
                }
                val bitmapResult = withContext(Dispatchers.IO) {
                    FieldImageLoader.load(projectPath, league, loaded.imageConfig.imagePath)
                }
                if (generation == loadGeneration) {
                    installLoadedState(FieldEditorState(
                        document = loaded.document,
                        projectRevision = projectRevision,
                        fieldImage = bitmapResult.getOrNull(),
                        fieldImageConfig = loaded.imageConfig,
                        obstacles = loaded.obstacles,
                        gamePieces = loaded.gamePieces,
                        gamePieceTypes = loaded.gamePieceTypes,
                        aprilTags = loaded.aprilTags,
                        fieldWaypoints = loaded.fieldWaypoints,
                        isLoading = false,
                        isDirty = false,
                        errorMessage = bitmapResult.exceptionOrNull()?.message,
                    ))
                }
            } catch (error: Exception) {
                if (generation == loadGeneration) {
                    _state.update {
                        it.copy(isLoading = false, errorMessage = error.message ?: "Failed to load field layout")
                    }
                }
            }
        }
    }

    private fun applyEdit(historyGroup: String? = null, transform: (FieldEditorState) -> FieldEditorState) {
        val current = _state.value
        val transformed = transform(current)
        if (transformed.editorSnapshot() == current.editorSnapshot()) return
        val shouldRecordHistory = historyGroup == null || historyGroup != lastHistoryGroup || saveJob?.isActive != true
        if (shouldRecordHistory) {
            pushBounded(undoStack, current.editorSnapshot())
            redoStack.clear()
        }
        lastHistoryGroup = historyGroup
        val base = transformed.document ?: FieldDocumentMapper.newDocument(activeLeague, transformed.fieldImageConfig)
        val document = FieldDocumentMapper.withEditorData(
            base = base,
            league = activeLeague,
            image = transformed.fieldImageConfig,
            obstacles = transformed.obstacles,
            gamePieces = transformed.gamePieces,
            gamePieceTypes = transformed.gamePieceTypes.ifEmpty { FieldDocumentMapper.defaultGamePieceTypes(activeLeague) },
            aprilTags = transformed.aprilTags,
            fieldWaypoints = transformed.fieldWaypoints
        )
        _state.value = withValidation(
            transformed.copy(
                document = document,
                isDirty = true,
                saveStatus = "Unsaved changes",
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        )
        scheduleSave(document)
    }

    private fun updateGamePieceTypes(types: List<GamePieceType>) {
        val normalized = types.map { it.copy(id = it.id.trim(), name = it.name.trim(), colorHex = it.colorHex.trim()) }
        val error = when {
            normalized.isEmpty() -> "Keep at least one game-piece type in the catalog."
            normalized.any { !it.id.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) } ->
                "Game-piece type IDs use lowercase letters, numbers, and single hyphens."
            normalized.map { it.id }.distinct().size != normalized.size -> "Game-piece type IDs must be unique."
            normalized.map { it.name.lowercase() }.distinct().size != normalized.size -> "Game-piece type names must be unique."
            normalized.any { it.shape !in setOf("circle", "box", "sphere", "cylinder") } -> "Choose a supported game-piece shape."
            normalized.any {
                !it.diameter.isFinite() || !it.width.isFinite() || !it.height.isFinite() ||
                    it.diameter <= 0.0 || it.width <= 0.0 || it.height <= 0.0
            } -> "Game-piece dimensions must be finite and positive."
            normalized.any { !it.massKg.isFinite() || it.massKg <= 0.0 } -> "Game-piece mass must be finite and positive."
            normalized.any { !it.friction.isFinite() || it.friction !in 0.0..1.0 } -> "Friction must be between 0 and 1."
            normalized.any { !it.restitution.isFinite() || it.restitution !in 0.0..1.0 } -> "Restitution must be between 0 and 1."
            normalized.any { !it.colorHex.matches(Regex("#[0-9A-Fa-f]{6}")) } -> "Colors must use #RRGGBB hexadecimal form."
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(errorMessage = error, saveStatus = "Catalog not changed") }
            return
        }
        val byId = normalized.associateBy { it.id }
        val usedMissing = _state.value.gamePieces.firstOrNull { piece -> piece.typeId != null && piece.typeId !in byId }
        if (usedMissing != null) {
            _state.update {
                it.copy(
                    errorMessage = "${usedMissing.name} still uses '${usedMissing.typeId}'. Change or remove that placement before deleting its catalog type.",
                    saveStatus = "Catalog not changed",
                )
            }
            return
        }
        applyEdit("game-piece-catalog") { state ->
            val updatedPieces = state.gamePieces.map { piece ->
                val type = piece.typeId?.let(byId::get)
                    ?: normalized.singleOrNull { it.name.equals(piece.type, ignoreCase = true) }
                    ?: normalized.first()
                piece.copy(type = type.name, typeId = type.id)
            }
            state.copy(gamePieceTypes = normalized, gamePieces = updatedPieces, errorMessage = null)
        }
    }

    private fun installLoadedState(state: FieldEditorState) {
        undoStack.clear()
        redoStack.clear()
        clipboard = FieldEditorClipboard()
        lastHistoryGroup = null
        _state.value = withValidation(
            state.copy(
                selectedElementIds = emptySet(),
                canUndo = false,
                canRedo = false,
                clipboardCount = 0,
                isLoading = false
            )
        )
    }

    private fun selectElements(ids: Set<String>, additive: Boolean) {
        _state.update { state ->
            val available = allElementIds(state)
            val validIds = ids.intersect(available)
            val selection = if (additive) state.selectedElementIds + validIds else validIds
            state.copy(selectedElementIds = selection)
        }
    }

    private fun selectAll() {
        _state.update { it.copy(selectedElementIds = allElementIds(it)) }
    }

    private fun undo() {
        if (undoStack.isEmpty()) return
        val current = _state.value
        pushBounded(redoStack, current.editorSnapshot())
        restoreSnapshot(undoStack.removeLast(), current.selectedElementIds)
        lastHistoryGroup = null
    }

    private fun redo() {
        if (redoStack.isEmpty()) return
        val current = _state.value
        pushBounded(undoStack, current.editorSnapshot())
        restoreSnapshot(redoStack.removeLast(), current.selectedElementIds)
        lastHistoryGroup = null
    }

    private fun restoreSnapshot(snapshot: FieldEditorSnapshot, previousSelection: Set<String>) {
        val current = snapshot.applyTo(_state.value)
        val base = current.document ?: FieldDocumentMapper.newDocument(activeLeague, current.fieldImageConfig)
        val document = FieldDocumentMapper.withEditorData(
            base = base,
            league = activeLeague,
            image = current.fieldImageConfig,
            obstacles = current.obstacles,
            gamePieces = current.gamePieces,
            gamePieceTypes = current.gamePieceTypes.ifEmpty { FieldDocumentMapper.defaultGamePieceTypes(activeLeague) },
            aprilTags = current.aprilTags,
            fieldWaypoints = current.fieldWaypoints
        )
        val validSelection = previousSelection.intersect(allElementIds(current))
        _state.value = withValidation(
            current.copy(
                document = document,
                selectedElementIds = validSelection,
                isDirty = true,
                saveStatus = "Unsaved changes",
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        )
        scheduleSave(document)
    }

    private fun copySelection() {
        clipboard = selectionClipboard(_state.value)
        _state.update {
            it.copy(
                clipboardCount = clipboard.size,
                saveStatus = if (clipboard.size == 0) "Nothing selected" else "Copied ${clipboard.size} field item${if (clipboard.size == 1) "" else "s"}"
            )
        }
    }

    private fun pasteSelection() {
        if (clipboard.size == 0) return
        pasteClipboard(clipboard)
    }

    private fun duplicateSelection() {
        val selected = selectionClipboard(_state.value)
        if (selected.size == 0) return
        pasteClipboard(selected)
    }

    private fun pasteClipboard(source: FieldEditorClipboard) {
        val offset = _state.value.gridSpacingMeters.coerceAtLeast(0.01)
        val idMap = linkedMapOf<String, String>()
        fun clonedId(original: String, prefix: String): String = idMap.getOrPut(original) { nextId(prefix) }

        val clonedObstacles = source.obstacles.map { obstacle ->
            val id = clonedId(obstacle.id, "obstacle")
            when (obstacle) {
                is Obstacle.Circle -> obstacle.copy(id = id, name = "${obstacle.name} copy", centerX = obstacle.centerX + offset, centerY = obstacle.centerY + offset, locked = false)
                is Obstacle.Rectangle -> obstacle.copy(id = id, name = "${obstacle.name} copy", centerX = obstacle.centerX + offset, centerY = obstacle.centerY + offset, locked = false)
                is Obstacle.Polygon -> obstacle.copy(id = id, name = "${obstacle.name} copy", vertices = obstacle.vertices.map { PathPoint(it.x + offset, it.y + offset) }, locked = false)
            }
        }
        val clonedPieces = source.gamePieces.map { it.copy(id = clonedId(it.id, "piece"), name = "${it.name} copy", x = it.x + offset, y = it.y + offset, locked = false) }
        val usedTagIds = _state.value.aprilTags.mapTo(hashSetOf()) { it.tagId }
        val clonedTags = source.aprilTags.map {
            val nextTagId = generateSequence(1) { candidate -> candidate + 1 }.first { candidate -> candidate !in usedTagIds }
            usedTagIds += nextTagId
            it.copy(id = clonedId(it.id, "apriltag"), tagId = nextTagId, x = it.x + offset, y = it.y + offset, locked = false)
        }
        val clonedWaypoints = source.fieldWaypoints.map { it.copy(id = clonedId(it.id, "waypoint"), name = "${it.name} copy", x = it.x + offset, y = it.y + offset, locked = false) }
        val newSelection = idMap.values.toSet()

        applyEdit {
            it.copy(
                obstacles = it.obstacles + clonedObstacles,
                gamePieces = it.gamePieces + clonedPieces,
                aprilTags = it.aprilTags + clonedTags,
                fieldWaypoints = it.fieldWaypoints + clonedWaypoints,
                selectedElementIds = newSelection
            )
        }
    }

    private fun deleteSelection() {
        val selected = _state.value.selectedElementIds
        if (selected.isEmpty()) return
        applyEdit {
            it.copy(
                obstacles = it.obstacles.filterNot { value -> value.id in selected && !value.locked },
                gamePieces = it.gamePieces.filterNot { value -> value.id in selected && !value.locked },
                aprilTags = it.aprilTags.filterNot { value -> value.id in selected && !value.locked },
                fieldWaypoints = it.fieldWaypoints.filterNot { value -> value.id in selected && !value.locked },
                selectedElementIds = emptySet()
            )
        }
    }

    private fun nudgeSelection(deltaX: Double, deltaY: Double) {
        val selected = _state.value.selectedElementIds
        if (selected.isEmpty()) return
        applyEdit("nudge") {
            it.copy(
                obstacles = it.obstacles.map { obstacle ->
                    if (obstacle.id !in selected || obstacle.locked) obstacle else when (obstacle) {
                        is Obstacle.Circle -> obstacle.copy(centerX = obstacle.centerX + deltaX, centerY = obstacle.centerY + deltaY)
                        is Obstacle.Rectangle -> obstacle.copy(centerX = obstacle.centerX + deltaX, centerY = obstacle.centerY + deltaY)
                        is Obstacle.Polygon -> obstacle.copy(vertices = obstacle.vertices.map { point -> PathPoint(point.x + deltaX, point.y + deltaY) })
                    }
                },
                gamePieces = it.gamePieces.map { value -> if (value.id in selected && !value.locked) value.copy(x = value.x + deltaX, y = value.y + deltaY) else value },
                aprilTags = it.aprilTags.map { value -> if (value.id in selected && !value.locked) value.copy(x = value.x + deltaX, y = value.y + deltaY) else value },
                fieldWaypoints = it.fieldWaypoints.map { value -> if (value.id in selected && !value.locked) value.copy(x = value.x + deltaX, y = value.y + deltaY) else value }
            )
        }
    }

    private fun addPrefab(prefabId: String) {
        val prefab = FieldPrefabCatalog.find(activeLeague, prefabId) ?: return
        val state = _state.value
        val centerX = if (activeLeague == League.FTC) 0.0 else state.fieldImageConfig.widthMeters / 2.0
        val centerY = if (activeLeague == League.FTC) 0.0 else state.fieldImageConfig.heightMeters / 2.0
        when (prefab.kind) {
            FieldPrefabKind.RECTANGLE -> {
                val obstacle = Obstacle.Rectangle(nextId("rect"), prefab.name, centerX, centerY, prefab.widthMeters, prefab.heightMeters)
                applyEdit { it.copy(obstacles = it.obstacles + obstacle, selectedElementIds = setOf(obstacle.id)) }
            }
            FieldPrefabKind.CIRCLE -> {
                val obstacle = Obstacle.Circle(nextId("circle"), prefab.name, centerX, centerY, prefab.radiusMeters)
                applyEdit { it.copy(obstacles = it.obstacles + obstacle, selectedElementIds = setOf(obstacle.id)) }
            }
            FieldPrefabKind.GAME_PIECE -> {
                val type = state.gamePieceTypes.singleOrNull {
                    it.name.equals(prefab.gamePieceType, ignoreCase = true) || it.id == prefab.gamePieceType
                } ?: state.gamePieceTypes.firstOrNull()
                val piece = GamePiece(
                    id = nextId("piece"),
                    name = prefab.name,
                    x = centerX,
                    y = centerY,
                    type = type?.name ?: prefab.gamePieceType ?: "Custom",
                    typeId = type?.id,
                )
                applyEdit { it.copy(gamePieces = it.gamePieces + piece, selectedElementIds = setOf(piece.id)) }
            }
            FieldPrefabKind.APRIL_TAG -> {
                val tag = AprilTagPlacement(nextId("apriltag"), nextAvailableTagId(), centerX, centerY)
                applyEdit { it.copy(aprilTags = it.aprilTags + tag, selectedElementIds = setOf(tag.id)) }
            }
            FieldPrefabKind.WAYPOINT -> {
                val waypoint = FieldWaypoint(nextId("waypoint"), "Waypoint ${state.fieldWaypoints.size + 1}", centerX, centerY, 0.0)
                applyEdit { it.copy(fieldWaypoints = it.fieldWaypoints + waypoint, selectedElementIds = setOf(waypoint.id)) }
            }
        }
    }

    private fun pushToSimulator() {
        val current = _state.value
        val document = current.document
        val publisher = fieldConfigPublisher ?: nt4ClientService?.let { client ->
            { payload: String -> client.publishString("ARES/Input/fieldConfig", payload) }
        }
        if (publisher == null || document == null) {
            _state.update { it.copy(simulatorStatus = "Simulator connection is unavailable") }
            return
        }
        val validationErrors = current.validationIssues.count { it.severity == FieldValidationSeverity.ERROR }
        if (validationErrors > 0) {
            val firstError = current.validationIssues.first { it.severity == FieldValidationSeverity.ERROR }.message
            _state.update {
                it.copy(
                    simulatorStatus = "Field not pushed: $firstError" +
                        if (validationErrors == 1) "" else " (+${validationErrors - 1} more)"
                )
            }
            return
        }
        scope.launch {
            _state.update { it.copy(simulatorStatus = "Pushing field…") }
            try {
                val payload = RobotFieldDocument.encode(document)
                val expected = ExpectedSimulatorField(
                    configId = document.id,
                    revision = document.revision,
                    sha256 = sha256Hex(payload),
                )
                val previousReceipt = currentSimulatorReceipt()
                val previousFailure = currentSimulatorFailure()
                val published = publisher(payload)
                if (!published) {
                    _state.update {
                        it.copy(simulatorStatus = "Simulator push failed: field configuration was not accepted")
                    }
                    return@launch
                }
                val confirmer = fieldApplyConfirmer ?: nt4ClientService?.let { client ->
                    { wanted: ExpectedSimulatorField, previous: SimulatorFieldApplyReceipt? ->
                        awaitSimulatorReceipt(client, wanted, previous)
                    }
                }
                val receipt = confirmer?.invoke(expected, previousReceipt)
                val failure = currentSimulatorFailure()
                    ?.takeIf { it.eventId != previousFailure?.eventId && it.message.isNotBlank() }
                _state.update {
                    it.copy(
                        simulatorStatus = when {
                            receipt?.matches(expected) == true ->
                                "Simulator applied field revision ${receipt.revision}: " +
                                    "${receipt.obstacleCount} obstacle(s), ${receipt.elementCount} game piece(s), " +
                                    "${receipt.aprilTagCount} AprilTag(s)"
                            previousReceipt?.matches(expected) == true ->
                                "Simulator already has exact field revision ${previousReceipt.revision}: " +
                                    "${previousReceipt.obstacleCount} obstacle(s), " +
                                    "${previousReceipt.elementCount} game piece(s), " +
                                    "${previousReceipt.aprilTagCount} AprilTag(s)"
                            failure != null ->
                                "Simulator rejected this field: ${failure.message} " +
                                    "The previous field remains active."
                            confirmer == null ->
                                "Field sent; this connection cannot confirm simulator application"
                            else ->
                                "Field sent, but the simulator did not confirm the exact revision. " +
                                    "Its previous field remains active; retry or relaunch the simulator."
                        }
                    )
                }
            } catch (error: Exception) {
                _state.update { it.copy(simulatorStatus = "Simulator push failed: ${error.message}") }
            }
        }
    }

    private fun currentSimulatorReceipt(): SimulatorFieldApplyReceipt? =
        fieldApplyReceiptProvider?.invoke() ?: nt4ClientService
            ?.latestValues
            ?.get(SIMULATOR_FIELD_APPLIED_RECEIPT_TOPIC)
            ?.stringValue
            .let(::parseSimulatorFieldApplyReceipt)

    private fun currentSimulatorFailure(): SimulatorFieldApplyFailure? =
        fieldApplyFailureProvider?.invoke() ?: nt4ClientService
            ?.latestValues
            ?.get(SIMULATOR_FIELD_APPLY_ERROR_TOPIC)
            ?.takeIf { !it.stringValue.isNullOrBlank() }
            ?.let { frame ->
                SimulatorFieldApplyFailure(
                    eventId = "${frame.sessionId}:${frame.timestampUs}:${frame.sampleOrder}",
                    message = frame.stringValue.orEmpty(),
                )
            }

    private suspend fun awaitSimulatorReceipt(
        client: Nt4ClientService,
        expected: ExpectedSimulatorField,
        previousReceipt: SimulatorFieldApplyReceipt?,
    ): SimulatorFieldApplyReceipt? = withTimeoutOrNull(FIELD_APPLY_CONFIRMATION_TIMEOUT_MS) {
        client.telemetryFlow.first { frame ->
            if (frame.key.trimStart('/') != SIMULATOR_FIELD_APPLIED_RECEIPT_TOPIC) return@first false
            val receipt = parseSimulatorFieldApplyReceipt(frame.stringValue) ?: return@first false
            receipt.eventId != previousReceipt?.eventId && receipt.matches(expected)
        }.stringValue.let(::parseSimulatorFieldApplyReceipt)
    }

    private fun withValidation(state: FieldEditorState): FieldEditorState {
        val width = state.fieldImageConfig.widthMeters.takeIf { it > 0.0 } ?: if (activeLeague == League.FTC) 3.6576 else 16.541
        val height = state.fieldImageConfig.heightMeters.takeIf { it > 0.0 } ?: if (activeLeague == League.FTC) 3.6576 else 8.211
        val editorIssues = FieldEditorValidator.validate(
            league = activeLeague,
            widthMeters = width,
            heightMeters = height,
            obstacles = state.obstacles,
            gamePieces = state.gamePieces,
            aprilTags = state.aprilTags,
            waypoints = state.fieldWaypoints
        )
        val requiredFieldType = if (activeLeague == League.FTC) FieldType.FTC else FieldType.FRC
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
                requireAprilTags = requiredFieldType == FieldType.FTC,
            )
        }.orEmpty()

    private fun selectionClipboard(state: FieldEditorState): FieldEditorClipboard {
        val selected = state.selectedElementIds
        return FieldEditorClipboard(
            obstacles = state.obstacles.filter { it.id in selected },
            gamePieces = state.gamePieces.filter { it.id in selected },
            aprilTags = state.aprilTags.filter { it.id in selected },
            fieldWaypoints = state.fieldWaypoints.filter { it.id in selected }
        )
    }

    private fun allElementIds(state: FieldEditorState): Set<String> = buildSet {
        state.obstacles.forEach { add(it.id) }
        state.gamePieces.forEach { add(it.id) }
        state.aprilTags.forEach { add(it.id) }
        state.fieldWaypoints.forEach { add(it.id) }
    }

    private fun nextAvailableTagId(): Int {
        val used = _state.value.aprilTags.mapTo(hashSetOf()) { it.tagId }
        return generateSequence(1) { it + 1 }.first { it !in used }
    }

    private fun nextId(prefix: String): String = "$prefix-${ID_SEQUENCE.incrementAndGet()}"

    private fun pushBounded(stack: ArrayDeque<FieldEditorSnapshot>, snapshot: FieldEditorSnapshot) {
        if (stack.size >= MAX_HISTORY_ENTRIES) stack.removeFirst()
        stack.addLast(snapshot)
    }

    private fun scheduleSave(document: RobotFieldConfig) {
        val projectPath = activeProjectPath?.takeIf(String::isNotBlank) ?: return
        val league = activeLeague
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            persistSnapshot(projectPath, league, document)
        }
    }

    private fun saveImmediately() {
        val document = _state.value.document ?: return
        val projectPath = activeProjectPath?.takeIf(String::isNotBlank) ?: return
        val league = activeLeague
        saveJob?.cancel()
        saveJob = scope.launch { persistSnapshot(projectPath, league, document) }
    }

    private suspend fun persistSnapshot(projectPath: String, league: League, document: RobotFieldConfig) {
        saveMutex.withLock {
            if (_state.value.document?.revision != document.revision) return@withLock
            _state.update { it.copy(saveStatus = "Saving…") }
            try {
                val updatedRevision = withContext(Dispatchers.IO) {
                    val session = projectSession
                    val revision = _state.value.projectRevision
                    if (session != null && revision != null) {
                        when (val result = session.saveField(revision, league, document)) {
                            is ProjectSessionMutationResult.Applied -> result.snapshot.revision
                            is ProjectSessionMutationResult.Stale -> error("The project changed after this field loaded. Reload before saving.")
                            is ProjectSessionMutationResult.Conflict -> error(result.message)
                            is ProjectSessionMutationResult.Failed -> error(result.message)
                        }
                    } else {
                        FieldDocumentStore.save(projectPath, league, document)
                        null
                    }
                }
                if (updatedRevision != null) {
                    _state.update { it.copy(projectRevision = updatedRevision) }
                }
                if (_state.value.document?.revision == document.revision) {
                    _state.update { it.copy(isDirty = false, saveStatus = "Saved field revision ${document.revision}") }
                }
            } catch (error: Exception) {
                if (_state.value.document?.revision == document.revision) {
                    _state.update { it.copy(saveStatus = "Failed to save field: ${error.message}") }
                }
            }
        }
    }

    private fun importFieldImage(intent: FieldEditorIntent.ImportFieldImage) {
        val projectPath = intent.projectPath?.takeIf(String::isNotBlank) ?: return
        activeProjectPath = projectPath
        activeLeague = intent.league
        scope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val target = File(ProjectLayout.assetsDirectory(projectPath, intent.league), "field_image.png")
                    target.parentFile?.mkdirs()
                    val temporary = File(target.parentFile, ".field_image.png.tmp")
                    try {
                        Files.copy(intent.imageFile.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    } finally {
                        if (temporary.exists()) temporary.delete()
                    }
                    org.jetbrains.skia.Image.makeFromEncoded(target.readBytes()).toComposeImageBitmap()
                }
                applyEdit {
                    it.copy(
                        fieldImage = bitmap,
                        fieldImageConfig = it.fieldImageConfig.copy(imagePath = "field_image.png")
                    )
                }
            } catch (error: Exception) {
                _state.update { it.copy(saveStatus = "Failed to import field image: ${error.message}") }
            }
        }
    }

    private fun previewAprilTagMap(intent: FieldEditorIntent.PreviewAprilTagMap) {
        activeProjectPath = intent.projectPath ?: activeProjectPath
        activeLeague = intent.league
        try {
            val decoded = when {
                intent.fileName.endsWith(".fmap", ignoreCase = true) ->
                    AprilTagMapCodec.decodeLimelightFmapForField(
                        intent.content,
                        requireNotNull(_state.value.document) { "Load a field before importing its AprilTags" },
                    )
                else -> runCatching { AprilTagMapCodec.decodeWpilib(intent.content) }
                    .recoverCatching { AprilTagMapCodec.decodeAresField(intent.content) }
                    .recoverCatching {
                        AprilTagMapCodec.decodeLimelightFmapForField(
                            intent.content,
                            requireNotNull(_state.value.document) { "Load a field before importing its AprilTags" },
                        )
                    }
                    .getOrThrow()
            }
            val placements = decoded.tags.map { tag ->
                AprilTagPlacement(
                    id = tag.editorId.ifBlank { "apriltag_${tag.id}" },
                    tagId = tag.id,
                    name = tag.name,
                    family = tag.family,
                    sizeMeters = tag.sizeMeters,
                    x = tag.x,
                    y = tag.y,
                    z = tag.z,
                    rollDegrees = tag.roll,
                    pitchDegrees = tag.pitch,
                    yawDegrees = tag.yaw,
                )
            }
            val existingIds = _state.value.aprilTags.mapTo(hashSetOf()) { it.tagId }
            val conflicts = placements.count { it.tagId in existingIds }
            val warnings = buildList {
                decoded.omittedMetadata.sorted().forEach { omitted ->
                    add("The source does not contain $omitted; review those values before hardware use.")
                }
                if (conflicts > 0) {
                    add("$conflicts imported tag ID(s) already exist. Merge keeps the current versions; replace uses the import.")
                }
                if (intent.league == League.FTC && placements.any { it.family.isBlank() || it.sizeMeters == null }) {
                    add("FTC VisionPortal requires a family and physical size for every tag before deployment.")
                }
            }
            _state.update {
                it.copy(
                    aprilTagImportPreview = AprilTagImportPreview(
                        format = decoded.format,
                        tags = placements,
                        fieldLengthMeters = decoded.fieldLengthMeters,
                        fieldWidthMeters = decoded.fieldWidthMeters,
                        warnings = warnings,
                        sourceName = intent.fileName,
                    ),
                    saveStatus = "Review ${placements.size} imported AprilTag(s) before applying them.",
                )
            }
        } catch (error: Exception) {
            _state.update {
                it.copy(
                    aprilTagImportPreview = null,
                    saveStatus = "Failed to parse AprilTag map: ${error.message}",
                )
            }
        }
    }

    private fun applyAprilTagImport(replaceExisting: Boolean) {
        val preview = _state.value.aprilTagImportPreview ?: return
        applyEdit { state ->
            val tags = if (replaceExisting) {
                preview.tags
            } else {
                val existingIds = state.aprilTags.mapTo(hashSetOf()) { it.tagId }
                state.aprilTags + preview.tags.filterNot { it.tagId in existingIds }
            }
            state.copy(
                aprilTags = tags,
                aprilTagImportPreview = null,
                fieldImageConfig = if (
                    replaceExisting && preview.fieldLengthMeters != null && preview.fieldWidthMeters != null
                ) {
                    state.fieldImageConfig.copy(
                        widthMeters = preview.fieldLengthMeters,
                        heightMeters = preview.fieldWidthMeters,
                    )
                } else {
                    state.fieldImageConfig
                },
            )
        }
    }

    private fun exportAprilTagMap(intent: FieldEditorIntent.ExportAprilTagMap) {
        val document = _state.value.document ?: return
        scope.launch {
            try {
                val content = withContext(Dispatchers.Default) {
                    when (intent.format) {
                        AprilTagExportFormat.LIMELIGHT_FMAP -> AprilTagMapCodec.encodeLimelightFmap(document)
                        AprilTagExportFormat.WPILIB_JSON -> AprilTagMapCodec.encodeWpilib(document)
                    }
                }
                withContext(Dispatchers.IO) {
                    writeFileAtomically(intent.destination) { temporary ->
                        temporary.writeText(content.trimEnd() + System.lineSeparator())
                    }
                }
                _state.update {
                    it.copy(saveStatus = "Exported ${document.apriltags.size} AprilTag(s) to ${intent.destination.name}.")
                }
            } catch (error: Exception) {
                _state.update { it.copy(saveStatus = "AprilTag export failed: ${error.message}") }
            }
        }
    }

    private companion object {
        const val SAVE_DEBOUNCE_MS = 350L
        const val FIELD_APPLY_CONFIRMATION_TIMEOUT_MS = 3_000L
        const val MAX_HISTORY_ENTRIES = 100
        val ID_SEQUENCE = AtomicLong(System.currentTimeMillis())
    }
}

private fun League.targetPlatform() = when (this) {
    League.FTC -> com.areslib.controls.ControllerInputPlatform.FTC
    League.FRC -> com.areslib.controls.ControllerInputPlatform.FRC
}

private data class FieldEditorSnapshot(
    val fieldImageConfig: FieldImageConfig,
    val obstacles: List<Obstacle>,
    val gamePieces: List<GamePiece>,
    val gamePieceTypes: List<GamePieceType>,
    val aprilTags: List<AprilTagPlacement>,
    val fieldWaypoints: List<FieldWaypoint>
) {
    fun applyTo(state: FieldEditorState): FieldEditorState = state.copy(
        fieldImageConfig = fieldImageConfig,
        obstacles = obstacles,
        gamePieces = gamePieces,
        gamePieceTypes = gamePieceTypes,
        aprilTags = aprilTags,
        fieldWaypoints = fieldWaypoints
    )
}

private fun FieldEditorState.editorSnapshot() = FieldEditorSnapshot(
    fieldImageConfig = fieldImageConfig,
    obstacles = obstacles,
    gamePieces = gamePieces,
    gamePieceTypes = gamePieceTypes,
    aprilTags = aprilTags,
    fieldWaypoints = fieldWaypoints
)

private data class FieldEditorClipboard(
    val obstacles: List<Obstacle> = emptyList(),
    val gamePieces: List<GamePiece> = emptyList(),
    val aprilTags: List<AprilTagPlacement> = emptyList(),
    val fieldWaypoints: List<FieldWaypoint> = emptyList()
) {
    val size: Int get() = obstacles.size + gamePieces.size + aprilTags.size + fieldWaypoints.size
}
