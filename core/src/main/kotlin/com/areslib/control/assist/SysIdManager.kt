package com.areslib.control.assist

import com.areslib.math.wrapAngle
import kotlin.math.sqrt

/**
 * System Identification (SysId) Target Mechanism Type.
 *
 * Defines the physical degree of freedom under characterization.
 */
enum class SysIdMechanism {
    /** Linear mechanism (drivetrain translation or elevator) with velocity in $m/s$ and position in $m$. */
    LINEAR,
    /** Rotational mechanism (drivetrain rotation or arm) with velocity in $rad/s$ and angle in $rad$. */
    ANGULAR,
    /** Flywheel mechanism with rotational velocity in $rad/s$ or $RPM$. */
    FLYWHEEL
}

/**
 * System Identification (SysId) Voltage Test Routine.
 *
 * Specifies the applied voltage profile pattern.
 */
enum class SysIdRoutine {
    /** Inactive routine state; 0.0V commanded. */
    NONE,
    /** Quasistatic test routine applying a slow linear voltage ramp $\dot{V} = 1.2\text{ V/s}$ to measure $k_S$ and $k_V$. */
    QUASISTATIC,
    /** Dynamic step-response routine applying a fast voltage step $V = \pm 3.0\text{ V}$ to measure $k_A$. */
    DYNAMIC
}

/**
 * System Identification Routine Controller & Data Logging Manager.
 *
 * Generates calibrated voltage test profiles (Quasistatic ramp and Dynamic step test) for calculating
 * feedforward gains ($k_S, k_V, k_A$) while enforcing displacement, rotation, and duration safety boundaries.
 *
 * ### Applied Voltage Profiles:
 * 1. **Quasistatic Ramp Routine**:
 *    $$V(t) = \begin{cases} 1.2 \cdot t & t < 2.5\text{ s} \\ -1.2 \cdot (t - 2.5) & t \ge 2.5\text{ s} \end{cases}$$
 * 2. **Dynamic Step Routine**:
 *    $$V(t) = \begin{cases} +3.0\text{ V} & t < 1.5\text{ s} \\ -3.0\text{ V} & t \ge 1.5\text{ s} \end{cases}$$
 *
 * ### Numerical Differentiation:
 * Calculates real-time acceleration:
 * $$a_k = \frac{v_k - v_{k-1}}{\Delta t}$$
 *
 * ### Physical Units & Limits:
 * - Voltage: Volts ($V$), clamped to battery range $[-12.0\text{V}, +12.0\text{V}]$
 * - Timestamp: Milliseconds ($ms$)
 * - Maximum Test Duration Safety Limit: $5.0$ seconds
 * - Maximum Linear Displacement Limit: $1.5$ meters ($m$)
 * - Maximum Rotational Travel Limit: $4\pi$ radians ($rad$)
 *
 * @see SysIdMechanism
 * @see SysIdRoutine
 */
class SysIdManager {
    /** Currently active mechanism under test. */
    var activeMechanism = SysIdMechanism.LINEAR
        private set

    /** Currently active voltage routine pattern. */
    var activeRoutine = SysIdRoutine.NONE
        private set

    /** Test start timestamp in milliseconds ($ms$). */
    var startTimeMs = 0L
        private set

    /** Initial X position at test start ($m$). */
    var startX = 0.0
        private set

    /** Initial Y position at test start ($m$). */
    var startY = 0.0
        private set

    /** Initial heading orientation at test start ($rad$). */
    var startHeading = 0.0
        private set

    /** Currently commanded motor output voltage ($V$). */
    var currentVoltage = 0.0
        private set

    private var lastTimeMs = 0L
    private var lastVelocity = 0.0
    private var lastHeading = 0.0

    /** Accumulated total heading rotation during angular test ($rad$). */
    var accumulatedHeadingChange = 0.0
        private set

    /** Integrated total displacement during linear test ($m$). */
    var accumulatedPosition = 0.0
        private set

    /** Numerically calculated instantaneous acceleration ($m/s^2$ or $rad/s^2$). */
    var calculatedAcceleration = 0.0
        private set

