package com.ares.analytics.ui.components.pathplanner

import com.ares.analytics.shared.models.League
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathPlannerHudAndHistoryTest {

    @Test
    fun `waypoint history stack supports undo redo and max capacity`() {
        val stack = WaypointHistoryStack(maxEntries = 3)
        val w0 = listOf(Waypoint(0.0, 0.0))
        val w1 = listOf(Waypoint(1.0, 0.0))
        val w2 = listOf(Waypoint(2.0, 0.0))
        val w3 = listOf(Waypoint(3.0, 0.0))
        val w4 = listOf(Waypoint(4.0, 0.0))

        assertFalse(stack.canUndo)
        assertFalse(stack.canRedo)
        assertNull(stack.undo(w0))

        stack.record(w0, w1)
        stack.record(w1, w2)
        stack.record(w2, w3)
        stack.record(w3, w4)

        assertEquals(3, stack.undoDepth)
        assertTrue(stack.canUndo)

        val u1 = stack.undo(w4)!!
        assertEquals(3.0, u1.single().x, 1e-6)
        assertTrue(stack.canRedo)

        val u2 = stack.undo(u1)!!
        assertEquals(2.0, u2.single().x, 1e-6)

        val r1 = stack.redo(u2)!!
        assertEquals(3.0, r1.single().x, 1e-6)
    }

    @Test
    fun `waypoint history stack coalesces rapid drag frames into single undo step`() {
        val stack = WaypointHistoryStack(maxEntries = 10)
        val start = listOf(Waypoint(0.0, 0.0))
        val mid1 = listOf(Waypoint(0.2, 0.1))
        val mid2 = listOf(Waypoint(0.5, 0.2))
        val end = listOf(Waypoint(1.0, 0.4))

        stack.record(start, mid1, timestampMs = 1_000L, coalesceWindowMs = 280L)
        stack.record(mid1, mid2, timestampMs = 1_016L, coalesceWindowMs = 280L)
        stack.record(mid2, end, timestampMs = 1_032L, coalesceWindowMs = 280L)

        assertEquals(1, stack.undoDepth)
        val undone = stack.undo(end)!!
        assertEquals(0.0, undone.single().x, 1e-6)
        assertEquals(0.0, undone.single().y, 1e-6)

        val redone = stack.redo(undone)!!
        assertEquals(1.0, redone.single().x, 1e-6)
        assertEquals(0.4, redone.single().y, 1e-6)
    }

    @Test
    fun `snapCoordinateMeters and snapHeadingRadians quantize to 5cm and 15 degrees`() {
        assertEquals(0.15, snapCoordinateMeters(0.141, 0.05), 1e-9)
        assertEquals(-0.20, snapCoordinateMeters(-0.182, 0.05), 1e-9)
        assertEquals(0.0, snapCoordinateMeters(-0.004, 0.05), 1e-9)

        val snapped45 = snapHeadingRadians(Math.toRadians(43.2), 15.0)
        assertEquals(45.0, Math.toDegrees(snapped45), 1e-6)

        val snappedNeg90 = snapHeadingRadians(Math.toRadians(-86.0), 15.0)
        assertEquals(-90.0, Math.toDegrees(snappedNeg90), 1e-6)
    }

    @Test
    fun `computePolylineLengthMeters sums segment lengths accurately`() {
        val pts = listOf(
            Waypoint(0.0, 0.0),
            Waypoint(3.0, 4.0),
            Waypoint(3.0, 6.5),
        )
        assertEquals(7.5, computePolylineLengthMeters(pts), 1e-6)
        assertEquals(0.0, computePolylineLengthMeters(listOf(Waypoint(1.0, 2.0))), 1e-6)
    }

    @Test
    fun `mirrorWaypointsAcrossAlliance preserves CCW radian conventions for FTC and FRC`() {
        val ftcSource = listOf(
            Waypoint(x = 0.6, y = 1.2, headingRad = PI / 4.0, rotationDeg = 45.0),
        )
        val ftcMirrored = mirrorWaypointsAcrossAlliance(ftcSource, League.FTC, 3.6576, 3.6576)
        assertEquals(0.6, ftcMirrored[0].x, 1e-6)
        assertEquals(-1.2, ftcMirrored[0].y, 1e-6)
        assertEquals(-PI / 4.0, ftcMirrored[0].headingRad!!, 1e-6)
        assertEquals(-45.0, ftcMirrored[0].rotationDeg!!, 1e-6)

        val frcSource = listOf(
            Waypoint(x = 2.0, y = 5.5, headingRad = PI / 6.0, rotationDeg = 30.0),
        )
        val frcMirrored = mirrorWaypointsAcrossAlliance(frcSource, League.FRC, 16.54, 8.21)
        assertEquals(14.54, frcMirrored[0].x, 1e-6)
        assertEquals(5.5, frcMirrored[0].y, 1e-6)
        assertEquals(5.0 * PI / 6.0, frcMirrored[0].headingRad!!, 1e-6)
        assertEquals(150.0, frcMirrored[0].rotationDeg!!, 1e-6)
    }
}
