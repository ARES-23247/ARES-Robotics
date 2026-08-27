package com.areslib.math.kinematics

import kotlin.math.sqrt

/**
 * 1D Kinematic Equations Solver.
 *
 * Computes constant-acceleration physical motion parameters.
 *
 * ### Mathematical Formulation:
 * 1D Velocity-Displacement Kinematic Equation:
 * $$v_f^2 = v_i^2 + 2 a d \implies v_f = \sqrt{\max(0, v_i^2 + 2 a d)}$$
 *
 * ### Physical Units:
 * - Velocity ($v_i, v_f$): Meters per second ($m/s$)
 * - Acceleration ($a$): Meters per second squared ($m/s^2$)
 * - Distance ($d$): Meters ($m$)
 *
 * ### Zero-GC Guarantee:
 * $O(1)$ primitive scalar execution with zero object allocations.
 */
object KinematicsMath {
    
    /**
     * Calculates the final velocity $v_f$ given initial velocity $v_i$, constant acceleration $a$, and distance $d$.
     *
     * @param initialVelocity Starting velocity $v_i$ in meters per second ($m/s$).
     * @param acceleration Constant acceleration $a$ in meters per second squared ($m/s^2$).
     * @param distance Distance traveled $d$ in meters ($m$).
     * @return Final velocity $v_f$ in meters per second ($m/s$). Returns $0.0$ if $v_i^2 + 2ad \le 0$.
     */
    fun finalVelocity(initialVelocity: Double, acceleration: Double, distance: Double): Double {
        val v2 = initialVelocity * initialVelocity + 2.0 * acceleration * distance
        return if (v2 <= 0.0) 0.0 else sqrt(v2)
    }
}

