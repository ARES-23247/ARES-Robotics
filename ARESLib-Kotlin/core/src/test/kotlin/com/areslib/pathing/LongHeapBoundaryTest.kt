package com.areslib.pathing

import com.areslib.pathing.planner.LongHeap
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LongHeapBoundaryTest {
    @Test fun `zero capacity grows on first insertion`() {
        val heap = LongHeap(0)
        heap.add(7L)
        assertEquals(7L, heap.poll())
        assertFalse(heap.isNotEmpty())
    }

    @Test fun `empty extraction cannot return stale data or corrupt size`() {
        val heap = LongHeap(1)
        heap.add(9L)
        assertEquals(9L, heap.poll())
        assertThrows(NoSuchElementException::class.java) { heap.poll() }
        assertEquals(0, heap.size)
        heap.add(3L)
        assertEquals(3L, heap.poll())
    }

    @Test fun `growth duplicates and reuse match a reference sorted sequence`() {
        val heap = LongHeap(1)
        val random = java.util.Random(23247L)
        val keys = LongArray(2000) { random.nextLong() }
        keys[0] = Long.MIN_VALUE
        keys[1] = Long.MAX_VALUE
        keys[2] = keys[3]
        for (key in keys) heap.add(key)
        keys.sort()
        for (key in keys) assertEquals(key, heap.poll())
        val buffer = heap.data
        heap.add(8L)
        heap.clear()
        assertEquals(0, heap.size)
        for (key in keys.reversedArray()) heap.add(key)
        for (key in keys) assertEquals(key, heap.poll())
        assertSame(buffer, heap.data)
    }
}
