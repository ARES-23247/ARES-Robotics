package com.areslib.control.profile

import kotlin.math.abs
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

        val direction = if (goalLocal.position < currentLocal.position) -1.0 else 1.0
        val dist = abs(goalLocal.position - currentLocal.position)

        if (dist < 1e-6 || constraints.maxAcceleration <= 0.0 || constraints.maxVelocity <= 0.0) {
            outState.setTo(goalLocal)
            return
        }

        val maxV = constraints.maxVelocity
        val maxA = constraints.maxAcceleration

        // Calculate time needed to reach goal
        val deltaV = maxV - abs(currentLocal.velocity)
        val accelTime = if (deltaV > 0) deltaV / maxA else 0.0
        val accelDist = abs(currentLocal.velocity) * accelTime + 0.5 * maxA * accelTime * accelTime
        val decelDist = 0.5 * (maxV * maxV) / maxA

        val cruiseDist: Double
        val actualMaxV: Double
        val actualAccelTime: Double

        if (accelDist + decelDist > dist) {
            // Triangular profile
            actualAccelTime = sqrt(dist / maxA)
            actualMaxV = maxA * actualAccelTime
            cruiseDist = 0.0
        } else {
            // Trapezoidal profile
            actualAccelTime = accelTime
            actualMaxV = maxV
            cruiseDist = dist - 2 * accelDist
        }

        val cruiseTime = if (actualMaxV > 0) cruiseDist / actualMaxV else 0.0
        val decelTime = actualAccelTime
        val totalTime = actualAccelTime + cruiseTime + decelTime

        if (dtSeconds >= totalTime) {
            outState.setTo(goalLocal)
            return
        }

        var newPos: Double
        var newVel: Double

        if (dtSeconds <= actualAccelTime) {
            // Acceleration phase
            newVel = currentLocal.velocity + direction * maxA * dtSeconds
            if (abs(newVel) > actualMaxV) newVel = direction * actualMaxV
            newPos = currentLocal.position + currentLocal.velocity * dtSeconds + 0.5 * direction * maxA * dtSeconds * dtSeconds
        } else if (dtSeconds <= actualAccelTime + cruiseTime) {
            // Constant velocity phase
            val cruiseDt = dtSeconds - actualAccelTime
            val startCruisePos = currentLocal.position + 0.5 * direction * maxA * actualAccelTime * actualAccelTime
            newVel = direction * actualMaxV
            newPos = startCruisePos + newVel * cruiseDt
        } else {
            // Deceleration phase
            val decelDt = dtSeconds - actualAccelTime - cruiseTime
            val startDecelPos = currentLocal.position + (0.5 * direction * maxA * actualAccelTime * actualAccelTime) + (direction * actualMaxV * cruiseTime)
            val startDecelVel = direction * actualMaxV
            newVel = startDecelVel - direction * maxA * decelDt
            newPos = startDecelPos + startDecelVel * decelDt - 0.5 * direction * maxA * decelDt * decelDt
        }

        // Final sanity check for direction overshooting
        if (direction > 0 && newPos > goalLocal.position) {
            newPos = goalLocal.position
            newVel = goalLocal.velocity
        } else if (direction < 0 && newPos < goalLocal.position) {
            newPos = goalLocal.position
            newVel = goalLocal.velocity
        }

        outState.position = newPos
        outState.velocity = newVel
    }
}
