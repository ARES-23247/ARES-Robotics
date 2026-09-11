// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ares.analytics.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertTrue

class VectorDrawingUtilsTest {
    private fun render(start: Offset, end: Offset, filled: Boolean = false,
        width: Float = 2f, head: Float = 10f, angle: Double = Math.PI / 6) = ImageBitmap(64, 64).also { bitmap ->
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(64f, 64f)) {
            drawVectorArrow(start, end, Color.Red, width, head, angle, filled)
        }
    }.toPixelMap()

    @Test
    fun `horizontal and vertical arrows put wings behind the endpoint`() {
        val horizontal = render(Offset(10f, 32f), Offset(50f, 32f))
        assertTrue(horizontal[20, 32].alpha > 0.5f)
        assertTrue((40..43).any { x -> (26..29).any { y -> horizontal[x, y].alpha > 0.5f } })
        assertTrue((40..43).any { x -> (35..38).any { y -> horizontal[x, y].alpha > 0.5f } })
        assertTrue(horizontal[55, 32].alpha == 0f)
        val vertical = render(Offset(32f, 10f), Offset(32f, 50f))
        assertTrue(vertical[32, 20].alpha > 0.5f)
        assertTrue((26..29).any { x -> (40..43).any { y -> vertical[x, y].alpha > 0.5f } })
        assertTrue((35..38).any { x -> (40..43).any { y -> vertical[x, y].alpha > 0.5f } })
    }

    @Test
    fun `filled arrowhead paints its interior while open head leaves the gap`() {
        val open = render(Offset(10f, 32f), Offset(50f, 32f))
        val filled = render(Offset(10f, 32f), Offset(50f, 32f), filled = true)
        assertTrue(open[42, 30].alpha < 0.2f)
        assertTrue(filled[42, 30].alpha > 0.8f)
    }

    @Test
    fun `zero and invalid vectors do not paint`() {
        val start = Offset(10f, 32f)
        val end = Offset(50f, 32f)
        val images = listOf(render(start, start), render(Offset(Float.NaN, 1f), end),
            render(start, Offset(Float.POSITIVE_INFINITY, 1f)), render(start, end, width = -1f),
            render(start, end, head = Float.NaN), render(start, end, angle = Double.NaN))
        for (pixels in images) assertTrue((0..63).all { x -> (0..63).all { y -> pixels[x, y].alpha == 0f } })
    }
}
