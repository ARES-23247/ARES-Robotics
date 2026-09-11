package com.areslib.pathing

import com.areslib.math.geometry.Translation2d
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.hypot
import kotlin.math.sqrt

internal const val SPLINE_CENTRIPETAL_ACCELERATION = 2.0
internal class SplineBoundedGrid(val positions: List<Double>, val edgeSpeedCeilings: DoubleArray)

/** Conservative regular-interval ceilings; singular boundary intervals retain pointwise limits. */
internal fun boundSplineIntervals(
    points: List<PathPlannerJsonParser.WaypointData>,
    original: List<Double>,
    stationary: List<SplineStationarySample>
): SplineBoundedGrid {
    val positions = ArrayList<Double>(original.size)
    positions.add(0.0)
    var ceilings = DoubleArray(original.size)
    ceilings[0] = Double.MAX_VALUE
    var planned = original.size
    val stops = stationary.filter { it.requiresStop }.mapTo(HashSet()) { it.relativePosition }
    data class Span(val lo: Double, val hi: Double)
    val pending = java.util.ArrayDeque<Span>()
    var nextOriginal = 1
    fun append(position: Double, ceiling: Double) {
        if (positions.size == ceilings.size) ceilings = ceilings.copyOf(minOf(MAX_TRAJECTORY_SAMPLES, maxOf(ceilings.size + 1, ceilings.size * 2)))
        ceilings[positions.size] = ceiling
        positions.add(position)
    }
    for (segment in 0 until points.size - 1) {
        val curve = SplineCurvatureBound(points[segment].anchor, points[segment].nextControl, points[segment + 1].prevControl, points[segment + 1].anchor)
        while (nextOriginal < original.size && original[nextOriginal] <= segment + 1.0) {
            val lo = original[nextOriginal - 1]
            val hi = original[nextOriginal++]
            if (curve.straight || lo in stops || hi in stops) {
                append(hi, Double.MAX_VALUE)
                continue
            }
            pending.addLast(Span(lo, hi))
            while (pending.isNotEmpty()) {
                val span = pending.removeLast()
                val ceiling = curve.speedCeiling(span.lo - segment, span.hi - segment)
                if (ceiling != null) append(span.hi, ceiling) else {
                    val middle = span.lo + (span.hi - span.lo) / 2.0
                    require(middle > span.lo && middle < span.hi) { "Regular spline derivative bound cannot be resolved in the sample grid" }
                    require(++planned <= MAX_TRAJECTORY_SAMPLES) { "Spline curvature refinement exceeds the $MAX_TRAJECTORY_SAMPLES-sample budget" }
                    pending.addLast(Span(middle, span.hi))
                    pending.addLast(Span(span.lo, middle))
                }
            }
        }
    }
    return SplineBoundedGrid(positions, if (ceilings.size == positions.size) ceilings else ceilings.copyOf(positions.size))
}

/** Bounds q=B'/3 and N=cross(q,q') with their exact quadratic Bernstein control ranges. */
internal class SplineCurvatureBound(p0: Translation2d, p1: Translation2d, p2: Translation2d, p3: Translation2d) {
    private val x = splineDerivativeCoefficients(p0, p1, p2, p3) { it.x }
    private val y = splineDerivativeCoefficients(p0, p1, p2, p3) { it.y }
    private val numerator = arrayOf(x[1] * y[0] - y[1] * x[0], TWO * (x[2] * y[0] - y[2] * x[0]), x[2] * y[1] - y[2] * x[1])
    val straight = numerator.all { it.signum() == 0 }

    private class Range(val start: BigDecimal, val end: BigDecimal, val low: BigDecimal, val high: BigDecimal)
    private fun range(coefficients: Array<BigDecimal>, lo: BigDecimal, hi: BigDecimal): Range {
        val a = coefficients[0]; val b = coefficients[1]; val c = coefficients[2]
        val start = (a * lo + b) * lo + c
        val end = (a * hi + b) * hi + c
        val control = start + (hi - lo) * (TWO * a * lo + b).divide(TWO)
        return Range(start, end, minOf(start, control, end), maxOf(start, control, end))
    }
    private fun axisLowerBound(range: Range): Double {
        val value = when {
            range.low.signum() > 0 -> range.low
            range.high.signum() < 0 -> -range.high
            else -> return 0.0
        }
        return maxOf(0.0, Math.nextDown(value.toDouble()))
    }

    /** Null requests subdivision. No finite interval claim is made for a singular endpoint. */
    fun speedCeiling(lo: Double, hi: Double): Double? {
        if (straight) return Double.MAX_VALUE
        val start = BigDecimal(lo); val end = BigDecimal(hi)
        val xr = range(x, start, end); val yr = range(y, start, end)
        if (xr.start.signum() == 0 && yr.start.signum() == 0 || xr.end.signum() == 0 && yr.end.signum() == 0) return Double.MAX_VALUE
        val lowerNorm = maxOf(0.0, Math.nextDown(Math.nextDown(hypot(axisLowerBound(xr), axisLowerBound(yr)))))
        if (lowerNorm == 0.0) return null
        val nr = range(numerator, start, end)
        val upperNumerator = maxOf(nr.low.abs(), nr.high.abs())
        // |k| <= Nmax/(3 L^3), so v^2 <= 3*a_c*L^3/Nmax. Keep the ratio in decimal
        // arithmetic to avoid losing either tiny numerators or squared speed range.
        val squaredCeiling = (BigDecimal(lowerNorm).pow(3) * ACCELERATION_FACTOR).divide(upperNumerator, LOWER_CONTEXT)
        return lowerSquareRoot(squaredCeiling)
    }
}

private val TWO = BigDecimal.valueOf(2)
private val ACCELERATION_FACTOR = BigDecimal(3.0 * SPLINE_CENTRIPETAL_ACCELERATION)
private val LOWER_CONTEXT = MathContext(34, RoundingMode.FLOOR)

private fun lowerSquareRoot(value: BigDecimal): Double {
    val exponent = value.precision() - value.scale() - 1
    val evenExponent = Math.floorDiv(exponent, 2) * 2
    val mantissa = Math.nextDown(value.scaleByPowerOfTen(-evenExponent).toDouble())
    val root = Math.nextDown(Math.nextDown(sqrt(mantissa)))
    val result = BigDecimal(root).scaleByPowerOfTen(evenExponent / 2).toDouble()
    return if (result.isInfinite()) Double.MAX_VALUE else maxOf(0.0, Math.nextDown(result))
}
