package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import com.areslib.math.wrapAngle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Drivetrain geometry relevant to trajectory generation. */
enum class DriveModel {
    MECANUM,
    SWERVE
}

/** Novice-facing intent. Robot configuration resolves the intent to explicit physical limits. */
enum class TrajectoryPreset {
    SAFE,
    BALANCED,
    FAST,
    ADAPTIVE
}

/** Generation strategies understood by ARES. Implementations may live outside core. */
enum class TrajectoryEngine {
    JERK_LIMITED,
    DYNAMICS_OPTIMIZED,
    ONLINE_REPLAN
}

/** Explicit physical limits after a robot-specific preset has been resolved. */
data class TrajectoryLimits(
    val maxVelocityMps: Double,
    val maxAccelerationMps2: Double,
    val maxJerkMps3: Double,
    val maxCentripetalAccelerationMps2: Double,
    val maxAngularVelocityRps: Double,
    val maxAngularAccelerationRps2: Double
)

/** Input shared by every trajectory provider. */
data class TrajectoryRequest(
    val waypoints: List<Pose2d>,
    val driveModel: DriveModel,
    val preset: TrajectoryPreset,
    val limits: TrajectoryLimits,
    val startVelocityMps: Double = 0.0,
    val endVelocityMps: Double = 0.0,
    val preferredEngine: TrajectoryEngine? = null,
    val sampleSpacingMeters: Double = 0.02
)

/** Optional per-module force feedforward emitted by a dynamics optimizer. */
data class ModuleForceFeedforward(
    val forceXNewtons: Double,
    val forceYNewtons: Double
)

enum class TrajectoryDiagnosticSeverity {
    INFO,
    WARNING,
    ERROR
}

/** Actionable generation feedback suitable for both tests and Analytics. */
data class TrajectoryDiagnostic(
    val severity: TrajectoryDiagnosticSeverity,
    val code: String,
    val message: String
)

data class TrajectoryGenerationResult(
    val trajectory: TimedTrajectory?,
    val diagnostics: List<TrajectoryDiagnostic> = emptyList()
) {
    val isSuccess: Boolean
        get() = trajectory != null && diagnostics.none { it.severity == TrajectoryDiagnosticSeverity.ERROR }
}

/** Pluggable path-to-trajectory generator. */
interface TrajectoryProvider {
    val engine: TrajectoryEngine
    fun supports(request: TrajectoryRequest): Boolean
    fun generate(request: TrajectoryRequest): TrajectoryGenerationResult
}

/**
 * Selects a provider without exposing solver details to novice call sites.
 *
 * `FAST` prefers a dynamics optimizer for swerve when installed. `ADAPTIVE` prefers online
 * replanning. All other automatic requests use the deterministic jerk-limited provider.
 */
class TrajectoryPlanner(providers: List<TrajectoryProvider>) {
    private val providersByEngine = buildMap {
        for (provider in providers) {
            require(put(provider.engine, provider) == null) { "Duplicate trajectory provider for ${provider.engine}" }
        }
    }

    fun generate(request: TrajectoryRequest): TrajectoryGenerationResult {
        val validation = validateTrajectoryRequest(request)
        if (validation.any { it.severity == TrajectoryDiagnosticSeverity.ERROR }) {
            return TrajectoryGenerationResult(null, validation)
        }

        val requestedEngine = request.preferredEngine ?: automaticEngine(request)
        val requestedProvider = providersByEngine[requestedEngine]
        val requestedSupported = requestedProvider?.supports(request) == true
        if (request.preferredEngine != null && !requestedSupported) {
            return TrajectoryGenerationResult(
                null,
                validation + TrajectoryDiagnostic(
                    TrajectoryDiagnosticSeverity.ERROR,
                    "engine_unavailable",
                    "${requestedEngine.name} does not support this ${request.driveModel.name.lowercase()} request"
                )
            )
        }

        val provider = when {
            requestedSupported -> requestedProvider
            requestedEngine != TrajectoryEngine.JERK_LIMITED ->
                providersByEngine[TrajectoryEngine.JERK_LIMITED]?.takeIf { it.supports(request) }
            else -> null
        } ?: return TrajectoryGenerationResult(
            null,
            validation + TrajectoryDiagnostic(
                TrajectoryDiagnosticSeverity.ERROR,
                "no_provider",
                "No installed trajectory provider supports this request"
            )
        )

        val fallbackDiagnostic = if (provider.engine != requestedEngine) {
            listOf(
                TrajectoryDiagnostic(
                    TrajectoryDiagnosticSeverity.WARNING,
                    "engine_fallback",
                    "${requestedEngine.name} is unavailable; generated a ${provider.engine.name} trajectory instead"
                )
            )
        } else {
            emptyList()
        }
        val generated = provider.generate(request)
        return generated.copy(diagnostics = validation + fallbackDiagnostic + generated.diagnostics)
    }

