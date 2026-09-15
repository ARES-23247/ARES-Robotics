package com.areslib.pathing

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class FieldWaypointLoaderAuditTest {
    private val waypoint = """{"id":"dock","name":"Dock","x":1.25,"y":-0.5,"headingDegrees":90.0}"""
    @Volatile private var allocationSink: Any? = null

    @Test fun `missing and invalid sources are resolved only once until clear`() {
        for (initial in listOf(null, "not-json", "null", "{}", "[null]")) {
            var reads = 0
            var source = initial
            val cache = FieldWaypointCache { reads++; source }
            val first = cache.load()
            assertTrue(first.isEmpty())
            source = "[$waypoint]"
            repeat(100) { assertSame(first, cache.load()) }
            assertEquals(1, reads)
            cache.clear()
            assertEquals(1, cache.load().size)
            assertEquals(2, reads)
        }
    }

    @Test fun `valid exported units and literal names survive immutable caching`() {
        val cache = FieldWaypointCache { "[$waypoint]" }
        val snapshot = cache.load()
        val item = assertNotNull(snapshot["Dock"])
        assertEquals("dock", item.id)
        assertFalse(item.locked)
        val pose = item.toPose()
        assertEquals(1.25, pose.x)
        assertEquals(-0.5, pose.y)
        assertEquals(Math.PI / 2, pose.heading.radians)
        assertSame(snapshot, cache.load())
        assertFailsWith<UnsupportedOperationException> { (snapshot as MutableMap).clear() }
        val spaced = FieldWaypointCache { "[${waypoint.replace("Dock", " Dock ").dropLast(1)},\"locked\":true}]" }
        assertTrue(assertNotNull(spaced.load()[" Dock "]).locked)
    }

    @Test fun `unsafe and ambiguous records reject the complete file`() {
        val invalid = listOf(
            waypoint.replace("\"x\":1.25,", ""),
            waypoint.replace("1.25", "1e400"),
            waypoint.replace("1.25", "\"NaN\""),
            waypoint.replace("-0.5", "null"),
            waypoint.replace("90.0", "\"90\""),
            waypoint.replace("\"dock\"", "null"),
            waypoint.replace("\"Dock\"", "\"  \""),
            waypoint.dropLast(1) + ",\"locked\":1}",
        )
        for (record in invalid) {
            val json = "[${waypoint.replace("dock", "other").replace("Dock", "Other")},$record]"
            assertTrue(FieldWaypointCache { json }.load().isEmpty(), record)
        }
        assertTrue(FieldWaypointCache { "[$waypoint,$waypoint]" }.load().isEmpty())
        assertTrue(FieldWaypointCache { "[$waypoint,${waypoint.replace("dock", "other")}]" }.load().isEmpty())
        assertTrue(FieldWaypointCache { "[$waypoint,${waypoint.replace("Dock", "Other")}]" }.load().isEmpty())
    }

    @Test fun `read failure is cached and explicit reload can recover`() {
        var reads = 0
        val cache = FieldWaypointCache { if (++reads == 1) error("read failed") else "[$waypoint]" }
        assertTrue(cache.load().isEmpty())
        assertTrue(cache.load().isEmpty())
        assertEquals(1, reads)
        cache.clear()
        assertEquals(1, cache.load().size)
    }

    @Test fun `simultaneous first reads share a single loaded snapshot`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var reads = 0
        val cache = FieldWaypointCache {
            reads++
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            "[$waypoint]"
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit<Map<String, FieldWaypoint>> { cache.load() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = workers.submit<Map<String, FieldWaypoint>> { cache.load() }
            release.countDown()
            assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
            assertEquals(1, reads)
        } finally {
            release.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun `warm missing and present lookups allocate no loop bytes`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        val missing = FieldWaypointCache { null }
        val present = FieldWaypointCache { "[$waypoint]" }
        val expected = assertNotNull(present.load()["Dock"])
        missing.load()
        // Warm the complete counter/lookup/counter routine with both map implementations and
        // the escaped allocation control before measuring. Do not change the JIT type profile
        // from an empty map to an unmodifiable map between warmup and measured windows.
        repeat(1_000) {
            measureLookups(bean, thread, missing, 1_000, false)
            measureLookups(bean, thread, present, 1_000, false)
            measureLookups(bean, thread, present, 100, true)
        }
        val missingBytes = LongArray(3)
        val presentBytes = LongArray(3)
        val controlBytes = LongArray(3)
        repeat(3) { window ->
            missingBytes[window] = measureLookups(bean, thread, missing, 10_000, false)
            assertNull(allocationSink)
            presentBytes[window] = measureLookups(bean, thread, present, 10_000, false)
            assertSame(expected, allocationSink)
            controlBytes[window] = measureLookups(bean, thread, present, 10_000, true)
        }
        println("Waypoint allocation bytes: missing=${missingBytes.toList()}, " +
            "present=${presentBytes.toList()}, escapedControl=${controlBytes.toList()}")
        repeat(3) { window ->
            assertEquals(0L, missingBytes[window], "missing window=$window")
            assertEquals(0L, presentBytes[window], "present window=$window")
            assertTrue(controlBytes[window] >= 10_000L * 16L, "allocation control window=$window")
        }
        allocationSink = null
    }

    private fun measureLookups(bean: ThreadMXBean, thread: Long, cache: FieldWaypointCache,
        count: Int, allocateControl: Boolean): Long {
        val before = bean.getThreadAllocatedBytes(thread)
        var index = 0
        while (index++ < count) {
            val result = cache.load()["Dock"]
            allocationSink = if (allocateControl) arrayOf(result) else result
        }
        return bean.getThreadAllocatedBytes(thread) - before
    }
}
