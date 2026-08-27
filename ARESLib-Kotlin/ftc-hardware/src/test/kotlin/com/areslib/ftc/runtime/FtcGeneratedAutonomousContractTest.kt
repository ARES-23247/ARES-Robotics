package com.areslib.ftc.runtime

import com.areslib.math.coordinate.FieldSymmetry
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineAlliance
import com.areslib.routine.RoutinePose
import com.areslib.state.Alliance
import com.areslib.state.RoutineExecutionState
import com.areslib.state.RoutineExecutionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FtcGeneratedAutonomousContractTest {
    @Test
    fun `selector is stable across repeated driver station frames`() {
        val selector = FtcAutonomousSelector(
            entries = listOf(entry("second", 1), entry("first", 0)),
            defaultEntryId = "second",
            initialAlliance = Alliance.RED,
        )

        assertEquals("second", selector.selected?.entryId)
        assertTrue(selector.update(left = false, right = true, toggleAlliance = false))
        assertEquals("first", selector.selected?.entryId)
        assertFalse(selector.update(left = false, right = true, toggleAlliance = false))
        selector.update(left = false, right = false, toggleAlliance = false)
        assertTrue(selector.update(left = true, right = false, toggleAlliance = true))
        assertEquals("second", selector.selected?.entryId)
        assertEquals(Alliance.BLUE, selector.alliance)
    }

    @Test
    fun `locked missing or disabled entries fail closed`() {
        val missing = FtcAutonomousSelector(
            entries = listOf(entry("first", 0)),
            defaultEntryId = "first",
            initialAlliance = Alliance.RED,
            lockedEntryId = "renamed",
        )
        val disabled = FtcAutonomousSelector(
            entries = listOf(entry("locked", 0, enabled = false)),
            defaultEntryId = "locked",
            initialAlliance = Alliance.RED,
            lockedEntryId = "locked",
        )

        assertNull(missing.selected)
        assertNull(disabled.selected)
        assertTrue(missing.selectAlliance(Alliance.BLUE))
    }

    @Test
    fun `pose resolution mirrors only across the authored alliance boundary`() {
        val catalogEntry = entry("auto", 0).copy(
            authoredAlliance = RoutineAlliance.RED,
            startingPose = RoutinePose(0.4, 0.7, 0.3),
        )

        val red = resolveFtcAutonomousPose(catalogEntry, Alliance.RED, symmetry = FieldSymmetry.MIRRORED)
        val blue = resolveFtcAutonomousPose(catalogEntry, Alliance.BLUE, symmetry = FieldSymmetry.MIRRORED)

        assertEquals(0.4, red.x, 1e-9)
        assertEquals(0.7, red.y, 1e-9)
        assertEquals(0.4, blue.x, 1e-9)
        assertEquals(-0.7, blue.y, 1e-9)
        assertEquals(-0.3, blue.heading.radians, 1e-9)
    }

    @Test
    fun `terminal and pose evidence are execution scoped`() {
        assertEquals(
            FtcAutoTerminalDecision.RUNNING,
            classifyFtcAutoTerminal(42L, terminal(41L, RoutineExecutionStatus.COMPLETED)),
        )
        assertEquals(
            FtcAutoTerminalDecision.COMPLETED,
            classifyFtcAutoTerminal(42L, terminal(42L, RoutineExecutionStatus.COMPLETED)),
        )
        assertTrue(shouldPersistFtcAutoPose(true, true, true, null))
        assertFalse(shouldPersistFtcAutoPose(true, false, true, null))
        assertFalse(shouldPersistFtcAutoPose(true, true, false, null))
        assertFalse(shouldPersistFtcAutoPose(true, true, true, "failed"))
    }

    private fun entry(id: String, order: Int, enabled: Boolean = true) = AutonomousCatalogEntry(
        entryId = id,
        displayName = id,
        routineId = id,
        startingPose = RoutinePose(0.0, 0.0, 0.0),
        sortOrder = order,
        enabled = enabled,
    )

    private fun terminal(id: Long, status: RoutineExecutionStatus) = RoutineExecutionState(
        executionId = id,
        routineId = "test",
        status = status,
        requestedAtMs = 1L,
    )
}
