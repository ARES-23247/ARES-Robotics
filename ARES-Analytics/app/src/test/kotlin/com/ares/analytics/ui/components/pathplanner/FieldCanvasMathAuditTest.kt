// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ares.analytics.ui.components.pathplanner

import androidx.compose.ui.geometry.Offset
import com.ares.analytics.shared.models.League
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldCanvasMathAuditTest {
    @Test
    fun `base origins and axis scaling match each league on a rectangular canvas`() {
        val point = Waypoint(0.5, 0.25)
        val expected = mapOf(League.FTC to Offset(350f, 75f), League.FRC to Offset(100f, 262.5f),
            League.XRP to Offset(500f, 112.5f))
        expected.forEach { (league, screen) ->
            assertEquals(screen, getCanvasOffsetBase(point, 800f, 300f, 4.0, 2.0, league))
            val restored = getRobotCoordBase(screen, 800f, 300f, 4.0, 2.0, league)
            assertEquals(point.x, restored.x, 1e-6)
            assertEquals(point.y, restored.y, 1e-6)
        }
    }

    @Test
    fun `rotated pan zoom and drag inversion agree in all leagues`() {
        for (league in League.entries) {
            val point = Waypoint(0.5, 0.25)
            val base = getCanvasOffsetBase(point, 800f, 300f, 4.0, 2.0, league)
            val pan = Offset(13f, -7f)
            val quarterTurn = getTransformedCanvasOffset(point, 800f, 300f, 4.0, 2.0, league, 2f, pan, 90f)
            assertEquals(400f - (base.y * 2f - 7f - 150f), quarterTurn.x, 1e-4f)
            assertEquals(150f + (base.x * 2f + 13f - 400f), quarterTurn.y, 1e-4f)
            for (angle in listOf(0f, 37f, 90f, -120f)) {
                val screen = getTransformedCanvasOffset(point, 800f, 300f, 4.0, 2.0, league, 2f, pan, angle)
                val restored = getRobotCoordFromScreen(screen, 800f, 300f, 4.0, 2.0, league, 2f, pan, angle)
                assertEquals(point.x, restored.x, 1e-6)
                assertEquals(point.y, restored.y, 1e-6)
                val restoredBase = getBaseCanvasFromScreen(screen, 800f, 300f, 2f, pan, angle)
                assertEquals(base.x, restoredBase.x, 1e-4f)
                assertEquals(base.y, restoredBase.y, 1e-4f)
                val drag = Offset(17f, -23f)
                val moved = getRobotCoordFromScreen(screen + drag, 800f, 300f, 4.0, 2.0, league, 2f, pan, angle)
                val delta = getDragDeltaInFieldCoords(drag, 800f, 300f, 4.0, 2.0, league, 2f, angle)
                assertEquals(moved.x - restored.x, delta.x, 1e-6)
                assertEquals(moved.y - restored.y, delta.y, 1e-6)
            }
        }
    }

    @Test
    fun `Hermite interpolation preserves endpoints linear motion and opposing tangents`() {
        assertEquals(2.0, cubicHermite(2.0, 3.0, 7.0, -4.0, 0.0))
        assertEquals(7.0, cubicHermite(2.0, 3.0, 7.0, -4.0, 1.0))
        for (t in listOf(0.0, 0.1, 0.5, 0.9, 1.0)) {
            assertEquals(1.0 + 2.0 * t, cubicHermite(1.0, 2.0, 3.0, 2.0, t), 1e-12)
            assertEquals(t * (1.0 - t), cubicHermite(0.0, 1.0, 0.0, -1.0, t), 1e-12)
        }
    }
}
