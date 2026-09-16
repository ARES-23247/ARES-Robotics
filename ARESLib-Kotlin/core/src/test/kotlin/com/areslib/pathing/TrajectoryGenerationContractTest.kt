package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TrajectoryGenerationContractTest {
    @Test fun `each invalid configured limit and boundary speed receives a diagnostic`() {
        val request = auditRequest()
        for (bad in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0)) {
            val limits = listOf(request.limits.copy(maxVelocityMps = bad), request.limits.copy(maxAccelerationMps2 = bad),
                request.limits.copy(maxJerkMps3 = bad), request.limits.copy(maxCentripetalAccelerationMps2 = bad),
                request.limits.copy(maxAngularVelocityRps = bad), request.limits.copy(maxAngularAccelerationRps2 = bad))
            for (limit in limits) assertTrue(validateTrajectoryRequest(request.copy(limits = limit)).any { it.code == "invalid_limit" })
        }
        for (bad in doubleArrayOf(-1.0, 4.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertTrue(validateTrajectoryRequest(request.copy(startVelocityMps = bad)).any { it.code == "invalid_start_velocity" })
            assertTrue(validateTrajectoryRequest(request.copy(endVelocityMps = bad)).any { it.code == "invalid_end_velocity" })
        }
        for (bad in doubleArrayOf(0.0, 0.004, 0.251, Double.NaN, Double.POSITIVE_INFINITY))
            assertTrue(validateTrajectoryRequest(request.copy(sampleSpacingMeters = bad)).any { it.code == "invalid_spacing" })
        assertTrue(validateTrajectoryRequest(request.copy(waypoints = emptyList())).any { it.code == "too_few_waypoints" })
        assertFalse(TrajectoryPlanner(emptyList()).generate(request.copy(startVelocityMps = -1.0)).isSuccess)
    }

    @Test fun `automatic and explicit provider selection avoid repeated capability calls`() {
        for (preset in TrajectoryPreset.entries) for (model in DriveModel.entries) {
            val expected = when {
                preset == TrajectoryPreset.ADAPTIVE -> TrajectoryEngine.ONLINE_REPLAN
                preset == TrajectoryPreset.FAST && model == DriveModel.SWERVE -> TrajectoryEngine.DYNAMICS_OPTIMIZED
                else -> TrajectoryEngine.JERK_LIMITED
            }
            val checks = mutableMapOf<TrajectoryEngine, Int>()
            val providers = TrajectoryEngine.entries.map { selected -> object : TrajectoryProvider {
                override val engine = selected
                override fun supports(request: TrajectoryRequest): Boolean { checks[selected] = (checks[selected] ?: 0) + 1; return true }
                override fun generate(request: TrajectoryRequest) = TrajectoryGenerationResult(
                    TimedTrajectory(listOf(auditState(0.0)), engine = selected))
            } }
            val result = TrajectoryPlanner(providers).generate(auditRequest().copy(preset = preset, driveModel = model))
            assertEquals(expected, result.trajectory!!.engine)
            assertEquals(mapOf(expected to 1), checks)
        }
        val missing = TrajectoryPlanner(emptyList()).generate(auditRequest())
        assertFalse(missing.isSuccess); assertTrue(missing.diagnostics.any { it.code == "no_provider" })
        var checks = 0
        val unsupported = object : TrajectoryProvider {
            override val engine = TrajectoryEngine.JERK_LIMITED
            override fun supports(request: TrajectoryRequest): Boolean { checks++; return false }
            override fun generate(request: TrajectoryRequest): TrajectoryGenerationResult = error("must not run")
        }
        assertFalse(TrajectoryPlanner(listOf(unsupported)).generate(auditRequest()).isSuccess)
        assertEquals(1, checks)
    }

    @Test fun `coincident orientation changes are explicit unsupported requests`() {
        val same = JerkLimitedTrajectoryProvider.generate(auditRequest(listOf(Pose2d(), Pose2d())))
        assertTrue(same.diagnostics.any { it.code == "translation_required" })
        val spin = JerkLimitedTrajectoryProvider.generate(auditRequest(listOf(Pose2d(), Pose2d(0.0, 0.0, Rotation2d(1.0)), Pose2d(1.0, 0.0))))
        assertTrue(spin.diagnostics.any { it.code == "rotation_without_translation" })
        val duplicates = JerkLimitedTrajectoryProvider.generate(auditRequest(listOf(Pose2d(), Pose2d(), Pose2d(1.0, 0.0))))
        assertTrue(duplicates.isSuccess)
        val tiny = JerkLimitedTrajectoryProvider.generate(auditRequest().copy(limits = auditRequest().limits.copy(maxVelocityMps = Double.MIN_VALUE)))
        assertFalse(tiny.isSuccess); assertTrue(tiny.diagnostics.any { it.code == "unrepresentable_profile" })
    }

    @Test fun `sample budget is checked before generation and uses a conservative ceiling`() {
        val request = auditRequest().copy(sampleSpacingMeters = 0.25)
        assertTrue(validateTrajectoryRequest(request.copy(waypoints = listOf(Pose2d(), Pose2d(24999.75, 0.0)))).isEmpty())
        assertTrue(validateTrajectoryRequest(request.copy(waypoints = listOf(Pose2d(), Pose2d(25000.0, 0.0)))).any { it.code == "sample_budget_exceeded" })
        val repeated = java.util.Collections.nCopies(100001, Pose2d())
        assertTrue(validateTrajectoryRequest(request.copy(waypoints = repeated)).any { it.code == "sample_budget_exceeded" })
        assertFalse(JerkLimitedTrajectoryProvider.generate(request.copy(waypoints = listOf(Pose2d(), Pose2d(1e100, 0.0)))).isSuccess)
        val constraints = SCurveTrajectoryParameterizer.Constraints(3.0, 2.0, 8.0)
        assertThrows(IllegalArgumentException::class.java) {
            SCurveTrajectoryParameterizer.generateTrajectory(listOf(Translation2d(), Translation2d(1e100, 0.0)), constraints)
        }
        val path = SCurveTrajectoryParameterizer.generateTrajectory(listOf(Translation2d(), Translation2d(1.0, 0.0)), constraints, spacingMeters = 0.03)
        for ((a, b) in path.points.zipWithNext()) assertTrue(b.distanceMeters - a.distanceMeters <= 0.0300000001)
    }

    @Test fun `direct spatial provider validates raw poses before even empty or singleton fast paths`() {
        val constraints = SCurveTrajectoryParameterizer.Constraints(3.0, 2.0, 8.0)
        for (bad in doubleArrayOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { SCurveTrajectoryParameterizer.generateTrajectory(emptyList(), constraints, startHeading = Rotation2d(bad)) }
            assertThrows(IllegalArgumentException::class.java) { SCurveTrajectoryParameterizer.generateTrajectory(emptyList(), constraints, endHeading = Rotation2d(bad)) }
            assertThrows(IllegalArgumentException::class.java) { SCurveTrajectoryParameterizer.generateTrajectory(listOf(Translation2d(bad, 0.0)), constraints) }
            assertThrows(IllegalArgumentException::class.java) { SCurveTrajectoryParameterizer.generateTrajectory(listOf(Translation2d(0.0, bad)), constraints) }
        }
        val tiny = JerkLimitedTrajectoryProvider.generate(auditRequest(listOf(Pose2d(), Pose2d(1e-8, 0.0, Rotation2d(0.5)))))
        assertTrue(tiny.isSuccess, tiny.diagnostics.toString())
        val trajectory = tiny.trajectory!!
        assertEquals(0.0, trajectory.states.first().pose.heading.radians)
        assertEquals(0.5, trajectory.states.last().pose.heading.radians, 1e-10)
    }
}
