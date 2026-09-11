package com.areslib.pathing

import com.areslib.math.geometry.Translation2d
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.atan2

internal class SplineStationarySample(val relativePosition: Double, val headingRadians: Double, val requiresStop: Boolean)

/** Exact common-root classification for the quadratic derivatives of stored Double controls. */
internal fun splineStationarySamples(points: List<PathPlannerJsonParser.WaypointData>): List<SplineStationarySample> {
    val result = ArrayList<SplineStationarySample>()
    for (segment in 0 until points.size - 1) {
        val p = arrayOf(points[segment].anchor, points[segment].nextControl, points[segment + 1].prevControl, points[segment + 1].anchor)
        // A nonconstant monotone control coordinate has a strictly signed interior derivative.
        fun monotone(axis: (Translation2d) -> Double): Boolean {
            val a = axis(p[0]); val b = axis(p[1]); val c = axis(p[2]); val d = axis(p[3])
            return (a <= b && b <= c && c <= d && a < d) || (a >= b && b >= c && c >= d && a > d)
        }
        if (monotone { it.x } || monotone { it.y }) continue
        fun coefficients(axis: (Translation2d) -> Double): Array<BigDecimal> {
            val v = p.map { BigDecimal(axis(it)) }
            return arrayOf(v[3] - THREE * v[2] + THREE * v[1] - v[0], TWO * (v[2] - TWO * v[1] + v[0]), v[1] - v[0])
        }
        val x = coefficients { it.x }; val y = coefficients { it.y }
        val common = commonDerivative(x, y) ?: continue
        for ((root, repeated) in realRoots(common)) {
            if (root <= BigDecimal.ZERO || root >= BigDecimal.ONE) continue
            val position = segment + root.toDouble()
            require(position > segment && position < segment + 1.0) { "Interior spline stationary parameter is not representable in the sample grid" }
            val heading = if (repeated) atan2(y[0].toDouble(), x[0].toDouble()) else 0.0
            result.add(SplineStationarySample(position, heading, !repeated))
        }
    }
    return result.sortedBy { it.relativePosition }
}

private val TWO = BigDecimal.valueOf(2)
private val THREE = BigDecimal.valueOf(3)
private val FOUR = BigDecimal.valueOf(4)
private fun zero(v: BigDecimal) = v.signum() == 0
private fun constant(p: Array<BigDecimal>) = zero(p[0]) && zero(p[1])

private fun commonDerivative(x: Array<BigDecimal>, y: Array<BigDecimal>): Array<BigDecimal>? {
    if (x.all(::zero)) return y.takeUnless { constant(it) }
    if (y.all(::zero)) return x.takeUnless { constant(it) }
    if (constant(x) || constant(y)) return null
    if (zero(x[0])) return commonLinear(x, y)
    if (zero(y[0])) return commonLinear(y, x)
    val b = y[0] * x[1] - x[0] * y[1]
    val c = y[0] * x[2] - x[0] * y[2]
    if (zero(b)) return if (zero(c)) x else null
    return commonLinear(arrayOf(BigDecimal.ZERO, b, c), x)
}

private fun commonLinear(line: Array<BigDecimal>, other: Array<BigDecimal>): Array<BigDecimal>? {
    // Substitute t=-c/b without dividing, so a near miss cannot become a common root.
    val b = line[1]; val c = line[2]
    val residual = other[0] * c * c - other[1] * c * b + other[2] * b * b
    return if (zero(residual)) line else null
}

private fun realRoots(p: Array<BigDecimal>): List<Pair<BigDecimal, Boolean>> {
    // Retain tiny exact coefficient terms that can distinguish an interior root from t=1.
    val context = MathContext(maxOf(80, p.maxOf { it.precision() } + 32), RoundingMode.HALF_EVEN)
    val a = p[0]; val b = p[1]; val c = p[2]
    if (zero(a)) return listOf((-c).divide(b, context) to false)
    val discriminant = b * b - FOUR * a * c
    if (discriminant.signum() < 0) return emptyList()
    if (zero(discriminant)) return listOf((-b).divide(TWO * a, context) to true)
    val squareRoot = decimalSquareRoot(discriminant, context)
    val q = (-(b + if (b.signum() < 0) -squareRoot else squareRoot)).divide(TWO, context)
    return listOf(q.divide(a, context) to false, c.divide(q, context) to false)
}

private fun decimalSquareRoot(value: BigDecimal, context: MathContext): BigDecimal {
    val exponent = value.precision() - value.scale() - 1
    val evenExponent = Math.floorDiv(exponent, 2) * 2
    val mantissa = value.scaleByPowerOfTen(-evenExponent).toDouble()
    var estimate = BigDecimal.valueOf(kotlin.math.sqrt(mantissa)).scaleByPowerOfTen(evenExponent / 2)
    repeat(12) {
        val next = (estimate + value.divide(estimate, context)).divide(TWO, context)
        if (next.compareTo(estimate) == 0) return next
        estimate = next
    }
    return estimate
}

/** Allocation-free monotone lookup after all critical positions have been merged into the grid. */
internal class SplineStationaryCursor(private val samples: List<SplineStationarySample>) {
    private var next = 0
    fun at(position: Double): SplineStationarySample? {
        while (next < samples.size && samples[next].relativePosition < position) next++
        if (next == samples.size || samples[next].relativePosition != position) return null
        var result = samples[next++]
        while (next < samples.size && samples[next].relativePosition == position) {
            val other = samples[next++]
            if (other.requiresStop) result = other
        }
        return result
    }
}
