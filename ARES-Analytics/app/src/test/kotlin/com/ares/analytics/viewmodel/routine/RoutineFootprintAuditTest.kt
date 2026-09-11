// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ares.analytics.viewmodel.routine

import com.ares.analytics.shared.models.League
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.areslib.routine.RoutinePose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutineFootprintAuditTest {
    @Test
    fun `guided plan rejects a centered robot wider than the field`() {
        val dimensions = RobotDimensions(0.2, 2.0)
        val plan = defaultGuidedFirstRoutinePlan(League.XRP, dimensions)
        val issues = validateGuidedFirstRoutinePlan(plan, League.XRP, dimensions)
        assertTrue(issues.any { it.startsWith("Starting pose") && "outside" in it })
        assertTrue(issues.any { it.startsWith("Drive goal") && "outside" in it })
    }

    @Test
    fun `routine validation rejects oversized start and drive footprints`() {
        val dimensions = RobotDimensions(0.2, 2.0)
        val plan = defaultGuidedFirstRoutinePlan(League.XRP, dimensions)
        val routine = guidedFirstRoutineDocument("test", plan)
        val entry = guidedFirstRoutineEntry("test", plan)
        val issues = routineEditorValidation(routine, null, emptyList(), League.XRP, dimensions, entry)
            .filter { it.code == "robot_outside_field" }
        assertEquals(2, issues.size)
    }

    @Test
    fun `nonfinite autonomous starting poses are rejected independently of clamping`() {
        val dimensions = RobotDimensions.defaultFor(League.XRP)
        val plan = defaultGuidedFirstRoutinePlan(League.XRP, dimensions)
        val routine = guidedFirstRoutineDocument("test", plan)
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (pose in listOf(RoutinePose(invalid, 0.0, 0.0), RoutinePose(0.0, invalid, 0.0),
                RoutinePose(0.0, 0.0, invalid))) {
                val entry = guidedFirstRoutineEntry("test", plan).copy(startingPose = pose)
                assertTrue(routineEditorValidation(routine, null, emptyList(), League.XRP, dimensions, entry)
                    .any { it.path == "autonomousEntry.startingPose" })
            }
        }
    }
}
