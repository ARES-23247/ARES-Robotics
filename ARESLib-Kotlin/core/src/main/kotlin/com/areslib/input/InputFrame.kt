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
    private val buttonWords: LongArray

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

    /** Increments once per call to [beginSample], including disconnected samples. */
    var sequence: Long = 0L
        private set

    init {
        require(axisCapacity > 0) { "axisCapacity must be positive" }
        require(buttonCapacity > 0) { "buttonCapacity must be positive" }
        axes = DoubleArray(axisCapacity)
        buttonWords = LongArray((buttonCapacity + Long.SIZE_BITS - 1) / Long.SIZE_BITS)
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
        require(reportedAxisCount in 0..axisCapacity) {
            "reportedAxisCount $reportedAxisCount exceeds capacity $axisCapacity"
        }
        require(reportedButtonCount in 0..buttonCapacity) {
            "reportedButtonCount $reportedButtonCount exceeds capacity $buttonCapacity"
        }

        Arrays.fill(axes, 0.0)
        Arrays.fill(buttonWords, 0L)
        isConnected = connected
        timestampNanos = sampleTimeNanos
        axisCount = if (connected) reportedAxisCount else 0
        buttonCount = if (connected) reportedButtonCount else 0
        sequence++
    }

    /** Writes one raw axis. Non-finite values are converted to zero for fail-safe behavior. */
    fun setAxis(index: Int, value: Double) {
        require(index in 0 until axisCount) {
            "axis index $index is outside the reported range 0 until $axisCount"
        }
        axes[index] = if (value.isFinite()) value else 0.0
    }

    /** Writes one raw button. */
    fun setButton(index: Int, pressed: Boolean) {
        require(index in 0 until buttonCount) {
            "button index $index is outside the reported range 0 until $buttonCount"
        }
        val wordIndex = index / Long.SIZE_BITS
        val mask = 1L shl (index % Long.SIZE_BITS)
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
        require(axisCapacity >= other.axisCount) { "axis capacity is too small for source frame" }
        require(buttonCapacity >= other.buttonCount) { "button capacity is too small for source frame" }
        beginSample(
            connected = other.isConnected,
            reportedAxisCount = other.axisCount,
            reportedButtonCount = other.buttonCount,
            sampleTimeNanos = other.timestampNanos,
        )
        var index = 0
        while (index < other.axisCount) {
            axes[index] = other.axes[index]
            index++
        }
        index = 0
        while (index < buttonWords.size && index < other.buttonWords.size) {
            buttonWords[index] = other.buttonWords[index]
            index++
        }
    }

    companion object {
        const val DEFAULT_AXIS_CAPACITY: Int = 16
        const val DEFAULT_BUTTON_CAPACITY: Int = 128
    }
}
