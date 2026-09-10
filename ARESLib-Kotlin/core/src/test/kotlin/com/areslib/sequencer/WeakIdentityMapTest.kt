package com.areslib.sequencer

import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Test
import kotlin.test.*

class WeakIdentityMapTest {
    /** Test-only collection instrumentation; production code has no reflection or probe hooks. */
    private class TrackedEntries(entries: Collection<Any>) : ArrayList<Any>(entries) {
        var reads = 0L
        var writes = 0L
        var removals = 0L
        var shifted = 0L
        override fun get(index: Int): Any { reads++; return super.get(index) }
        override fun set(index: Int, element: Any): Any { writes++; return super.set(index, element) }
        override fun removeAt(index: Int): Any {
            removals++; shifted += size - index - 1
            return super.removeAt(index)
        }
        fun resetCounts() { reads = 0; writes = 0; removals = 0; shifted = 0 }
        val operations: Long get() = reads + writes + removals + shifted
    }
    private fun tracked(map: Any): TrackedEntries {
        val field = map.javaClass.getDeclaredField("entries").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val result = TrackedEntries(field.get(map) as Collection<Any>)
        field.set(map, result)
        return result
    }
    private fun references(entries: List<Any>): List<WeakReference<*>> = entries.map { entry ->
        entry.javaClass.getDeclaredField("reference").apply { isAccessible = true }.get(entry) as WeakReference<*>
    }

    @Test fun `queued collection burst performs linear entry work`() {
        val count = 512
        val keys = Array(count) { Any() }
        val map = WeakIdentityMap<Any, Int>()
        keys.forEachIndexed { index, key -> map[key] = index }
        val entries = tracked(map)
        references(entries).forEach { it.clear(); assertTrue(it.enqueue()) }
        entries.resetCounts()
        assertFalse(map.containsKey(Any()))
        val operations = entries.operations
        println("[Weak map audit] $count collected entries: $operations entry operations (${entries.reads} reads, ${entries.writes} writes, ${entries.removals} removals, ${entries.shifted} shifted slots)")
        assertTrue(operations <= count * 4L, "Burst pruning touched $operations entry slots for $count keys")
        assertEquals(0, entries.size)
    }

    @Test fun `mixed collection retains live identities values and traversal order`() {
        val keys = Array(20) { Any() }
        val map = WeakIdentityMap<Any, String>()
        keys.forEachIndexed { index, key -> map[key] = "value-$index" }
        val entries = tracked(map)
        references(entries).forEachIndexed { index, ref -> if (index % 2 == 0) { ref.clear(); ref.enqueue() } }
        val visited = mutableListOf<String>()
        map.forEachLive { _, value -> visited.add(value) }
        assertEquals((1..19 step 2).map { "value-$it" }, visited)
        assertEquals(10, entries.size)
        keys.forEachIndexed { index, key -> assertEquals(if (index % 2 == 0) null else "value-$index", map[key]) }
    }

    @Test fun `late queue notification for an already removed entry cannot remove a live key`() {
        val first = Any(); val survivor = Any()
        val map = WeakIdentityMap<Any, String>(); map[first] = "first"; map[survivor] = "survivor"
        val refs = references(tracked(map))
        assertEquals("first", map.remove(first))
        refs.first().clear(); refs.first().enqueue()
        assertEquals("survivor", map[survivor])
        assertFalse(map.containsKey(first))
    }

    @Test fun `cleared references are never visited before their queue notification`() {
        val key = Any(); val map = WeakIdentityMap<Any, Int>(); map[key] = 1
        val ref = references(tracked(map)).single(); ref.clear()
        var visits = 0
        map.forEachLive { _, _ -> visits++ }
        assertEquals(0, visits)
        assertNull(map[key])
        ref.enqueue()
        assertFalse(map.containsKey(key))
    }

    @Test fun `identity keys never call user equality or hash methods`() {
        class Key {
            override fun equals(other: Any?): Boolean = error("equality must not run")
            override fun hashCode(): Int = error("hash must not run")
        }
        val first = Key(); val second = Key(); val absent = Key()
        val map = WeakIdentityMap<Key, String?>()
        map[first] = "one"; map[second] = "two"; map[first] = null
        assertTrue(map.containsKey(first)); assertNull(map[first])
        assertEquals("two", map[second]); assertNull(map[absent]); assertNull(map.remove(absent))
        assertNull(map.remove(first)); assertFalse(map.containsKey(first))
        assertEquals("two", map.remove(second)); assertFalse(map.containsKey(second))
    }

    @Test fun `visitor exception propagates without retaining the map monitor`() {
        val key = Any(); val map = WeakIdentityMap<Any, Int>(); map[key] = 1
        val failure = IllegalStateException("visitor")
        assertSame(failure, assertFailsWith<IllegalStateException> { map.forEachLive { _, _ -> throw failure } })
        var observed: Int? = null
        val worker = Thread { observed = map[key] }
        worker.start(); worker.join(2000)
        assertFalse(worker.isAlive); assertEquals(1, observed)
    }

    @Test fun `concurrent independent key updates and removals remain isolated`() {
        val keys = Array(4) { Any() }
        val map = WeakIdentityMap<Any, Int>()
        val start = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val workers = keys.map { key -> Thread {
            try {
                start.await()
                repeat(1000) { value -> map[key] = value; assertEquals(value, map[key]); assertEquals(value, map.remove(key)) }
            } catch (failure: Throwable) { error.compareAndSet(null, failure) }
        } }
        try { workers.forEach { it.start() }; start.countDown() }
        finally { start.countDown(); workers.forEach { it.join(2000) } }
        assertTrue(workers.none { it.isAlive }); assertNull(error.get())
        var remaining = 0; map.forEachLive { _, _ -> remaining++ }
        assertEquals(0, remaining)
    }
}
