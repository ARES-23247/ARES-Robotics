package com.ares.analytics.viewmodel.routine

import com.ares.analytics.shared.models.League
import com.areslib.routine.RoutineDriveStep
import com.areslib.routine.RoutinePose
import com.areslib.routine.RoutineStep
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutineTrajectoryPreviewCompilerTest {
    private val compiler = RoutineTrajectoryPreviewCompiler()

    @Test
    fun `autonomous preview compiles from declared start through every drive target`() {
        val preview = compiler.compile(
            steps = listOf(
                driveTo(0.8, 0.2, PI / 4.0),
                driveTo(1.3, -0.4, PI / 2.0),
            ).map(RoutineStep::driveTo),
            previewStart = RoutinePose(0.1, -0.2, 0.0),
            hasAutonomousStart = true,
            league = League.FTC,
        )

        val trajectory = assertNotNull(preview.trajectory)
        assertTrue(preview.estimatedDurationSeconds > 0.0)
        assertEquals(preview.estimatedDurationSeconds, trajectory.durationSeconds)
        assertEquals(0.1, trajectory.states.first().x, absoluteTolerance = 1e-9)
        assertEquals(-0.2, trajectory.states.first().y, absoluteTolerance = 1e-9)
        assertEquals(1.3, trajectory.states.last().x, absoluteTolerance = 1e-9)
        assertEquals(-0.4, trajectory.states.last().y, absoluteTolerance = 1e-9)
        assertEquals(PI / 2.0, trajectory.states.last().headingRad, absoluteTolerance = 1e-9)
        assertTrue(trajectory.states.zipWithNext().all { (left, right) -> right.timeSeconds >= left.timeSeconds })
    }

    @Test
    fun `neutral routine uses first drive target only as the preview anchor`() {
        val preview = compiler.compile(
            steps = listOf(
                driveTo(0.4, 0.3, 0.0),
                driveTo(1.1, 0.9, PI / 3.0),
            ).map(RoutineStep::driveTo),
            previewStart = RoutinePose(0.4, 0.3, 0.0),
            hasAutonomousStart = false,
            league = League.FRC,
        )

        val trajectory = assertNotNull(preview.trajectory)
        assertEquals(0.4, trajectory.states.first().x, absoluteTolerance = 1e-9)
        assertEquals(0.3, trajectory.states.first().y, absoluteTolerance = 1e-9)
        assertEquals(1.1, trajectory.states.last().x, absoluteTolerance = 1e-9)
        assertEquals(0.9, trajectory.states.last().y, absoluteTolerance = 1e-9)
    }

    @Test
    fun `single drive without autonomous start has no fabricated motion segment`() {
        val preview = compiler.compile(
            steps = listOf(RoutineStep.driveTo(driveTo(0.5, 0.5, 0.0))),
            previewStart = RoutinePose(0.5, 0.5, 0.0),
            hasAutonomousStart = false,
            league = League.FTC,
        )

        assertNull(preview.trajectory)
        assertEquals(0.0, preview.estimatedDurationSeconds)
    }

    @Test
    fun `unknown motion preset falls back to balanced preview`() {
        val start = RoutinePose(0.0, 0.0, 0.0)
        val balanced = compiler.compile(
            steps = listOf(RoutineStep.driveTo(driveTo(0.7, -0.3, 0.2, "balanced"))),
            previewStart = start,
            hasAutonomousStart = true,
            league = League.FTC,
        )
        val unknown = compiler.compile(
            steps = listOf(RoutineStep.driveTo(driveTo(0.7, -0.3, 0.2, "future-preset"))),
            previewStart = start,
            hasAutonomousStart = true,
            league = League.FTC,
        )

        assertEquals(balanced, unknown)
    }

    @Test
    fun `action-only autonomous has a stationary structural timeline and visible actions`() {
        val preview = compiler.compile(
            steps = listOf(
                RoutineStep.action("lights.set", mapOf("value" to "GREEN"), "set-green"),
                RoutineStep.wait(0.5, "show-green"),
                RoutineStep.action("lights.cycle", stepId = "cycle"),
                RoutineStep.wait(0.5, "show-cycle"),
            ),
            previewStart = RoutinePose(0.2, -0.3, PI / 2.0),
            hasAutonomousStart = true,
            league = League.FTC,
        )

        val trajectory = assertNotNull(preview.trajectory)
        assertEquals(1.0, preview.estimatedDurationSeconds, absoluteTolerance = 1e-9)
        assertEquals(listOf("lights.set", "lights.cycle"), preview.actions.map { it.actionKey })
        assertEquals(listOf(0.0, 0.5), preview.actions.map { it.timeSeconds })
        assertTrue(trajectory.states.all { it.x == 0.2 && it.y == -0.3 })
    }

    private fun driveTo(
        x: Double,
        y: Double,
        heading: Double,
        preset: String = "balanced",
    ): RoutineDriveStep = RoutineDriveStep(
        target = RoutinePose(x, y, heading),
        motionPresetKey = preset,
    )
}
