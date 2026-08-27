package com.areslib.math.filter

/**
 * Sliding window Median Filter for non-linear outlier spike rejection.
 *
 * Tracks a sliding ring-buffer of $N$ recent sensor samples, sorts them using a pre-allocated
 * primitive scratch array, and outputs the sample median. Completely rejects impulse noise spikes (e.g. ultrasonic sensor dropouts,
 * optical sensor reflection glints) without phase lag or attenuation.
 *
 * ### Mathematical Definition:
 * For sorted sample window $(x_{(1)} \le x_{(2)} \le \dots \le x_{(N)})$:
 * $$\text{Median} = \begin{cases} x_{\left(\frac{N+1}{2}\right)} & \text{if } N \text{ is odd} \\ \frac{x_{\left(\frac{N}{2}\right)} + x_{\left(\frac{N}{2}+1\right)}}{2} & \text{if } N \text{ is even} \end{cases}$$
 *
 * ### Zero-GC Guarantee:
 * Uses pre-allocated primitive array buffers (`buffer`, `tempBuffer`) to maintain zero dynamic heap allocations during updates.
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
    private val tempBuffer = DoubleArray(windowSize)
    private var size = 0
    private var writeIndex = 0

    /**
     * Pushes a new raw measurement into the sliding window and returns the current median.
     *
     * @param measurement Raw sensor reading.
     * @return Calculated median value across active window samples.
     */
    fun calculate(measurement: Double): Double {
        if (!measurement.isFinite()) return value

        if (size < windowSize) {
            buffer[size] = measurement
            size++
        } else {
            buffer[writeIndex] = measurement
            writeIndex = (writeIndex + 1) % windowSize
        }

        System.arraycopy(buffer, 0, tempBuffer, 0, size)
        tempBuffer.sort(0, size)

        return if (size % 2 == 1) {
            tempBuffer[size / 2]
        } else {
            val mid = size / 2
            (tempBuffer[mid - 1] + tempBuffer[mid]) / 2.0
        }
    }

    /**
     * Gets the current median value without pushing a new sample.
     */
    val value: Double
        get() {
            if (size == 0) return 0.0
            System.arraycopy(buffer, 0, tempBuffer, 0, size)
            tempBuffer.sort(0, size)
            return if (size % 2 == 1) {
                tempBuffer[size / 2]
            } else {
                val mid = size / 2
                (tempBuffer[mid - 1] + tempBuffer[mid]) / 2.0
            }
        }

    /**
     * Resets the buffer to a baseline pre-filled initial value.
     *
     * @param initialValue Baseline initial value to pre-fill the entire window buffer.
     */
    fun reset(initialValue: Double = 0.0) {
        buffer.fill(initialValue)
        size = windowSize
        writeIndex = 0
    }

    /**
     * Clears all samples in the sliding window buffer.
     */
    fun clear() {
        size = 0
        writeIndex = 0
    }
}
