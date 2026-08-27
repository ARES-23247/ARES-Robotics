package com.areslib.pathing

import com.areslib.math.geometry.Translation2d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PathOptimizationServiceTest {

    @Test
    fun `test path optimization rounds sharp corners and reduces trajectory time`() {
        val rawWaypoints = listOf(
            Translation2d(0.0, 0.0),
            Translation2d(1.0, 0.0),
            Translation2d(1.0, 1.0),
            Translation2d(2.0, 1.0)
        )

        val result = PathOptimizationService.optimizePath(rawWaypoints, maxVelMps = 3.0)

        assertNotNull(result)
        assertTrue(result.optimizedWaypoints.size > rawWaypoints.size)
        assertTrue(result.optimizedTimeSec <= result.originalTimeSec)
        assertEquals(0.0, result.originalWaypoints.first().x, 1e-4)
        assertEquals(2.0, result.originalWaypoints.last().x, 1e-4)
    }
}
