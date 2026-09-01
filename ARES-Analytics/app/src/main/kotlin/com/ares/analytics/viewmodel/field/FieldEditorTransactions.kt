package com.ares.analytics.viewmodel.field

import com.ares.analytics.shared.AprilTagPlacement
import com.ares.analytics.shared.FieldImageConfig
import com.ares.analytics.shared.FieldWaypoint
import com.ares.analytics.shared.GamePiece
import com.ares.analytics.shared.GamePieceType
import com.ares.analytics.shared.Obstacle
import com.ares.analytics.shared.PathPoint
import com.ares.analytics.viewmodel.FieldEditorState
import java.util.ArrayDeque

/** Bounded transaction history for canonical field-editor data. */
internal class FieldEditorHistory(
    private val maximumEntries: Int = 100,
) {
    init {
        require(maximumEntries > 0) { "Field editor history must keep at least one entry" }
    }

    private val undoStack = ArrayDeque<FieldEditorSnapshot>()
    private val redoStack = ArrayDeque<FieldEditorSnapshot>()
    private var lastHistoryGroup: String? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun reset() {
        undoStack.clear()
        redoStack.clear()
        lastHistoryGroup = null
    }

    fun record(
        snapshot: FieldEditorSnapshot,
        historyGroup: String?,
        groupWindowActive: Boolean,
    ) {
        val shouldRecord = historyGroup == null || historyGroup != lastHistoryGroup || !groupWindowActive
        if (shouldRecord) {
            pushBounded(undoStack, snapshot)
            redoStack.clear()
        }
        lastHistoryGroup = historyGroup
    }

    fun undo(current: FieldEditorSnapshot): FieldEditorSnapshot? {
        if (undoStack.isEmpty()) return null
        pushBounded(redoStack, current)
        lastHistoryGroup = null
        return undoStack.removeLast()
    }

    fun redo(current: FieldEditorSnapshot): FieldEditorSnapshot? {
        if (redoStack.isEmpty()) return null
        pushBounded(undoStack, current)
        lastHistoryGroup = null
        return redoStack.removeLast()
    }

    private fun pushBounded(stack: ArrayDeque<FieldEditorSnapshot>, snapshot: FieldEditorSnapshot) {
        if (stack.size >= maximumEntries) stack.removeFirst()
        stack.addLast(snapshot)
    }
}

/** Owns copied field elements and deterministic duplication transforms. */
internal class FieldEditorClipboard(
    private val nextId: (prefix: String) -> String,
) {
    private var payload = FieldEditorClipboardPayload()

    val size: Int get() = payload.size

    fun reset() {
        payload = FieldEditorClipboardPayload()
    }

    fun copyFrom(state: FieldEditorState): Int {
        payload = state.selectedElements()
        return payload.size
    }

    fun pasteInto(state: FieldEditorState): FieldEditorPasteResult? =
        payload.takeIf { it.size > 0 }?.clonedFor(state)

    fun duplicateFrom(state: FieldEditorState): FieldEditorPasteResult? =
        state.selectedElements().takeIf { it.size > 0 }?.clonedFor(state)

    private fun FieldEditorClipboardPayload.clonedFor(state: FieldEditorState): FieldEditorPasteResult {
        val offset = state.gridSpacingMeters.coerceAtLeast(0.01)
        val idMap = linkedMapOf<String, String>()
        fun clonedId(original: String, prefix: String): String = idMap.getOrPut(original) { nextId(prefix) }

        val clonedObstacles = obstacles.map { obstacle ->
            val id = clonedId(obstacle.id, "obstacle")
            when (obstacle) {
                is Obstacle.Circle -> obstacle.copy(
                    id = id,
                    name = "${obstacle.name} copy",
                    centerX = obstacle.centerX + offset,
                    centerY = obstacle.centerY + offset,
                    locked = false,
                )
                is Obstacle.Rectangle -> obstacle.copy(
                    id = id,
                    name = "${obstacle.name} copy",
                    centerX = obstacle.centerX + offset,
                    centerY = obstacle.centerY + offset,
                    locked = false,
                )
                is Obstacle.Polygon -> obstacle.copy(
                    id = id,
                    name = "${obstacle.name} copy",
                    vertices = obstacle.vertices.map { PathPoint(it.x + offset, it.y + offset) },
                    locked = false,
                )
            }
        }
        val clonedPieces = gamePieces.map { piece ->
            piece.copy(
                id = clonedId(piece.id, "piece"),
                name = "${piece.name} copy",
                x = piece.x + offset,
                y = piece.y + offset,
                locked = false,
            )
        }
        val usedTagIds = state.aprilTags.mapTo(hashSetOf()) { it.tagId }
        val clonedTags = aprilTags.map { tag ->
            val nextTagId = generateSequence(1) { candidate -> candidate + 1 }
                .first { candidate -> candidate !in usedTagIds }
            usedTagIds += nextTagId
            tag.copy(
                id = clonedId(tag.id, "apriltag"),
                tagId = nextTagId,
                x = tag.x + offset,
                y = tag.y + offset,
                locked = false,
            )
        }
        val clonedWaypoints = fieldWaypoints.map { waypoint ->
            waypoint.copy(
                id = clonedId(waypoint.id, "waypoint"),
                name = "${waypoint.name} copy",
                x = waypoint.x + offset,
                y = waypoint.y + offset,
                locked = false,
            )
        }
        return FieldEditorPasteResult(
            obstacles = clonedObstacles,
            gamePieces = clonedPieces,
            aprilTags = clonedTags,
            fieldWaypoints = clonedWaypoints,
            selectedElementIds = idMap.values.toSet(),
        )
    }
}

