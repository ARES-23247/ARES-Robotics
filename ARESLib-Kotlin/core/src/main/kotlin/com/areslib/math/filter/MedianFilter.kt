package com.areslib.math.filter

/**
 * Sliding window Median Filter for non-linear outlier spike rejection.
 *
 * Tracks a sliding ring-buffer of $N$ recent finite samples and a preallocated sorted window.
 * Each update removes the oldest entry and inserts the new entry in O(N) worst-case time;
 * reading the cached median is O(1). No full-window sort is performed during updates or reads.
 * Isolated outliers can be rejected, but step delay and nonlinear distortion depend on window
 * occupancy; a median filter does not guarantee removal of arbitrary bursts without lag.
 *
 * ### Mathematical Definition:
 * For sorted sample window $(x_{(1)} \le x_{(2)} \le \dots \le x_{(N)})$:
 * $$\text{Median} = \begin{cases} x_{\left(\frac{N+1}{2}\right)} & \text{if } N \text{ is odd} \\ \frac{x_{\left(\frac{N}{2}\right)} + x_{\left(\frac{N}{2}+1\right)}}{2} & \text{if } N \text{ is even} \end{cases}$$
 *
 * ### Zero-GC Guarantee:
 * Uses preallocated primitive ring/sorted arrays to maintain zero dynamic heap allocations during updates.
 *
 * @param windowSize Total number of historical samples to track ($N \ge 1$).
 */
class MedianFilter(
    private val windowSize: Int
) {

    init {
        require(windowSize > 0) { "Window size must be greater than 0" }
    }

    private val buffer = DoubleArray(windowSize)
    private val ordered = DoubleArray(windowSize)
    private var size = 0
    private var writeIndex = 0
    private var median = 0.0

    /**
     * Pushes a new raw measurement into the sliding window and returns the current median.
     *
     * @param measurement Raw sensor reading.
     * @return Calculated median value across active window samples.
     */
    fun calculate(measurement: Double): Double {
        if (!measurement.isFinite()) return median
        var retained = size
        if (size == windowSize) {
            val removal = java.util.Arrays.binarySearch(ordered, 0, size, buffer[writeIndex])
            System.arraycopy(ordered, removal + 1, ordered, removal, size - removal - 1)
            retained--
        } else size++
        buffer[writeIndex] = measurement
        writeIndex++
        if (writeIndex == windowSize) writeIndex = 0
        val found = java.util.Arrays.binarySearch(ordered, 0, retained, measurement)
        val insertion = if (found >= 0) found else -found - 1
        System.arraycopy(ordered, insertion, ordered, insertion + 1, retained - insertion)
        ordered[insertion] = measurement
        median = if (size % 2 == 1) ordered[size / 2] else {
            val lower = ordered[size / 2 - 1]
            val upper = ordered[size / 2]
            val sum = lower + upper
            if (sum.isFinite()) sum * 0.5 else lower * 0.5 + upper * 0.5
        }
        return median
    }

    /**
     * Gets the current median value without pushing a new sample.
     */
    val value: Double get() = median

    /**
     * Resets the buffer to a baseline pre-filled initial value.
     *
     * @param initialValue Baseline for the entire window. Nonfinite baselines clear history.
     */
    fun reset(initialValue: Double = 0.0) {
        if (!initialValue.isFinite()) { clear(); return }
        buffer.fill(initialValue)
        ordered.fill(initialValue)
        size = windowSize
        writeIndex = 0
        median = initialValue
    }

    /**
     * Clears all samples in the sliding window buffer.
     */
    fun clear() {
        size = 0
        writeIndex = 0
        median = 0.0
    }
}
