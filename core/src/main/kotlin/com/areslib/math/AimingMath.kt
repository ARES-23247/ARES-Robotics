package com.areslib.math

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Lead-shot target aiming and field-relative velocity compensation calculator.
 *
 * Solves the quadratic time-of-flight equation for projectile launch vectors from a moving robot
 * to hit a stationary target coordinate (e.g. goal basket or target zone).
 *
 * ### Mathematical Formulation:
 * Given relative displacement $\mathbf{d} = \mathbf{p}_{\text{target}} - \mathbf{p}_{\text{robot}}$
 * and robot velocity $\mathbf{v}_{\text{robot}} = [v_x, v_y]^T$, the launch vector $\mathbf{v}_{\text{launch}}$
 * with speed $v_{\text{shot}} = \|\mathbf{v}_{\text{launch}}\|$ satisfies:
 * $$\mathbf{v}_{\text{launch}} t + \mathbf{v}_{\text{robot}} t = \mathbf{d}$$
 * $$\|\mathbf{v}_{\text{launch}}\|^2 t^2 = \|\mathbf{d} - \mathbf{v}_{\text{robot}} t\|^2$$
 *
 * Expanding into standard quadratic form $a t^2 + b t + c = 0$:
 * $$a = v_{\text{shot}}^2 - \|\mathbf{v}_{\text{robot}}\|^2$$
 * $$b = 2 (\mathbf{d} \cdot \mathbf{v}_{\text{robot}}) = 2 (d_x v_x + d_y v_y)$$
 * $$c = -\|\mathbf{d}\|^2 = -(d_x^2 + d_y^2)$$
 *
 * Solving for positive time of flight $t$:
 * $$t = \frac{-b + \sqrt{b^2 - 4ac}}{2a}$$
 *
 * The compensated launch velocity components and robot heading $\theta$ are:
 * $$\mathbf{v}_{\text{launch}} = \frac{\mathbf{d}}{t} - \mathbf{v}_{\text{robot}}$$
 * $$\theta = \text{atan2}(v_{\text{launch}, y}, v_{\text{launch}, x})$$
 *
 * ### Physical Units & Coordinate Conventions:
 * - Position $(x, y)$: Field-centric meters ($m$)
 * - Velocity $(v_x, v_y, v_{\text{shot}})$: Field-centric meters per second ($m/s$)
 * - Time $(t)$: Seconds ($s$)
 * - Heading $(\theta)$: Radians ($rad$), **CCW-positive** ($0 = +X$, $\frac{\pi}{2} = +Y$)
 *
 * ### Zero-GC Guarantee:
 * Uses primitive scalar arithmetic and zero object allocations, making it 100% zero-GC safe for high-frequency control loops.
 */
object AimingMath {

    /**
     * Calculates the required field-relative robot heading to launch a projectile at [shotSpeed]
     * and hit [targetX], [targetY] while moving at field velocity [vx], [vy].
     *
     * @param robotX Current field-centric X position of the robot in meters ($m$).
     * @param robotY Current field-centric Y position of the robot in meters ($m$).
     * @param vx Field-relative X velocity of the robot in meters per second ($m/s$).
     * @param vy Field-relative Y velocity of the robot in meters per second ($m/s$).
     * @param targetX Target X coordinate in meters ($m$).
     * @param targetY Target Y coordinate in meters ($m$).
     * @param shotSpeed Exit velocity magnitude of the projectile relative to the robot shooter in meters per second ($m/s$).
     * @return Compensated target heading in radians ($rad$), CCW-positive ($0 = +X$, $\frac{\pi}{2} = +Y$).
     *         Returns uncompensated geometric angle $\text{atan2}(d_y, d_x)$ if no real non-negative solution exists.
     */
    fun calculateCompensatedHeading(
        robotX: Double,
        robotY: Double,
        vx: Double,
        vy: Double,
        targetX: Double,
        targetY: Double,
        shotSpeed: Double
    ): Double {
        val dx = targetX - robotX
        val dy = targetY - robotY
        if (!dx.isFinite() || !dy.isFinite()) return 0.0
        val geometricHeading = atan2(dy, dx)
        if (!vx.isFinite() || !vy.isFinite() || !shotSpeed.isFinite() || shotSpeed <= 0.0) {
            return geometricHeading
        }
        val distanceSq = dx * dx + dy * dy
        val robotSpeedSq = vx * vx + vy * vy
        
        // Dot product of distance vector and robot velocity vector
        val p = dx * vx + dy * vy
        
        val a = shotSpeed * shotSpeed - robotSpeedSq
        val b = 2.0 * p
        val c = -distanceSq
        
        val discriminant = b * b - 4.0 * a * c
        if (discriminant < 0.0 || kotlin.math.abs(a) < 1e-6) {
            if (kotlin.math.abs(a) < 1e-6 && kotlin.math.abs(b) >= 1e-6) {
                val tLinear = -c / b
                if (tLinear.isFinite() && tLinear > 0.0) {
                    val launchVx = (dx / tLinear) - vx
                    val launchVy = (dy / tLinear) - vy
                    return atan2(launchVy, launchVx)
                }
            }
            // Fallback: Geometric aiming (no compensation) if no real solution
            return geometricHeading
        }
        
        // Solve for time of flight (positive root)
        val t = (-b + sqrt(discriminant)) / (2.0 * a)
        if (t <= 0.0) {
            return geometricHeading
        }
        
        // Calculate the compensated launch vector in the robot frame
        val launchVx = (dx / t) - vx
        val launchVy = (dy / t) - vy
        
        return atan2(launchVy, launchVx)
    }
}