    private fun automaticEngine(request: TrajectoryRequest): TrajectoryEngine = when {
        request.preset == TrajectoryPreset.ADAPTIVE -> TrajectoryEngine.ONLINE_REPLAN
        request.preset == TrajectoryPreset.FAST && request.driveModel == DriveModel.SWERVE -> {
            TrajectoryEngine.DYNAMICS_OPTIMIZED
        }
        else -> TrajectoryEngine.JERK_LIMITED
    }
}

/** Deterministic provider used by FTC and as the cross-platform fallback. */
object JerkLimitedTrajectoryProvider : TrajectoryProvider {
    override val engine: TrajectoryEngine = TrajectoryEngine.JERK_LIMITED

    override fun supports(request: TrajectoryRequest): Boolean = true

    override fun generate(request: TrajectoryRequest): TrajectoryGenerationResult {
        val validation = validateTrajectoryRequest(request)
        if (validation.any { it.severity == TrajectoryDiagnosticSeverity.ERROR }) {
            return TrajectoryGenerationResult(null, validation)
        }

        val distinctWaypoints = ArrayList<Pose2d>(request.waypoints.size)
        for (pose in request.waypoints) {
            val previous = distinctWaypoints.lastOrNull()
            if (previous == null || hypot(pose.x - previous.x, pose.y - previous.y) > 0.0) {
                distinctWaypoints.add(pose)
            } else if (wrapAngle(pose.heading.radians - previous.heading.radians) != 0.0) {
                return TrajectoryGenerationResult(null, listOf(errorDiagnostic("rotation_without_translation",
                    "The jerk-limited provider cannot rotate between coincident waypoints")))
            }
        }
        if (distinctWaypoints.size < 2) {
            return TrajectoryGenerationResult(
                null,
                listOf(
                    TrajectoryDiagnostic(
                        TrajectoryDiagnosticSeverity.ERROR,
                        "translation_required",
                        "The jerk-limited provider requires at least two distinct translations"
                    )
                )
            )
        }

        return try {
            val path = SCurveTrajectoryParameterizer.generateTrajectory(
                waypoints = distinctWaypoints.map { Translation2d(it.x, it.y) },
                constraints = SCurveTrajectoryParameterizer.Constraints(
                    maxVelocityMps = request.limits.maxVelocityMps,
                    maxAccelerationMps2 = request.limits.maxAccelerationMps2,
                    maxJerkMps3 = request.limits.maxJerkMps3,
                    maxCentripetalAccelMps2 = request.limits.maxCentripetalAccelerationMps2
                ),
                startHeading = distinctWaypoints.first().heading,
                endHeading = distinctWaypoints.last().heading,
                startVelocityMps = request.startVelocityMps,
                endVelocityMps = request.endVelocityMps,
                spacingMeters = request.sampleSpacingMeters
            )
            applyWaypointHeadings(path, distinctWaypoints)
            val trajectory = timeParameterize(path, request.limits)
            val diagnostics = mutableListOf(TrajectoryDiagnostic(
                TrajectoryDiagnosticSeverity.INFO,
                "kinematic_profile",
                "Generated a kinematic profile with checked sample acceleration and jerk; no drivetrain force optimization was applied"
            ))
            val first = trajectory.states.first()
            val last = trajectory.states.last()
            if (abs(hypot(first.velocityXMps, first.velocityYMps) - request.startVelocityMps) > 1e-6 ||
                abs(hypot(last.velocityXMps, last.velocityYMps) - request.endVelocityMps) > 1e-6) {
                diagnostics += TrajectoryDiagnostic(
                    TrajectoryDiagnosticSeverity.WARNING,
                    "boundary_velocity_scaled",
                    "Time scaling reduced the requested entry or exit speed; review the trajectory handover"
                )
            }
            TrajectoryGenerationResult(
                trajectory = trajectory,
                diagnostics = diagnostics
            )
        } catch (failure: IllegalArgumentException) {
            TrajectoryGenerationResult(null, listOf(errorDiagnostic("unrepresentable_profile",
                "Cannot represent this trajectory with the configured limits: ${failure.message}")))
        }
    }

