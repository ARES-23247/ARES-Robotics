package com.areslib.input

import kotlin.math.abs

enum class AnalogEmissionPolicy {
    /** Invoke the value callback on every connected update. */
    EVERY_UPDATE,

    /** Invoke only when output changes by at least [AnalogBinding.changeEpsilon]. */
    ON_CHANGE,
}

interface AnalogBindingListener {
    fun onValue(value: Double) = Unit
}

interface AnalogZoneListener {
    fun onEnter(value: Double) = Unit
    fun onActive(value: Double) = Unit
    fun onExit(value: Double) = Unit
}

/**
 * Inclusive analog range with exit hysteresis.
 *
 * An inactive zone enters inside `[minimum, maximum]`. Once active it remains active until the
 * value leaves `[minimum - hysteresis, maximum + hysteresis]`.
 */
data class AnalogZone(
    val id: String,
    val minimum: Double,
    val maximum: Double,
    val hysteresis: Double = 0.0,
    val listener: AnalogZoneListener,
) {
    init {
        require(id.isNotBlank()) { "zone id must not be blank" }
        require(minimum.isFinite() && maximum.isFinite() && minimum <= maximum) {
            "zone must have finite minimum <= maximum"
        }
        require(hysteresis.isFinite() && hysteresis >= 0.0) {
            "zone hysteresis must be finite and non-negative"
        }
    }
}

/**
 * Stateful analog processing with calibration, response shaping, slew limiting, and zones.
 *
 * All mutable storage is allocated during construction. After a disconnect, zero is emitted and
 * the axis must pass through [rearmNeutralThreshold] before it can produce output again. This
 * prevents a held trigger or deflected stick from restarting a mechanism when the controller
 * reconnects.
 */
class AnalogBinding(
    val axisIndex: Int,
    val transform: AxisTransform = AxisTransform(),
    val listener: AnalogBindingListener,
    zones: List<AnalogZone> = emptyList(),
    val emissionPolicy: AnalogEmissionPolicy = AnalogEmissionPolicy.EVERY_UPDATE,
    val changeEpsilon: Double = 1e-6,
    val riseRatePerSecond: Double = Double.POSITIVE_INFINITY,
    val fallRatePerSecond: Double = Double.POSITIVE_INFINITY,
    val rearmNeutralThreshold: Double = 0.05,
) {
    private val zoneArray = zones.toTypedArray()
    private val zoneActive = BooleanArray(zoneArray.size)
    private var hasOutput = false
    private var lastOutput = 0.0
    private var lastEmitted = 0.0
    private var lastUpdateNanos = 0L
    private var waitingForNeutral = false

    init {
        require(axisIndex >= 0) { "axisIndex must not be negative" }
        require(changeEpsilon.isFinite() && changeEpsilon >= 0.0) {
            "changeEpsilon must be finite and non-negative"
        }
        require(isPositiveRate(riseRatePerSecond)) { "rise rate must be positive" }
        require(isPositiveRate(fallRatePerSecond)) { "fall rate must be positive" }
        require(rearmNeutralThreshold.isFinite() && rearmNeutralThreshold >= 0.0) {
            "rearmNeutralThreshold must be finite and non-negative"
        }
        var outer = 0
        while (outer < zoneArray.size) {
            var inner = outer + 1
            while (inner < zoneArray.size) {
                require(zoneArray[outer].id != zoneArray[inner].id) {
                    "duplicate analog zone id '${zoneArray[outer].id}'"
                }
                inner++
            }
            outer++
        }
    }

    val value: Double get() = lastOutput

    fun update(frame: InputFrame, nowNanos: Long) {
        val target = transform.apply(frame.axis(axisIndex))

        if (waitingForNeutral) {
            if (abs(target) > rearmNeutralThreshold) return
            waitingForNeutral = false
            hasOutput = false
            lastOutput = 0.0
            lastEmitted = 0.0
            lastUpdateNanos = nowNanos
            return
        }

        val output = if (!hasOutput) {
            if (riseRatePerSecond.isInfinite() && fallRatePerSecond.isInfinite()) target else 0.0
        } else {
            applySlew(target, elapsedNanos(nowNanos, lastUpdateNanos))
        }
        hasOutput = true
        lastOutput = output
        lastUpdateNanos = nowNanos

        if (emissionPolicy == AnalogEmissionPolicy.EVERY_UPDATE || abs(output - lastEmitted) >= changeEpsilon) {
            listener.onValue(output)
            lastEmitted = output
        }
        updateZones(output)
    }

    private fun applySlew(target: Double, elapsedNanos: Long): Double {
        val delta = target - lastOutput
        if (delta == 0.0) return target
        val rate = if (delta > 0.0) riseRatePerSecond else fallRatePerSecond
        if (rate.isInfinite()) return target
        val maximumDelta = rate * (elapsedNanos.toDouble() / NANOS_PER_SECOND)
        return when {
            delta > maximumDelta -> lastOutput + maximumDelta
            delta < -maximumDelta -> lastOutput - maximumDelta
            else -> target
        }
    }

    private fun updateZones(output: Double) {
        var index = 0
        while (index < zoneArray.size) {
            val zone = zoneArray[index]
            val activeNow = if (zoneActive[index]) {
                output >= zone.minimum - zone.hysteresis && output <= zone.maximum + zone.hysteresis
            } else {
                output >= zone.minimum && output <= zone.maximum
            }
            if (activeNow && !zoneActive[index]) zone.listener.onEnter(output)
            if (!activeNow && zoneActive[index]) zone.listener.onExit(output)
            zoneActive[index] = activeNow
            if (activeNow) zone.listener.onActive(output)
            index++
        }
    }

    internal fun terminate(requireNeutral: Boolean) {
        var index = 0
        while (index < zoneArray.size) {
            if (zoneActive[index]) zoneArray[index].listener.onExit(lastOutput)
            zoneActive[index] = false
            index++
        }
        if (hasOutput) listener.onValue(0.0)
        hasOutput = false
        lastOutput = 0.0
        lastEmitted = 0.0
        lastUpdateNanos = 0L
        waitingForNeutral = requireNeutral
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000.0

        private fun isPositiveRate(rate: Double): Boolean =
            (rate.isFinite() && rate > 0.0) || rate == Double.POSITIVE_INFINITY
    }
}
