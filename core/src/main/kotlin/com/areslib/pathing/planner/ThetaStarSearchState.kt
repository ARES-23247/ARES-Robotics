package com.areslib.pathing.planner

/**
 * Primitive Long-Packed Binary Min-Heap Priority Queue for Zero-GC Pathfinding.
 *
 * Packs 32-bit floating point $f$-cost bits and 32-bit integer grid node indices into primitive 64-bit `Long` elements
 * to achieve $O(\log N)$ priority queue insertion and extraction with zero heap allocations.
 *
 * ### Bit-Packing Layout:
 * `element = (fCostBits.toLong() shl 32) or (nodeIndex.toLong() and 0xFFFFFFFFL)`
 *
 * @param capacity Initial primitive array capacity.
 */
class LongHeap(capacity: Int) {
    var data = LongArray(capacity)
    var size = 0

    /** Pushes a packed 64-bit `(fCost, nodeIndex)` key into the min-heap. */
    fun add(value: Long) {
        if (size == data.size) {
            data = data.copyOf(data.size * 2)
        }
        var i = size
        size++
        while (i > 0) {
            val p = (i - 1) ushr 1
            if (data[p] <= value) break
            data[i] = data[p]
            i = p
        }
        data[i] = value
    }

    /** Extracts and returns the minimum `(fCost, nodeIndex)` key from the min-heap root. */
    fun poll(): Long {
        val result = data[0]
        size--
        if (size > 0) {
            val value = data[size]
            var i = 0
            while ((i shl 1) + 1 < size) {
                var child = (i shl 1) + 1
                if (child + 1 < size && data[child + 1] < data[child]) {
                    child++
                }
                if (value <= data[child]) break
                data[i] = data[child]
                i = child
            }
            data[i] = value
        }
        return result
    }

    /** Resets heap element count to 0 in $O(1)$ constant time without array reallocation. */
    fun clear() { size = 0 }
    
    /** Returns true if the heap contains at least one active element. */
    fun isNotEmpty(): Boolean = size > 0
}

/**
 * Thread-Local Epoch-Resetting Theta* Search State Scratchpad.
 *
 * Maintains pre-allocated arrays (`gCosts`, `parents`, `generations`) and an epoch generation counter (`generation`).
 * Calling [ensureCapacity] increments the epoch counter, invalidating stale array entries in $O(1)$ constant time
 * without requiring array zeroing passes or dynamic object allocations.
 *
 * @param capacity Maximum grid node count ($N_{\text{rows}} \times N_{\text{cols}}$).
 */
class PlannerState(capacity: Int) {

    var gCosts = DoubleArray(capacity)
    var parents = IntArray(capacity)
    var generations = IntArray(capacity)
    var generation = 0
    var openQueue = LongHeap(capacity)
    var pathPool = DoubleArray(capacity * 2)

    /**
     * ensureCapacity declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun ensureCapacity(capacity: Int) {
        if (gCosts.size < capacity) {
            gCosts = DoubleArray(capacity)
            parents = IntArray(capacity)
            generations = IntArray(capacity)
        }
        // Advance epoch — all nodes with stale generation are implicitly reset
        generation++
        if (generation == Int.MAX_VALUE) {
            // Overflow guard: reset epoch and zero out generations array
            generation = 1
            generations.fill(0, 0, generations.size)
        }
        openQueue.clear()
        if (pathPool.size < capacity * 2) {
            val newPool = DoubleArray(capacity * 2)
            System.arraycopy(pathPool, 0, newPool, 0, pathPool.size)
            pathPool = newPool
        }
    }

    /** Read gCost for a node, returning POSITIVE_INFINITY if the node hasn't been touched this epoch. */
    fun getGCost(key: Int): Double {
        return if (generations[key] == generation) gCosts[key] else Double.POSITIVE_INFINITY
    }

    /** Write gCost for a node, marking it as active in the current epoch. */
    fun setGCost(key: Int, value: Double) {
        gCosts[key] = value
        generations[key] = generation
    }

    /** Check if a node has been closed (visited) this epoch. Uses the sign bit of parents as a flag. */
    fun isClosed(key: Int): Boolean {
        return generations[key] == generation && parents[key] < -1
    }

    /** Mark a node as closed by encoding it into the parent value (negate and subtract 2). */
    fun setClosed(key: Int) {
        // Encode: closedParent = -(realParent) - 2, so realParent >= -1 maps to closedParent <= -2
        parents[key] = -(parents[key]) - 2
    }

    /** Get the real parent key, whether the node is closed or open. */
    fun getParent(key: Int): Int {
        if (generations[key] != generation) return -1
        val p = parents[key]
        return if (p < -1) -(p + 2) else p
    }

    /** Set the parent for a node (open state). */
    fun setParent(key: Int, parentKey: Int) {
        parents[key] = parentKey
    }
}
