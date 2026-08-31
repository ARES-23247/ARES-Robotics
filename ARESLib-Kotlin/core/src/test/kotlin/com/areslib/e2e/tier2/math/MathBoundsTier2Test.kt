package com.areslib.e2e.tier2.math

import com.areslib.math.estimation.PoseEstimator
import com.areslib.math.estimation.PoseEstimatorState
import com.areslib.math.geometry.Translation2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.pathing.Costmap
import com.areslib.pathing.ThetaStarPlanner
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MathBoundsTier2Test {

    @Test
    fun testThetaStarZeroDistanceMoveBoundary() {
        val costmap = Costmap(widthMeters = 2.0, heightMeters = 2.0, resolutionMeters = 0.1)
        val start = Translation2d(0.0, 0.0)
        val end = Translation2d(0.0, 0.0) // Start == End

        val path = ThetaStarPlanner.plan(costmap, start, end)
        
        // Should return a list with exactly start and end points
        assertEquals(2, path.size)
        assertEquals(0.0, path[0].x, 1e-6)
        assertEquals(0.0, path[1].x, 1e-6)
    }

    @Test
    fun testThetaStarOutOfBoundsBoundary() {
        val costmap = Costmap(widthMeters = 2.0, heightMeters = 2.0, resolutionMeters = 0.1)
        
        // Start is way outside width/height limit
        val start = Translation2d(100.0, 100.0)
        val end = Translation2d(0.0, 0.0)

        val path = ThetaStarPlanner.plan(costmap, start, end)
        
        // Should return empty path gracefully
        assertTrue(path.isEmpty())
    }

    @Test
    fun testEkfPitchHysteresisBoundaries() {
        val initialState = PoseEstimatorState()
        
        // Hysteresis boundary verification:
        // Beaching triggers at pitch > 15.0. 
        // 1. Check exact 15.0 pitch -> should not be beached
        val stateAt15 = PoseEstimator.addOdometryObservation(
            initialState, 100L, Translation2d(1.0, 0.0), Rotation2d(0.0),
            pitchDegrees = 15.0
        )
        assertFalse(stateAt15.isBeached)

        // 2. Check 15.01 pitch -> should trip beaching
        val stateAt15Point01 = PoseEstimator.addOdometryObservation(
            initialState, 100L, Translation2d(1.0, 0.0), Rotation2d(0.0),
            pitchDegrees = 15.01
        )
        assertTrue(stateAt15Point01.isBeached)

        // 3. Beached state recovery triggers at pitch < 12.0.
        // Pitch at exactly 12.0 -> should still be beached
        val stateAt12 = PoseEstimator.addOdometryObservation(
            stateAt15Point01, 200L, Translation2d(1.0, 0.0), Rotation2d(0.0),
            pitchDegrees = 12.0
        )
        assertTrue(stateAt12.isBeached)

        // 4. Pitch at 11.99 -> should recover from beaching
        val stateAt11Point99 = PoseEstimator.addOdometryObservation(
            stateAt15Point01, 200L, Translation2d(1.0, 0.0), Rotation2d(0.0),
            pitchDegrees = 11.99
        )
        assertFalse(stateAt11Point99.isBeached)
    }

}
