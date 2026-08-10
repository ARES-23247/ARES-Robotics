package com.areslib.control.profile

import kotlin.math.sqrt

/**
 * Zero-Allocation 1D Kinematic Trapezoidal Motion Profile Generator.
 *
 * Computes deterministic position ($x$) and velocity ($v$) trajectory setpoints to transition a physical mechanism from an initial
 * state $(x_0, v_0)$ to a target goal state $(x_{goal}, v_{goal})$ while strictly respecting physical maximum cruise velocity ($v_{max}$)
 * and maximum acceleration ($a_{max}$) limits.
 *
 * ### Kinematic Equations:
 * 1. **Acceleration Phase** ($t \le t_{accel}$):
 *    $$v(t) = v_0 + \text{dir} \cdot a_{max} \cdot t$$
 *    $$x(t) = x_0 + v_0 \cdot t + \frac{1}{2} \cdot \text{dir} \cdot a_{max} \cdot t^2$$
 * 2. **Constant Velocity Cruise Phase** ($t_{accel} < t \le t_{accel} + t_{cruise}$):
 *    $$v(t) = \text{dir} \cdot v_{max}$$
 *    $$x(t) = x_{accel} + v(t) \cdot (t - t_{accel})$$
 * 3. **Deceleration Phase** ($t_{accel} + t_{cruise} < t \le t_{total}$):
 *    $$v(t) = v_{cruise} - \text{dir} \cdot a_{max} \cdot (t - t_{accel} - t_{cruise})$$
 *    $$x(t) = x_{decel} + v_{cruise} \cdot (t') - \frac{1}{2} \cdot \text{dir} \cdot a_{max} \cdot (t')^2$$
 *
 * ### Physical Units & Properties:
 * - Position ($x$): Meters ($m$) or Radians ($rad$)
 * - Velocity ($v$): Meters per second ($m/s$) or Radians per second ($rad/s$)
 * - Acceleration ($a$): Meters per second squared ($m/s^2$) or Radians per second squared ($rad/s^2$)
 * - Timestep ($\Delta t$): Seconds ($s$)
 * - Memory Footprint: 100% Zero-GC allocation compliance during update cycles.
 *
 * @see State
 * @see Constraints
 */
class TrapezoidProfile {
    private val currentLocal = State()
    private val goalLocal = State()

    /**
     * Physical kinematic maximum velocity and acceleration limits bounding mechanism movement.
     *
     * @property maxVelocity Maximum allowable cruise velocity limit $v_{max}$ ($m/s$ or $rad/s$).
     * @property maxAcceleration Maximum allowable acceleration limit $a_{max}$ ($m/s^2$ or $rad/s^2$).
     */
    data class Constraints(
        var maxVelocity: Double = 0.0,
        var maxAcceleration: Double = 0.0
    )

    /**
     * Trajectory state representation at a discrete time instant $(x(t), v(t))$.
     *
     * @property position Mechanism position $x(t)$ in meters ($m$) or radians ($rad$).
     * @property velocity Mechanism velocity $v(t)$ in meters per second ($m/s$) or radians per second ($rad/s$).
     */
    data class State(
        var position: Double = 0.0,
        var velocity: Double = 0.0
    ) {
        /**
         * Copies values from another state into this instance without heap allocations.
         *
         * @param other Source [State] to copy from.
         */
        fun setTo(other: State) {
            this.position = other.position
            this.velocity = other.velocity
        }
    }

