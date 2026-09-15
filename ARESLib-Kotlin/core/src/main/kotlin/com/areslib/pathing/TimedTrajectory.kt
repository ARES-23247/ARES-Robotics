package com.areslib.pathing

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.wrapAngle
import java.util.Collections
import kotlin.math.hypot

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
 * State, event and nested force lists are defensively copied into read-only random-access storage.
 * Construction/copy must not race mutation of the input lists. Value equality, copy and destructuring
 * are retained explicitly; this is no longer a Kotlin data class for reflection purposes.
 */
class TimedTrajectory(
    states: List<TimedTrajectoryState>,
    events: List<TimedTrajectoryEvent> = emptyList(),
    val engine: TrajectoryEngine
) {
    /** Owned random-access snapshots, including nested force lists. */
    val states: List<TimedTrajectoryState> = Collections.unmodifiableList(states.map { state ->
        val forces = if (state.moduleFeedforwards.isEmpty()) emptyList()
            else Collections.unmodifiableList(ArrayList(state.moduleFeedforwards))
        if (forces === state.moduleFeedforwards) state else state.copy(moduleFeedforwards = forces)
    })
    val events: List<TimedTrajectoryEvent> = Collections.unmodifiableList(ArrayList(events))

    init {
        require(this.states.isNotEmpty()) { "A trajectory must contain at least one state" }
        require(this.states.first().timeSeconds == 0.0) { "A trajectory must start at t=0" }
        require(this.states.first().distanceMeters >= 0.0) { "Trajectory distance cannot be negative" }
        this.states.forEachIndexed { index, state ->
            require(state.isFinite()) { "Trajectory state $index contains a non-finite value" }
            if (index > 0) {
                require(state.timeSeconds > this.states[index - 1].timeSeconds) {
                    "Trajectory times must be strictly increasing"
                }
                require(state.distanceMeters >= this.states[index - 1].distanceMeters) {
                    "Trajectory distance must be non-decreasing"
                }
            }
        }
        this.events.forEach { event ->
            require(event.timeSeconds.isFinite() && event.timeSeconds in 0.0..durationSeconds) {
                "Event '${event.command}' lies outside the trajectory duration"
            }
        }
    }

    // Preserve the former data-class value operations while enforcing ownership on construction/copy.
    operator fun component1(): List<TimedTrajectoryState> = states
    operator fun component2(): List<TimedTrajectoryEvent> = events
    operator fun component3(): TrajectoryEngine = engine
    fun copy(states: List<TimedTrajectoryState> = this.states,
             events: List<TimedTrajectoryEvent> = this.events,
             engine: TrajectoryEngine = this.engine): TimedTrajectory = TimedTrajectory(states, events, engine)
    override fun equals(other: Any?): Boolean = this === other ||
        (other is TimedTrajectory && states == other.states && events == other.events && engine == other.engine)
    override fun hashCode(): Int = (states.hashCode() * 31 + events.hashCode()) * 31 + engine.hashCode()
    override fun toString(): String = "TimedTrajectory(states=$states, events=$events, engine=$engine)"

    val durationSeconds: Double
        get() = states.last().timeSeconds

    /** Adapts the canonical time trajectory to the current distance-based follower. */
    fun toPath(): Path {
        val points = states.map { state ->
            val speed = hypot(state.velocityXMps, state.velocityYMps)
            require(speed.isFinite()) { "Trajectory speed magnitude cannot be represented by the distance adapter" }
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

    /**
     * Finite-time binary-search sampling, clamped to endpoints. Exact knots reuse owned states;
     * interior samples allocate pose/state values and interpolate scalar fields independently.
     * Heading/tangent follow their shortest wrapped arcs; module forces use the nearest sample
     * (the later sample wins midpoint ties). This is not continuous-dynamics integration.
     */
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
        if (timeSeconds == before.timeSeconds) return before
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
            pathTangentRadians = wrapAngle(wrapAngle(before.pathTangentRadians) +
                wrapAngle(wrapAngle(after.pathTangentRadians) - wrapAngle(before.pathTangentRadians)) * fraction),
            moduleFeedforwards = if (fraction < 0.5) before.moduleFeedforwards else after.moduleFeedforwards
        )
    }
}

private fun TimedTrajectoryState.isFinite(): Boolean =
    timeSeconds.isFinite() && pose.hasFiniteTrajectoryCoordinates() && velocityXMps.isFinite() && velocityYMps.isFinite() &&
        angularVelocityRps.isFinite() && accelerationXMps2.isFinite() && accelerationYMps2.isFinite() &&
        angularAccelerationRps2.isFinite() && distanceMeters.isFinite() && curvature.isFinite() &&
        pathTangentRadians.isFinite() &&
        moduleFeedforwards.all { it.forceXNewtons.isFinite() && it.forceYNewtons.isFinite() }

private fun lerp(start: Double, end: Double, fraction: Double): Double =
    if ((start <= 0.0 && end >= 0.0) || (start >= 0.0 && end <= 0.0)) {
        start * (1.0 - fraction) + end * fraction
    } else start + (end - start) * fraction
