package com.ares.analytics.ui.components.pathplanner

import com.ares.analytics.service.AlignedDataRow
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.SysIdService
import com.ares.analytics.shared.Obstacle
import com.ares.analytics.shared.models.League
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.ares.analytics.viewmodel.routine.RoutineTrajectoryPreviewCompiler
import com.ares.analytics.viewmodel.routine.routineEditorValidation
import com.ares.analytics.viewmodel.routine.withRoutineRouteWaypointsAndAppendRemaining
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineAlliance
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineDriveStep
import com.areslib.routine.RoutineHeadingMode
import com.areslib.routine.RoutinePose
import com.areslib.routine.RoutineStep
import com.areslib.routine.RoutineValidationSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PedroV3AnalyticsImprovementsTest {

    @Test
    fun `Ramer-Douglas-Peucker simplifies colinear points and preserves corners`() {
        val rawStroke = listOf(
            Waypoint(0.0, 0.0),
            Waypoint(0.25, 0.01),
            Waypoint(0.50, -0.01),
            Waypoint(0.75, 0.005),
            Waypoint(1.00, 0.0),
            Waypoint(1.01, 0.40),
            Waypoint(0.99, 0.80),
            Waypoint(1.00, 1.20),
        )
        val simplified = FreehandStrokeSplineFitter.simplifyRamerDouglasPeucker(rawStroke, epsilonMeters = 0.05)
        assertEquals(3, simplified.size)
        assertEquals(0.0, simplified.first().x, 1e-6)
        assertEquals(1.0, simplified[1].x, 1e-6)
        assertEquals(0.0, simplified[1].y, 1e-6)
        assertEquals(1.0, simplified.last().x, 1e-6)
        assertEquals(1.2, simplified.last().y, 1e-6)
    }

    @Test
    fun `strokeToRoutineDriveSteps sets blendHandoff true on interior anchors and false on final anchor`() {
        val rawStroke = listOf(
            Waypoint(0.0, 0.0),
            Waypoint(0.8, 0.0),
            Waypoint(0.8, 0.8),
            Waypoint(1.5, 0.8),
        )
        val steps = FreehandStrokeSplineFitter.strokeToRoutineDriveSteps(
            stroke = rawStroke,
            epsilonMeters = 0.05,
        )
        assertEquals(3, steps.size)
        assertTrue(steps[0].blendHandoff)
        assertEquals(RoutineHeadingMode.TANGENTIAL, steps[0].headingMode)
        assertTrue(steps[1].blendHandoff)
        assertEquals(RoutineHeadingMode.TANGENTIAL, steps[1].headingMode)
        assertFalse(steps[2].blendHandoff)
    }

    @Test
    fun `withRoutineRouteWaypointsAndAppendRemaining appends freehand pen waypoints as blended drive steps`() {
        val initialSteps = listOf(
            RoutineStep.driveTo(RoutineDriveStep(target = RoutinePose(0.2, 0.2, 0.0))),
        )
        val newWaypoints = listOf(
            Waypoint(0.2, 0.2),
            Waypoint(0.6, 0.4),
            Waypoint(1.0, 0.8),
        )
        val updated = initialSteps.withRoutineRouteWaypointsAndAppendRemaining(
            driveWaypoints = newWaypoints,
            league = League.FTC,
            dimensions = RobotDimensions(0.45, 0.45),
        )
        assertEquals(3, updated.size)
        assertTrue(updated[1].drive!!.blendHandoff)
        assertEquals(RoutineHeadingMode.TANGENTIAL, updated[1].drive!!.headingMode)
        assertFalse(updated[2].drive!!.blendHandoff)
    }

    @Test
    fun `findCollidingTrajectoryIndices detects swept footprint obstacle and boundary collisions`() {
        val trajectory = listOf(
            Waypoint(0.0, 0.0, headingRad = 0.0),
            Waypoint(0.5, 0.0, headingRad = 0.0),
            Waypoint(1.0, 0.0, headingRad = 0.0),
        )
        val obstacles = listOf(
            Obstacle.Circle(id = "obs1", name = "Post", centerX = 0.5, centerY = 0.1, radius = 0.15),
        )
        val colliding = FreehandStrokeSplineFitter.findCollidingTrajectoryIndices(
            trajectoryPoints = trajectory,
            obstacles = obstacles,
            robotDimensions = RobotDimensions(0.45, 0.45),
            fieldWidthM = 3.6576,
            fieldHeightM = 3.6576,
            league = League.FTC,
        )
        assertTrue(1 in colliding, "Middle pose should collide with obstacle at (0.5, 0.1)")
        assertFalse(0 in colliding)
        assertFalse(2 in colliding)
    }

    @Test
    fun `routineEditorValidation emits non-blocking WARNING when swept footprint rotates across boundary`() {
        // Rectangular robot (0.80 m x 0.30 m) near top wall:
        // At heading 0 rad, half-width along Y is 0.15 m, so y = 1.62 m fits inside halfField = 1.8288 m (1.62 + 0.15 = 1.77 <= 1.8288).
        // At heading 180 deg (PI rad), half-width along Y is also 0.15 m, so both endpoints fit!
        // But at mid-segment (heading = 90 deg = PI/2), half-extent along Y becomes 0.40 m (1.62 + 0.40 = 2.02 > 1.8288)!
        val dimensions = RobotDimensions(lengthMeters = 0.80, widthMeters = 0.30)
        val startPose = RoutinePose(xMeters = 0.0, yMeters = 1.62, headingRadians = 0.0)
        val endPose = RoutinePose(xMeters = 0.5, yMeters = 1.62, headingRadians = Math.PI)
        val routine = RoutineDocument(
            documentId = "swept-check",
            name = "Swept Check",
            steps = listOf(
                RoutineStep.driveTo(
                    RoutineDriveStep(
                        target = endPose,
                        headingMode = RoutineHeadingMode.LINEAR,
                    ),
                ),
            ),
        )
        val entry = AutonomousCatalogEntry(
            entryId = "swept-check",
            displayName = "Swept Check",
            routineId = "swept-check",
            startingPose = startPose,
            authoredAlliance = RoutineAlliance.RED,
        )
        val issues = routineEditorValidation(
            routine = routine,
            catalog = null,
            projectRoutines = listOf(routine),
            league = League.FTC,
            dimensions = dimensions,
            autonomousEntry = entry,
        )
        assertTrue(issues.none { it.severity == RoutineValidationSeverity.ERROR }, "Endpoints are legal so no ERROR should be emitted")
        assertTrue(
            issues.any { it.severity == RoutineValidationSeverity.WARNING && it.code == "swept_footprint_collision_warning" },
            "Expected non-blocking swept_footprint_collision_warning when rotating near wall",
        )
    }

    @Test
    fun `RoutineTrajectoryPreviewCompiler supports POINT_TOWARDS and blendHandoff`() {
        val compiler = RoutineTrajectoryPreviewCompiler()
        val start = RoutinePose(0.0, 0.0, 0.0)
        val stepsStopped = listOf(
            RoutineStep.driveTo(
                RoutineDriveStep(
                    target = RoutinePose(0.8, 0.0, 0.0),
                    headingMode = RoutineHeadingMode.POINT_TOWARDS,
                    lookAtPoint = RoutinePose(0.8, 1.0, 0.0),
                    blendHandoff = false,
                ),
            ),
            RoutineStep.driveTo(
                RoutineDriveStep(
                    target = RoutinePose(1.6, 0.0, 0.0),
                    blendHandoff = false,
                ),
            ),
        )
        val stepsBlended = listOf(
            RoutineStep.driveTo(
                RoutineDriveStep(
                    target = RoutinePose(0.8, 0.0, 0.0),
                    headingMode = RoutineHeadingMode.POINT_TOWARDS,
                    lookAtPoint = RoutinePose(0.8, 1.0, 0.0),
                    blendHandoff = true,
                ),
            ),
            RoutineStep.driveTo(
                RoutineDriveStep(
                    target = RoutinePose(1.6, 0.0, 0.0),
                    blendHandoff = false,
                ),
            ),
        )
        val previewStopped = compiler.compile(stepsStopped, start, hasAutonomousStart = true, league = League.FTC)
        val previewBlended = compiler.compile(stepsBlended, start, hasAutonomousStart = true, league = League.FTC)
        assertNotNull(previewStopped.trajectory)
        assertNotNull(previewBlended.trajectory)
        assertTrue(
            previewBlended.estimatedDurationSeconds < previewStopped.estimatedDurationSeconds,
            "Blended handoff preview (${previewBlended.estimatedDurationSeconds}s) should be faster than full-stop preview (${previewStopped.estimatedDurationSeconds}s)",
        )
    }

    @Test
    fun `SysIdService estimateBrakingDeceleration extracts braking and coasting deceleration`() {
        val sysId = SysIdService(DatabaseService())
        val rows = listOf(
            AlignedDataRow(timestampMs = 0L, voltage = 0.2, velocity = 1.5, accel = -2.4),
            AlignedDataRow(timestampMs = 10L, voltage = 0.1, velocity = 1.2, accel = -2.2),
            AlignedDataRow(timestampMs = 20L, voltage = -6.0, velocity = 0.9, accel = -3.6),
            AlignedDataRow(timestampMs = 30L, voltage = -6.0, velocity = 0.5, accel = -3.4),
        )
        val summary = sysId.estimateBrakingDeceleration(rows)
        assertEquals(4, summary.sampleCount)
        assertEquals(2.9, summary.brakingDecelerationMps2, 1e-6)
        assertEquals(2.3, summary.zeroPowerCoastingDecelerationMps2, 1e-6)
        assertTrue(summary.recommendedMaxOpposingBrakingMps > 0.35)
        assertTrue(summary.quadraticStoppingCoeff > 0.0)
        assertTrue(summary.smallErrorKp > summary.largeErrorKp)
    }
}