    /**
     * Computes the interpolated motion profile state after $\Delta t$ seconds, writing the result directly into [outState].
     *
     * @param dtSeconds Timestep duration in seconds ($\Delta t > 0$).
     * @param current Current mechanism state $(x_0, v_0)$.
     * @param goal Target mechanism state $(x_{goal}, v_{goal})$.
     * @param constraints Physical velocity ($v_{max}$) and acceleration ($a_{max}$) constraints [Constraints].
     * @param outState Pre-allocated [State] output container receiving computed position and velocity setpoints.
     */
    fun calculate(
        dtSeconds: Double,
        current: State,
        goal: State,
        constraints: Constraints,
        outState: State
    ) {
        currentLocal.setTo(current)
        goalLocal.setTo(goal)

        if (!dtSeconds.isFinite() || dtSeconds <= 0.0 ||
            !constraints.maxAcceleration.isFinite() || constraints.maxAcceleration <= 0.0 ||
            !constraints.maxVelocity.isFinite() || constraints.maxVelocity <= 0.0 ||
            !currentLocal.position.isFinite() || !currentLocal.velocity.isFinite() ||
            !goalLocal.position.isFinite() || !goalLocal.velocity.isFinite()
        ) {
            if (currentLocal.position.isFinite() && currentLocal.velocity.isFinite()) {
                outState.setTo(currentLocal)
            } else {
                outState.position = 0.0
                outState.velocity = 0.0
            }
            return
        }

        // Solve in a coordinate system where the goal is always in the positive direction.
        // This is the standard cutoff-distance formulation and supports arbitrary finite
        // initial and final velocities rather than assuming both are zero.
        var direction = if (currentLocal.position > goalLocal.position) -1.0 else 1.0
        val maxV = constraints.maxVelocity
        val maxA = constraints.maxAcceleration
        if (kotlin.math.abs(goalLocal.velocity) > maxV) {
            outState.setTo(currentLocal)
            return
        }

        var currentPosition = currentLocal.position * direction
        var currentVelocity = currentLocal.velocity * direction
        var goalPosition = goalLocal.position * direction
        var goalVelocity = goalLocal.velocity * direction
        var cutoffBegin = currentVelocity / maxA
        var cutoffDistBegin = cutoffBegin * cutoffBegin * maxA * 0.5
        var cutoffEnd = goalVelocity / maxA
        var cutoffDistEnd = cutoffEnd * cutoffEnd * maxA * 0.5
        var fullTrapezoidDist = cutoffDistBegin + (goalPosition - currentPosition) + cutoffDistEnd
        if (!fullTrapezoidDist.isFinite() || fullTrapezoidDist < 0.0) {
            outState.setTo(currentLocal)
            return
        }

        var accelerationTime = maxV / maxA
        var fullSpeedDist = fullTrapezoidDist - accelerationTime * accelerationTime * maxA
        if (fullSpeedDist < 0.0) {
            accelerationTime = sqrt(fullTrapezoidDist / maxA)
            fullSpeedDist = 0.0
        }

        // A positive boundary velocity above the monotonic profile's peak means the
        // mechanism cannot stop at the goal without first overshooting it. Reverse the
        // profile coordinate so the solution brakes, reverses, and returns continuously.
        if (cutoffBegin > accelerationTime || cutoffEnd > accelerationTime) {
            direction = -direction
            currentPosition = currentLocal.position * direction
            currentVelocity = currentLocal.velocity * direction
            goalPosition = goalLocal.position * direction
            goalVelocity = goalLocal.velocity * direction
            cutoffBegin = currentVelocity / maxA
            cutoffDistBegin = cutoffBegin * cutoffBegin * maxA * 0.5
            cutoffEnd = goalVelocity / maxA
            cutoffDistEnd = cutoffEnd * cutoffEnd * maxA * 0.5
            fullTrapezoidDist = cutoffDistBegin + (goalPosition - currentPosition) + cutoffDistEnd
            if (!fullTrapezoidDist.isFinite() || fullTrapezoidDist < 0.0) {
                outState.setTo(currentLocal)
                return
            }
            accelerationTime = maxV / maxA
            fullSpeedDist = fullTrapezoidDist - accelerationTime * accelerationTime * maxA
            if (fullSpeedDist < 0.0) {
                accelerationTime = sqrt(fullTrapezoidDist / maxA)
                fullSpeedDist = 0.0
            }
        }

        val endAccel = accelerationTime - cutoffBegin
        val endFullSpeed = endAccel + fullSpeedDist / maxV
        val endDecel = endFullSpeed + accelerationTime - cutoffEnd

        val newPosition: Double
        val newVelocity: Double
        when {
            dtSeconds < endAccel -> {
                newVelocity = currentVelocity + dtSeconds * maxA
                newPosition = currentPosition + (currentVelocity + dtSeconds * maxA * 0.5) * dtSeconds
            }
            dtSeconds < endFullSpeed -> {
                newVelocity = maxV
                newPosition = currentPosition +
                    (currentVelocity + endAccel * maxA * 0.5) * endAccel +
                    maxV * (dtSeconds - endAccel)
            }
            dtSeconds <= endDecel -> {
                val timeLeft = endDecel - dtSeconds
                newVelocity = goalVelocity + timeLeft * maxA
                newPosition = goalPosition - (goalVelocity + timeLeft * maxA * 0.5) * timeLeft
            }
            else -> {
                outState.setTo(goalLocal)
                return
            }
        }

        outState.position = newPosition * direction
        outState.velocity = newVelocity * direction
    }
}
