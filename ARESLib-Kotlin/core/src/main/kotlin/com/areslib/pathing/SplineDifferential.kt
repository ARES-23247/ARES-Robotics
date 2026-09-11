package com.areslib.pathing

import com.areslib.math.geometry.Translation2d
import kotlin.math.atan2
import kotlin.math.hypot

/** Cached cubic control differences; evaluation reuses primitive output fields. */
internal class SplineDifferential(p0: Translation2d, p1: Translation2d, p2: Translation2d, p3: Translation2d) {
    private val ax = p1.x - p0.x
    private val ay = p1.y - p0.y
    private val bx = p2.x - p1.x
    private val by = p2.y - p1.y
    private val cx = p3.x - p2.x
    private val cy = p3.y - p2.y
    private val jx = 6.0 * (cx - 2.0 * bx + ax)
    private val jy = 6.0 * (cy - 2.0 * by + ay)

    var headingRadians = 0.0
        private set
    var curvature = 0.0
        private set
    var requiresStop = false
        private set

    fun evaluate(t: Double) {
        val u = 1.0 - t
        val dx = 3.0 * u * u * ax + 6.0 * u * t * bx + 3.0 * t * t * cx
        val dy = 3.0 * u * u * ay + 6.0 * u * t * by + 3.0 * t * t * cy
        val ddx = 6.0 * (u * (bx - ax) + t * (cx - bx))
        val ddy = 6.0 * (u * (by - ay) + t * (cy - by))
        val speed = hypot(dx, dy)
        requiresStop = false
        if (speed == 0.0) {
            curvature = 0.0 // No finite curvature is available at a singular sample; it must stop.
            val acceleration = hypot(ddx, ddy)
            val jerk = hypot(jx, jy)
            if (acceleration == 0.0) {
                // A cubic with B'=B''=0 follows its B''' direction on both sides.
                headingRadians = atan2(jy, jx)
                requiresStop = jerk == 0.0
            } else if (t == 0.0 || t == 1.0) {
                val sign = if (t == 0.0) 1.0 else -1.0
                headingRadians = atan2(sign * ddy, sign * ddx)
                // Noncollinear second/third derivatives give divergent one-sided curvature.
                requiresStop = jerk > 0.0 &&
                    (ddx / acceleration * (jy / jerk) - ddy / acceleration * (jx / jerk)) != 0.0
            } else {
                headingRadians = 0.0 // A reversal has no single two-sided tangent.
                requiresStop = true
            }
            return
        }
        headingRadians = atan2(dy, dx)
        val acceleration = hypot(ddx, ddy)
        if (acceleration == 0.0) {
            curvature = 0.0
            return
        }
        val cross = dx / speed * (ddy / acceleration) - dy / speed * (ddx / acceleration)
        if (cross == 0.0) {
            curvature = 0.0
            return
        }
        // k = cross(unit B', unit B'') * |B''| / |B'|^2. Binary scaling avoids
        // overflowing/underflowing the intermediate squared norm for tiny geometry.
        val crossExponent = Math.getExponent(cross)
        val accelerationExponent = Math.getExponent(acceleration)
        val speedExponent = Math.getExponent(speed)
        val normalizedSpeed = Math.scalb(speed, -speedExponent)
        val normalized = Math.scalb(cross, -crossExponent) * Math.scalb(acceleration, -accelerationExponent) /
            (normalizedSpeed * normalizedSpeed)
        curvature = Math.scalb(normalized, crossExponent + accelerationExponent - 2 * speedExponent)
        require(curvature.isFinite() && curvature != 0.0) { "Nonzero spline curvature is not representable as a finite nonzero path sample" }
    }
}
