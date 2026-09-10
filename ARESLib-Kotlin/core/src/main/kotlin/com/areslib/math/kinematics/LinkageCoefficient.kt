package com.areslib.math.kinematics

import kotlin.math.abs

/** Cold, immutable coefficient retaining exponents beyond Double's final-result range. */
internal class LinkageCoefficient private constructor(private val fraction: Double, private val exponent: Int) {
    val value: Double = Math.scalb(fraction, exponent)

    fun times(weight: Double): Double {
        if (!weight.isFinite()) return Double.NaN
        if (weight == 0.0 || fraction == 0.0) return fraction * weight
        val direct = value * weight
        if (value.isFinite() && abs(value) >= java.lang.Double.MIN_NORMAL &&
            direct.isFinite() && abs(direct) >= java.lang.Double.MIN_NORMAL) return direct
        val e = normalExponent(weight)
        return Math.scalb(fraction * Math.scalb(weight, -e), exponent + e)
    }

    companion object {
        fun product(a: Double, b: Double, c: Double = 1.0, d: Double = 1.0): LinkageCoefficient {
            require(a.isFinite() && b.isFinite() && c.isFinite() && d.isFinite())
            if (a == 0.0 || b == 0.0 || c == 0.0 || d == 0.0) return LinkageCoefficient(0.0, 0)
            val ea = normalExponent(a)
            val eb = normalExponent(b)
            val ec = normalExponent(c)
            val ed = normalExponent(d)
            val f = Math.scalb(a, -ea) * Math.scalb(b, -eb) * Math.scalb(c, -ec) * Math.scalb(d, -ed)
            val ef = normalExponent(f)
            return LinkageCoefficient(Math.scalb(f, -ef), ea + eb + ec + ed + ef)
        }

        /** Add largest components first so a small residual survives opposing large torques. */
        fun sum(a: LinkageCoefficient, aw: Double, b: LinkageCoefficient, bw: Double,
                c: LinkageCoefficient, cw: Double): Double {
            if (!aw.isFinite() || !bw.isFinite() || !cw.isFinite()) return Double.NaN
            var x = a.times(aw)
            var y = b.times(bw)
            var z = c.times(cw)
            if (x.isFinite() && y.isFinite() && z.isFinite() &&
                ((x == 0.0 && (aw == 0.0 || a.fraction == 0.0)) || abs(x) >= java.lang.Double.MIN_NORMAL) &&
                ((y == 0.0 && (bw == 0.0 || b.fraction == 0.0)) || abs(y) >= java.lang.Double.MIN_NORMAL) &&
                ((z == 0.0 && (cw == 0.0 || c.fraction == 0.0)) || abs(z) >= java.lang.Double.MIN_NORMAL)) {
                if (abs(x) < abs(y)) { val swap = x; x = y; y = swap }
                if (abs(y) < abs(z)) { val swap = y; y = z; z = swap }
                if (abs(x) < abs(y)) { val swap = x; x = y; y = swap }
                val pair = x + y
                val total = pair + z
                if (pair.isFinite() && total.isFinite()) {
                    val error = (x - pair) + y
                    val tailError = if (abs(pair) >= abs(z)) (pair - total) + z else (z - total) + pair
                    return total + (error + tailError)
                }
            }
            // Keep weighted terms scaled until cancellation has occurred. No temporary
            // arrays or arbitrary-precision objects are needed in periodic paths.
            var ex = a.exponent + normalExponent(aw)
            var ey = b.exponent + normalExponent(bw)
            var ez = c.exponent + normalExponent(cw)
            x = a.fraction * Math.scalb(aw, -normalExponent(aw))
            y = b.fraction * Math.scalb(bw, -normalExponent(bw))
            z = c.fraction * Math.scalb(cw, -normalExponent(cw))
            if (x == 0.0) ex = -100_000
            if (y == 0.0) ey = -100_000
            if (z == 0.0) ez = -100_000
            if (ex < ey) { val f = x; x = y; y = f; val e = ex; ex = ey; ey = e }
            if (ey < ez) { val f = y; y = z; z = f; val e = ey; ey = ez; ez = e }
            if (ex < ey) { val f = x; x = y; y = f; val e = ex; ex = ey; ey = e }
            val pair = x + Math.scalb(y, ey - ex)
            if (pair == 0.0) return Math.scalb(z, ez)
            val ep = normalExponent(pair)
            val pairExponent = ex + ep
            val common = maxOf(pairExponent, ez)
            return Math.scalb(Math.scalb(pair, -ep) * Math.scalb(1.0, pairExponent - common) +
                Math.scalb(z, ez - common), common)
        }

        private fun normalExponent(value: Double): Int {
            val e = Math.getExponent(value)
            return if (e == -1023 && value != 0.0) Math.getExponent(Math.scalb(value, 54)) - 54 else e
        }
    }
}

/** Preserve finite individual angles when their sum exceeds Double's range. */
internal fun linkageCosSum(a: Double, b: Double): Double {
    val sum = a + b
    return if (sum.isFinite()) kotlin.math.cos(sum)
        else kotlin.math.cos(a) * kotlin.math.cos(b) - kotlin.math.sin(a) * kotlin.math.sin(b)
}
