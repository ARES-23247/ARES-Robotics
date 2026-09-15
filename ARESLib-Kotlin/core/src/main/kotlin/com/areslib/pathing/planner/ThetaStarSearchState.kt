package com.areslib.pathing.planner

/**
 * Primitive Long-Packed Binary Min-Heap Priority Queue for Zero-GC Pathfinding.
 *
 * Packs 32-bit floating point $f$-cost bits and 32-bit integer grid node indices into primitive 64-bit `Long` elements
 * to achieve $O(\log N)$ insertion and extraction without allocations while capacity is sufficient.
 * Growth allocates a replacement primitive array. Keys use signed Long ordering, which preserves
 * packed non-negative finite float costs and their node-index tie breaks.
 *
 * ### Bit-Packing Layout:
 * `element = (fCostBits.toLong() shl 32) or (nodeIndex.toLong() and 0xFFFFFFFFL)`
 *
 * @param capacity Initial primitive array capacity.
 */
class LongHeap(capacity: Int) {
    init { require(capacity >= 0) { "Heap capacity must be non-negative" } }
    var data = LongArray(capacity)
    var size = 0

    /** Pushes a packed 64-bit `(fCost, nodeIndex)` key into the min-heap. */
    fun add(value: Long) {
        if (size == data.size) {
            check(data.size < Int.MAX_VALUE) { "Heap capacity exhausted" }
            data = data.copyOf((data.size.toLong() * 2L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt())
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
        if (size == 0) throw NoSuchElementException("Heap is empty")
        val result = data[0]
        size--
        if (size > 0) {
            val value = data[size]
            var i = 0
            while (i < size / 2) {
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
 * Exclusively owned, pooled Theta* search scratchpad with epoch resets.
 *
 * Maintains pre-allocated arrays (`gCosts`, `parents`, `generations`) and an epoch generation counter (`generation`).
 * Calling [ensureCapacity] increments the epoch counter, invalidating stale array entries in $O(1)$ constant time
 * without requiring array zeroing passes or dynamic object allocations.
 *
 * @param capacity Maximum grid node count ($N_{\text{rows}} \times N_{\text{cols}}$).
 */
class PlannerState(capacity: Int) {
    init { require(capacity in 0..Int.MAX_VALUE / 8) { "Planner capacity is out of range" } }

    var gCosts = DoubleArray(capacity)
    var parents = IntArray(capacity)
    var generations = IntArray(capacity)
    var generation = 1
    var openQueue = LongHeap(capacity * 8)
    var pathPool = DoubleArray(capacity * 2)

    fun ensureCapacity(capacity: Int) {
        require(capacity in 0..Int.MAX_VALUE / 8) { "Planner capacity is out of range" }
        if (gCosts.size < capacity) {
            gCosts = DoubleArray(capacity)
            parents = IntArray(capacity)
            generations = IntArray(capacity)
        }
        // Advance epoch — all nodes with stale generation are implicitly reset
        if (generation == Int.MAX_VALUE) {
            // Overflow guard: reset epoch and zero out generations array
            generation = 1
            generations.fill(0, 0, generations.size)
        } else generation++
        openQueue.clear()
        if (openQueue.data.size < capacity * 8) openQueue.data = LongArray(capacity * 8)
        if (pathPool.size < capacity * 2) {
            // The previous search's reconstructed path is stale; no copy is needed.
            pathPool = DoubleArray(capacity * 2)
        }
    }

    /** Read gCost for a node, returning POSITIVE_INFINITY if the node hasn't been touched this epoch. */
    fun getGCost(key: Int): Double {
        return if (generations[key] == generation) gCosts[key] else Double.POSITIVE_INFINITY
    }

    /** Write gCost for a node, marking it as active in the current epoch. */
    fun setGCost(key: Int, value: Double) {
        if (generations[key] != generation) parents[key] = -1
        gCosts[key] = value
        generations[key] = generation
    }

    /** Check if a node has been closed (visited) this epoch. Uses the sign bit of parents as a flag. */
    fun isClosed(key: Int): Boolean {
        return generations[key] == generation && parents[key] < -1
    }

    /** Marks an initialized node closed; repeated calls preserve its parent and closed flag. */
    fun setClosed(key: Int) {
        check(generations[key] == generation) { "Cannot close an unseen node" }
        // Include the missing-parent sentinel: -1 -> -2, 0 -> -3, etc.
        if (parents[key] >= -1) parents[key] = -parents[key] - 3
    }

    /** Get the real parent key, whether the node is closed or open. */
    fun getParent(key: Int): Int {
        if (generations[key] != generation) return -1
        val p = parents[key]
        return if (p < -1) -(p + 3) else p
    }

    /** Set the parent for a node (open state). */
    fun setParent(key: Int, parentKey: Int) {
        require(parentKey >= -1 && parentKey < parents.size) { "Parent key is out of range" }
        if (generations[key] != generation) {
            gCosts[key] = Double.POSITIVE_INFINITY
            generations[key] = generation
        }
        parents[key] = parentKey
    }
}
