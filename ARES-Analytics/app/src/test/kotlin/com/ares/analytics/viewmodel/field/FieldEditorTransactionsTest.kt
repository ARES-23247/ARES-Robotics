package com.ares.analytics.viewmodel.field

import com.ares.analytics.shared.AprilTagPlacement
import com.ares.analytics.shared.GamePiece
import com.ares.analytics.shared.Obstacle
import com.ares.analytics.shared.PathPoint
import com.ares.analytics.shared.FieldWaypoint
import com.ares.analytics.shared.FieldImageConfig
import com.ares.analytics.shared.GamePieceType
import com.ares.analytics.viewmodel.FieldEditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class FieldEditorTransactionsTest {
    @Test
    fun `redo is invalidated by a new edit and reset discards both histories`() {
        assertFailsWith<IllegalArgumentException> { FieldEditorHistory(0) }
        val history = FieldEditorHistory()
        val before = stateWithObstacle("before").editorSnapshot()
        val after = stateWithObstacle("after").editorSnapshot()
        history.record(before, "edit", false)
        assertEquals(before, history.undo(after))
        assertEquals(after, history.redo(before))
        assertEquals(before, history.undo(after))
        history.record(before, "edit", true)
        assertFalse(history.canRedo)
        assertNull(history.redo(after))
        history.reset()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertNull(history.undo(after))
    }

    @Test
    fun `multiple copied tags receive lowest unused IDs across gaps`() {
        var sequence = 0
        val clipboard = FieldEditorClipboard { "$it-${++sequence}" }
        val tags = listOf(1, 3, 4).map { AprilTagPlacement("tag-$it", it, 0.0, 0.0) }
        val state = FieldEditorState(aprilTags = tags, selectedElementIds = tags.map { it.id }.toSet())
        val paste = requireNotNull(clipboard.duplicateFrom(state))
        assertEquals(listOf(2, 5, 6), paste.aprilTags.map { it.tagId })
        assertEquals(0, clipboard.size)
        assertNull(clipboard.pasteInto(state))
        clipboard.copyFrom(state)
        clipboard.reset()
        assertNull(clipboard.pasteInto(state))
    }

    @Test
    fun `clipboard geometry and snapshots preserve authored fields without replacing editor settings`() {
        var sequence = 0
        val clipboard = FieldEditorClipboard { "$it-${++sequence}" }
        val source = FieldEditorState(
            fieldImageConfig = FieldImageConfig(rotationDegrees = 30.0),
            obstacles = listOf(Obstacle.Circle("c", "Circle", 1.0, 2.0, 0.3),
                Obstacle.Polygon("p", "Polygon", listOf(PathPoint(0.0, 0.0), PathPoint(1.0, 0.0), PathPoint(0.0, 1.0)))),
            gamePieces = listOf(GamePiece("g", "Game", 2.0, 3.0, typeId = "catalog", rotationRadians = 0.4, locked = true)),
            gamePieceTypes = listOf(GamePieceType("catalog", "Catalog")),
            fieldWaypoints = listOf(FieldWaypoint("w", "Waypoint", 3.0, 4.0, 75.0, locked = true)),
            selectedElementIds = setOf("c", "p", "g", "w"), gridSpacingMeters = 0.25,
        )
        assertEquals(4, clipboard.copyFrom(source))
        val paste = requireNotNull(clipboard.pasteInto(source))
        assertEquals(1.25, (paste.obstacles[0] as Obstacle.Circle).centerX)
        assertEquals(listOf(PathPoint(0.25, 0.25), PathPoint(1.25, 0.25), PathPoint(0.25, 1.25)),
            (paste.obstacles[1] as Obstacle.Polygon).vertices)
        assertEquals(source.gamePieces.single().copy(id = "piece-3", name = "Game copy", x = 2.25, y = 3.25, locked = false), paste.gamePieces.single())
        assertEquals(source.fieldWaypoints.single().copy(id = "waypoint-4", name = "Waypoint copy", x = 3.25, y = 4.25, locked = false), paste.fieldWaypoints.single())
        assertEquals(4, paste.selectedElementIds.size)
        val applied = paste.applyTo(source)
        assertEquals(4, applied.obstacles.size)
        assertEquals(2, applied.gamePieces.size)
        val restored = source.editorSnapshot().applyTo(applied.copy(gridSpacingMeters = 0.5))
        assertEquals(source.editorSnapshot(), restored.editorSnapshot())
        assertEquals(0.5, restored.gridSpacingMeters)
    }
    @Test
    fun `history is bounded and restores whole editor snapshots`() {
        val history = FieldEditorHistory(maximumEntries = 2)
        val first = stateWithObstacle("first")
        val second = stateWithObstacle("second")
        val third = stateWithObstacle("third")
        val current = stateWithObstacle("current")

        history.record(first.editorSnapshot(), historyGroup = null, groupWindowActive = false)
        history.record(second.editorSnapshot(), historyGroup = null, groupWindowActive = false)
        history.record(third.editorSnapshot(), historyGroup = null, groupWindowActive = false)

        assertEquals("third", history.undo(current.editorSnapshot())?.obstacles?.single()?.id)
        assertEquals("second", history.undo(third.editorSnapshot())?.obstacles?.single()?.id)
        assertNull(history.undo(second.editorSnapshot()))
        assertTrue(history.canRedo)
    }

    @Test
    fun `active edit groups coalesce into one undo transaction`() {
        val history = FieldEditorHistory()
        val before = stateWithObstacle("before")
        val intermediate = stateWithObstacle("intermediate")
        val after = stateWithObstacle("after")

        history.record(before.editorSnapshot(), historyGroup = "nudge", groupWindowActive = false)
        history.record(intermediate.editorSnapshot(), historyGroup = "nudge", groupWindowActive = true)

        assertEquals("before", history.undo(after.editorSnapshot())?.obstacles?.single()?.id)
        assertFalse(history.canUndo)
    }

    @Test
    fun `clipboard duplication assigns new identities and unlocks copies`() {
        var sequence = 0
        val clipboard = FieldEditorClipboard { prefix -> "$prefix-${++sequence}" }
        val source = FieldEditorState(
            obstacles = listOf(
                Obstacle.Rectangle("barrier", "Barrier", 1.0, 2.0, 0.5, 0.25, locked = true),
            ),
            gamePieces = listOf(GamePiece("piece", "Piece", 0.0, 0.0)),
            aprilTags = listOf(AprilTagPlacement("tag", 1, x = 0.5, y = 0.75, locked = true)),
            selectedElementIds = setOf("barrier", "tag"),
            gridSpacingMeters = 0.2,
        )

        assertEquals(2, clipboard.copyFrom(source))
        val paste = requireNotNull(clipboard.pasteInto(source))

        assertEquals(setOf("obstacle-1", "apriltag-2"), paste.selectedElementIds)
        assertEquals(1.2, (paste.obstacles.single() as Obstacle.Rectangle).centerX, 1e-12)
        assertFalse(paste.obstacles.single().locked)
        assertEquals(2, paste.aprilTags.single().tagId)
        assertFalse(paste.aprilTags.single().locked)
    }

    private fun stateWithObstacle(id: String) = FieldEditorState(
        obstacles = listOf(Obstacle.Circle(id, id, 0.0, 0.0, 0.1)),
    )
}
