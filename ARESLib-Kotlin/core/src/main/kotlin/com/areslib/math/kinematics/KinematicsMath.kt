package com.areslib.math.kinematics

import kotlin.math.sqrt
import kotlin.math.abs
import com.areslib.math.productRoundoff

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
     * Returns a nonnegative speed magnitude; this equation does not determine direction.
     * Invalid inputs, nonpositive squared speed, or a result outside the finite Double range
     * return zero. Scaled arithmetic preserves representable results when intermediate squares
     * overflow or underflow, and retains small positive residuals when braking terms cancel.
     *
     * @return Final speed magnitude in meters per second ($m/s$).
     */
    fun finalVelocity(initialVelocity: Double, acceleration: Double, distance: Double): Double {
        if (!initialVelocity.isFinite() || !acceleration.isFinite() || !distance.isFinite()) return 0.0
        if (acceleration == 0.0 || distance == 0.0) return abs(initialVelocity)
        val velocitySquared = initialVelocity * initialVelocity
        val contribution = 2.0 * acceleration * distance
        val direct = velocitySquared + contribution
        // Ordinary accelerating profile points need only the original arithmetic and sqrt.
        if (contribution >= java.lang.Double.MIN_NORMAL && direct.isFinite() &&
            (initialVelocity == 0.0 || velocitySquared >= java.lang.Double.MIN_NORMAL)) return sqrt(direct)

        val accelerationExponent = Math.getExponent(acceleration)
        val productExponent = accelerationExponent + Math.getExponent(distance) + 1
        val scaleExponent = maxOf(Math.getExponent(initialVelocity), Math.floorDiv(productExponent, 2))
        val v = Math.scalb(initialVelocity, -scaleExponent)
        val a = Math.scalb(acceleration, -accelerationExponent)
        val d = Math.scalb(distance, accelerationExponent + 1 - 2 * scaleExponent)
        val vv = v * v
        val ad = a * d
        val sum = vv + ad
        val sumError = if (vv >= abs(ad)) (vv - sum) + ad else (ad - sum) + vv
        val squared = sum + (sumError + productRoundoff(v, v, vv) + productRoundoff(a, d, ad))
        if (squared <= 0.0) return 0.0
        val result = Math.scalb(sqrt(squared), scaleExponent)
        return if (result.isFinite()) result else 0.0
    }
}