internal data class FieldEditorPasteResult(
    val obstacles: List<Obstacle>,
    val gamePieces: List<GamePiece>,
    val aprilTags: List<AprilTagPlacement>,
    val fieldWaypoints: List<FieldWaypoint>,
    val selectedElementIds: Set<String>,
) {
    fun applyTo(state: FieldEditorState): FieldEditorState = state.copy(
        obstacles = state.obstacles + obstacles,
        gamePieces = state.gamePieces + gamePieces,
        aprilTags = state.aprilTags + aprilTags,
        fieldWaypoints = state.fieldWaypoints + fieldWaypoints,
        selectedElementIds = selectedElementIds,
    )
}

internal data class FieldEditorSnapshot(
    val fieldImageConfig: FieldImageConfig,
    val obstacles: List<Obstacle>,
    val gamePieces: List<GamePiece>,
    val gamePieceTypes: List<GamePieceType>,
    val aprilTags: List<AprilTagPlacement>,
    val fieldWaypoints: List<FieldWaypoint>,
) {
    fun applyTo(state: FieldEditorState): FieldEditorState = state.copy(
        fieldImageConfig = fieldImageConfig,
        obstacles = obstacles,
        gamePieces = gamePieces,
        gamePieceTypes = gamePieceTypes,
        aprilTags = aprilTags,
        fieldWaypoints = fieldWaypoints,
    )
}

internal fun FieldEditorState.editorSnapshot() = FieldEditorSnapshot(
    fieldImageConfig = fieldImageConfig,
    obstacles = obstacles,
    gamePieces = gamePieces,
    gamePieceTypes = gamePieceTypes,
    aprilTags = aprilTags,
    fieldWaypoints = fieldWaypoints,
)

private data class FieldEditorClipboardPayload(
    val obstacles: List<Obstacle> = emptyList(),
    val gamePieces: List<GamePiece> = emptyList(),
    val aprilTags: List<AprilTagPlacement> = emptyList(),
    val fieldWaypoints: List<FieldWaypoint> = emptyList(),
) {
    val size: Int get() = obstacles.size + gamePieces.size + aprilTags.size + fieldWaypoints.size
}

private fun FieldEditorState.selectedElements(): FieldEditorClipboardPayload {
    val selected = selectedElementIds
    return FieldEditorClipboardPayload(
        obstacles = obstacles.filter { it.id in selected },
        gamePieces = gamePieces.filter { it.id in selected },
        aprilTags = aprilTags.filter { it.id in selected },
        fieldWaypoints = fieldWaypoints.filter { it.id in selected },
    )
}
