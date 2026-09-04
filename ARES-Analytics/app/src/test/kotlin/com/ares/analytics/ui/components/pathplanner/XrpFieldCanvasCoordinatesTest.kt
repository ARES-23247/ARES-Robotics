package com.ares.analytics.ui.components.pathplanner

import androidx.compose.ui.geometry.Offset
import com.ares.analytics.shared.models.League
import kotlin.test.Test
import kotlin.test.assertEquals

class XrpFieldCanvasCoordinatesTest {
    @Test
    fun `xrp center origin maps to canvas center and round trips`() {
        val canvas = getCanvasOffsetBase(
            wp = Waypoint(-0.9, -0.4),
            canvasW = 1000f,
            canvasH = 560f,
            fieldW = 2.54,
            fieldH = 1.4224,
            league = League.XRP,
        )
        val restored = getRobotCoordBase(
            offset = canvas,
            canvasW = 1000f,
            canvasH = 560f,
            fieldW = 2.54,
            fieldH = 1.4224,
            league = League.XRP,
        )

        assertEquals(145.6693f, canvas.x, 0.001f)
        assertEquals(437.4803f, canvas.y, 0.001f)
        assertEquals(-0.9, restored.x, 1e-6)
        assertEquals(-0.4, restored.y, 1e-6)
        assertEquals(Offset(500f, 280f), getCanvasOffsetBase(Waypoint(0.0, 0.0), 1000f, 560f, 2.54, 1.4224, League.XRP))
    }

    @Test
    fun `xrp drag delta preserves center origin axes`() {
        val delta = getDragDeltaInFieldCoords(
            dragAmount = Offset(100f, -56f),
            canvasW = 1000f,
            canvasH = 560f,
            fieldW = 2.54,
            fieldH = 1.4224,
            league = League.XRP,
            zoomScale = 1f,
        )

        assertEquals(0.254, delta.x, 1e-6)
        assertEquals(0.14224, delta.y, 1e-6)
    }
}
