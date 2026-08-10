package com.areslib.control.feedback

import com.areslib.control.profile.TrapezoidProfile

/**
 * Closed-Loop Profiled Feedback Controller Coupling [PIDController] and 1D [TrapezoidProfile].
 *
 * Enforces smooth velocity ($v_{max}$) and acceleration ($a_{max}$) kinematic constraints on mechanism setpoint motion
 * to minimize mechanical jerk, motor current spikes, and trajectory overshoots.
 *
 * ### Control Theory & Mathematics:
 * 1. **Trapezoidal Profile Reference Step**:
 *    $$\mathbf{x}_{prof}(k) = \text{TrapezoidProfile}\left(\mathbf{x}_{prof}(k-1), \mathbf{x}_{goal}, v_{max}, a_{max}, \Delta t\right)$$
 * 2. **PID Feedback Evaluation against Profile Reference**:
 *    $$u(k) = \text{PID}\left(y(k), x_{prof}(k), \Delta t\right)$$
 *
 * ### Physical Units & Properties:
 * - Position: Meters ($m$) or Radians ($rad$)
 * - Velocity ($v_{max}$): Meters per second ($m/s$) or Radians per second ($rad/s$)
 * - Acceleration ($a_{max}$): Meters per second squared ($m/s^2$) or Radians per second squared ($rad/s^2$)
 * - Control Output ($u$): Motor Voltage ($V$) or normalized duty cycle ($-1.0 \dots +1.0$)
 * - Timestep ($\Delta t$): Seconds ($s$)
 *
 * ### Zero-GC Compliance:
 * Uses pre-allocated [TrapezoidProfile.State] instances (`currentState`, `targetState`, `nextTargetState`) to execute with zero heap allocations during 50Hz–1000Hz loops.
 *
 * @property p Proportional gain coefficient $K_p$.
 * @property i Integral gain coefficient $K_i$.
 * @property d Derivative gain coefficient $K_d$.
 * @property constraints Physical kinematic maximum velocity ($v_{max}$) and acceleration ($a_{max}$) bounds [TrapezoidProfile.Constraints].
 *
 * @see PIDController
 * @see TrapezoidProfile
 */
class ProfiledPIDController(
    p: Double,
    i: Double,
    d: Double,
    var constraints: TrapezoidProfile.Constraints
) {
    /** Underlying un-profiled feedback controller instance. */
    val pidController = PIDController(p, i, d)

    var p: Double = p
        set(value) {
            field = value
            pidController.p = value
        }

    var i: Double = i
        set(value) {
            field = value
            pidController.i = value
        }

    var d: Double = d
        set(value) {
            field = value
            pidController.d = value
        }

    /** Underlying 1D motion profile generator. */
    val profile = TrapezoidProfile()

    /** Pre-allocated current motion profile state buffer $(x, v)$. */
    val currentState = TrapezoidProfile.State()

    /** Pre-allocated target goal state buffer $(x_{goal}, v_{goal})$. */
    val targetState = TrapezoidProfile.State()

    /** Pre-allocated next step profile state buffer. */
    val nextTargetState = TrapezoidProfile.State()

    /**
     * Resets internal PID feedback accumulators and sets the initial measured state of the mechanism.
     *
     * @param position Current measured physical position ($m$ or $rad$).
     * @param velocity Current measured physical velocity ($m/s$ or $rad/s$, default: $0.0$).
     */
    fun reset(position: Double, velocity: Double = 0.0) {
        pidController.reset()
        currentState.position = position
        currentState.velocity = velocity
        targetState.position = position
        targetState.velocity = velocity
    }

    /**
     * Sets the overall target goal state for the profiled controller sequence.
     *
     * @param goalPosition Target final position ($m$ or $rad$).
     * @param goalVelocity Target final velocity ($m/s$ or $rad/s$, default: $0.0$).
     */
    fun setGoal(goalPosition: Double, goalVelocity: Double = 0.0) {
        targetState.position = goalPosition
        targetState.velocity = goalVelocity
    }

    /**
     * Configures control effort output saturation bounds $[u_{min}, u_{max}]$ on the internal PID controller.
     *
     * @param min Lower allowable control output bound.
     * @param max Upper allowable control output bound.
     */
    fun setOutputLimits(min: Double, max: Double) {
        pidController.setOutputLimits(min, max)
    }

    /**
     * Configures anti-windup bounds $[I_{min}, I_{max}]$ on the internal PID controller.
     *
     * @param min Lower allowable integral sum bound.
     * @param max Upper allowable integral sum bound.
     */
    fun setIntegratorRange(min: Double, max: Double) {
        pidController.setIntegratorRange(min, max)
    }

    /**
     * Calculates the next control effort $u(k)$, advancing the trapezoidal motion profile reference step.
     *
     * @param measurement Measured physical position of the mechanism ($m$ or $rad$).
     * @param dtSeconds Timestep duration in seconds ($\Delta t > 0$).
     * @return Computed control effort output $u(k)$ driving towards the profiled setpoint.
     */
    fun calculate(measurement: Double, dtSeconds: Double): Double {
        // Calculate the next step of the profile reference towards the targetState
        profile.calculate(dtSeconds, currentState, targetState, constraints, nextTargetState)
        
        // Update current profile state to the computed next state
        currentState.setTo(nextTargetState)

        // Run the feedback controller relative to the profiled reference position
        pidController.setSetpoint(currentState.position)
        
        return pidController.calculate(measurement, dtSeconds)
    }
}