    /** Preserve each requested orientation, independently of the spatial seed's endpoint heading. */
    private fun applyWaypointHeadings(path: Path, waypoints: List<Pose2d>) {
        var segment = 0
        var segmentStart = 0.0
        var segmentEnd = hypot(waypoints[1].x - waypoints[0].x, waypoints[1].y - waypoints[0].y)
        for (point in path.points) {
            while (segment < waypoints.lastIndex - 1 && point.distanceMeters > segmentEnd) {
                segmentStart = segmentEnd
                segment++
                segmentEnd += hypot(waypoints[segment + 1].x - waypoints[segment].x,
                    waypoints[segment + 1].y - waypoints[segment].y)
            }
            val fraction = ((point.distanceMeters - segmentStart) / (segmentEnd - segmentStart)).coerceIn(0.0, 1.0)
            val start = waypoints[segment].heading.radians
            val heading = start + wrapAngle(waypoints[segment + 1].heading.radians - start) * fraction
            point.pose = Pose2d(point.pose.x, point.pose.y, Rotation2d(heading))
        }
    }

    private fun timeParameterize(path: Path, limits: TrajectoryLimits): TimedTrajectory {
        val count = path.points.size
        val segmentTimes = DoubleArray(max(0, count - 1))
        val segmentAngularVelocities = DoubleArray(segmentTimes.size)
        var timeScale = 1.0
        for (index in segmentTimes.indices) {
            val before = path.points[index]
            val after = path.points[index + 1]
            val distance = after.distanceMeters - before.distanceMeters
            val speedScale = max(before.velocityMps, after.velocityMps)
            require(distance > 0.0 && speedScale > 0.0) { "Profile contains an unreachable or unrepresentable segment" }
            // Normalize before summing: no overflow and no arbitrary near-zero speed cutoff.
            val translationTime = (distance / speedScale) /
                ((before.velocityMps / speedScale + after.velocityMps / speedScale) * 0.5)
            require(translationTime.isFinite() && translationTime > 0.0) { "Segment time must be finite and positive" }
            val headingDelta = wrapAngle(after.pose.heading.radians - before.pose.heading.radians)
            val rotationTime = abs(headingDelta) / limits.maxAngularVelocityRps
            segmentTimes[index] = translationTime
            timeScale = max(timeScale, rotationTime / segmentTimes[index])
            segmentAngularVelocities[index] = headingDelta / segmentTimes[index]
        }

        val omega = DoubleArray(count) { index ->
            when (index) {
                0 -> segmentAngularVelocities.first()
                count - 1 -> segmentAngularVelocities.last()
                else -> segmentAngularVelocities[index - 1] * 0.5 + segmentAngularVelocities[index] * 0.5
            }
        }
        var maximumAlpha = 0.0
        for (index in 1 until count) {
            maximumAlpha = max(maximumAlpha, abs(omega[index] - omega[index - 1]) / segmentTimes[index - 1])
        }
        timeScale = max(timeScale, sqrt(maximumAlpha) / sqrt(limits.maxAngularAccelerationRps2))

        // The spatial sweeps are a seed, not a proof of the final vector acceleration
        // or jerk bounds (especially at a turn or a cruise transition). Measure those
        // derivatives and stretch the entire clock coherently. Under t' = s*t,
        // velocity, acceleration and jerk scale by 1/s, 1/s² and 1/s³ respectively.
        var previousAx = 0.0
        var previousAy = 0.0
        for (index in segmentTimes.indices) {
            val before = path.points[index]
            val after = path.points[index + 1]
            val dt = segmentTimes[index]
            val ax = (after.velocityMps * cos(after.tangentRadians) -
                before.velocityMps * cos(before.tangentRadians)) / dt
            val ay = (after.velocityMps * sin(after.tangentRadians) -
                before.velocityMps * sin(before.tangentRadians)) / dt
            timeScale = max(timeScale, sqrt(hypot(ax, ay)) / sqrt(limits.maxAccelerationMps2))
            if (index > 0) {
                val jerk = hypot(ax - previousAx, ay - previousAy) / dt
                timeScale = max(timeScale, Math.cbrt(jerk) / Math.cbrt(limits.maxJerkMps3))
            }
            previousAx = ax
            previousAy = ay
        }
        require(timeScale.isFinite()) { "Profile time scaling cannot be represented" }
        if (timeScale > 1.0) {
            for (index in segmentTimes.indices) {
                segmentTimes[index] *= timeScale
            }
        }

        val times = DoubleArray(count)
        for (index in 1 until count) {
            times[index] = times[index - 1] + segmentTimes[index - 1]
            require(times[index].isFinite() && times[index] > times[index - 1]) { "Accumulated trajectory times must remain finite and distinct" }
        }
        val velocityX = DoubleArray(count)
        val velocityY = DoubleArray(count)
        for (index in 0 until count) {
            val point = path.points[index]
            val adjustedSpeed = point.velocityMps / timeScale
            velocityX[index] = adjustedSpeed * cos(point.tangentRadians)
            velocityY[index] = adjustedSpeed * sin(point.tangentRadians)
            omega[index] /= timeScale
        }

        val states = ArrayList<TimedTrajectoryState>(count)
        for (index in 0 until count) {
            val derivativeIndex = if (index == 0) 1.coerceAtMost(count - 1) else index
            val previousIndex = (derivativeIndex - 1).coerceAtLeast(0)
            val dt = times[derivativeIndex] - times[previousIndex]
            states += TimedTrajectoryState(
                timeSeconds = times[index],
                pose = path.points[index].pose,
                velocityXMps = velocityX[index],
                velocityYMps = velocityY[index],
                angularVelocityRps = omega[index],
                accelerationXMps2 = (velocityX[derivativeIndex] - velocityX[previousIndex]) / dt,
                accelerationYMps2 = (velocityY[derivativeIndex] - velocityY[previousIndex]) / dt,
                angularAccelerationRps2 = (omega[derivativeIndex] - omega[previousIndex]) / dt,
                distanceMeters = path.points[index].distanceMeters,
                curvature = path.points[index].curvature,
                pathTangentRadians = path.points[index].tangentRadians
            )
        }
        return TimedTrajectory(states = states, engine = engine)
    }
}

