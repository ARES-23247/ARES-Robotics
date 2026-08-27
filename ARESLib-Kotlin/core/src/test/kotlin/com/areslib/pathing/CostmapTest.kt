package com.areslib.pathing

import com.areslib.math.geometry.Translation2d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CostmapTest {

    @Test
    fun testCellQueryAtBoundaries() {
        val costmap = Costmap(10.0, 10.0, 0.1, Translation2d(0.0, 0.0))
        
        // Inside boundaries
        assertTrue(costmap.isCellTraversable(0, 0))
        assertTrue(costmap.isCellTraversable(99, 99))
        
        // Outside boundaries
        assertFalse(costmap.isCellTraversable(-1, 0))
        assertFalse(costmap.isCellTraversable(0, -1))
        assertFalse(costmap.isCellTraversable(100, 0))
        assertFalse(costmap.isCellTraversable(0, 100))
    }

    @Test
    fun testInflationRadiusExpansion() {
        val costmap = Costmap(10.0, 10.0, 0.1, Translation2d(0.0, 0.0))
        costmap.setObstacle(5.0, 5.0, true)
        
        // Before inflation, adjacent cell should be traversable
        assertTrue(costmap.isCellTraversable(51, 50))
        
        // Inflate by 0.2 meters (2 cells)
        costmap.inflate(0.2)
        
        // After inflation, adjacent cells within radius should be blocked
        assertFalse(costmap.isCellTraversable(51, 50), "Cell within inflation radius should be blocked")
        assertFalse(costmap.isCellTraversable(50, 51), "Cell within inflation radius should be blocked")
        
        // Cell outside inflation radius should be traversable
        assertTrue(costmap.isCellTraversable(53, 50), "Cell outside inflation radius should be traversable")
    }

    @Test
    fun testEmptyGrid() {
        val costmap = Costmap(10.0, 10.0, 0.1, Translation2d(0.0, 0.0))
        costmap.clear()
        
        for (x in 0 until 100 step 10) {
            for (y in 0 until 100 step 10) {
                assertTrue(costmap.isCellTraversable(x, y))
                assertFalse(costmap.isCellOccupied(x, y))
            }
        }
    }

    @Test
    fun testFullGrid() {
        val costmap = Costmap(10.0, 10.0, 0.1, Translation2d(0.0, 0.0))
        for (x in 0 until 100) {
            for (y in 0 until 100) {
                costmap.setObstacle(x, y, true)
            }
        }
        
        for (x in 0 until 100 step 10) {
            for (y in 0 until 100 step 10) {
                assertTrue(costmap.isCellOccupied(x, y))
            }
        }
    }

    @Test
    fun testOutOfBoundsQueriesReturnSafeDefaults() {
        val costmap = Costmap(10.0, 10.0, 0.1, Translation2d(0.0, 0.0))
        
        // Out of bounds is considered occupied and not traversable
        assertTrue(costmap.isCellOccupied(-5, -5))
        assertFalse(costmap.isCellTraversable(-5, -5))
        
        assertTrue(costmap.isOccupied(-100.0, -100.0))
        assertFalse(costmap.isTraversable(-100.0, -100.0))
    }
}
