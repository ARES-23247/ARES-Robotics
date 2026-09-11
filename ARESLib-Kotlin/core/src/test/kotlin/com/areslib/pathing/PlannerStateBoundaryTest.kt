package com.areslib.pathing

import com.areslib.pathing.planner.PlannerState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlannerStateBoundaryTest {
    @Test fun `invalid capacities and parent keys fail before state mutation`() {
        for (capacity in listOf(-1, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { PlannerState(capacity) }
        }
        val state = PlannerState(3)
        state.setGCost(0, 7.0)
        state.setParent(0, 2)
        val generation = state.generation
        val costs = state.gCosts
        assertThrows(IllegalArgumentException::class.java) { state.ensureCapacity(Int.MAX_VALUE) }
        assertSame(costs, state.gCosts)
        assertEquals(generation, state.generation)
        for (parent in listOf(-2, 3)) {
            assertThrows(IllegalArgumentException::class.java) { state.setParent(0, parent) }
            assertEquals(2, state.getParent(0))
        }
        assertThrows(IllegalStateException::class.java) { state.setClosed(1) }
        assertEquals(Double.POSITIVE_INFINITY, state.getGCost(1))
    }

    @Test fun `new nodes are unseen and either setter can initialize an epoch`() {
        val state = PlannerState(3)
        assertEquals(Double.POSITIVE_INFINITY, state.getGCost(0))
        assertEquals(-1, state.getParent(0))
        state.setParent(0, 2)
        assertEquals(Double.POSITIVE_INFINITY, state.getGCost(0))
        state.setGCost(0, 7.0)
        assertEquals(2, state.getParent(0))
        state.ensureCapacity(3)
        state.setGCost(0, 8.0)
        assertEquals(-1, state.getParent(0))
        assertFalse(state.isClosed(0))
    }

    @Test fun `closed missing parent has a distinct encoding and closure is idempotent`() {
        val state = PlannerState(3)
        state.ensureCapacity(3)
        state.setGCost(0, 0.0)
        state.setParent(0, -1)
        state.setClosed(0)
        assertTrue(state.isClosed(0))
        assertEquals(-1, state.getParent(0))
        state.setClosed(0)
        assertTrue(state.isClosed(0))
        state.setParent(0, 2)
        assertFalse(state.isClosed(0))
        state.setClosed(0)
        state.setClosed(0)
        assertTrue(state.isClosed(0))
        assertEquals(2, state.getParent(0))
    }

    @Test fun `capacity growth prepares queue outside search and wrap invalidates old generations`() {
        val state = PlannerState(0)
        state.ensureCapacity(4)
        assertTrue(state.openQueue.data.size >= 32)
        state.generation = Int.MAX_VALUE
        state.setGCost(1, 3.0)
        state.setParent(1, 2)
        state.generations[2] = 1
        state.gCosts[2] = 123.0
        state.ensureCapacity(4)
        assertEquals(1, state.generation)
        assertEquals(Double.POSITIVE_INFINITY, state.getGCost(1))
        assertEquals(Double.POSITIVE_INFINITY, state.getGCost(2))
        val costs = state.gCosts
        val parents = state.parents
        val epochs = state.generations
        val path = state.pathPool
        val queue = state.openQueue.data
        repeat(1000) { state.ensureCapacity(4) }
        assertSame(costs, state.gCosts)
        assertSame(parents, state.parents)
        assertSame(epochs, state.generations)
        assertSame(path, state.pathPool)
        assertSame(queue, state.openQueue.data)
    }
}
