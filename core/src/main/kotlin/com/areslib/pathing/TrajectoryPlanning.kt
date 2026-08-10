package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import com.areslib.math.wrapAngle
import kotlin.math.abs
import kotlin.math.atan2
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

/** One time-parameterized, field-relative trajectory sample. */
data class TimedTrajectoryState(
    val timeSeconds: Double,
    val pose: Pose2d,
    val velocityXMps: Double,
    val velocityYMps: Double,
    val angularVelocityRps: Double,
    val accelerationXMps2: Double,
    val accelerationYMps2: Double,
    val angularAccelerationRps2: Double,
    val distanceMeters: Double,
    val curvature: Double,
    val pathTangentRadians: Double,
    val moduleFeedforwards: List<ModuleForceFeedforward> = emptyList()
)

/** Time-based marker independent of any external editor's file format. */
data class TimedTrajectoryEvent(
    val command: CommandKey,
    val timeSeconds: Double
)

/**
 * Canonical ARES trajectory consumed by robot followers.
 *
 * Geometry editors and importers produce requests; providers produce this representation. A
 * trajectory records its actual engine so logs and analysis never confuse an optimized profile
 * with the kinematic fallback.
 */
data class TimedTrajectory(
    val states: List<TimedTrajectoryState>,
    val events: List<TimedTrajectoryEvent> = emptyList(),
    val engine: TrajectoryEngine
) {
    init {
        require(states.isNotEmpty()) { "A trajectory must contain at least one state" }
        require(states.first().timeSeconds == 0.0) { "A trajectory must start at t=0" }
        states.forEachIndexed { index, state ->
            require(state.isFinite()) { "Trajectory state $index contains a non-finite value" }
            if (index > 0) {
                require(state.timeSeconds > states[index - 1].timeSeconds) {
                    "Trajectory times must be strictly increasing"
                }
                require(state.distanceMeters >= states[index - 1].distanceMeters) {
                    "Trajectory distance must be non-decreasing"
                }
            }
        }
        events.forEach { event ->
            require(event.timeSeconds.isFinite() && event.timeSeconds in 0.0..durationSeconds) {
                "Event '${event.command}' lies outside the trajectory duration"
            }
        }
    }

    val durationSeconds: Double
        get() = states.last().timeSeconds

    /** Adapts the canonical time trajectory to the current distance-based follower. */
    fun toPath(): Path {
        val points = states.map { state ->
            val speed = hypot(state.velocityXMps, state.velocityYMps)
            PathPoint(
                pose = state.pose,
                velocityMps = speed,
                distanceMeters = state.distanceMeters,
                curvature = state.curvature,
                tangentRadians = state.pathTangentRadians
            )
        }
        val pathEvents = events.map { event ->
            PathEvent(event.command.value, sample(event.timeSeconds).distanceMeters)
        }
        return Path(points, pathEvents)
    }

    /** Samples this immutable trajectory at [timeSeconds] with wrapped heading interpolation. */
    fun sample(timeSeconds: Double): TimedTrajectoryState {
        require(timeSeconds.isFinite()) { "Sample time must be finite" }
        if (timeSeconds <= 0.0) return states.first()
        if (timeSeconds >= durationSeconds) return states.last()

        var low = 0
        var high = states.lastIndex
        while (low + 1 < high) {
            val middle = (low + high) ushr 1
            if (states[middle].timeSeconds <= timeSeconds) low = middle else high = middle
        }
        val before = states[low]
        val after = states[high]
        val fraction = (timeSeconds - before.timeSeconds) / (after.timeSeconds - before.timeSeconds)
        val heading = before.pose.heading.radians +
            wrapAngle(after.pose.heading.radians - before.pose.heading.radians) * fraction
        return TimedTrajectoryState(
            timeSeconds = timeSeconds,
            pose = Pose2d(
                lerp(before.pose.x, after.pose.x, fraction),
                lerp(before.pose.y, after.pose.y, fraction),
                Rotation2d(heading)
            ),
            velocityXMps = lerp(before.velocityXMps, after.velocityXMps, fraction),
            velocityYMps = lerp(before.velocityYMps, after.velocityYMps, fraction),
            angularVelocityRps = lerp(before.angularVelocityRps, after.angularVelocityRps, fraction),
            accelerationXMps2 = lerp(before.accelerationXMps2, after.accelerationXMps2, fraction),
            accelerationYMps2 = lerp(before.accelerationYMps2, after.accelerationYMps2, fraction),
            angularAccelerationRps2 = lerp(
                before.angularAccelerationRps2,
                after.angularAccelerationRps2,
                fraction
            ),
            distanceMeters = lerp(before.distanceMeters, after.distanceMeters, fraction),
            curvature = lerp(before.curvature, after.curvature, fraction),
            pathTangentRadians = before.pathTangentRadians +
                wrapAngle(after.pathTangentRadians - before.pathTangentRadians) * fraction,
            moduleFeedforwards = if (fraction < 0.5) before.moduleFeedforwards else after.moduleFeedforwards
        )
    }
}

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
    private val providersByEngine = providers.associateBy { it.engine }

    fun generate(request: TrajectoryRequest): TrajectoryGenerationResult {
        val validation = validateTrajectoryRequest(request)
        if (validation.any { it.severity == TrajectoryDiagnosticSeverity.ERROR }) {
            return TrajectoryGenerationResult(null, validation)
        }

        val requestedEngine = request.preferredEngine ?: automaticEngine(request)
        val requestedProvider = providersByEngine[requestedEngine]
        if (request.preferredEngine != null && requestedProvider?.supports(request) != true) {
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
            requestedProvider?.supports(request) == true -> requestedProvider
            else -> providersByEngine[TrajectoryEngine.JERK_LIMITED]?.takeIf { it.supports(request) }
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

        val distinctWaypoints = request.waypoints.filterIndexed { index, pose ->
            index == 0 || hypot(
                pose.x - request.waypoints[index - 1].x,
                pose.y - request.waypoints[index - 1].y
            ) > 1e-9
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
        val trajectory = timeParameterize(path, request.limits)
        return TrajectoryGenerationResult(
            trajectory = trajectory,
            diagnostics = listOf(
                TrajectoryDiagnostic(
                    TrajectoryDiagnosticSeverity.INFO,
                    "kinematic_profile",
                    "Generated a jerk-limited kinematic profile; no drivetrain force optimization was applied"
                )
            )
        )
    }

    private fun timeParameterize(path: Path, limits: TrajectoryLimits): TimedTrajectory {
        val count = path.points.size
        val segmentTimes = DoubleArray(max(0, count - 1))
        val segmentAngularVelocities = DoubleArray(segmentTimes.size)
        for (index in segmentTimes.indices) {
            val before = path.points[index]
            val after = path.points[index + 1]
            val distance = after.distanceMeters - before.distanceMeters
            val velocitySum = before.velocityMps + after.velocityMps
            val translationTime = if (distance <= 1e-12) {
                0.0
            } else {
                require(velocitySum > 1e-9) { "Profile contains an unreachable zero-velocity segment" }
                2.0 * distance / velocitySum
            }
            val headingDelta = wrapAngle(after.pose.heading.radians - before.pose.heading.radians)
            val rotationTime = abs(headingDelta) / limits.maxAngularVelocityRps
            segmentTimes[index] = max(translationTime, rotationTime).coerceAtLeast(1e-6)
            segmentAngularVelocities[index] = headingDelta / segmentTimes[index]
        }

        var maximumAlpha = 0.0
        for (index in 1 until segmentAngularVelocities.size) {
            val averageTime = (segmentTimes[index - 1] + segmentTimes[index]) * 0.5
            maximumAlpha = max(
                maximumAlpha,
                abs(segmentAngularVelocities[index] - segmentAngularVelocities[index - 1]) / averageTime
            )
        }
        val angularScale = if (maximumAlpha > limits.maxAngularAccelerationRps2) {
            sqrt(maximumAlpha / limits.maxAngularAccelerationRps2)
        } else {
            1.0
        }
        if (angularScale > 1.0) {
            for (index in segmentTimes.indices) {
                segmentTimes[index] *= angularScale
                segmentAngularVelocities[index] /= angularScale
            }
        }

        val times = DoubleArray(count)
        for (index in 1 until count) {
            times[index] = times[index - 1] + segmentTimes[index - 1]
        }
        val velocityX = DoubleArray(count)
        val velocityY = DoubleArray(count)
        val omega = DoubleArray(count)
        for (index in 0 until count) {
            val point = path.points[index]
            val adjustedSpeed = point.velocityMps / angularScale
            velocityX[index] = adjustedSpeed * cos(point.tangentRadians)
            velocityY[index] = adjustedSpeed * sin(point.tangentRadians)
            omega[index] = when {
                segmentAngularVelocities.isEmpty() -> 0.0
                index == 0 -> segmentAngularVelocities.first()
                index == count - 1 -> segmentAngularVelocities.last()
                else -> (segmentAngularVelocities[index - 1] + segmentAngularVelocities[index]) * 0.5
            }
        }

        val states = ArrayList<TimedTrajectoryState>(count)
        for (index in 0 until count) {
            val derivativeIndex = if (index == 0) 1.coerceAtMost(count - 1) else index
            val previousIndex = (derivativeIndex - 1).coerceAtLeast(0)
            val dt = (times[derivativeIndex] - times[previousIndex]).coerceAtLeast(1e-9)
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
    if (request.waypoints.size < 2) {
        diagnostics += errorDiagnostic("too_few_waypoints", "At least two waypoints are required")
    }
    request.waypoints.forEachIndexed { index, pose ->
        if (!pose.isFinite()) {
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
    }
    return diagnostics
}

private fun TimedTrajectoryState.isFinite(): Boolean =
    timeSeconds.isFinite() && pose.isFinite() && velocityXMps.isFinite() && velocityYMps.isFinite() &&
        angularVelocityRps.isFinite() && accelerationXMps2.isFinite() && accelerationYMps2.isFinite() &&
        angularAccelerationRps2.isFinite() && distanceMeters.isFinite() && curvature.isFinite() &&
        pathTangentRadians.isFinite() &&
        moduleFeedforwards.all { it.forceXNewtons.isFinite() && it.forceYNewtons.isFinite() }

private fun Pose2d.isFinite(): Boolean =
    x.isFinite() && y.isFinite() && heading.radians.isFinite()

private fun lerp(start: Double, end: Double, fraction: Double): Double =
    start + (end - start) * fraction

private fun errorDiagnostic(code: String, message: String): TrajectoryDiagnostic =
    TrajectoryDiagnostic(TrajectoryDiagnosticSeverity.ERROR, code, message)
