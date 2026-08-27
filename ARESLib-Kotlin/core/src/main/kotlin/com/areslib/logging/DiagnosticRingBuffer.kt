package com.areslib.logging

import com.areslib.util.RobotClock

/**
 * A highly optimized, thread-safe circular diagnostic buffer.
 * It provides a zero-GC allocation footprint when logging telemetry data
 * or logging character arrays inside high-frequency real-time loops.
 */
class DiagnosticRingBuffer(val capacity: Int = 1000) {
    // Buffers for numeric telemetry
    private val tags = arrayOfNulls<String>(capacity)
    private val values = DoubleArray(capacity)
    private val numericalTimestamps = LongArray(capacity)
    private var numericHead = 0
    private var numericCount = 0

    // Buffers for text/message logging
    // We store character logs in a flat circular CharArray to avoid allocating String objects.
    private val charBuffer = CharArray(capacity * 64) // 64 chars per message on average
    private val messageLengths = IntArray(capacity)
    private val messageTimestamps = LongArray(capacity)
    private val messageOffsets = IntArray(capacity)
    private var messageHead = 0
    private var messageCount = 0
    private var charBufferWriteIndex = 0

    private val lock = Any()

    /**
     * Records a numeric diagnostic trace with exactly zero GC allocations.
     * @param tag The tag for the trace (should be a literal to avoid allocations).
     * @param value The value of the metric.
     */
    fun log(tag: String, value: Double) {
        log(tag, value, RobotClock.currentTimeMillis())
    }

    /**
     * Records a numeric diagnostic trace with a custom timestamp.
     */
    fun log(tag: String, value: Double, timestampMs: Long) {
        synchronized(lock) {
            tags[numericHead] = tag
            values[numericHead] = value
            numericalTimestamps[numericHead] = timestampMs
            
            numericHead = (numericHead + 1) % capacity
            if (numericCount < capacity) {
                numericCount++
            }
        }
    }

    /**
     * Records a raw character message to the buffer, copying the characters in-place
     * to avoid any string object allocation.
     * @param message The character array containing the diagnostic message.
     */
    fun log(message: CharArray) {
        log(message, 0, message.size, RobotClock.currentTimeMillis())
    }

    /**
     * Records a slice of a raw character message with a timestamp.
     */
    fun log(message: CharArray, offset: Int, length: Int, timestampMs: Long) {
        if (length <= 0) return
        synchronized(lock) {
            val lengthToCopy = kotlin.math.min(length, 128) // Cap message length at 128 to prevent overflow
            
            // Check if wrapping is needed in flat char buffer
            if (charBufferWriteIndex + lengthToCopy > charBuffer.size) {
                charBufferWriteIndex = 0 // Wrap flat char buffer
            }

            // Copy characters into circular buffer
            message.copyInto(charBuffer, charBufferWriteIndex, offset, offset + lengthToCopy)

            messageOffsets[messageHead] = charBufferWriteIndex
            messageLengths[messageHead] = lengthToCopy
            messageTimestamps[messageHead] = timestampMs

            charBufferWriteIndex += lengthToCopy
            messageHead = (messageHead + 1) % capacity
            if (messageCount < capacity) {
                messageCount++
            }
        }
    }

    /**
     * Get the count of logged numeric entries.
     */
    fun getNumericCount(): Int {
        synchronized(lock) {
            return numericCount
        }
    }

    /**
     * Get the count of logged text entries.
     */
    fun getMessageCount(): Int {
        synchronized(lock) {
            return messageCount
        }
    }

    /**
     * Reads the numeric log at a specific index into pre-allocated memory,
     * ensuring zero allocations.
     *
     * @param index 0-indexed offset (0 is the oldest, count-1 is the newest).
     * @param outValues Pre-allocated double array of size >= 1 where the logged value is written.
     * @return The Tag associated with the log at this index.
     */
    fun getNumericEntry(index: Int, outValues: DoubleArray): String? {
        synchronized(lock) {
            if (index < 0 || index >= numericCount) return null
            val physicalIndex = if (numericCount == capacity) {
                (numericHead + index) % capacity
            } else {
                index
            }
            outValues[0] = values[physicalIndex]
            return tags[physicalIndex]
        }
    }

    /**
     * Reads a text message entry at the given index into a pre-allocated char array.
     * @param index 0-indexed offset (0 is the oldest, count-1 is the newest).
     * @param dest The destination character array.
     * @return The number of characters copied, or -1 if the index is invalid.
     */
    fun getMessageEntry(index: Int, dest: CharArray): Int {
        synchronized(lock) {
            if (index < 0 || index >= messageCount) return -1
            val physicalIndex = if (messageCount == capacity) {
                (messageHead + index) % capacity
            } else {
                index
            }
            val offset = messageOffsets[physicalIndex]
            val len = messageLengths[physicalIndex]
            val copyLen = kotlin.math.min(len, dest.size)
            charBuffer.copyInto(dest, 0, offset, offset + copyLen)
            return copyLen
        }
    }

    /**
     * Resets/clears the circular buffers.
     */
    fun clear() {
        synchronized(lock) {
            numericHead = 0
            numericCount = 0
            messageHead = 0
            messageCount = 0
            charBufferWriteIndex = 0
            for (i in 0 until capacity) {
                tags[i] = null
            }
        }
    }
}
