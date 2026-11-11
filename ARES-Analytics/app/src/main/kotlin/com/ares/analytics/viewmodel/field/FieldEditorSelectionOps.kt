package com.ares.analytics.viewmodel.field

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.writeFileAtomically
import com.ares.analytics.shared.AppJson
import com.ares.analytics.shared.AprilTagPlacement
import com.ares.analytics.shared.FieldWaypoint
import com.ares.analytics.shared.GamePiece
import com.ares.analytics.shared.GamePieceType
import com.ares.analytics.shared.Obstacle
import com.ares.analytics.shared.PathPoint
import com.ares.analytics.shared.models.League
import com.ares.analytics.util.ProjectLayout
import com.ares.analytics.util.Sha256
import com.ares.analytics.viewmodel.AprilTagExportFormat
import com.ares.analytics.viewmodel.AprilTagImportPreview
import com.ares.analytics.viewmodel.FieldEditorIntent
import com.ares.analytics.viewmodel.FieldEditorState
import com.areslib.state.AprilTagMapCodec
import com.areslib.state.FieldType
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldDocument
import com.areslib.state.RobotFieldValidator
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

internal object FieldEditorSelectionAndCatalogOps {

    fun validateGamePieceTypesCatalog(
        types: List<GamePieceType>,
        existingPieces: List<GamePiece>,
    ): Pair<List<GamePieceType>, String?> {
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
        if (error != null) return normalized to error

        val byId = normalized.associateBy { it.id }
        val usedMissing = existingPieces.firstOrNull { piece -> piece.typeId != null && piece.typeId !in byId }
        if (usedMissing != null) {
            return normalized to "${usedMissing.name} still uses '${usedMissing.typeId}'. Change or remove that placement before deleting its catalog type."
        }
        return normalized to null
    }

    fun applyGamePieceTypesUpdate(
        state: FieldEditorState,
        normalized: List<GamePieceType>,
    ): FieldEditorState {
        val byId = normalized.associateBy { it.id }
        val updatedPieces = state.gamePieces.map { piece ->
            val type = piece.typeId?.let(byId::get)
                ?: normalized.singleOrNull { it.name.equals(piece.type, ignoreCase = true) }
                ?: normalized.first()
            piece.copy(type = type.name, typeId = type.id)
        }
        return state.copy(gamePieceTypes = normalized, gamePieces = updatedPieces, errorMessage = null)
    }

    fun deleteSelectedElements(
        state: FieldEditorState,
        selected: Set<String>,
    ): FieldEditorState = state.copy(
        obstacles = state.obstacles.filterNot { value -> value.id in selected && !value.locked },
        gamePieces = state.gamePieces.filterNot { value -> value.id in selected && !value.locked },
        aprilTags = state.aprilTags.filterNot { value -> value.id in selected && !value.locked },
        fieldWaypoints = state.fieldWaypoints.filterNot { value -> value.id in selected && !value.locked },
        selectedElementIds = emptySet(),
    )

    fun nudgeSelectedElements(
        state: FieldEditorState,
        selected: Set<String>,
        deltaX: Double,
        deltaY: Double,
    ): FieldEditorState = state.copy(
        obstacles = state.obstacles.map { obstacle ->
            if (obstacle.id !in selected || obstacle.locked) obstacle else when (obstacle) {
                is Obstacle.Circle -> obstacle.copy(centerX = obstacle.centerX + deltaX, centerY = obstacle.centerY + deltaY)
                is Obstacle.Rectangle -> obstacle.copy(centerX = obstacle.centerX + deltaX, centerY = obstacle.centerY + deltaY)
                is Obstacle.Polygon -> obstacle.copy(vertices = obstacle.vertices.map { point -> PathPoint(point.x + deltaX, point.y + deltaY) })
            }
        },
        gamePieces = state.gamePieces.map { value -> if (value.id in selected && !value.locked) value.copy(x = value.x + deltaX, y = value.y + deltaY) else value },
        aprilTags = state.aprilTags.map { value -> if (value.id in selected && !value.locked) value.copy(x = value.x + deltaX, y = value.y + deltaY) else value },
        fieldWaypoints = state.fieldWaypoints.map { value -> if (value.id in selected && !value.locked) value.copy(x = value.x + deltaX, y = value.y + deltaY) else value },
    )

    fun addPrefabToState(
        state: FieldEditorState,
        activeLeague: League,
        prefabId: String,
        nextId: (String) -> String,
        nextAvailableTagId: () -> Int,
    ): ((FieldEditorState) -> FieldEditorState)? {
        val prefab = FieldPrefabCatalog.find(activeLeague, prefabId) ?: return null
        val usesCenteredCoordinates = activeLeague == League.FTC || activeLeague == League.XRP
        val centerX = if (usesCenteredCoordinates) 0.0 else state.fieldImageConfig.widthMeters / 2.0
        val centerY = if (usesCenteredCoordinates) 0.0 else state.fieldImageConfig.heightMeters / 2.0
        return when (prefab.kind) {
            FieldPrefabKind.RECTANGLE -> {
                val obstacle = Obstacle.Rectangle(nextId("rect"), prefab.name, centerX, centerY, prefab.widthMeters, prefab.heightMeters)
                ({ it.copy(obstacles = it.obstacles + obstacle, selectedElementIds = setOf(obstacle.id)) })
            }
            FieldPrefabKind.CIRCLE -> {
                val obstacle = Obstacle.Circle(nextId("circle"), prefab.name, centerX, centerY, prefab.radiusMeters)
                ({ it.copy(obstacles = it.obstacles + obstacle, selectedElementIds = setOf(obstacle.id)) })
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
                ({ it.copy(gamePieces = it.gamePieces + piece, selectedElementIds = setOf(piece.id)) })
            }
            FieldPrefabKind.APRIL_TAG -> {
                val tag = AprilTagPlacement(nextId("apriltag"), nextAvailableTagId(), centerX, centerY)
                ({ it.copy(aprilTags = it.aprilTags + tag, selectedElementIds = setOf(tag.id)) })
            }
            FieldPrefabKind.WAYPOINT -> {
                val waypoint = FieldWaypoint(nextId("waypoint"), "Waypoint ${state.fieldWaypoints.size + 1}", centerX, centerY, 0.0)
                ({ it.copy(fieldWaypoints = it.fieldWaypoints + waypoint, selectedElementIds = setOf(waypoint.id)) })
            }
        }
    }

    fun formatSimulatorFieldApplyStatus(
        expected: ExpectedSimulatorField,
        receipt: SimulatorFieldApplyReceipt?,
        previousReceipt: SimulatorFieldApplyReceipt?,
        failure: SimulatorFieldApplyFailure?,
        hasConfirmer: Boolean,
    ): String = when {
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
        !hasConfirmer ->
            "Field sent; this connection cannot confirm simulator application"
        else ->
            "Field sent, but the simulator did not confirm the exact revision. " +
                "Its previous field remains active; retry or relaunch the simulator."
    }
}
