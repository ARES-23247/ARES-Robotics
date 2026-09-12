package com.areslib.input

import com.areslib.util.RobotClock
import java.util.Arrays

/**
 * A reusable snapshot of every raw input exposed by a controller.
 *
 * Button and axis indexes are zero-based raw HID indexes. Platform adapters translate their
 * native numbering exactly once at the boundary (WPILib's one-based button numbers, for example,
 * become zero-based here). The backing arrays are allocated only when the frame is constructed;
 * [beginSample], [setAxis], and [setButton] are allocation-free and are intended for the robot
 * loop.
 *
 * Call [beginSample] before writing each hardware sample. It clears every previous value so a
 * partially reported or disconnected device cannot leave a stale command active.
 */
class InputFrame(
    val axisCapacity: Int = DEFAULT_AXIS_CAPACITY,
    val buttonCapacity: Int = DEFAULT_BUTTON_CAPACITY,
) {
    private val axes: DoubleArray
    private val availableAxes: BooleanArray
    private val buttonWords: LongArray
    private val availableButtonWords: LongArray

    /** Whether the adapter reported a usable controller for this sample. */
    var isConnected: Boolean = false
        private set

    /** Monotonic timestamp associated with this sample. */
    var timestampNanos: Long = 0L
        private set

    /** Number of axes reported by the current device, never greater than [axisCapacity]. */
    var axisCount: Int = 0
        private set

    /** Number of buttons reported by the current device, never greater than [buttonCapacity]. */
    var buttonCount: Int = 0
        private set

    /** Changes when a sample begins or an invalid write clears the sample. */
    var sequence: Long = 0L
        private set

    init {
        require(axisCapacity > 0) { "axisCapacity must be positive" }
        require(buttonCapacity > 0) { "buttonCapacity must be positive" }
        axes = DoubleArray(axisCapacity)
        availableAxes = BooleanArray(axisCapacity)
        buttonWords = LongArray(buttonWordCount(buttonCapacity))
        availableButtonWords = LongArray(buttonWords.size)
    }

    /**
     * Starts a new sample and clears all old values.
     *
     * [reportedAxisCount] and [reportedButtonCount] describe the connected device, while the
     * capacities describe storage. Passing a count larger than the configured capacity is an
     * adapter error and fails immediately instead of silently dropping controls.
     */
    fun beginSample(
        connected: Boolean,
        reportedAxisCount: Int = 0,
        reportedButtonCount: Int = 0,
        sampleTimeNanos: Long = RobotClock.nanoTime(),
    ) {
        clearSample(sampleTimeNanos)
        require(reportedAxisCount in 0..axisCapacity) {
            "reportedAxisCount $reportedAxisCount exceeds capacity $axisCapacity"
        }
        require(reportedButtonCount in 0..buttonCapacity) {
            "reportedButtonCount $reportedButtonCount exceeds capacity $buttonCapacity"
        }

        isConnected = connected
        axisCount = if (connected) reportedAxisCount else 0
        buttonCount = if (connected) reportedButtonCount else 0
    }

    /** Writes one raw axis. Non-finite values read as zero but remain unavailable for calibration. */
    fun setAxis(index: Int, value: Double) {
        if (index !in 0 until axisCount) clearSample(timestampNanos)
        require(index in 0 until axisCount) {
            "axis index $index is outside the reported range 0 until $axisCount"
        }
        axes[index] = if (value.isFinite()) value else 0.0
        availableAxes[index] = value.isFinite()
    }

    /** Writes one raw button. */
    fun setButton(index: Int, pressed: Boolean) {
        if (index !in 0 until buttonCount) clearSample(timestampNanos)
        require(index in 0 until buttonCount) {
            "button index $index is outside the reported range 0 until $buttonCount"
        }
        val wordIndex = index / Long.SIZE_BITS
        val mask = 1L shl (index % Long.SIZE_BITS)
        availableButtonWords[wordIndex] = availableButtonWords[wordIndex] or mask
        buttonWords[wordIndex] = if (pressed) {
            buttonWords[wordIndex] or mask
        } else {
            buttonWords[wordIndex] and mask.inv()
        }
    }

    /** Returns zero for a valid capacity slot that this device did not report. */
    fun axis(index: Int): Double {
        require(index in 0 until axisCapacity) { "axis index $index is outside this frame" }
        return if (isConnected && index < axisCount) axes[index] else 0.0
    }

    /** True only for an explicitly written finite axis in the current connected sample. */
    fun isAxisAvailable(index: Int): Boolean {
        require(index in 0 until axisCapacity) { "axis index $index is outside this frame" }
        return isConnected && index < axisCount && availableAxes[index]
    }

    /** True only for an explicitly written button in the current connected sample. */
    fun isButtonAvailable(index: Int): Boolean {
        require(index in 0 until buttonCapacity) { "button index $index is outside this frame" }
        return isConnected && index < buttonCount &&
            availableButtonWords[index / Long.SIZE_BITS] and (1L shl (index % Long.SIZE_BITS)) != 0L
    }

    /** Returns false for a valid capacity slot that this device did not report. */
    fun button(index: Int): Boolean {
        require(index in 0 until buttonCapacity) { "button index $index is outside this frame" }
        if (!isConnected || index >= buttonCount) return false
        val wordIndex = index / Long.SIZE_BITS
        val mask = 1L shl (index % Long.SIZE_BITS)
        return buttonWords[wordIndex] and mask != 0L
    }

    /**
     * Copies [other] without allocating. The receiving frame must have at least as much storage.
     */
    fun copyFrom(other: InputFrame) {
        if (other === this) return
        beginSample(
            connected = other.isConnected,
            reportedAxisCount = other.axisCount,
            reportedButtonCount = other.buttonCount,
            sampleTimeNanos = other.timestampNanos,
        )
        var index = 0
        while (index < other.axisCount) {
            axes[index] = other.axes[index]
            availableAxes[index] = other.availableAxes[index]
            index++
        }
        index = 0
        while (index < buttonWords.size && index < other.buttonWords.size) {
            buttonWords[index] = other.buttonWords[index]
            availableButtonWords[index] = other.availableButtonWords[index]
            index++
        }
    }

    private fun clearSample(sampleTimeNanos: Long) {
        isConnected = false
        axisCount = 0
        buttonCount = 0
        timestampNanos = sampleTimeNanos
        sequence++
        Arrays.fill(axes, 0.0)
        Arrays.fill(availableAxes, false)
        Arrays.fill(buttonWords, 0L)
        Arrays.fill(availableButtonWords, 0L)
    }

    companion object {
        const val DEFAULT_AXIS_CAPACITY: Int = 16
        const val DEFAULT_BUTTON_CAPACITY: Int = 128
    }
}

internal fun buttonWordCount(capacity: Int): Int {
    require(capacity > 0) { "buttonCapacity must be positive" }
    return (capacity - 1) / Long.SIZE_BITS + 1
}
