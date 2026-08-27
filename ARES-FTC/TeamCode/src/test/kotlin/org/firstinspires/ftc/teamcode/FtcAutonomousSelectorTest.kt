package org.firstinspires.ftc.teamcode

import com.areslib.math.wrapAngle
import com.areslib.math.coordinate.FieldSymmetry
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineAlliance
import com.areslib.routine.RoutinePose
import com.areslib.state.Alliance
import com.areslib.ftc.runtime.FtcAutonomousSelector
import com.areslib.ftc.runtime.resolveFtcAutonomousPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FtcAutonomousSelectorTest {
    @Test
    fun `selector honors generated default and changes only on rising edges`() {
        val selector = FtcAutonomousSelector(
            entries = listOf(entry("second", 1), entry("first", 0)),
            defaultEntryId = "second",
            initialAlliance = Alliance.RED,
        )

        assertEquals("second", selector.selected?.entryId)
        assertTrue(selector.update(left = false, right = true, toggleAlliance = false))
        assertEquals("first", selector.selected?.entryId)
        assertFalse(selector.update(left = false, right = true, toggleAlliance = false))
        assertEquals("first", selector.selected?.entryId)
        selector.update(left = false, right = false, toggleAlliance = false)
        assertTrue(selector.update(left = true, right = false, toggleAlliance = false))
        assertEquals("second", selector.selected?.entryId)
    }

    @Test
    fun `disabled entries disappear and an empty catalog safely selects nothing`() {
        val onlyDisabled = FtcAutonomousSelector(
            entries = listOf(entry("disabled", 0, enabled = false)),
            defaultEntryId = "disabled",
            initialAlliance = Alliance.RED,
        )

        assertNull(onlyDisabled.selected)
        assertFalse(onlyDisabled.update(left = true, right = false, toggleAlliance = false))
    }

    @Test
    fun `dashboard selection accepts stable generated IDs and rejects unknown IDs`() {
        val selector = FtcAutonomousSelector(
            entries = listOf(entry("first", 0), entry("second", 1)),
            defaultEntryId = "first",
            initialAlliance = Alliance.RED,
        )

        assertTrue(selector.selectEntry("second"))
        assertEquals("second", selector.selected?.entryId)
        assertFalse(selector.selectEntry("missing"))
        assertEquals("second", selector.selected?.entryId)
        assertFalse(selector.selectEntry("second"))
    }

    @Test
    fun `locked validation mode ignores routine and alliance inputs`() {
        val selector = FtcAutonomousSelector(
            entries = listOf(entry("first", 0), entry("second", 1)),
            defaultEntryId = "first",
            initialAlliance = Alliance.RED,
            lockedEntryId = "second",
            lockedAlliance = Alliance.BLUE,
        )

        assertFalse(selector.update(left = true, right = false, toggleAlliance = true))
        assertFalse(selector.selectAlliance(Alliance.RED))
        assertEquals("second", selector.selected?.entryId)
        assertEquals(Alliance.BLUE, selector.alliance)
    }

    @Test
    fun `unlocked selector accepts dashboard alliance and resolves matching mirrored start pose`() {
        val catalogEntry = entry("dashboard-auto", 0).copy(
            authoredAlliance = RoutineAlliance.RED,
            startingPose = RoutinePose(0.4, 0.7, 0.3),
        )
        val selector = FtcAutonomousSelector(
            entries = listOf(catalogEntry),
            defaultEntryId = catalogEntry.entryId,
            initialAlliance = Alliance.RED,
        )

        assertTrue(selector.selectAlliance(Alliance.BLUE))
        assertEquals(Alliance.BLUE, selector.alliance)
        val bluePose = resolveFtcAutonomousPose(
            requireNotNull(selector.selected),
            selector.alliance,
            symmetry = FieldSymmetry.MIRRORED,
        )
        assertEquals(0.4, bluePose.x, 1e-9)
        assertEquals(-0.7, bluePose.y, 1e-9)
        assertEquals(-0.3, bluePose.heading.radians, 1e-9)
        val blueRuntimeTarget = resolveFtcAutonomousPose(
            requireNotNull(selector.selected),
            selector.alliance,
            pose = RoutinePose(0.9, 0.5, 0.2),
            symmetry = FieldSymmetry.MIRRORED,
        )
        assertEquals(0.9, blueRuntimeTarget.x, 1e-9)
        assertEquals(-0.5, blueRuntimeTarget.y, 1e-9)
        assertEquals(-0.2, blueRuntimeTarget.heading.radians, 1e-9)
        assertFalse(selector.selectAlliance(Alliance.BLUE))
    }

    @Test
    fun `missing or disabled locked entry fails closed instead of selecting first auto`() {
        val missing = FtcAutonomousSelector(
            entries = listOf(entry("first", 0), entry("second", 1)),
            defaultEntryId = "first",
            initialAlliance = Alliance.RED,
            lockedEntryId = "renamed-entry",
        )
        val disabled = FtcAutonomousSelector(
            entries = listOf(entry("first", 0), entry("locked", 1, enabled = false)),
            defaultEntryId = "first",
            initialAlliance = Alliance.RED,
            lockedEntryId = "locked",
        )

        assertNull(missing.selected)
        assertNull(disabled.selected)
        assertFalse(missing.update(left = true, right = true, toggleAlliance = false))
    }

    @Test
    fun `FTC pose mirroring is relative to authored alliance and uses season symmetry`() {
        val entry = entry("score", 0).copy(
            authoredAlliance = RoutineAlliance.RED,
            startingPose = RoutinePose(1.0, 2.0, 0.25),
        )

        val red = resolveFtcAutonomousPose(entry, Alliance.RED, symmetry = FieldSymmetry.MIRRORED)
        assertEquals(1.0, red.x, 1e-9)
        assertEquals(2.0, red.y, 1e-9)
        assertEquals(0.25, red.heading.radians, 1e-9)

        val blue = resolveFtcAutonomousPose(entry, Alliance.BLUE, symmetry = FieldSymmetry.MIRRORED)
        assertEquals(1.0, blue.x, 1e-9)
        assertEquals(-2.0, blue.y, 1e-9)
        assertEquals(wrapAngle(-0.25), blue.heading.radians, 1e-9)
    }

    @Test
    fun `entry can explicitly disable alliance mirroring`() {
        val entry = entry("skills", 0).copy(
            startingPose = RoutinePose(0.4, -0.6, -0.2),
            mirrorForOppositeAlliance = false,
        )

        val pose = resolveFtcAutonomousPose(entry, Alliance.BLUE)
        assertEquals(0.4, pose.x, 1e-9)
        assertEquals(-0.6, pose.y, 1e-9)
        assertEquals(-0.2, pose.heading.radians, 1e-9)
    }

    private fun entry(id: String, order: Int, enabled: Boolean = true) = AutonomousCatalogEntry(
        entryId = id,
        displayName = id,
        routineId = id,
        startingPose = RoutinePose(0.0, 0.0, 0.0),
        sortOrder = order,
        enabled = enabled,
    )
}
