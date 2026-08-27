package com.areslib.networktables

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Thread-safe pool of reusable byte arrays for zero-allocation NetworkTables 4
 * MessagePack serialization and WebSocket transport.
 */
object NT4BufferPool {
    private const val DEFAULT_BUFFER_SIZE = 8192
    private val pool = ConcurrentLinkedQueue<ByteArray>()

    /**
     * Acquire a byte array of at least [minCapacity] bytes.
     */
    fun acquireByteArray(minCapacity: Int = DEFAULT_BUFFER_SIZE): ByteArray {
        var buf = pool.poll()
        while (buf != null && buf.size < minCapacity) {
            buf = pool.poll()
        }
        return buf ?: ByteArray(kotlin.math.max(minCapacity, DEFAULT_BUFFER_SIZE))
    }

    /**
     * Return a byte array to the pool for future reuse.
     */
    fun releaseByteArray(buffer: ByteArray) {
        if (buffer.size <= 65536) { // Bound maximum pooled array size to prevent memory bloat
            pool.offer(buffer)
        }
    }

    /**
     * Clear all cached buffers from memory.
     */
    fun clear() {
        pool.clear()
    }
}
