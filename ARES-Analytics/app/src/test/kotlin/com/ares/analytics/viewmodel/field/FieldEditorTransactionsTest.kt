package com.ares.analytics.viewmodel.field

import com.ares.analytics.shared.AprilTagPlacement
import com.ares.analytics.shared.GamePiece
import com.ares.analytics.shared.Obstacle
import com.ares.analytics.viewmodel.FieldEditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FieldEditorTransactionsTest {
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
