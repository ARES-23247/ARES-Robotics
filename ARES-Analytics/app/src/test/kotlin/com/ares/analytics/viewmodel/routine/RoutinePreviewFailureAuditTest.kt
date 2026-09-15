package com.ares.analytics.viewmodel.routine

import com.ares.analytics.shared.models.League
import com.areslib.math.geometry.Pose2d
import com.areslib.pathing.*
import com.areslib.routine.*
import java.util.concurrent.CancellationException
import kotlin.test.*

class RoutinePreviewFailureAuditTest {
    private val start = RoutinePose(0.0, 0.0, 0.0)
    private fun drive(x: Double, key: String = "balanced") = RoutineStep.driveTo(
        RoutineDriveStep(RoutinePose(x, 0.0, 0.0), motionPresetKey = key),
    )
    private fun compile(compiler: RoutineTrajectoryPreviewCompiler, steps: List<RoutineStep>) =
        compiler.compile(steps, start, true, League.FTC)
    private fun compiler(generateResult: (TrajectoryRequest) -> TrajectoryGenerationResult) =
        RoutineTrajectoryPreviewCompiler(TrajectoryPlanner(listOf(object : TrajectoryProvider {
            override val engine = TrajectoryEngine.JERK_LIMITED
            override fun supports(request: TrajectoryRequest) = true
            override fun generate(request: TrajectoryRequest) = generateResult(request)
        })))
    private fun trajectory(request: TrajectoryRequest) = TimedTrajectory(listOf(
        sample(0.0, request.waypoints.first(), 0.0), sample(1.0, request.waypoints.last(), 1.0),
    ), engine = TrajectoryEngine.JERK_LIMITED)
    private fun sample(time: Double, pose: Pose2d, distance: Double) = TimedTrajectoryState(
        timeSeconds = time, pose = pose, velocityXMps = 0.0, velocityYMps = 0.0, angularVelocityRps = 0.0,
        accelerationXMps2 = 0.0, accelerationYMps2 = 0.0, angularAccelerationRps2 = 0.0,
        distanceMeters = distance, curvature = 0.0, pathTangentRadians = 0.0,
    )
    private fun assertUnavailable(preview: RoutineTrajectoryPreview) {
        assertNotNull(preview.warning)
        assertNull(preview.trajectory)
        assertEquals(0.0, preview.estimatedDurationSeconds)
        assertTrue(preview.actions.isEmpty())
    }

    @Test fun `failed later segment never leaves a partial route or action timeline`() {
        var calls = 0
        val compiler = compiler { request ->
            if (++calls == 1) TrajectoryGenerationResult(trajectory(request))
            else TrajectoryGenerationResult(null, listOf(TrajectoryDiagnostic(
                TrajectoryDiagnosticSeverity.ERROR, "blocked", "Target is blocked",
            )))
        }
        assertUnavailable(compile(compiler, listOf(drive(1.0), RoutineStep.action("before"),
            RoutineStep.wait(0.5), drive(2.0), RoutineStep.action("after"))))
        assertEquals(2, calls)
    }

    @Test fun `error diagnostic rejects even a nonnull provider trajectory`() {
        val compiler = compiler { request -> TrajectoryGenerationResult(trajectory(request), listOf(
            TrajectoryDiagnostic(TrajectoryDiagnosticSeverity.ERROR, "unsafe", "Constraints not satisfied"),
        )) }
        assertUnavailable(compile(compiler, listOf(drive(1.0))))
    }

    @Test fun `provider exception becomes an unavailable preview`() {
        val compiler = compiler { error("solver failed") }
        assertUnavailable(compile(compiler, listOf(drive(1.0))))
    }

    @Test fun `cancellation is preserved rather than converted to a preview failure`() {
        val cancelled = CancellationException("cancelled preview")
        val compiler = compiler { throw cancelled }
        assertSame(cancelled, assertFailsWith<CancellationException> { compile(compiler, listOf(drive(1.0))) })
    }

