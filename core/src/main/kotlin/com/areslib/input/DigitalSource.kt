package com.areslib.input

/**
 * Allocation-free digital signal sampled by a [DigitalBinding].
 *
 * Sources are stateful when necessary (axis hysteresis and chord timing), so a source instance
 * should belong to one binding. Sharing a raw source is harmless, but sharing stateful sources can
 * make lifecycle resets surprising.
 */
interface DigitalSource {
    fun sample(frame: InputFrame, nowNanos: Long): Boolean

    /** Clears source history after disconnect, disable, or time rewind. */
    fun reset()
}

/** One raw HID button. */
class RawButtonSource(val buttonIndex: Int) : DigitalSource {
    init {
        require(buttonIndex >= 0) { "buttonIndex must not be negative" }
    }

    override fun sample(frame: InputFrame, nowNanos: Long): Boolean = frame.button(buttonIndex)

    override fun reset() = Unit
}

enum class ThresholdDirection { ABOVE, BELOW }

/**
 * Converts an analog axis into a stable digital signal with separate press/release thresholds.
 *
 * For [ThresholdDirection.ABOVE], `pressThreshold > releaseThreshold`; for
 * [ThresholdDirection.BELOW], the relationship is reversed. The gap is hysteresis and prevents
 * noisy trigger values from rapidly pressing and releasing near one threshold.
 */
class AxisThresholdSource(
    val axisIndex: Int,
    val pressThreshold: Double,
    val releaseThreshold: Double,
    val direction: ThresholdDirection = ThresholdDirection.ABOVE,
    val transform: AxisTransform = AxisTransform(),
) : DigitalSource {
    private var active = false

    init {
        require(axisIndex >= 0) { "axisIndex must not be negative" }
        require(pressThreshold.isFinite() && releaseThreshold.isFinite()) {
            "thresholds must be finite"
        }
        when (direction) {
            ThresholdDirection.ABOVE -> require(pressThreshold > releaseThreshold) {
                "ABOVE threshold requires pressThreshold > releaseThreshold"
            }
            ThresholdDirection.BELOW -> require(pressThreshold < releaseThreshold) {
                "BELOW threshold requires pressThreshold < releaseThreshold"
            }
        }
    }

    override fun sample(frame: InputFrame, nowNanos: Long): Boolean {
        val value = transform.apply(frame.axis(axisIndex))
        active = when (direction) {
            ThresholdDirection.ABOVE -> if (active) value > releaseThreshold else value >= pressThreshold
            ThresholdDirection.BELOW -> if (active) value < releaseThreshold else value <= pressThreshold
        }
        return active
    }

    override fun reset() {
        active = false
    }
}

/**
 * A simultaneous combination of two or more digital inputs.
 *
 * Every child must transition to active within [simultaneityWindowNanos]. Holding one button and
 * pressing another much later therefore does not accidentally activate the chord. The chord stays
 * active until any child is released. Suppressing overlapping single-button bindings is a policy
 * of the controls compiler, not this primitive.
 */
class ChordSource(
    sources: List<DigitalSource>,
    val simultaneityWindowNanos: Long = DEFAULT_SIMULTANEITY_WINDOW_NANOS,
) : DigitalSource {
    private val sourceArray = sources.toTypedArray()
    private val previousActive = BooleanArray(sourceArray.size)
    private val pressedAtNanos = LongArray(sourceArray.size)

    init {
        require(sourceArray.size >= 2) { "a chord requires at least two sources" }
        require(simultaneityWindowNanos >= 0L) { "simultaneity window must not be negative" }
    }

    override fun sample(frame: InputFrame, nowNanos: Long): Boolean {
        var allActive = true
        var earliestPress = Long.MAX_VALUE
        var latestPress = Long.MIN_VALUE
        var index = 0
        while (index < sourceArray.size) {
            val active = sourceArray[index].sample(frame, nowNanos)
            if (active && !previousActive[index]) pressedAtNanos[index] = nowNanos
            previousActive[index] = active
            if (!active) {
                allActive = false
            } else {
                val pressedAt = pressedAtNanos[index]
                if (pressedAt < earliestPress) earliestPress = pressedAt
                if (pressedAt > latestPress) latestPress = pressedAt
            }
            index++
        }
        return allActive && latestPress - earliestPress <= simultaneityWindowNanos
    }

    override fun reset() {
        var index = 0
        while (index < sourceArray.size) {
            sourceArray[index].reset()
            previousActive[index] = false
            pressedAtNanos[index] = 0L
            index++
        }
    }

    companion object {
        const val DEFAULT_SIMULTANEITY_WINDOW_NANOS: Long = 75_000_000L

        fun rawButtons(
            firstButtonIndex: Int,
            secondButtonIndex: Int,
            vararg additionalButtonIndexes: Int,
            simultaneityWindowNanos: Long = DEFAULT_SIMULTANEITY_WINDOW_NANOS,
        ): ChordSource {
            val sources = ArrayList<DigitalSource>(additionalButtonIndexes.size + 2)
            sources.add(RawButtonSource(firstButtonIndex))
            sources.add(RawButtonSource(secondButtonIndex))
            var index = 0
            while (index < additionalButtonIndexes.size) {
                sources.add(RawButtonSource(additionalButtonIndexes[index]))
                index++
            }
            return ChordSource(sources, simultaneityWindowNanos)
        }
    }
}