    /**
     * Initializes and launches a new System Identification characterization routine.
     *
     * @param mechanism Target mechanism under characterization ([SysIdMechanism]).
     * @param routine Target test routine profile ([SysIdRoutine]).
     * @param timestampMs Start timestamp in milliseconds ($ms$).
     * @param x Initial robot X position in meters ($m$).
     * @param y Initial robot Y position in meters ($m$).
     * @param heading Initial robot heading in radians ($rad$).
     */
    fun start(mechanism: SysIdMechanism, routine: SysIdRoutine, timestampMs: Long, x: Double, y: Double, heading: Double) {
        activeMechanism = mechanism
        activeRoutine = routine
        startTimeMs = timestampMs
        startX = x
        startY = y
        startHeading = heading
        currentVoltage = 0.0
        lastTimeMs = timestampMs
        lastVelocity = 0.0
        lastHeading = heading
        accumulatedHeadingChange = 0.0
        accumulatedPosition = 0.0
        calculatedAcceleration = 0.0
    }

    /**
     * Stops the active routine immediately and resets output voltage to 0.0V.
     */
    fun stop() {
        activeRoutine = SysIdRoutine.NONE
        currentVoltage = 0.0
    }

    /**
     * Checks if a SysId characterization routine is currently active.
     *
     * @return `true` if active routine is not [SysIdRoutine.NONE]; `false` otherwise.
     */
    fun isActive(): Boolean = activeRoutine != SysIdRoutine.NONE

    /**
     * Evaluates safety limit constraints (time, displacement, rotation).
     *
     * @param x Current robot X position in meters ($m$).
     * @param y Current robot Y position in meters ($m$).
     * @param heading Current robot heading in radians ($rad$).
     * @param timestampMs Current loop timestamp in milliseconds ($ms$).
     * @return `true` if operation is within safe bounds; `false` if a safety threshold was exceeded (must abort).
     */
    fun checkSafety(x: Double, y: Double, heading: Double, timestampMs: Long): Boolean {
        if (!isActive()) return true
        
        val elapsedSec = (timestampMs - startTimeMs) / 1000.0
        if (elapsedSec > 5.0) {
            return false // Time safety limit
        }

        if (activeMechanism == SysIdMechanism.FLYWHEEL) {
            return true
        }

        if (activeMechanism == SysIdMechanism.LINEAR) {
            val dx = x - startX
            val dy = y - startY
            val dist = sqrt(dx * dx + dy * dy)
            if (dist > 1.5) {
                return false // Distance safety limit
            }
        } else {
            val diff = wrapAngle(heading - lastHeading)
            accumulatedHeadingChange += kotlin.math.abs(diff)
            lastHeading = heading
            if (accumulatedHeadingChange > 4.0 * kotlin.math.PI) {
                return false // Rotation safety limit (2 full rotations)
            }
        }
        return true
    }

    /**
     * Updates numerical differentiation/integration state and computes current target output voltage.
     *
     * @param timestampMs Current timestamp in milliseconds ($ms$).
     * @param velocity Current measured velocity ($m/s$ for linear, $rad/s$ for angular).
     * @return Commanded target motor voltage in Volts ($V$), clamped to $[-12.0\text{V}, +12.0\text{V}]$.
     */
    fun update(timestampMs: Long, velocity: Double): Double {
        if (!isActive()) return 0.0
        
        val elapsedSec = (timestampMs - startTimeMs) / 1000.0
        val dt = (timestampMs - lastTimeMs) / 1000.0
        
        // Calculate acceleration and integrate position
        if (dt > 1e-4) {
            accumulatedPosition += velocity * dt
            calculatedAcceleration = (velocity - lastVelocity) / dt
        }
        lastTimeMs = timestampMs
        lastVelocity = velocity

        currentVoltage = when (activeRoutine) {
            SysIdRoutine.QUASISTATIC -> {
                if (elapsedSec < 2.5) {
                    1.2 * elapsedSec
                } else {
                    -1.2 * (elapsedSec - 2.5)
                }
            }
            SysIdRoutine.DYNAMIC -> {
                if (elapsedSec < 1.5) {
                    3.0
                } else {
                    -3.0
                }
            }
            else -> 0.0
        }
        
        // Clamp voltage to battery limits
        if (currentVoltage > 12.0) currentVoltage = 12.0
        if (currentVoltage < -12.0) currentVoltage = -12.0
        
        return currentVoltage
    }
}
