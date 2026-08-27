package com.areslib.control.assist

import com.areslib.math.wrapAngle
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * System Identification (SysId) Target Mechanism Type.
 *
 * Defines the physical degree of freedom under characterization.
 */
enum class SysIdMechanism {
    /** Linear mechanism (drivetrain translation) with velocity in $m/s$ and position in $m$. */
    LINEAR,
    /** Rotational mechanism (drivetrain rotation) with velocity in $rad/s$ and angle in $rad$. */
    ANGULAR,
    /** Flywheel mechanism with rotational velocity in $rad/s$ or $RPM$. */
    FLYWHEEL,
    /** Vertical linear elevator with position in $m$, velocity in $m/s$, and gravity bias $k_G$. */
    ELEVATOR,
    /** Rotating arm with angle in $rad$, angular velocity in $rad/s$, and gravity feedforward $k_G \cos\theta$. */
    ARM,
    /** Custom mechanism with arbitrary continuous single degree of freedom. */
    CUSTOM
}

/**
 * System Identification (SysId) Voltage Test Routine.
 *
 * Specifies the applied voltage profile pattern.
 */
enum class SysIdRoutine {
    /** Inactive routine state; 0.0V commanded. */
    NONE,
    /** Quasistatic test routine applying a linear voltage ramp $\dot{V} = 1.2\text{ V/s}$ to measure $k_S, k_V, k_G$. */
    QUASISTATIC,
    /** Dynamic step-response routine applying a fast voltage step to measure $k_A$. */
    DYNAMIC
}

/**
 * System Identification Routine Controller & Data Logging Manager.
 *
 * Generates calibrated voltage test profiles (Quasistatic ramp and Dynamic step test) for calculating
 * feedforward gains ($k_S, k_V, k_A, k_G$) while enforcing displacement, rotation, stall current, and duration safety boundaries.
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

    /** Maximum allowed motor continuous current before tripwire abort ($A$). */
    var maxCurrentAmps = 40.0

    /** Minimum allowable position travel boundary ($m$ or $rad$). */
    var minPosition = -Double.MAX_VALUE

    /** Maximum allowable position travel boundary ($m$ or $rad$). */
    var maxPosition = Double.MAX_VALUE

    /** Maximum allowed continuous stall duration at or above [maxCurrentAmps] ($ms$). */
    var stallTimeoutMs = 200L

    private var stallStartTimeMs = -1L
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
     * @param x Initial robot X position or mechanism position in meters or radians.
     * @param y Initial robot Y position in meters ($m$).
     * @param heading Initial robot heading in radians ($rad$).
     */
    fun start(
        mechanism: SysIdMechanism,
        routine: SysIdRoutine,
        timestampMs: Long,
        x: Double,
        y: Double = 0.0,
        heading: Double = 0.0
    ) {
        if (!x.isFinite() || !y.isFinite() || !heading.isFinite()) {
            stop()
            return
        }
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
        stallStartTimeMs = -1L
    }

    /**
     * Stops the active routine immediately and resets output voltage to 0.0V.
     */
    fun stop() {
        activeRoutine = SysIdRoutine.NONE
        currentVoltage = 0.0
        stallStartTimeMs = -1L
    }

    /**
     * Checks if a SysId characterization routine is currently active.
     *
     * @return `true` if active routine is not [SysIdRoutine.NONE]; `false` otherwise.
     */
    fun isActive(): Boolean = activeRoutine != SysIdRoutine.NONE

    /**
     * Evaluates safety limit constraints (time, displacement, rotation, stall current).
     *
     * @param x Current robot X position or mechanism position ($m$ or $rad$).
     * @param y Current robot Y position in meters ($m$).
     * @param heading Current robot heading in radians ($rad$).
     * @param currentAmps Current measured motor draw ($A$).
     * @param timestampMs Current loop timestamp in milliseconds ($ms$).
     * @return `true` if operation is within safe bounds; `false` if a safety threshold was exceeded (must abort).
     */
    fun checkSafety(
        x: Double,
        y: Double,
        heading: Double,
        timestampMs: Long,
        currentAmps: Double = 0.0
    ): Boolean {
        if (!isActive()) return true

        if (!x.isFinite() || !y.isFinite() || !heading.isFinite() || timestampMs < startTimeMs) {
            stop()
            return false
        }

        val elapsedSec = (timestampMs - startTimeMs) / 1000.0
        if (elapsedSec > 5.0) {
            stop()
            return false // Time safety limit
        }

        // Stall current watchdog
        if (currentAmps.isFinite() && currentAmps >= maxCurrentAmps) {
            if (stallStartTimeMs < 0L) {
                stallStartTimeMs = timestampMs
            } else if (timestampMs - stallStartTimeMs >= stallTimeoutMs) {
                stop()
                return false // Stall current safety tripwire
            }
        } else {
            stallStartTimeMs = -1L
        }

        // Soft-stop position boundary limits
        if (x < minPosition || x > maxPosition) {
            stop()
            return false // Position boundary limit exceeded
        }

        when (activeMechanism) {
            SysIdMechanism.FLYWHEEL -> return true
            SysIdMechanism.ELEVATOR -> {
                val travel = x - startX
                if (travel < -0.1 || travel > 1.5) {
                    stop()
                    return false
                }
            }
            SysIdMechanism.ARM -> {
                val angleTravel = abs(x - startX)
                if (angleTravel > 2.0 * Math.PI) {
                    stop()
                    return false
                }
            }
            SysIdMechanism.CUSTOM -> return true
            SysIdMechanism.LINEAR -> {
                val dx = x - startX
                val dy = y - startY
                val dist = sqrt(dx * dx + dy * dy)
                if (!dist.isFinite() || dist > 1.5) {
                    stop()
                    return false // Distance safety limit
                }
            }
            SysIdMechanism.ANGULAR -> {
                val diff = wrapAngle(heading - lastHeading)
                accumulatedHeadingChange += abs(diff)
                lastHeading = heading
                if (!accumulatedHeadingChange.isFinite() || accumulatedHeadingChange > 4.0 * Math.PI) {
                    stop()
                    return false // Rotation safety limit (2 full rotations)
                }
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

        if (!velocity.isFinite() || timestampMs < startTimeMs || timestampMs < lastTimeMs) {
            stop()
            return 0.0
        }

        val elapsedSec = (timestampMs - startTimeMs) / 1000.0
        if (elapsedSec > 5.0) {
            stop()
            return 0.0
        }
        val dt = (timestampMs - lastTimeMs) / 1000.0

        // Calculate acceleration and integrate position
        if (dt > 1e-4) {
            accumulatedPosition += velocity * dt
            calculatedAcceleration = (velocity - lastVelocity) / dt
        }
        lastTimeMs = timestampMs
        lastVelocity = velocity

        val isUnidirectional = activeMechanism == SysIdMechanism.FLYWHEEL || activeMechanism == SysIdMechanism.ELEVATOR

        currentVoltage = when (activeRoutine) {
            SysIdRoutine.QUASISTATIC -> {
                if (isUnidirectional) {
                    1.2 * elapsedSec
                } else if (elapsedSec < 2.5) {
                    1.2 * elapsedSec
                } else {
                    -1.2 * (elapsedSec - 2.5)
                }
            }
            SysIdRoutine.DYNAMIC -> {
                if (isUnidirectional) {
                    if (elapsedSec < 2.5) 6.0 else 0.0
                } else if (elapsedSec < 1.5) {
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