    @Test fun `invalid structural steps cannot manufacture a timeline`() {
        val compiler = compiler { TrajectoryGenerationResult(trajectory(it)) }
        val invalid = listOf(
            RoutineStep.wait(Double.NaN), RoutineStep.wait(-0.1),
            RoutineStep.wait(1.0).copy(durationSeconds = null),
            RoutineStep.action("x").copy(actionKey = null),
            RoutineStep.action(" "),
            drive(1.0).copy(drive = null),
            RoutineStep.wait(1.0).copy(kind = RoutineStepKind.WAIT_UNTIL, timeoutSeconds = Double.POSITIVE_INFINITY),
        )
        for (step in invalid) assertUnavailable(compile(compiler, listOf(RoutineStep.wait(0.25), step)))
    }

    @Test fun `overflowed or unrepresentable cumulative times reject the whole timeline`() {
        val compiler = compiler { TrajectoryGenerationResult(trajectory(it)) }
        assertUnavailable(compile(compiler, listOf(RoutineStep.wait(Double.MAX_VALUE), RoutineStep.wait(Double.MAX_VALUE))))
        assertUnavailable(compile(compiler, listOf(RoutineStep.wait(1e20), RoutineStep.wait(1.0))))
        assertUnavailable(compile(compiler, listOf(RoutineStep.wait(1e20), drive(1.0))))
    }

    @Test fun `instant action arguments belong to the compiled snapshot`() {
        val args = mutableMapOf("color" to "GREEN")
        val result = compile(compiler { TrajectoryGenerationResult(trajectory(it)) },
            listOf(RoutineStep.action("lights", args), RoutineStep.wait(0.5)))
        args["color"] = "RED"
        assertEquals("GREEN", result.actions.single().arguments["color"])
    }

    @Test fun `wait and wait-until offsets retain exact action ordering and upper bound`() {
        val condition = RoutineStep.wait(1.0).copy(kind = RoutineStepKind.WAIT_UNTIL,
            durationSeconds = null, timeoutSeconds = 0.75, conditionKey = "ready")
        val result = compile(compiler { TrajectoryGenerationResult(trajectory(it)) }, listOf(
            RoutineStep.wait(0.25), RoutineStep.action("before"), drive(1.0), condition, RoutineStep.action("after"),
        ))
        assertEquals(2.0, result.estimatedDurationSeconds)
        assertEquals(listOf(0.25, 2.0), result.actions.map { it.timeSeconds })
        val points = assertNotNull(result.trajectory).states
        assertTrue(points.zipWithNext().all { (a,b) -> a.timeSeconds < b.timeSeconds })
        assertEquals(1.0, points.last().x)
    }

    @Test fun `unknown and mixed-case presets select balanced and requested enums without exceptions`() {
        val observed = mutableListOf<TrajectoryPreset>()
        val compiler = compiler { observed += it.preset; TrajectoryGenerationResult(trajectory(it)) }
        compile(compiler, listOf(drive(1.0, "unknown"), drive(2.0, "SaFe"), drive(3.0, "BALANCED")))
        assertEquals(listOf(TrajectoryPreset.BALANCED, TrajectoryPreset.SAFE, TrajectoryPreset.BALANCED), observed)
    }
    @Test fun `obsolete preview stops before generating later segments`() {
        var calls = 0
        val compiler = compiler { calls++; TrajectoryGenerationResult(trajectory(it)) }
        val cancelled = CancellationException("a newer edit owns the preview")
        assertSame(cancelled, assertFailsWith<CancellationException> {
            compiler.compile(listOf(drive(1.0), drive(2.0)), start, true, League.FTC,
                checkActive = { if (calls > 0) throw cancelled })
        })
        assertEquals(1, calls)
    }

    @Test fun `nonfinite start or target never reaches the trajectory provider`() {
        var calls = 0
        val compiler = compiler { calls++; TrajectoryGenerationResult(trajectory(it)) }
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertUnavailable(compiler.compile(listOf(drive(1.0)), start.copy(headingRadians = value), true, League.FTC))
            assertUnavailable(compile(compiler, listOf(drive(value))))
        }
        assertEquals(0, calls)
    }

}
