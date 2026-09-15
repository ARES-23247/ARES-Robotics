// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ares.analytics.viewmodel.pathing

import com.ares.analytics.shared.models.League
import com.areslib.math.coordinate.CoordinateTransformers
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoFieldBoundsTest {
    @Test
    fun `bounds agree with independently rotated footprint corners in every league`() {
        val robot = RobotDimensions(0.6, 0.3)
        for (league in League.entries) for (angle in listOf(0.0, Math.PI / 2, -0.73, 2.31)) {
            val field = when (league) {
                League.FTC -> listOf(-CoordinateTransformers.FTC_FIELD_SIZE / 2, CoordinateTransformers.FTC_FIELD_SIZE / 2,
                    -CoordinateTransformers.FTC_FIELD_SIZE / 2, CoordinateTransformers.FTC_FIELD_SIZE / 2)
                League.FRC -> listOf(0.0, CoordinateTransformers.FRC_FIELD_LENGTH, 0.0, CoordinateTransformers.FRC_FIELD_WIDTH)
                League.XRP -> listOf(-1.27, 1.27, -0.7112, 0.7112)
            }
            val corners = listOf(-0.3, 0.3).flatMap { x -> listOf(-0.15, 0.15).map { y ->
                (x * cos(angle) - y * sin(angle)) to (x * sin(angle) + y * cos(angle))
            } }
            val bounds = legalCenterBounds(league, robot, angle)
            assertTrue(bounds.canFit)
            assertEquals(field[0] - corners.minOf { it.first }, bounds.minX, 1e-12)
            assertEquals(field[1] - corners.maxOf { it.first }, bounds.maxX, 1e-12)
            assertEquals(field[2] - corners.minOf { it.second }, bounds.minY, 1e-12)
            assertEquals(field[3] - corners.maxOf { it.second }, bounds.maxY, 1e-12)
        }
    }

    @Test
    fun `impossible axes retain finite editor fallback without claiming a fit`() {
        val dimensions = RobotDimensions(0.2, 2.0)
        val bounds = legalCenterBounds(League.XRP, dimensions, 0.0)
        assertFalse(bounds.canFit)
        assertEquals(0.0, bounds.minY)
        assertEquals(0.0, bounds.maxY)
        assertEquals(-1.17, bounds.minX, 1e-12)
        assertTrue(legalCenterBounds(League.XRP, dimensions, Math.PI / 2).canFit)
        assertTrue(legalCenterBounds(League.XRP, RobotDimensions(0.2, 1.4224), 0.0).canFit)
    }

    @Test
    fun `dimension normalization and league defaults preserve documented editor limits`() {
        assertEquals(RobotDimensions(0.1, 2.0), RobotDimensions(-1.0, 10.0).normalized())
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(RobotDimensions(0.4572, 0.4572), RobotDimensions(invalid, invalid).normalized())
            assertEquals(legalCenterBounds(League.XRP, RobotDimensions(0.2, 0.3), 0.0),
                legalCenterBounds(League.XRP, RobotDimensions(0.2, 0.3), invalid))
        }
        for ((league, size) in mapOf(League.FTC to 0.4572, League.FRC to 0.8, League.XRP to 0.16))
            assertEquals(RobotDimensions(size, size), RobotDimensions.defaultFor(league))
    }
}
