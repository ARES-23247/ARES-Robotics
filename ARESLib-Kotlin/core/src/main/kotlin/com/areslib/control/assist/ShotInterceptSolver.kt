package com.areslib.control.assist

import kotlin.math.*

/** Solves every linear TOF segment and both clamped tails; selects the earliest valid intercept. */
internal object ShotInterceptSolver {
    fun flightTime(rx: Double, ry: Double, vx: Double, vy: Double,
                   keys: DoubleArray, times: DoubleArray): Double {
        var best = Double.POSITIVE_INFINITY
        val firstDistance = hypot(rx - vx * times[0], ry - vy * times[0])
        if (firstDistance.isFinite() && firstDistance <= keys[0]) best = times[0]
        val last = keys.lastIndex
        val lastDistance = hypot(rx - vx * times[last], ry - vy * times[last])
        if (lastDistance.isFinite() && lastDistance >= keys[last]) best = min(best, times[last])
        for (i in 0 until last) {
            val lo = keys[i]
            val hi = keys[i + 1]
            val slope = (times[i + 1] - times[i]) / (hi - lo)
            val intercept = times[i] - slope * lo
            val px = rx - vx * intercept
            val py = ry - vy * intercept
            val length = hypot(px, py)
            val qx = vx * slope
            val qy = vy * slope
            if (!length.isFinite() || !qx.isFinite() || !qy.isFinite()) continue
            // d = |p - q*d|. Normalize distance by |p| and coefficients by max(1,|q|)^2.
            val scale = max(1.0, hypot(qx, qy))
            val inverseScale = 1.0 / scale
            val nx = qx / scale
            val ny = qy / scale
            val a = inverseScale * inverseScale - nx * nx - ny * ny
            if (length == 0.0) {
                best = min(best, candidate(lo, lo, hi, times[i], slope, rx, ry, vx, vy))
                best = min(best, candidate(hi, lo, hi, times[i], slope, rx, ry, vx, vy))
                continue
            }
            val b = 2.0 * ((px / length) * nx + (py / length) * ny) * inverseScale
            val c = -inverseScale * inverseScale
            if (a == 0.0) {
                if (b != 0.0) best = min(best, candidate((-c / b) * length,
                    lo, hi, times[i], slope, rx, ry, vx, vy))
                continue
            }
            val discriminant = b * b - 4.0 * a * c
            if (discriminant < 0.0 || !discriminant.isFinite()) continue
            // Stable quadratic roots avoid cancellation in the smaller root.
            val rootTerm = -0.5 * (b + Math.copySign(sqrt(discriminant), b))
            best = min(best, candidate((rootTerm / a) * length,
                lo, hi, times[i], slope, rx, ry, vx, vy))
            if (rootTerm != 0.0) best = min(best, candidate((c / rootTerm) * length,
                lo, hi, times[i], slope, rx, ry, vx, vy))
        }
        return if (best.isFinite()) best else Double.NaN
    }

    private fun candidate(distance: Double, lo: Double, hi: Double, timeAtLo: Double, slope: Double,
                          rx: Double, ry: Double, vx: Double, vy: Double): Double {
        if (!distance.isFinite() || distance < 0.0) return Double.POSITIVE_INFINITY
        val tolerance = 1e-10 * max(1.0, distance)
        if (distance < lo - tolerance || distance > hi + tolerance) return Double.POSITIVE_INFINITY
        val bounded = distance.coerceIn(lo, hi)
        val time = timeAtLo + slope * (bounded - lo)
        if (!time.isFinite() || time < 0.0) return Double.POSITIVE_INFINITY
        val actual = hypot(rx - vx * time, ry - vy * time)
        return if (actual.isFinite() && abs(actual - bounded) <= tolerance) time else Double.POSITIVE_INFINITY
    }
}