/** Performs format-independent validation before any solver is invoked. */
fun validateTrajectoryRequest(request: TrajectoryRequest): List<TrajectoryDiagnostic> {
    val diagnostics = mutableListOf<TrajectoryDiagnostic>()
    if (request.waypoints.size > MAX_TRAJECTORY_SAMPLES) {
        return listOf(errorDiagnostic("sample_budget_exceeded", "Trajectory input exceeds the 100000-sample budget"))
    }
    if (request.waypoints.size < 2) {
        diagnostics += errorDiagnostic("too_few_waypoints", "At least two waypoints are required")
    }
    request.waypoints.forEachIndexed { index, pose ->
        if (!pose.hasFiniteTrajectoryCoordinates()) {
            diagnostics += errorDiagnostic("invalid_waypoint", "Waypoint $index contains a non-finite value")
        }
    }
    val limits = request.limits
    val limitValues = listOf(
        "maximum velocity" to limits.maxVelocityMps,
        "maximum acceleration" to limits.maxAccelerationMps2,
        "maximum jerk" to limits.maxJerkMps3,
        "maximum centripetal acceleration" to limits.maxCentripetalAccelerationMps2,
        "maximum angular velocity" to limits.maxAngularVelocityRps,
        "maximum angular acceleration" to limits.maxAngularAccelerationRps2
    )
    limitValues.forEach { (label, value) ->
        if (!value.isFinite() || value <= 0.0) {
            diagnostics += errorDiagnostic("invalid_limit", "$label must be finite and positive")
        }
    }
    if (!request.startVelocityMps.isFinite() || request.startVelocityMps < 0.0 ||
        request.startVelocityMps > limits.maxVelocityMps
    ) {
        diagnostics += errorDiagnostic("invalid_start_velocity", "Start velocity is outside the configured limits")
    }
    if (!request.endVelocityMps.isFinite() || request.endVelocityMps < 0.0 ||
        request.endVelocityMps > limits.maxVelocityMps
    ) {
        diagnostics += errorDiagnostic("invalid_end_velocity", "End velocity is outside the configured limits")
    }
    if (!request.sampleSpacingMeters.isFinite() || request.sampleSpacingMeters !in 0.005..0.25) {
        diagnostics += errorDiagnostic(
            "invalid_spacing",
            "Sample spacing must be finite and between 0.005 m and 0.25 m"
        )
    } else {
        var previous: Pose2d? = null
        var count = 1L
        for (pose in request.waypoints) {
            val before = previous
            if (before != null) {
                val steps = boundedTrajectorySegmentSteps(hypot(pose.x - before.x, pose.y - before.y), request.sampleSpacingMeters)
                count += if (steps < 0) MAX_TRAJECTORY_SAMPLES.toLong() else steps.toLong()
                if (count > MAX_TRAJECTORY_SAMPLES) {
                    diagnostics += errorDiagnostic("sample_budget_exceeded", "Trajectory geometry exceeds the 100000-sample budget or finite distance range")
                    break
                }
            }
            previous = pose
        }
    }
    return diagnostics
}

internal fun Pose2d.hasFiniteTrajectoryCoordinates(): Boolean =
    x.isFinite() && y.isFinite() && heading.rawRadians.isFinite()

private fun errorDiagnostic(code: String, message: String): TrajectoryDiagnostic =
    TrajectoryDiagnostic(TrajectoryDiagnosticSeverity.ERROR, code, message)
